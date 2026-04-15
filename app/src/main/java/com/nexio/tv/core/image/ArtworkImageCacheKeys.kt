package com.nexio.tv.core.image

object ArtworkImageCacheKeys {
    private const val NATIVE_PROVIDER = "native"

    fun poster(itemId: String, providerTag: String?): String =
        build(itemId, providerTag?.takeIf { it.isNotBlank() } ?: NATIVE_PROVIDER, "poster")

    fun backdrop(itemId: String): String =
        build(itemId, NATIVE_PROVIDER, "background")

    fun logo(itemId: String): String =
        build(itemId, NATIVE_PROVIDER, "logo")

    fun thumbnail(itemId: String): String =
        build(itemId, NATIVE_PROVIDER, "thumbnail")

    private fun build(itemId: String, provider: String, type: String): String =
        "${itemId}_${provider}_${type}"
}
