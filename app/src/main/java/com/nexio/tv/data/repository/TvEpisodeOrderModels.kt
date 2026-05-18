package com.nexio.tv.data.repository

enum class TvEpisodeOrderProvider {
    TMDB_DEFAULT,
    TVDB_DEFAULT
}

data class TvEpisodeOrderResolution(
    val provider: TvEpisodeOrderProvider,
    val tmdbTvId: String,
    val tvdbSeriesId: String? = null,
    val reason: String
)

fun normalizeTmdbTvEpisodeOrderKey(tmdbTvId: String?): String? {
    val value = tmdbTvId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val id = when {
        value.startsWith(TMDB_TV_PREFIX) -> value.removePrefix(TMDB_TV_PREFIX)
        value.startsWith(TMDB_PREFIX) -> value.removePrefix(TMDB_PREFIX)
        else -> value
    }.trim()
    return id.takeIf { it.isNotEmpty() }?.let { "$TMDB_TV_PREFIX$it" }
}

private const val TMDB_PREFIX = "tmdb:"
private const val TMDB_TV_PREFIX = "tmdb:tv:"
