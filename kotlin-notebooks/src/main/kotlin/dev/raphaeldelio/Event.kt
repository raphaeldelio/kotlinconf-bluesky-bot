package dev.raphaeldelio

import redis.clients.jedis.resps.StreamEntry
import redis.clients.jedis.search.Document

data class Event(
    val did: String,
    val rkey: String,
    val text: String,
    val timeUs: String,
    val operation: String,
    val uri: String,
    val parentUri: String,
    val rootUri: String,
    val langs: List<String>,
    val similarityScore: Double
) {
    fun toMap() = mapOf(
        "did" to this.did,
        "timeUs" to this.timeUs,
        "text" to this.text,
        "langs" to this.langs.joinToString("|"),
        "operation" to this.operation,
        "rkey" to this.rkey,
        "parentUri" to this.parentUri,
        "rootUri" to this.rootUri,
        "uri" to this.uri
    )

    companion object {
        fun fromMap(entry: StreamEntry): Event {
            return fromMap(entry.fields)
        }

        fun fromMap(document: Document): Event {
            val fields = document.properties.associate { entry ->  entry.key to entry.value.toString()}
            return fromMap(fields)
        }

        fun fromMap(fields: Map<String, String>): Event {
            return Event(
                did = fields["did"] ?: "",
                rkey = fields["rkey"] ?: "",
                text = fields["text"] ?: "",
                timeUs = fields["timeUs"] ?: "",
                operation = fields["operation"] ?: "",
                uri = fields["uri"] ?: "",
                parentUri = fields["parentUri"] ?: "",
                rootUri = fields["rootUri"] ?: "",
                langs = fields["langs"]?.replace("[", "")?.replace("]", "")?.split(", ") ?: emptyList(),
                similarityScore = fields["similarityScore"]?.toDouble() ?: 0.0
            )
        }
    }
}