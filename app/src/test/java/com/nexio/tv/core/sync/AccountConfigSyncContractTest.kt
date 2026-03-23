package com.nexio.tv.core.sync

import com.nexio.tv.data.local.AnimeSkipSettingsDataStore
import com.nexio.tv.data.local.ImdbSettingsDataStore
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.data.local.OmdbSettingsDataStore
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.PosterRatingsSettingsDataStore
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.data.remote.supabase.AccountAddonPayload
import com.nexio.tv.data.remote.supabase.AccountConfigSnapshotRpcResponse
import com.nexio.tv.data.remote.supabase.AccountConfigSyncPayload
import com.nexio.tv.data.remote.supabase.CustomFormatterSyncTemplate
import com.nexio.tv.data.remote.supabase.DebridSyncSettings
import com.nexio.tv.data.remote.supabase.FormatterSyncSettings
import com.nexio.tv.data.remote.supabase.GeminiSyncSettings
import com.nexio.tv.data.remote.supabase.ImdbSyncSettings
import com.nexio.tv.data.remote.supabase.IntegrationSettings
import com.nexio.tv.data.remote.supabase.MDBListSyncSettings
import com.nexio.tv.data.remote.supabase.OmdbSyncSettings
import com.nexio.tv.data.remote.supabase.PosterRatingsSyncSettings
import com.nexio.tv.data.remote.supabase.PremiumizeSyncSettings
import com.nexio.tv.data.remote.supabase.RealDebridSyncSettings
import com.nexio.tv.data.remote.supabase.TmdbSyncSettings
import com.nexio.tv.data.remote.supabase.TraktAuthSyncSettings
import com.nexio.tv.domain.model.ImdbSettings
import com.nexio.tv.domain.model.AddonParserPreset
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountConfigSyncContractTest {

    @Test
    fun `buildAccountConfigSyncPayload serializes integrations catalogs formatter and imdb`() {
        val payload = buildAccountConfigSyncPayload(
            integrations = IntegrationSettings(
                debrid = DebridSyncSettings(
                    premiumize = PremiumizeSyncSettings(configured = true, customerId = 42),
                    realDebrid = RealDebridSyncSettings(connected = true, username = "rd-user")
                ),
                tmdb = TmdbSyncSettings(enabled = true, useArtwork = false),
                omdb = OmdbSyncSettings(enabled = true),
                imdb = ImdbSyncSettings(enabled = true, baseUrl = "https://custom.imdb.example"),
                mdblist = MDBListSyncSettings(enabled = true, showImdb = false),
                animeSkip = com.nexio.tv.data.remote.supabase.AnimeSkipSyncSettings(
                    enabled = true,
                    clientId = "anime-client"
                ),
                gemini = GeminiSyncSettings(enabled = true),
                posterRatings = PosterRatingsSyncSettings(rpdbEnabled = true, topPostersEnabled = true),
                traktAuth = TraktAuthSyncSettings(connected = true, username = "trakt-user", userSlug = "trakt-slug")
            ),
            heroCatalogKeys = listOf("hero-a"),
            homeCatalogOrderKeys = listOf("row-a", "row-b"),
            disabledHomeCatalogKeys = listOf("row-c"),
            traktCatalogEnabledSet = listOf("trakt_up_next"),
            traktCatalogOrder = listOf("trakt_up_next", "trakt_recommended_movies"),
            traktSelectedPopularListKeys = listOf("popular-a"),
            mdbListHiddenPersonalListKeys = listOf("personal-hidden"),
            mdbListSelectedTopListKeys = listOf("top-selected"),
            mdbListCatalogOrder = listOf("mdb-top", "mdb-personal"),
            formatter = FormatterSyncSettings(
                enabled = false,
                selectedTemplateId = "custom",
                customTemplate = CustomFormatterSyncTemplate(
                    id = "custom",
                    label = "My Formatter",
                    nameTemplate = "{stream.title}",
                    descriptionTemplate = "{stream.quality}"
                )
            )
        )

        val json = Json.encodeToJsonElement(AccountConfigSyncPayload.serializer(), payload) as JsonObject

        assertEquals(setOf("schemaVersion", "integrations", "catalogs", "formatter"), json.keys)
        assertEquals(3, json["schemaVersion"]?.toString()?.toInt())
        assertEquals("\"custom\"", json["formatter"]?.jsonObject?.get("selectedTemplateId")?.toString())
        assertEquals("true", json["integrations"]?.jsonObject?.get("omdb")?.jsonObject?.get("enabled")?.toString())
        assertEquals(
            "https://custom.imdb.example",
            json["integrations"]?.jsonObject?.get("imdb")?.jsonObject?.get("baseUrl")?.toString()?.trim('"')
        )
        assertFalse(json.containsKey("appearance"))
        assertFalse(json.containsKey("layout"))
        assertFalse(json.containsKey("playback"))
        assertFalse(json.containsKey("trakt"))
        assertFalse(json.containsKey("debug"))
    }

    @Test
    fun `build account config sync rpc params includes contract version 3`() {
        val payload = buildAccountConfigSyncPayload(
            integrations = IntegrationSettings(),
            heroCatalogKeys = listOf("hero-a"),
            homeCatalogOrderKeys = listOf("row-a"),
            disabledHomeCatalogKeys = emptyList(),
            traktCatalogEnabledSet = listOf("trakt_up_next"),
            traktCatalogOrder = listOf("trakt_up_next"),
            traktSelectedPopularListKeys = emptyList(),
            mdbListHiddenPersonalListKeys = emptyList(),
            mdbListSelectedTopListKeys = emptyList(),
            mdbListCatalogOrder = emptyList(),
            formatter = FormatterSyncSettings()
        )

        val pushParams = buildAccountConfigSyncPushParams(payload)
        val pullParams = buildAccountConfigSyncPullParams()

        assertEquals(ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION, pushParams["p_contract_version"]?.toString()?.toInt())
        assertEquals("\"app\"", pushParams["p_source"].toString())
        assertTrue(pushParams.containsKey("p_settings_payload"))
        assertEquals(ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION, pullParams["p_contract_version"]?.toString()?.toInt())
    }

    @Test
    fun `buildImdbSyncSettings reads from the imdb store`() = runTest {
        val imdbSettingsDataStore = mockk<ImdbSettingsDataStore>()
        every { imdbSettingsDataStore.settings } returns flowOf(
            ImdbSettings(
                enabled = true,
                baseUrl = "https://custom.imdb.example",
                apiKey = "secret-key"
            )
        )

        val settings = buildImdbSyncSettings(imdbSettingsDataStore)

        assertEquals(true, settings.enabled)
        assertEquals("https://custom.imdb.example", settings.baseUrl)
    }

    @Test
    fun `applyImdbSyncSettings writes enabled and baseUrl into the imdb store`() = runTest {
        val imdbSettingsDataStore = mockk<ImdbSettingsDataStore>(relaxed = true)
        val settings = ImdbSyncSettings(
            enabled = true,
            baseUrl = "https://custom.imdb.example"
        )

        applyImdbSyncSettings(settings, imdbSettingsDataStore)

        coVerify(exactly = 1) { imdbSettingsDataStore.setEnabled(true) }
        coVerify(exactly = 1) { imdbSettingsDataStore.setBaseUrl("https://custom.imdb.example") }
    }

    @Test
    fun `observeAccountConfigSyncChanges emits for account owned change signals`() = runTest {
        val heroCatalogSelections = MutableSharedFlow<Unit>(replay = 1)
        val homeCatalogOrderKeys = MutableSharedFlow<Unit>(replay = 1)
        val disabledHomeCatalogKeys = MutableSharedFlow<Unit>(replay = 1)
        val tmdbSettings = MutableSharedFlow<Unit>(replay = 1)
        val mdbListSettings = MutableSharedFlow<Unit>(replay = 1)
        val mdbListCatalogPreferences = MutableSharedFlow<Unit>(replay = 1)
        val omdbSettings = MutableSharedFlow<Unit>(replay = 1)
        val animeSkipEnabled = MutableSharedFlow<Unit>(replay = 1)
        val animeSkipClientId = MutableSharedFlow<Unit>(replay = 1)
        val geminiSettings = MutableSharedFlow<Unit>(replay = 1)
        val imdbSettings = MutableSharedFlow<Unit>(replay = 1)
        val posterRatingsSettings = MutableSharedFlow<Unit>(replay = 1)
        val premiumizeSettings = MutableSharedFlow<Unit>(replay = 1)
        val premiumizeAccountState = MutableSharedFlow<Unit>(replay = 1)
        val realDebridState = MutableSharedFlow<Unit>(replay = 1)
        val traktAuthState = MutableSharedFlow<Unit>(replay = 1)
        val traktCatalogPreferences = MutableSharedFlow<Unit>(replay = 1)

        val emission = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            observeAccountConfigSyncChanges(
                heroCatalogSelections = heroCatalogSelections,
                homeCatalogOrderKeys = homeCatalogOrderKeys,
                disabledHomeCatalogKeys = disabledHomeCatalogKeys,
                tmdbSettings = tmdbSettings,
                mdbListSettings = mdbListSettings,
                mdbListCatalogPreferences = mdbListCatalogPreferences,
                omdbSettings = omdbSettings,
                animeSkipEnabled = animeSkipEnabled,
                animeSkipClientId = animeSkipClientId,
                geminiSettings = geminiSettings,
                imdbSettings = imdbSettings,
                posterRatingsSettings = posterRatingsSettings,
                premiumizeSettings = premiumizeSettings,
                premiumizeAccountState = premiumizeAccountState,
                realDebridState = realDebridState,
                traktAuthState = traktAuthState,
                traktCatalogPreferences = traktCatalogPreferences
            ).first()
        }

        heroCatalogSelections.emit(Unit)
        advanceUntilIdle()

        assertEquals(Unit, emission.await())
    }

    @Test
    fun `observeAccountConfigSyncChanges emits when imdb settings change`() = runTest {
        val imdbSettings = MutableSharedFlow<Unit>(replay = 1)

        val emission = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            observeAccountConfigSyncChanges(
                heroCatalogSelections = MutableSharedFlow<Unit>(),
                homeCatalogOrderKeys = MutableSharedFlow<Unit>(),
                disabledHomeCatalogKeys = MutableSharedFlow<Unit>(),
                tmdbSettings = MutableSharedFlow<Unit>(),
                mdbListSettings = MutableSharedFlow<Unit>(),
                mdbListCatalogPreferences = MutableSharedFlow<Unit>(),
                omdbSettings = MutableSharedFlow<Unit>(),
                animeSkipEnabled = MutableSharedFlow<Unit>(),
                animeSkipClientId = MutableSharedFlow<Unit>(),
                geminiSettings = MutableSharedFlow<Unit>(),
                imdbSettings = imdbSettings,
                posterRatingsSettings = MutableSharedFlow<Unit>(),
                premiumizeSettings = MutableSharedFlow<Unit>(),
                premiumizeAccountState = MutableSharedFlow<Unit>(),
                realDebridState = MutableSharedFlow<Unit>(),
                traktAuthState = MutableSharedFlow<Unit>(),
                traktCatalogPreferences = MutableSharedFlow<Unit>()
            ).first()
        }

        imdbSettings.emit(Unit)
        advanceUntilIdle()

        assertEquals(Unit, emission.await())
    }

    @Test
    fun `buildRemoteAddonInstallConfigs preserves v2 snapshot addons for startup reconcile`() = runTest {
        val snapshot = AccountConfigSnapshotRpcResponse(
            settings = AccountConfigSyncPayload(),
            addons = listOf(
                AccountAddonPayload(
                    url = "https://disabled.example",
                    parserPreset = "GENERIC",
                    enabled = false,
                    sortOrder = 0
                ),
                AccountAddonPayload(
                    url = "https://alpha.example",
                    parserPreset = "unknown",
                    enabled = true,
                    sortOrder = 1
                ),
                AccountAddonPayload(
                    url = "https://beta.example",
                    parserPreset = "torrentio",
                    enabled = true,
                    sortOrder = 2
                )
            )
        )

        val addonConfigs = buildRemoteAddonInstallConfigs(snapshot.addons) { addon ->
            Result.success("${addon.url}/manifest.json")
        }

        assertEquals(2, addonConfigs.size)
        assertEquals("https://alpha.example/manifest.json", addonConfigs[0].url)
        assertEquals(AddonParserPreset.GENERIC, addonConfigs[0].parserPreset)
        assertEquals("https://beta.example/manifest.json", addonConfigs[1].url)
        assertEquals(AddonParserPreset.TORRENTIO, addonConfigs[1].parserPreset)
    }

    @Test
    fun `applyAccountConfigSyncSettings routes only synced settings to the synced stores`() = runTest {
        val layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>(relaxed = true)
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>(relaxed = true)
        val mdbListSettingsDataStore = mockk<MDBListSettingsDataStore>(relaxed = true)
        val omdbSettingsDataStore = mockk<OmdbSettingsDataStore>(relaxed = true)
        val animeSkipSettingsDataStore = mockk<AnimeSkipSettingsDataStore>(relaxed = true)
        val geminiSettingsDataStore = mockk<com.nexio.tv.data.local.GeminiSettingsDataStore>(relaxed = true)
        val imdbSettingsDataStore = mockk<ImdbSettingsDataStore>(relaxed = true)
        val posterRatingsSettingsDataStore = mockk<PosterRatingsSettingsDataStore>(relaxed = true)
        val traktSettingsDataStore = mockk<TraktSettingsDataStore>(relaxed = true)

        val settings = buildAccountConfigSyncPayload(
            integrations = IntegrationSettings(
                tmdb = TmdbSyncSettings(enabled = true, useArtwork = false, useBasicInfo = false),
                imdb = ImdbSyncSettings(enabled = true, baseUrl = "https://custom.imdb.example"),
                mdblist = MDBListSyncSettings(
                    enabled = true,
                    showTrakt = false,
                    showImdb = false,
                    showTmdb = true,
                    showLetterboxd = false,
                    showTomatoes = true,
                    showAudience = false,
                    showMetacritic = true
                ),
                omdb = OmdbSyncSettings(enabled = true),
                animeSkip = com.nexio.tv.data.remote.supabase.AnimeSkipSyncSettings(enabled = true, clientId = "anime-client"),
                gemini = GeminiSyncSettings(enabled = true),
                posterRatings = PosterRatingsSyncSettings(rpdbEnabled = true, topPostersEnabled = false)
            ),
            heroCatalogKeys = listOf("hero-a"),
            homeCatalogOrderKeys = listOf("row-a", "row-b"),
            disabledHomeCatalogKeys = listOf("row-c"),
            traktCatalogEnabledSet = listOf("trakt_up_next"),
            traktCatalogOrder = listOf("trakt_up_next", "trakt_recommended_movies"),
            traktSelectedPopularListKeys = listOf("popular-a"),
            mdbListHiddenPersonalListKeys = listOf("personal-hidden"),
            mdbListSelectedTopListKeys = listOf("top-selected"),
            mdbListCatalogOrder = listOf("mdb-top"),
            formatter = FormatterSyncSettings(
                enabled = true,
                selectedTemplateId = "custom",
                customTemplate = CustomFormatterSyncTemplate(
                    id = "custom",
                    label = "Custom",
                    nameTemplate = "{stream.title}",
                    descriptionTemplate = "{stream.quality}"
                )
            )
        )

        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>(relaxed = true)

        applyAccountConfigSyncSettings(
            settings = settings,
            layoutPreferenceDataStore = layoutPreferenceDataStore,
            tmdbSettingsDataStore = tmdbSettingsDataStore,
            mdbListSettingsDataStore = mdbListSettingsDataStore,
            omdbSettingsDataStore = omdbSettingsDataStore,
            animeSkipSettingsDataStore = animeSkipSettingsDataStore,
            geminiSettingsDataStore = geminiSettingsDataStore,
            imdbSettingsDataStore = imdbSettingsDataStore,
            posterRatingsSettingsDataStore = posterRatingsSettingsDataStore,
            traktSettingsDataStore = traktSettingsDataStore,
            playerSettingsDataStore = playerSettingsDataStore
        )

        coVerify(exactly = 1) { layoutPreferenceDataStore.setHeroCatalogKeys(listOf("hero-a")) }
        coVerify(exactly = 1) { layoutPreferenceDataStore.setHomeCatalogOrderKeys(listOf("row-a", "row-b")) }
        coVerify(exactly = 1) { layoutPreferenceDataStore.setDisabledHomeCatalogKeys(listOf("row-c")) }
        coVerify(exactly = 1) { tmdbSettingsDataStore.setEnabled(true) }
        coVerify(exactly = 1) { imdbSettingsDataStore.setEnabled(true) }
        coVerify(exactly = 1) { imdbSettingsDataStore.setBaseUrl("https://custom.imdb.example") }
        coVerify(exactly = 1) { omdbSettingsDataStore.setEnabled(true) }
        coVerify(exactly = 1) { mdbListSettingsDataStore.setCatalogPreferences(setOf("personal-hidden"), setOf("top-selected"), listOf("mdb-top")) }
        coVerify(exactly = 1) {
            traktSettingsDataStore.setCatalogPreferences(
                enabledCatalogs = setOf("trakt_up_next"),
                catalogOrder = listOf("trakt_up_next", "trakt_recommended_movies"),
                selectedPopularListKeys = setOf("popular-a")
            )
        }
        coVerify(exactly = 1) { playerSettingsDataStore.setSyncedFormatterEnabled(true) }
        coVerify(exactly = 1) { playerSettingsDataStore.setSyncedFormatterSelectedTemplateId("custom") }
        coVerify(exactly = 1) {
            playerSettingsDataStore.setSyncedFormatterCustomTemplate(
                label = "Custom",
                nameTemplate = "{stream.title}",
                descriptionTemplate = "{stream.quality}"
            )
        }
    }
}
