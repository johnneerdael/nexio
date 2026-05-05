package com.nexio.tv.data.repository

enum class ScrobbleRejectionReason {
    /** Content id uses an anime-native scheme (kitsu:, mal:, anilist:, anidb:) with no parseable Trakt ids. */
    NO_PARSEABLE_IDS,

    /** AnimeSeasonProjectionResolver could not produce a confident scrobble coordinate (Phase 1). */
    ANIME_COORDINATE_UNRESOLVED,

    /** Provider returned an empty id bundle after resolution. */
    EMPTY_ID_BUNDLE,
}
