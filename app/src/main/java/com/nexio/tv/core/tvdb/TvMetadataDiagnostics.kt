package com.nexio.tv.core.tvdb

enum class TvProvider {
    TVDB,
    TMDB,
    KITSU
}

enum class TvMetadataDecisionReason(val eventName: String) {
    TVDB_INACTIVE("tvdb_inactive_tmdb_fallback"),
    TVDB_SUCCESS("tvdb_success"),
    TVDB_FALLBACK_TMDB("tvdb_fallback_tmdb"),
    TVDB_IDENTITY_MISSING("tvdb_identity_missing"),
    TVDB_RECORD_MISSING("tvdb_record_missing"),
    KITSU_SUCCESS("kitsu_success"),
    TMDB_TV_SKIPPED("tmdb_tv_skipped"),
    POSTER_RATINGS_OVERRIDE("poster_ratings_override"),
    TVDB_SEASON_TYPE_PRESENT("tvdb_season_type_present"),
    TVDB_CANONICAL_TRAKT_NUMBERING_USED("tvdb_canonical_trakt_numbering_used"),
    TVDB_ALTERNATE_ORDER_PRESERVED("tvdb_alternate_order_preserved"),
    TVDB_ADVANCED_SURFACE_SUCCESS("tvdb_advanced_surface_success"),
    TVDB_ADVANCED_SURFACE_MISSING("tvdb_advanced_surface_missing"),
    TVDB_TRAILER_SUCCESS("tvdb_trailer_success"),
    TVDB_TRAILER_MISSING("tvdb_trailer_missing"),
    TVDB_TRAILER_UNUSABLE_URL("tvdb_trailer_unusable_url"),
    TMDB_TRAILER_FALLBACK("tmdb_trailer_fallback")
}

data class TvMetadataDiagnosticEvent(
    val reason: TvMetadataDecisionReason,
    val contentId: String,
    val provider: TvProvider? = null,
    val fallbackProvider: TvProvider? = null,
    val detail: String? = null
)
