package com.nexio.tv.core.tvdb

private const val TVDB_ARTWORK_BASE_URL = "https://artworks.thetvdb.com"

private val EPISODE_STILL_IMAGE_TYPES = setOf(11, 12)

internal fun String?.toTvdbImageUrl(): String? {
    val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return when {
        value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true) -> value
        value.startsWith("/") -> "$TVDB_ARTWORK_BASE_URL$value"
        else -> "$TVDB_ARTWORK_BASE_URL/$value"
    }
}

internal fun String?.toTvdbEpisodeStillUrl(
    imageType: Int?,
    fallbackThumbnail: String?
): String? {
    val image = this?.trim()?.takeIf { it.isNotBlank() }
    val fallback = fallbackThumbnail?.trim()?.takeIf { it.isNotBlank() }

    return when {
        image.isTvdbEpisodeStillPath(imageType) -> image.toTvdbImageUrl()
        fallback.isTvdbEpisodeStillPath(imageType = null) -> fallback.toTvdbImageUrl()
        else -> null
    }
}

private fun String?.isTvdbEpisodeStillPath(imageType: Int?): Boolean {
    val value = this ?: return false
    return imageType in EPISODE_STILL_IMAGE_TYPES ||
        value.contains("/episode/", ignoreCase = true) ||
        value.contains("/episodes/", ignoreCase = true)
}
