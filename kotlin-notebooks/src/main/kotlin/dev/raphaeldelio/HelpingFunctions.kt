package dev.raphaeldelio

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
import java.io.File

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

val ollamaApi = OllamaApi.builder()
    .baseUrl("http://localhost:11434")
    .build()

val ollamaOptions = OllamaOptions.builder().model("deepseek-coder-v2").build()

val ollamaChatModel = OllamaChatModel.builder()
    .ollamaApi(ollamaApi)
    .defaultOptions(ollamaOptions)
    .build()

val topicModelingSystemPrompt = File("/Users/raphaeldelio/Documents/GitHub/redis/kotlinconf-bsky-bot/kotlin-notebooks/notebooks/resources/topic-modeling-prompt.txt").readText()

fun topicModeling(post: String, existingTopics: String): String {
    val messages = listOf(
        SystemMessage(topicModelingSystemPrompt),
        UserMessage("Existing topics: $existingTopics"),
        UserMessage("Post: $post")
    )

    val response = ollamaChatModel.call(Prompt(messages))
    return response.result.output.text ?: ""
}