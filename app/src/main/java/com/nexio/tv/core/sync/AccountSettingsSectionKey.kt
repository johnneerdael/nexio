package com.nexio.tv.core.sync

import com.nexio.tv.data.remote.supabase.AccountConfigSyncPayload
import com.nexio.tv.data.remote.supabase.AccountConfigSyncPayloadJson
import com.nexio.tv.data.remote.supabase.AnimeSkipSyncSettings
import com.nexio.tv.data.remote.supabase.EasyDebridSyncSettings
import com.nexio.tv.data.remote.supabase.FormatterSyncSettings
import com.nexio.tv.data.remote.supabase.GeminiSyncSettings
import com.nexio.tv.data.remote.supabase.HomeCatalogSyncSettings
import com.nexio.tv.data.remote.supabase.ImdbSyncSettings
import com.nexio.tv.data.remote.supabase.KitsuAuthSyncSettings
import com.nexio.tv.data.remote.supabase.KitsuCatalogSyncSettings
import com.nexio.tv.data.remote.supabase.MDBListCatalogSyncSettings
import com.nexio.tv.data.remote.supabase.MDBListSyncSettings
import com.nexio.tv.data.remote.supabase.OmdbSyncSettings
import com.nexio.tv.data.remote.supabase.PosterRatingsSyncSettings
import com.nexio.tv.data.remote.supabase.PremiumizeSyncSettings
import com.nexio.tv.data.remote.supabase.RealDebridSyncSettings
import com.nexio.tv.data.remote.supabase.SimklAuthSyncSettings
import com.nexio.tv.data.remote.supabase.SimklCatalogSyncSettings
import com.nexio.tv.data.remote.supabase.StreamSelectionConfigSyncSettings
import com.nexio.tv.data.remote.supabase.SubtitleTranslationSyncSettings
import com.nexio.tv.data.remote.supabase.TmdbCatalogSyncSettings
import com.nexio.tv.data.remote.supabase.TmdbSyncSettings
import com.nexio.tv.data.remote.supabase.TorBoxSyncSettings
import com.nexio.tv.data.remote.supabase.TraktAuthSyncSettings
import com.nexio.tv.data.remote.supabase.TraktCatalogSyncSettings
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement

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

fun AccountSettingsSectionKey.applyToPayload(
    current: AccountConfigSyncPayload,
    sectionPayload: JsonElement
): AccountConfigSyncPayload {
    return when (this) {
        AccountSettingsSectionKey.INTEGRATIONS_SUBTITLE_TRANSLATION -> current.copy(
            integrations = current.integrations.copy(
                subtitleTranslation = decodeSectionPayload(
                    SubtitleTranslationSyncSettings.serializer(),
                    sectionPayload
                )
            )
        )
        AccountSettingsSectionKey.INTEGRATIONS_IMDB -> current.copy(
            integrations = current.integrations.copy(
                imdb = decodeSectionPayload(ImdbSyncSettings.serializer(), sectionPayload)
            )
        )
        AccountSettingsSectionKey.INTEGRATIONS_GEMINI -> current.copy(
            integrations = current.integrations.copy(
                gemini = decodeSectionPayload(GeminiSyncSettings.serializer(), sectionPayload)
            )
        )
        AccountSettingsSectionKey.INTEGRATIONS_TMDB -> current.copy(
            integrations = current.integrations.copy(
                tmdb = decodeSectionPayload(TmdbSyncSettings.serializer(), sectionPayload)
            )
        )
        AccountSettingsSectionKey.INTEGRATIONS_OMDB -> current.copy(
            integrations = current.integrations.copy(
                omdb = decodeSectionPayload(OmdbSyncSettings.serializer(), sectionPayload)
            )
        )
        AccountSettingsSectionKey.INTEGRATIONS_POSTER_RATINGS -> current.copy(
            integrations = current.integrations.copy(
                posterRatings = decodeSectionPayload(
                    PosterRatingsSyncSettings.serializer(),
                    sectionPayload
                )
            )
        )
        AccountSettingsSectionKey.INTEGRATIONS_ANIME_SKIP -> current.copy(
            integrations = current.integrations.copy(
                animeSkip = decodeSectionPayload(AnimeSkipSyncSettings.serializer(), sectionPayload)
            )
        )
        AccountSettingsSectionKey.INTEGRATIONS_MDBLIST -> current.copy(
            integrations = current.integrations.copy(
                mdblist = decodeSectionPayload(MDBListSyncSettings.serializer(), sectionPayload)
            )
        )
        AccountSettingsSectionKey.INTEGRATIONS_KITSU -> current
        AccountSettingsSectionKey.INTEGRATIONS_TRAKT_AUTH -> current.copy(
            integrations = current.integrations.copy(
                traktAuth = decodeSectionPayload(TraktAuthSyncSettings.serializer(), sectionPayload)
            )
        )
        AccountSettingsSectionKey.INTEGRATIONS_SIMKL_AUTH -> current.copy(
            integrations = current.integrations.copy(
                simklAuth = decodeSectionPayload(SimklAuthSyncSettings.serializer(), sectionPayload)
            )
        )
        AccountSettingsSectionKey.INTEGRATIONS_KITSU_AUTH -> current.copy(
            integrations = current.integrations.copy(
                kitsuAuth = decodeSectionPayload(KitsuAuthSyncSettings.serializer(), sectionPayload)
            )
        )
        AccountSettingsSectionKey.INTEGRATIONS_DEBRID_PREMIUMIZE -> current.copy(
            integrations = current.integrations.copy(
                debrid = current.integrations.debrid.copy(
                    premiumize = decodeSectionPayload(PremiumizeSyncSettings.serializer(), sectionPayload)
                )
            )
        )
        AccountSettingsSectionKey.INTEGRATIONS_DEBRID_REAL_DEBRID -> current.copy(
            integrations = current.integrations.copy(
                debrid = current.integrations.debrid.copy(
                    realDebrid = decodeSectionPayload(RealDebridSyncSettings.serializer(), sectionPayload)
                )
            )
        )
        AccountSettingsSectionKey.INTEGRATIONS_DEBRID_TOR_BOX -> current.copy(
            integrations = current.integrations.copy(
                debrid = current.integrations.debrid.copy(
                    torBox = decodeSectionPayload(TorBoxSyncSettings.serializer(), sectionPayload)
                )
            )
        )
        AccountSettingsSectionKey.INTEGRATIONS_DEBRID_EASY_DEBRID -> current.copy(
            integrations = current.integrations.copy(
                debrid = current.integrations.debrid.copy(
                    easyDebrid = decodeSectionPayload(EasyDebridSyncSettings.serializer(), sectionPayload)
                )
            )
        )
        AccountSettingsSectionKey.CATALOGS_MDBLIST -> current.copy(
            catalogs = current.catalogs.copy(
                mdblist = decodeSectionPayload(MDBListCatalogSyncSettings.serializer(), sectionPayload)
            )
        )
        AccountSettingsSectionKey.CATALOGS_TRAKT -> current.copy(
            catalogs = current.catalogs.copy(
                trakt = decodeSectionPayload(TraktCatalogSyncSettings.serializer(), sectionPayload)
            )
        )
        AccountSettingsSectionKey.CATALOGS_SIMKL -> current.copy(
            catalogs = current.catalogs.copy(
                simkl = decodeSectionPayload(SimklCatalogSyncSettings.serializer(), sectionPayload)
            )
        )
        AccountSettingsSectionKey.CATALOGS_TMDB -> current.copy(
            catalogs = current.catalogs.copy(
                tmdb = decodeSectionPayload(TmdbCatalogSyncSettings.serializer(), sectionPayload)
            )
        )
        AccountSettingsSectionKey.CATALOGS_KITSU -> current.copy(
            catalogs = current.catalogs.copy(
                kitsu = decodeSectionPayload(KitsuCatalogSyncSettings.serializer(), sectionPayload)
            )
        )
        AccountSettingsSectionKey.CATALOGS_HOME -> current.copy(
            catalogs = current.catalogs.copy(
                home = decodeSectionPayload(HomeCatalogSyncSettings.serializer(), sectionPayload)
            )
        )
        AccountSettingsSectionKey.PLAYBACK_STREAM_SELECTION -> current.copy(
            playback = current.playback.copy(
                streamSelection = decodeSectionPayload(
                    StreamSelectionConfigSyncSettings.serializer(),
                    sectionPayload
                )
            )
        )
        AccountSettingsSectionKey.FORMATTER -> current.copy(
            formatter = decodeSectionPayload(FormatterSyncSettings.serializer(), sectionPayload)
        )
    }
}

private fun <T> decodeSectionPayload(serializer: KSerializer<T>, payload: JsonElement): T {
    return AccountConfigSyncPayloadJson.decodeFromJsonElement(serializer, payload)
}
