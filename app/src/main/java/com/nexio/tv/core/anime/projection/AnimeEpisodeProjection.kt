package com.nexio.tv.core.anime.projection

enum class EpisodeProjectionTarget {
    UI_DISPLAY,
    TRAKT_SCROBBLE,
    SIMKL_SCROBBLE,
    PREMIUM_THUMBNAIL,
    EPISODE_RATING,
    CONTINUE_WATCHING,
}

enum class FallbackReason {
    NO_TVDB_MAPPING,
    NO_TMDB_MAPPING,
    LOW_CONFIDENCE_FLAT_KITSU,
    OVERLAY_MISSING,
}

data class AnimeEpisodeProjection(
    val sourceKitsuId: String,
    val sourceKitsuCoordinate: EpisodeCoordinate,
    val displayCoordinate: EpisodeCoordinate,
    /** The canonical coordinate for the requested EpisodeProjectionTarget. Used by ContinueWatching for dedup. */
    val targetCoordinate: EpisodeCoordinate?,
    val scrobbleCoordinate: EpisodeCoordinate?,
    val premiumArtworkCoordinate: EpisodeCoordinate?,
    val tvdbCoordinate: EpisodeCoordinate?,
    val tmdbCoordinate: EpisodeCoordinate?,
    val confidence: CoordinateConfidence,
    val fallbackReason: FallbackReason?,
    val evidence: List<String>,
)
