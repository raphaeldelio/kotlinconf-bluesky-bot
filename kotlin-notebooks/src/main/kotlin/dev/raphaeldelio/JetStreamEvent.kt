package dev.raphaeldelio

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class JetStreamEvent(
    val did: String,
    @SerialName("time_us") val timeUs: Long,
    val kind: String? = null,
    val commit: Commit? = null
) {
    @Serializable
    data class Commit(
        val rev: String? = null,
        val operation: String? = null,
        val collection: String? = null,
        val rkey: String? = null,
        val record: Record? = null,
        val cid: String? = null
    )

    @Serializable
    data class Record(
        @SerialName("\$type") val type: String? = null,
        val timeUs: String? = null,
        val text: String? = null,
        val langs: List<String>? = null,
        val facets: List<Facet>? = null,
        val reply: Reply? = null,
        val embed: Embed? = null
    )

    @Serializable
    data class Reply(
        val parent: PostRef? = null,
        val root: PostRef? = null
    )

    @Serializable
    data class PostRef(
        val cid: String? = null,
        val uri: String? = null
    )

    @Serializable
    data class Facet(
        @SerialName("\$type") val type: String? = null,
        val features: List<Feature>? = null,
        val index: Index? = null
    )

    @Serializable
    data class Feature(
        @SerialName("\$type") val type: String? = null,
        val did: String? = null
    )

    @Serializable
    data class Index(
        val byteStart: Int? = null,
        val byteEnd: Int? = null
    )

    @Serializable
    data class Embed(
        @SerialName("\$type") val type: String? = null,
        val images: List<EmbedImage>? = null
    )

    @Serializable
    data class EmbedImage(
        val alt: String? = null,
        val aspectRatio: AspectRatio? = null,
        val image: Image? = null
    )

    @Serializable
    data class AspectRatio(
        val height: Int? = null,
        val width: Int? = null
    )

    @Serializable
    data class Image(
        @SerialName("\$type") val type: String? = null,
        val ref: Ref? = null,
        val mimeType: String? = null,
        val size: Int? = null
    )

    @Serializable
    data class Ref(
        @SerialName("\$link") val link: String? = null
    )
}