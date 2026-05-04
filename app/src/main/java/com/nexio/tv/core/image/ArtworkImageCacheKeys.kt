package com.nexio.tv.core.image

import java.security.MessageDigest
import java.util.Locale

object ArtworkImageCacheKeys {
    private const val NATIVE_PROVIDER = "native"
    private const val INTERNAL_PROVIDER = "internal"
    private const val RPDB_PROVIDER = "rpdb"
    private const val TOP_POSTERS_PROVIDER = "top_posters"
    private const val POSTER_CACHE_TYPE = "poster"

    fun poster(itemId: String, providerTag: String?): String =
        build(itemId, resolvePosterProvider(providerTag, posterUrl = null), POSTER_CACHE_TYPE)

    fun poster(itemId: String, providerTag: String?, posterUrl: String?): String =
        internalModelKey(itemId, POSTER_CACHE_TYPE, posterUrl)
            ?: build(itemId, resolvePosterProvider(providerTag, posterUrl), POSTER_CACHE_TYPE)

    fun backdrop(itemId: String): String =
        build(itemId, NATIVE_PROVIDER, "background")

    fun backdrop(itemId: String, imageUrl: String?): String =
        internalModelKey(itemId, "background", imageUrl) ?: backdrop(itemId)

    fun logo(itemId: String): String =
        build(itemId, NATIVE_PROVIDER, "logo")

    fun logo(itemId: String, imageUrl: String?): String =
        internalModelKey(itemId, "logo", imageUrl) ?: logo(itemId)

    fun thumbnail(itemId: String): String =
        build(itemId, NATIVE_PROVIDER, "thumbnail")

    fun thumbnail(itemId: String, imageUrl: String?): String =
        internalModelKey(itemId, "thumbnail", imageUrl) ?: thumbnail(itemId)

    private fun resolvePosterProvider(providerTag: String?, posterUrl: String?): String {
        providerTag?.trim()?.takeIf { it.isNotBlank() }?.let { return it.lowercase(Locale.ROOT) }
        val normalizedUrl = posterUrl?.trim().orEmpty()
        return when {
            normalizedUrl.startsWith("https://api.ratingposterdb.com/") -> RPDB_PROVIDER
            normalizedUrl.startsWith("https://api.top-posters.com/") -> TOP_POSTERS_PROVIDER
            else -> NATIVE_PROVIDER
        }
    }

    private fun build(itemId: String, provider: String, type: String): String =
        "artwork:$provider:$type:$itemId:imageLang:en:policy:1"

    private fun internalModelKey(itemId: String, type: String, imageUrl: String?): String? {
        val model = imageUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (!model.isInternalArtworkModel()) return null
        return "artwork:$INTERNAL_PROVIDER:$type:$itemId:model:${model.sha256()}:imageLang:en:policy:1"
    }

    private fun String.isInternalArtworkModel(): Boolean =
        startsWith("nexio-artwork://") || startsWith("nexio-placeholder://")

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
