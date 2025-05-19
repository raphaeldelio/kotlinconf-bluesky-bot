import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.ollama.OllamaChatModel
import org.springframework.ai.ollama.api.OllamaApi
import org.springframework.ai.ollama.api.OllamaOptions
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.StreamEntryID
import redis.clients.jedis.bloom.BFReserveParams
import redis.clients.jedis.exceptions.JedisDataException
import redis.clients.jedis.params.XReadGroupParams
import redis.clients.jedis.resps.StreamEntry
import java.io.File
import java.time.Duration
import java.time.LocalDateTime

fun main() {
    val jedis = JedisPooled()
    val jedisPool = JedisPool()

    // Ollama doesn't run concurrently :( - OpenAI could do it
    val chatModel = getOllamaChatModel()
    getOpenAiChatModel()

    val bloomFilterName = "topic-extractor-bf"
    createBloomFilter(jedis, bloomFilterName)
    createConsumerGroup(jedis, "filtered-events", "topic-extractor-example")

    runBlocking {
        listOf(
            async(Dispatchers.IO) {
                consumeStream(
                    jedisPool,
                    jedis,
                    streamName = "filtered-events",
                    consumerGroup = "topic-extractor-example",
                    consumer = "topic-extractor-1",
                    handlers = listOf(
                        deduplicate(jedis, bloomFilterName),
                        extractTopics(chatModel, jedisPool),
                        printUri,
                    ),
                    count = 1
                )
            },
            async(Dispatchers.IO) {
                consumeStream(
                    jedisPool,
                    jedis,
                    streamName = "filtered-events",
                    consumerGroup = "topic-extractor-example",
                    consumer = "topic-extractor-2",
                    handlers = listOf(
                        deduplicate(jedis, bloomFilterName),
                        extractTopics(chatModel, jedisPool),
                        printUri,
                    ),
                    count = 1
                )
            }
        ).awaitAll()
    }
}

private fun getOpenAiChatModel(): OpenAiChatModel {
    val factory = SimpleClientHttpRequestFactory().apply {
        setReadTimeout(Duration.ofSeconds(60))
    }

    val openAiApi = OpenAiApi.builder()
        .apiKey(System.getenv("OPEN_AI_KEY"))
        .restClientBuilder(RestClient.builder().requestFactory(factory))
        .build()

    val options = OpenAiChatOptions.builder()
        .model("gpt-4o-mini")
        .build()

    val openAiChatModel = OpenAiChatModel.builder()
        .openAiApi(openAiApi)
        .defaultOptions(options)
        .build()
    return openAiChatModel
}

private fun getOllamaChatModel(): OllamaChatModel {
    val ollamaApi = OllamaApi.builder()
        .baseUrl("http://localhost:11434")
        .build()

    val ollamaOptions = OllamaOptions.builder().model("deepseek-coder-v2").build()

    return OllamaChatModel.builder()
        .ollamaApi(ollamaApi)
        .defaultOptions(ollamaOptions)
        .build()
}

val topicModelingSystemPrompt = File("/Users/raphaeldelio/Documents/GitHub/redis/kotlinconf-bsky-bot/kotlin-notebooks/notebooks/resources/topic-modeling-prompt.txt").readText()

fun createConsumerGroup(jedis: JedisPooled, streamName: String, consumerGroupName: String) {
    try {
        jedis.xgroupCreate(streamName, consumerGroupName, StreamEntryID("0-0"), true)
    } catch (_: Exception) {
        println("Group already exists")
    }
}

fun readFromStream(jedis: JedisPooled, streamName: String, consumerGroup: String, consumer: String, count: Int): List<Map.Entry<String, List<StreamEntry>>> {
    return jedis.xreadGroup(
        consumerGroup,
        consumer,
        XReadGroupParams().count(count).block(2000),
        mapOf(
            streamName to StreamEntryID.XREADGROUP_UNDELIVERED_ENTRY
        )
    ) ?: emptyList()
}

fun ackAndBfFn(jedisPool: JedisPool, bloomFilter: String, streamName: String, consumerGroup: String, entry: StreamEntry) {
    jedisPool.resource.use { jedis ->
        // Create a transaction
        val multi = jedis.multi()

        // Acknowledge the message
        multi.xack(
            streamName,
            consumerGroup,
            entry.id
        )

        // Add the URI to the bloom filter
        multi.bfAdd(bloomFilter, Event.fromMap(entry).uri)

        // Execute the transaction
        multi.exec()
    }
}

fun consumeStream(
    jedisPool: JedisPool,
    jedis: JedisPooled,
    streamName: String,
    consumerGroup: String,
    consumer: String,
    handlers: List<(Event) -> Pair<Boolean, String>>,
    count: Int = 5
) {
    while (!Thread.currentThread().isInterrupted) {
        val entries = readFromStream(jedis, streamName, consumerGroup, consumer, count)
        val allEntries = entries.flatMap { it.value }
        for (entry in allEntries) {
            val event = Event.fromMap(entry)

            for (handler in handlers) {
                val (shouldContinue, message) = handler(event)
                ackAndBfFn(jedisPool, "store-bf", streamName, consumerGroup, entry)

                if (!shouldContinue) {
                    println("$consumer: Handler stopped processing: $message")
                    break
                }
            }
        }
    }
}

fun topicModeling(chatModel: ChatModel, post: String, existingTopics: String): String {
    val messages = listOf(
        SystemMessage(topicModelingSystemPrompt),
        UserMessage("Existing topics: $existingTopics"),
        UserMessage("Post: $post")
    )

    val response = chatModel.call(Prompt(messages))

    return response.result.output.text ?: ""
}

fun createBloomFilter(jedis: JedisPooled, name: String) {
    runCatching {
        jedis.bfReserve(name, 0.01, 1_000_000L, BFReserveParams().expansion(2))
    }.onFailure {
        println("Bloom filter already exists")
    }
}

fun createCountMinSketch(jedisPool: JedisPool): String {
    val windowBucket = LocalDateTime.now().withSecond(0).withNano(0)
    try {
        jedisPool.resource.use {
            val multi = it.multi()
            multi.cmsInitByDim("topics-cms:$windowBucket", 3000, 10)
            multi.exec()
        }
    } catch (_: JedisDataException) {
        println("Count-min sketch already exists")
    }

    return "topics-cms:$windowBucket"
}

val printUri: (Event) -> Pair<Boolean, String> = {
    println("Got event from ${it.uri}")
    Pair(true, "OK")
}

fun deduplicate(jedis: JedisPooled, bloomFilter: String): (Event) -> Pair<Boolean, String> {
    return { event ->
        if (jedis.bfExists(bloomFilter, event.uri)) {
            Pair(false, "${event.uri} already processed")
        } else {
            Pair(true, "OK")
        }
    }
}

fun extractTopics(chatModel: ChatModel, jedisPool: JedisPool): (Event) -> Pair<Boolean, String> = { event ->
    jedisPool.resource.use { jedis ->
        val existingTopics = jedis.smembers("topics")
        val topics = topicModeling(chatModel, event.text, existingTopics.joinToString(", "))
            .replace("\"", "")
            .replace("“", "")
            .replace("”", "")
            .split(",")
            .map { it.trim() }

        val cmsKey = createCountMinSketch(jedisPool)
        val multi = jedis.multi()
        multi.hset("post:" + event.uri, mapOf("topics" to topics.joinToString("|")))
        multi.sadd("topics", *topics.toTypedArray())
        multi.cmsIncrBy(cmsKey, topics.filter { it.isNotBlank() }.associateWith { 1 })
        multi.exec()
        Pair(true, "OK")
    }
}