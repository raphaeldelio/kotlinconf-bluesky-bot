package dev.raphaeldelio

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.StreamEntryID
import redis.clients.jedis.bloom.BFReserveParams
import redis.clients.jedis.params.XReadGroupParams
import redis.clients.jedis.resps.StreamEntry
import org.springframework.ai.transformers.TransformersEmbeddingModel
import org.springframework.ai.ollama.OllamaChatModel
import org.springframework.ai.ollama.api.OllamaApi
import org.springframework.ai.ollama.api.OllamaOptions
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.document.Document
import java.io.File
import redis.clients.jedis.search.Query
import java.time.LocalDateTime
import org.springframework.ai.vectorstore.redis.RedisVectorStore
import org.springframework.ai.vectorstore.redis.RedisVectorStore.MetadataField
import redis.clients.jedis.search.Schema.FieldType
import org.springframework.ai.vectorstore.SearchRequest
import java.util.UUID

val webSocketClient = HttpClient(CIO) {
    install(WebSockets)
}

val httpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
        })
    }
}

val jsonParser = Json { ignoreUnknownKeys = true }

val jedisPooled = JedisPooled()

fun createConsumerGroup(streamName: String, consumerGroupName: String) {
    try {
        jedisPooled.xgroupCreate(streamName, consumerGroupName, StreamEntryID("0-0"), true)
    } catch (e: Exception) {
        println("Group already exists")
    }
}

fun readFromStream(streamName: String, consumerGroup: String, consumer: String, count: Int): List<Map.Entry<String, List<StreamEntry>>> {
    return jedisPooled.xreadGroup(
        consumerGroup,
        consumer,
        XReadGroupParams().count(count),
        mapOf(
            streamName to StreamEntryID.XREADGROUP_UNDELIVERED_ENTRY
        )
    ) ?: emptyList()
}

fun consumeStream(
    streamName: String,
    consumerGroup: String,
    consumer: String,
    handlers: List<(Event) -> Pair<Boolean, String>>,
    ackFunction: ((String, String, StreamEntry) -> Unit),
    count: Int = 5,
    limit: Int = 5
) {
    var lastMessageTime = System.currentTimeMillis()
    var consumed = 0

    while (consumed < limit) {
        val entries = readFromStream(streamName, consumerGroup, consumer, count)
        val allEntries = entries.flatMap { it.value }
        allEntries.map { entry ->
            consumed++
            val event = Event.fromMap(entry)

            for (handler in handlers) {
                val (shouldContinue, message) = handler(event)
                ackFunction(streamName, consumerGroup, entry)

                if (!shouldContinue) {
                    println("$consumer: Handler stopped processing: $message")
                    break
                }
            }
        }

        if (allEntries.isEmpty()) {
            val now = System.currentTimeMillis()
            if (now - lastMessageTime >= 2_000) {
                println("$consumer: No new messages for 2 seconds. Stopping.")
                break
            }
        }
    }
}

val printUri: (Event) -> Pair<Boolean, String> = {
    println("Got event from ${it.uri}")
    Pair(true, "OK")
}

fun createBloomFilter(name: String) {
    runCatching {
        jedisPooled.bfReserve(name, 0.01, 1_000_000L, BFReserveParams().expansion(2))
    }.onFailure {
        println("Bloom filter already exists")
    }
}

fun deduplicate(bloomFilter: String): (Event) -> Pair<Boolean, String> {
    return { event ->
        if (jedisPooled.bfExists(bloomFilter, event.uri)) {
            Pair(false, "${event.uri} already processed")
        } else {
            Pair(true, "OK")
        }
    }
}

fun ackFn():  (String, String, StreamEntry) -> Unit = { streamName, consumerGroup, entry ->
    jedisPooled.xack(
        streamName,
        consumerGroup,
        entry.id
    )
}

fun ackAndBfFn(bloomFilter: String):  (String, String, StreamEntry) -> Unit = { streamName, consumerGroup, entry ->
    jedisPooled.xack(
        streamName,
        consumerGroup,
        entry.id
    )

    jedisPooled.bfAdd(bloomFilter, Event.fromMap(entry).uri)
}

fun getEmbeddingModel(): TransformersEmbeddingModel {
    val embeddingModel = TransformersEmbeddingModel()
    embeddingModel.afterPropertiesSet()
    return embeddingModel
}

fun getEmbeddingModelForRouting(): TransformersEmbeddingModel {
    val embeddingModel = TransformersEmbeddingModel()
    embeddingModel.setModelResource("file:resources/model/bge-large-en-v1.5/model.onnx")
    embeddingModel.setTokenizerResource("file:resources/model/bge-large-en-v1.5/tokenizer.json")
    embeddingModel.afterPropertiesSet()
    return embeddingModel
}

val ollamaApi = OllamaApi.builder()
    .baseUrl("http://localhost:11434")
    .build()

val ollamaOptions = OllamaOptions.builder().model("deepseek-coder-v2").build()

val ollamaChatModel = OllamaChatModel.builder()
    .ollamaApi(ollamaApi)
    .defaultOptions(ollamaOptions)
    .build()

val topicModelingSystemPrompt = File("/Users/raphaeldelio/Documents/GitHub/redis/kotlinconf-bsky-bot/kotlin-notebooks/notebooks/resources/topic-extractor-prompt.txt").readText()

fun topicExtraction(post: String, existingTopics: String): String {
    val messages = listOf(
        SystemMessage(topicModelingSystemPrompt),
        UserMessage("Existing topics: $existingTopics"),
        UserMessage("Post: $post")
    )

    val response = ollamaChatModel.call(Prompt(messages))
    return response.result.output.text ?: ""
}

fun breakSentenceIntoClauses(sentence: String): List<String> {
    return sentence.split(Regex("""[!?,.:;()"\[\]{}]+"""))
        .filter { it.isNotBlank() }.map { it.trim() }
}

fun matchRoute(query: String): Set<String> {
    return breakSentenceIntoClauses(query).flatMap { clause ->
        val result = getRedisVectorStore().similaritySearch(
            SearchRequest.builder()
                .topK(1)
                .query(clause)
                .build()
        )

        val route = result?.firstOrNull()?.metadata?.get("route") as String
        val minThreshold = result.firstOrNull()?.metadata?.get("minThreshold") as String

        result.forEach {
            println(clause)
            println(route)
            println(it.score ?: 0.0)
            println(minThreshold)
            println()
        }

        result.filter { (it?.score ?: 0.0) > minThreshold.toDouble() }.map {
            it?.metadata?.get("route") as String
        }
    }.toSet()
}

fun trendingTopics(): Set<String> {
    val currentMinute = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0).toString()
    val topTopics = jedisPooled.topkList("topics-topk:$currentMinute")
    topTopics.add("These are the most mentioned topics. Don't try to guess what's being said in the topics.")
    return topTopics.filter { it.isNotBlank() }.toSet()
}

fun processUserRequest(
    query: String,
    routesHandler: (String, String) -> Iterable<String>
): String {
    val routes = matchRoute(query)
    println(routes)

    if (routes.isEmpty()) {
        return "Sorry, I couldn't find any relevant information from your post. Try asking what's trending or what people are saying about a specific topic."
    }

    val enrichedData = routes.map { route -> routesHandler(route, query) }
    println(enrichedData + "\n")

    val systemPrompt = "You are an AI assistant that analyzes social media posts about artificial intelligence. You may receive datasets to support your analysis. Respond in a single paragraph with a maximum of 300 characters—like a tweet. Your answer must be concise, informative, and context-aware. Include relevant insights, trends, or classifications, but never exceed 300 characters. Avoid filler, repetition, or unnecessary explanation. Prioritize clarity, accuracy, and relevance. If unsure, default to brief summaries or best-effort classification. Your goal is to help users quickly understand or categorize AI-related content."

    println("LLM Response:")
    return ollamaChatModel.call(
        Prompt(
            SystemMessage(systemPrompt),
            SystemMessage("Enriching data: $enrichedData"),
            UserMessage("User query: $query")
        )
    ).result.output.text ?: ""
}

fun summarization(userQuery: String): List<String> {
    val existingTopics = jedisPooled.smembers("topics").joinToString { ", " }
    val queryTopics = topicExtraction(userQuery, existingTopics)
        .replace("\"", "")
        .replace("“", "")
        .replace("”", "")
        .split(", ")
        .map { it.trim() }
    println(queryTopics)

    return queryTopics.map { topic ->
        val query = Query("@topics:{'$topic'}")
            .returnFields("text")
            .setSortBy("time_us", false)
            .dialect(2)
            .limit(0, 10)

        val result = jedisPooled.ftSearch(
            "postIdx",
            query
        )

        result.documents.map {
                document -> document.get("text").toString()
        }
    }.flatten()
}

val multiHandler: (String, String) -> Iterable<String> = { route, query ->
    when (route) {
        "trending_topics" -> trendingTopics()
        "summarization" -> summarization(query)
        else -> emptyList()
    }
}

fun getRedisVectorStore(): RedisVectorStore {
    val redisVectorStore = RedisVectorStore.builder(jedisPooled, getEmbeddingModelForRouting())
        .indexName("routeIdx")
        .contentFieldName("text")
        .embeddingFieldName("textEmbedding")
        .metadataFields(
            MetadataField("route", FieldType.TEXT),
            MetadataField("minThreshold", FieldType.NUMERIC),
        )
        .prefix("route:")
        .initializeSchema(true)
        .vectorAlgorithm(RedisVectorStore.Algorithm.FLAT)
        .build()
    redisVectorStore.afterPropertiesSet()
    return redisVectorStore
}

fun getSemanticCacheRedisVectorStore(embeddingModel: TransformersEmbeddingModel): RedisVectorStore {
    val redisVectorStore = RedisVectorStore.builder(jedisPooled, embeddingModel)
        .indexName("semanticCacheIdx")
        .contentFieldName("text")
        .embeddingFieldName("textEmbedding")
        .metadataFields(
            MetadataField("answer", FieldType.TEXT),
        )
        .prefix("semanticcache:")
        .initializeSchema(true)
        .vectorAlgorithm(RedisVectorStore.Algorithm.FLAT)
        .build()
    redisVectorStore.afterPropertiesSet()
    return redisVectorStore
}

fun insertIntoCache(redisVectorStore: RedisVectorStore, post: String, answer: String) {
    redisVectorStore.add(listOf(createDocument(post, answer)))
}

fun createDocument(post: String, answer: String): Document {
    return Document(
        UUID.randomUUID().toString(),
        post,
        mapOf("answer" to answer),
    )
}

fun getFromCache(redisVectorStore: RedisVectorStore, post: String): String {
    val result = redisVectorStore.similaritySearch(post)
    if (result?.isEmpty() == true) {
        return ""
    }

    val score = redisVectorStore.similaritySearch(post)?.first()?.score ?: 0.0
    if (score > 0.9) {
        return result?.first()?.metadata?.get("answer").toString()
    } else {
        return ""
    }
}