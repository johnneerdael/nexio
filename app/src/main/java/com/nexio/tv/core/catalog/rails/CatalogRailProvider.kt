package com.nexio.tv.core.catalog.rails

/**
 * Closed set of catalog-rail providers in the Nexio app.
 *
 * Every catalog rail surfaced on Home / Discover originates from exactly one of these.
 * The [id] is the stable string used in trace events, audit reports, and on-disk caches —
 * never rename it.
 */
enum class CatalogRailProvider(val id: String) {
    TRAKT("trakt"),
    MDBLIST("mdblist"),
    SIMKL("simkl"),
    TMDB_BUILTIN("tmdb-builtin"),
    KITSU_BUILTIN("kitsu-builtin"),
    ADDON("addon");
}
