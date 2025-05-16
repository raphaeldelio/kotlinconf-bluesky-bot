import redis.clients.jedis.resps.StreamEntry

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
) {
    fun toMap(): Map<String, String> = mapOf(
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
            val fields = entry.fields
            return Event(
                did = fields["did"] ?: "",
                rkey = fields["rkey"] ?: "",
                text = fields["text"] ?: "",
                timeUs = fields["timeUs"] ?: "",
                operation = fields["operation"] ?: "",
                uri = fields["uri"] ?: "",
                parentUri = fields["parentUri"] ?: "",
                rootUri = fields["rootUri"] ?: "",
                langs = fields["langs"]?.replace("[", "")?.replace("]", "")?.split(", ") ?: emptyList()
            )
        }
    }
}