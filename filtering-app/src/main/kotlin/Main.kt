import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.djl.inference.Predictor
import ai.djl.modality.nlp.translator.ZeroShotClassificationInput
import ai.djl.modality.nlp.translator.ZeroShotClassificationOutput
import ai.djl.repository.zoo.Criteria
import ai.djl.repository.zoo.ModelZoo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.StreamEntryID
import redis.clients.jedis.bloom.BFReserveParams
import redis.clients.jedis.params.XAddParams
import redis.clients.jedis.params.XReadGroupParams
import redis.clients.jedis.resps.StreamEntry
import java.nio.file.Paths

fun main() {
    val jedis = JedisPooled()

    val tokenizer = HuggingFaceTokenizer.newInstance(Paths.get("/Users/raphaeldelio/Documents/GitHub/redis/kotlinconf-bluesky-bot/model/DeBERTa-v3-large-mnli-fever-anli-ling-wanli/tokenizer.json"))

    val translator = CustomZeroShotClassificationTranslator.builder(tokenizer).build()

    val criteria: Criteria<ZeroShotClassificationInput, ZeroShotClassificationOutput> = Criteria.builder()
        .setTypes(
            ZeroShotClassificationInput::class.java,
            ZeroShotClassificationOutput::class.java
        )
        .optModelPath(Paths.get("/Users/raphaeldelio/Documents/GitHub/redis/kotlinconf-bluesky-bot/model/DeBERTa-v3-large-mnli-fever-anli-ling-wanli"))
        .optEngine("PyTorch")
        .optTranslator(translator)
        .build()

    val model = ModelZoo.loadModel(criteria)
    val predictor = model.newPredictor()

    val bloomFilterName = "store-bf"
    createBloomFilter(jedis, bloomFilterName)

    createConsumerGroup(jedis, "jetstream", "store-example")

    runBlocking {
        listOf(
            async(Dispatchers.IO) {
                consumeStream(
                    jedis = jedis,
                    consumer = "store-1",
                    predictor = predictor,
                    bloomFilterName = bloomFilterName
                )
            },
            async(Dispatchers.IO) {
                consumeStream(
                    jedis = jedis,
                    consumer = "store-2",
                    predictor = predictor,
                    bloomFilterName = bloomFilterName
                )
            },
            async(Dispatchers.IO) {
                consumeStream(
                    jedis = jedis,
                    consumer = "store-3",
                    predictor = predictor,
                    bloomFilterName = bloomFilterName
                )
            },
            async(Dispatchers.IO) {
                consumeStream(
                    jedis = jedis,
                    consumer = "store-4",
                    predictor = predictor,
                    bloomFilterName = bloomFilterName
                )
            }
        ).awaitAll()
    }
}

fun consumeStream(
    jedis: JedisPooled,
    consumer: String,
    predictor: Predictor<ZeroShotClassificationInput, ZeroShotClassificationOutput>,
    bloomFilterName: String) {
    consumeStream(
        jedis,
        streamName = "jetstream",
        consumerGroup = "store-example",
        consumer = consumer,
        handlers = listOf(
            deduplicate(jedis, bloomFilterName),
            filter(predictor),
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

fun classify(predictor: Predictor<ZeroShotClassificationInput, ZeroShotClassificationOutput>, premise: String): ZeroShotClassificationOutput {
    val candidateLabels = listOf("Politics")
    val input = ZeroShotClassificationInput(premise, candidateLabels.toTypedArray(), true, "{}")
    return predictor.predict(input)
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

fun filter(predictor: Predictor<ZeroShotClassificationInput, ZeroShotClassificationOutput>): (Event) -> Pair<Boolean, String> =
    { event ->
        if (event.text.isNotBlank() && event.operation != "delete") {
            val classification = classify(predictor, event.text)
            if (classification.scores.any { it > 0.90 }) {
                Pair(true, "OK")
            } else {
                Pair(false, "Not a post related to software")
            }
        } else {
            Pair(false, "Text is null or empty")
        }
    }

fun storeEvent(jedis: JedisPooled): (Event) -> Pair<Boolean, String> = { event ->
    jedis.hset("post:" + event.uri, event.toMap())
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


