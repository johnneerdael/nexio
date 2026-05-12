package com.nexio.tv.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountSettingsSectionKeyTest {

    private val expectedKeys = listOf(
        "integrations.subtitleTranslation",
        "integrations.imdb",
        "integrations.gemini",
        "integrations.tmdb",
        "integrations.omdb",
        "integrations.posterRatings",
        "integrations.animeSkip",
        "integrations.mdblist",
        "integrations.kitsu",
        "integrations.traktAuth",
        "integrations.simklAuth",
        "integrations.kitsuAuth",
        "integrations.debrid.premiumize",
        "integrations.debrid.realDebrid",
        "integrations.debrid.torBox",
        "integrations.debrid.easyDebrid",
        "catalogs.mdblist",
        "catalogs.trakt",
        "catalogs.simkl",
        "catalogs.tmdb",
        "catalogs.kitsu",
        "catalogs.home",
        "playback.streamSelection",
        "formatter",
    )

    @Test
    fun `registry contains exactly the v13 account settings sections`() {
        assertEquals(expectedKeys, AccountSettingsSectionKey.entries.map { it.key })
    }

    @Test
    fun `fromKey maps exact section keys`() {
        assertEquals(
            AccountSettingsSectionKey.INTEGRATIONS_DEBRID_REAL_DEBRID,
            AccountSettingsSectionKey.fromKey("integrations.debrid.realDebrid")
        )
        assertEquals(
            AccountSettingsSectionKey.FORMATTER,
            AccountSettingsSectionKey.fromKey("formatter")
        )
    }

    @Test
    fun `fromChangedPath maps exact keys and dot-boundary descendants`() {
        assertEquals(
            AccountSettingsSectionKey.PLAYBACK_STREAM_SELECTION,
            AccountSettingsSectionKey.fromChangedPath("playback.streamSelection")
        )
        assertEquals(
            AccountSettingsSectionKey.PLAYBACK_STREAM_SELECTION,
            AccountSettingsSectionKey.fromChangedPath("playback.streamSelection.trackingProvider")
        )
        assertEquals(
            AccountSettingsSectionKey.INTEGRATIONS_DEBRID_REAL_DEBRID,
            AccountSettingsSectionKey.fromChangedPath("integrations.debrid.realDebrid.clientId")
        )
    }

    @Test
    fun `fromChangedPath prefers the longest matching section key`() {
        assertEquals(
            AccountSettingsSectionKey.INTEGRATIONS_DEBRID_PREMIUMIZE,
            AccountSettingsSectionKey.fromChangedPath("integrations.debrid.premiumize.apiKey")
        )
    }

    @Test
    fun `removed and non synced surfaces are not registered`() {
        assertNull(AccountSettingsSectionKey.fromKey("integrations.wyzie"))
        assertNull(AccountSettingsSectionKey.fromKey("integrations.theIntroDb"))
        assertNull(AccountSettingsSectionKey.fromKey("integrations.tvdb"))
        assertNull(AccountSettingsSectionKey.fromChangedPath("integrations.tvdb.apiKey"))
        assertNull(AccountSettingsSectionKey.fromChangedPath("tmdb_api_key"))
    }

    @Test
    fun `partial path prefixes do not match sections`() {
        assertNull(AccountSettingsSectionKey.fromChangedPath("integrations.tmdbApiKey"))
        assertNull(AccountSettingsSectionKey.fromChangedPath("catalogs.homepage"))
        assertNull(AccountSettingsSectionKey.fromChangedPath("format"))
    }
}
