package com.nexio.tv.core.image

object ArtworkImageCacheKeys {
    private const val NATIVE_PROVIDER = "native"
    private const val RPDB_PROVIDER = "rpdb"
    private const val TOP_POSTERS_PROVIDER = "top_posters"
    private const val POSTER_CACHE_TYPE = "poster_v2"

    fun poster(itemId: String, providerTag: String?): String =
        build(itemId, resolvePosterProvider(providerTag, posterUrl = null), POSTER_CACHE_TYPE)

    fun poster(itemId: String, providerTag: String?, posterUrl: String?): String =
        build(itemId, resolvePosterProvider(providerTag, posterUrl), POSTER_CACHE_TYPE)

    fun backdrop(itemId: String): String =
        build(itemId, NATIVE_PROVIDER, "background")

    fun logo(itemId: String): String =
        build(itemId, NATIVE_PROVIDER, "logo")

    fun thumbnail(itemId: String): String =
        build(itemId, NATIVE_PROVIDER, "thumbnail")

    private fun resolvePosterProvider(providerTag: String?, posterUrl: String?): String {
        providerTag?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        val normalizedUrl = posterUrl?.trim().orEmpty()
        return when {
            normalizedUrl.startsWith("https://api.ratingposterdb.com/") -> RPDB_PROVIDER
            normalizedUrl.startsWith("https://api.top-posters.com/") -> TOP_POSTERS_PROVIDER
            else -> NATIVE_PROVIDER
        }
    }

    private fun build(itemId: String, provider: String, type: String): String =
        "artwork:$provider:$type:$itemId:imageLang:en:policy:1"
}
