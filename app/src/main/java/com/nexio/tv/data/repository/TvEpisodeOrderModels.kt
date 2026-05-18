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

fun toTmdbTvOrderKey(tmdbTvId: String?): String {
    val value = tmdbTvId?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw IllegalArgumentException("TMDB TV ID is required")
    val id = when {
        value.startsWith(TMDB_TV_PREFIX) -> value.removePrefix(TMDB_TV_PREFIX)
        value.startsWith(TMDB_PREFIX) -> value.removePrefix(TMDB_PREFIX)
        else -> value
    }.trim()
    require(id.isNotEmpty() && id.all { char -> char.isDigit() }) {
        "TMDB TV ID must be numeric"
    }
    return "$TMDB_TV_PREFIX$id"
}

fun normalizeTmdbTvEpisodeOrderKey(tmdbTvId: String?): String? =
    runCatching { toTmdbTvOrderKey(tmdbTvId) }.getOrNull()

private const val TMDB_PREFIX = "tmdb:"
private const val TMDB_TV_PREFIX = "tmdb:tv:"
