package dev.raphaeldelio

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.StreamEntryID
import redis.clients.jedis.params.XAddParams

fun main() {
    val client = HttpClient(CIO) {
        install(WebSockets)
    }

    val jedis = JedisPooled()

    runBlocking {
        consumeJetstream(client) { event ->
            println("Received event: ${event.did}")
            addToStream(jedis, "jetstream", event.toMap())
        }
    }
}

suspend fun consumeJetstream(client: HttpClient, limit: Int = -1, onEvent: (Event) -> Unit) {
    val jsonParser = Json { ignoreUnknownKeys = true }
    var i = 0
    client.webSocket("wss://jetstream2.us-east.bsky.network/subscribe?wantedCollections=app.bsky.feed.post") {
        for (message in incoming) {
            i++
            if (limit > 0 && i > limit) {
                close()
                break
            }

            if (message is Frame.Text) {
                val event = jsonParser.decodeFromString<Event>(message.readText())
                onEvent(event)
            }
        }
    }
}

fun addToStream(jedis: JedisPooled, streamName: String, hash: Map<String, String>) = jedis.xadd(
    streamName,
    XAddParams.xAddParams()
        .id(StreamEntryID.NEW_ENTRY)
        .maxLen(1_000_000)
        .exactTrimming(),
    hash
)
