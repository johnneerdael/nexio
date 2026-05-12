package com.nexio.tv.core.sync

enum class AccountSettingsSectionKey(val key: String) {
    INTEGRATIONS_SUBTITLE_TRANSLATION("integrations.subtitleTranslation"),
    INTEGRATIONS_IMDB("integrations.imdb"),
    INTEGRATIONS_GEMINI("integrations.gemini"),
    INTEGRATIONS_TMDB("integrations.tmdb"),
    INTEGRATIONS_OMDB("integrations.omdb"),
    INTEGRATIONS_POSTER_RATINGS("integrations.posterRatings"),
    INTEGRATIONS_ANIME_SKIP("integrations.animeSkip"),
    INTEGRATIONS_MDBLIST("integrations.mdblist"),
    INTEGRATIONS_KITSU("integrations.kitsu"),
    INTEGRATIONS_TRAKT_AUTH("integrations.traktAuth"),
    INTEGRATIONS_SIMKL_AUTH("integrations.simklAuth"),
    INTEGRATIONS_KITSU_AUTH("integrations.kitsuAuth"),
    INTEGRATIONS_DEBRID_PREMIUMIZE("integrations.debrid.premiumize"),
    INTEGRATIONS_DEBRID_REAL_DEBRID("integrations.debrid.realDebrid"),
    INTEGRATIONS_DEBRID_TOR_BOX("integrations.debrid.torBox"),
    INTEGRATIONS_DEBRID_EASY_DEBRID("integrations.debrid.easyDebrid"),
    CATALOGS_MDBLIST("catalogs.mdblist"),
    CATALOGS_TRAKT("catalogs.trakt"),
    CATALOGS_SIMKL("catalogs.simkl"),
    CATALOGS_TMDB("catalogs.tmdb"),
    CATALOGS_KITSU("catalogs.kitsu"),
    CATALOGS_HOME("catalogs.home"),
    PLAYBACK_STREAM_SELECTION("playback.streamSelection"),
    FORMATTER("formatter");

    companion object {
        private val byKey = entries.associateBy { it.key }
        private val longestFirst = entries.sortedByDescending { it.key.length }

        fun fromKey(key: String): AccountSettingsSectionKey? = byKey[key]

        fun fromChangedPath(path: String): AccountSettingsSectionKey? {
            return longestFirst.firstOrNull { section ->
                path == section.key || path.startsWith("${section.key}.")
            }
        }
    }
}
