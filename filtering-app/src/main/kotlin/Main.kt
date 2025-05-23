import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.springframework.ai.document.Document
import org.springframework.ai.transformers.TransformersEmbeddingModel
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.redis.RedisVectorStore
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.StreamEntryID
import redis.clients.jedis.bloom.BFReserveParams
import redis.clients.jedis.params.XAddParams
import redis.clients.jedis.params.XReadGroupParams
import redis.clients.jedis.resps.StreamEntry
import java.io.File
import java.util.UUID

fun main() {
    val jedis = JedisPooled()

    val embeddingModel = TransformersEmbeddingModel()
    embeddingModel.setModelResource("https://huggingface.co/sentence-transformers/all-mpnet-base-v2/resolve/main/onnx/model.onnx?download=true")
    embeddingModel.setTokenizerResource("https://huggingface.co/sentence-transformers/all-mpnet-base-v2/raw/main/tokenizer.json")
    embeddingModel.afterPropertiesSet()

    val wasIndexAlreadyCreated = jedis.ftList().contains("classifierIdx")
    val redisVectorStore = getRedisVectorStore(jedis, embeddingModel)

    if (!wasIndexAlreadyCreated) {
        loadReferencesIntoRedis(redisVectorStore)
    }

    val bloomFilterName = "store-bf"
    createBloomFilter(jedis, bloomFilterName)

    createConsumerGroup(jedis, "jetstream", "store-example")

    runBlocking {
        listOf(
            async(Dispatchers.IO) {
                consumeStream(
                    jedis = jedis,
                    consumer = "store-1",
                    redisVectorStore = redisVectorStore,
                    bloomFilterName = bloomFilterName
                )
            },
            async(Dispatchers.IO) {
                consumeStream(
                    jedis = jedis,
                    consumer = "store-2",
                    redisVectorStore = redisVectorStore,
                    bloomFilterName = bloomFilterName
                )
            },
            async(Dispatchers.IO) {
                consumeStream(
                    jedis = jedis,
                    consumer = "store-3",
                    redisVectorStore = redisVectorStore,
                    bloomFilterName = bloomFilterName
                )
            },
            async(Dispatchers.IO) {
                consumeStream(
                    jedis = jedis,
                    consumer = "store-4",
                    redisVectorStore = redisVectorStore,
                    bloomFilterName = bloomFilterName
                )
            }
        ).awaitAll()
    }
}

fun consumeStream(
    jedis: JedisPooled,
    consumer: String,
    redisVectorStore: RedisVectorStore,
    bloomFilterName: String) {
    consumeStream(
        jedis,
        streamName = "jetstream",
        consumerGroup = "store-example",
        consumer = consumer,
        handlers = listOf(
            deduplicate(jedis, bloomFilterName),
            filter(redisVectorStore),
            storeEvent(jedis),
            printUri,
            addFilteredEventToStream(jedis)
        ),
        count = 1
    )
}

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

fun ackAndBfFn(jedisPooled: JedisPooled, bloomFilter: String, streamName: String, consumerGroup: String, entry: StreamEntry) {
    // Acknowledge the message
    jedisPooled.xack(
        streamName,
        consumerGroup,
        entry.id
    )

    // Add the URI to the bloom filter
    jedisPooled.bfAdd(bloomFilter, Event.fromMap(entry).uri)
}

fun consumeStream(
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
                ackAndBfFn(jedis, "store-bf", streamName, consumerGroup, entry)

                if (!shouldContinue) {
                    println("$consumer: Handler stopped processing: $message")
                    break
                }
            }
        }
    }
}

fun createBloomFilter(jedis: JedisPooled, name: String) {
    runCatching {
        jedis.bfReserve(name, 0.01, 1_000_000L, BFReserveParams().expansion(2))
    }.onFailure {
        println("Bloom filter already exists")
    }
}

fun breakSentenceIntoClauses(sentence: String): List<String> {
    return sentence.split(Regex("""[!?,.:]+"""))
        .filter { it.isNotBlank() }.map { it.trim() }
}

fun removeUrls(text: String): String {
    return text.replace(Regex("""(?:https?:\/\/)?(?:www\.)?[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}(\/\S*)?"""), "")
        .replace(Regex("""@\w+"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

fun classify(redisVectorStore: RedisVectorStore, post: String): List<Double> {
    val cleanedPost = removeUrls(post)
    return breakSentenceIntoClauses(cleanedPost).map { clause ->
        println(clause)
        (redisVectorStore.similaritySearch(
            SearchRequest.builder()
                .topK(1)
                .query(clause)
                .build()
        )?.map {
            println("Matched sentence: ${it.text}")
            it.score ?: 0.0
        } ?: emptyList())
    }.flatten()
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

fun filter(redisVectorStore: RedisVectorStore): (Event) -> Pair<Boolean, String> =
    { event ->
        if (event.text.isNotBlank() && event.langs.contains("en") && event.operation != "delete") {
            val scores = classify(redisVectorStore, event.text)
            if (scores.any { it > 0.76 }) {
                Pair(true, "OK")
            } else {
                Pair(false, "Not a post related to software")
            }
        } else {
            Pair(false, "Text is null or empty")
        }
    }

fun storeEvent(jedis: JedisPooled): (Event) -> Pair<Boolean, String> = { event ->
    jedis.hset("post:" + event.uri.replace("at://did:plc:", ""), event.toMap())
    Pair(true, "OK")
}

fun addFilteredEventToStream(jedis: JedisPooled): (Event) -> Pair<Boolean, String> = { event ->
    jedis.xadd(
        "filtered-events",
        XAddParams.xAddParams()
            .id(StreamEntryID.NEW_ENTRY)
            .maxLen(1_000_000)
            .exactTrimming(),
        event.toMap()
    )
    Pair(true, "OK")
}

fun getRedisVectorStore(jedisPooled: JedisPooled, embeddingModel: TransformersEmbeddingModel): RedisVectorStore {
    val redisVectorStore = RedisVectorStore.builder(jedisPooled, embeddingModel)
        .indexName("classifierIdx")
        .contentFieldName("text")
        .embeddingFieldName("textEmbedding")
        .prefix("classifier:")
        .initializeSchema(true)
        .vectorAlgorithm(RedisVectorStore.Algorithm.FLAT)
        .build()
    redisVectorStore.afterPropertiesSet()
    return redisVectorStore
}

fun loadReferencesIntoRedis(redisVectorStore: RedisVectorStore) {
    val references = Json.decodeFromString<List<String>>(File("/Users/raphaeldelio/Documents/GitHub/redis/kotlinconf-bsky-bot/filtering-app/src/main/resources/filtering_examples.json").readText())

    val documents = references.map { text ->
        createFilterDocument(text)
    }

    redisVectorStore.add(documents)
}

fun createFilterDocument(text: String): Document {
    return Document(
        UUID.randomUUID().toString(),
        text,
        mapOf(
            "text" to text,
        )
    )
}