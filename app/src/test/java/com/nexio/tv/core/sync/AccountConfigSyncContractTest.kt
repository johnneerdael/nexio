package com.nexio.tv.core.sync

import com.nexio.tv.data.local.AnimeSkipSettingsDataStore
import com.nexio.tv.data.local.KitsuCatalogSettingsDataStore
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.data.local.OmdbSettingsDataStore
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.PosterRatingsSettingsDataStore
import com.nexio.tv.data.local.SubtitleTranslationSettingsDataStore
import com.nexio.tv.data.local.TheIntroDbSettingsDataStore
import com.nexio.tv.data.local.TmdbCatalogSettingsDataStore
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.ui.screens.home.order.HomeRailOrderStore
import com.nexio.tv.data.remote.supabase.AccountAddonPayload
import com.nexio.tv.data.remote.supabase.AccountConfigSnapshotRpcResponse
import com.nexio.tv.data.remote.supabase.AccountConfigSyncPayload
import com.nexio.tv.data.remote.supabase.CatalogSyncSettings
import com.nexio.tv.data.remote.supabase.CustomFormatterSyncTemplate
import com.nexio.tv.data.remote.supabase.KitsuCatalogSyncSettings
import com.nexio.tv.data.remote.supabase.TmdbCatalogSyncSettings
import com.nexio.tv.data.remote.supabase.DebridSyncSettings
import com.nexio.tv.data.remote.supabase.FormatterSyncSettings
import com.nexio.tv.data.remote.supabase.GeminiSyncSettings
import com.nexio.tv.data.remote.supabase.IntegrationSettings
import com.nexio.tv.data.remote.supabase.KitsuAuthSyncSettings
import com.nexio.tv.data.remote.supabase.MDBListPinnedListOptionSync
import com.nexio.tv.data.remote.supabase.MDBListSyncSettings
import com.nexio.tv.data.remote.supabase.OmdbSyncSettings
import com.nexio.tv.data.remote.supabase.PosterRatingsSyncSettings
import com.nexio.tv.data.remote.supabase.PremiumizeSyncSettings
import com.nexio.tv.data.remote.supabase.RealDebridSyncSettings
import com.nexio.tv.data.remote.supabase.SimklAuthSyncSettings
import com.nexio.tv.data.remote.supabase.SubtitleTranslationSyncSettings
import com.nexio.tv.data.remote.supabase.TheIntroDbSyncSettings
import com.nexio.tv.data.remote.supabase.TmdbSyncSettings
import com.nexio.tv.data.remote.supabase.TraktAuthSyncSettings
import com.nexio.tv.data.remote.supabase.TraktPinnedListOptionSync
import com.nexio.tv.data.remote.supabase.TvdbSyncSettings
import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.SubtitleTranslationProvider
import com.nexio.tv.domain.model.TrackingProvider
import java.io.File
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountConfigSyncContractTest {
    @Test
    fun `account sync contract includes kitsu auth settings and secrets`() {
        val contract = File("supabase/account_settings_sync.sql").readText()

        assertTrue(contract.contains("\"kitsuAuth\""))
        assertTrue(contract.contains("\"includeNsfw\""))
        assertTrue(contract.contains("'kitsu_access_token'"))
        assertTrue(contract.contains("'kitsu_refresh_token'"))
        assertFalse(contract.contains("'kitsu_password'"))
    }

    @Test
    fun `kitsu auth settings migration updates deployed canonical contract`() {
        val migration = File("supabase/migrations/20260420013000_add_kitsu_auth_settings_sync.sql").readText()

        assertTrue(migration.contains("account_settings_v2_default_payload"))
        assertTrue(migration.contains("account_settings_extract_canonical_v2"))
        assertTrue(migration.contains("\"kitsuAuth\""))
        assertTrue(migration.contains("\"includeNsfw\""))
        assertTrue(migration.contains("'kitsuAuth'"))
        assertTrue(migration.contains("{integrations,kitsuAuth}"))
    }

    @Test
    fun `subtitle translation sync defaults use OpenRouter free route`() {
        val settings = SubtitleTranslationSyncSettings()

        assertEquals("OPENAI", settings.provider)
        assertEquals("openrouter/free", settings.model)
        assertEquals("https://openrouter.ai/api/v1", settings.baseUrl)
    }

    @Test
    fun `metadata provider sync defaults are core enabled`() {
        val tmdb = TmdbSyncSettings()
        val tvdb = TvdbSyncSettings()

        assertTrue(tmdb.enabled)
        assertTrue(tvdb.enabled)
        assertTrue(tvdb.configured)
        assertEquals("VALID", tvdb.validationStatus)
        assertEquals("", tvdb.lastFailure)
    }

    @Test
    fun `buildAccountConfigSyncPayload serializes integrations catalogs and formatter`() {
        val payload = buildAccountConfigSyncPayload(
            integrations = IntegrationSettings(
                debrid = DebridSyncSettings(
                    premiumize = PremiumizeSyncSettings(configured = true, customerId = 42),
                    realDebrid = RealDebridSyncSettings(connected = true, username = "rd-user")
                ),
                theIntroDb = TheIntroDbSyncSettings(
                    enabled = true,
                    showIntroButton = false,
                    showRecapButton = true,
                    showCreditsButton = false,
                    showPreviewButton = true
                ),
                tmdb = TmdbSyncSettings(enabled = true, useArtwork = false),
                tvdb = TvdbSyncSettings(
                    enabled = true,
                    configured = true,
                    validationStatus = "VALID",
                    lastFailure = ""
                ),
                omdb = OmdbSyncSettings(enabled = true),
                mdblist = MDBListSyncSettings(enabled = true, showImdb = false),
                animeSkip = com.nexio.tv.data.remote.supabase.AnimeSkipSyncSettings(
                    enabled = true,
                ),
                subtitleTranslation = SubtitleTranslationSyncSettings(
                    enabled = true,
                    provider = "OPENAI",
                    model = "openai/gpt-5.2",
                    baseUrl = "https://openrouter.ai/api/v1"
                ),
                gemini = GeminiSyncSettings(enabled = true),
                posterRatings = PosterRatingsSyncSettings(rpdbEnabled = true, topPostersEnabled = true),
                kitsuAuth = KitsuAuthSyncSettings(
                    enabled = true,
                    connected = true,
                    username = "kitsu-user",
                    accessTokenSecretRef = "kitsu_access_token",
                    refreshTokenSecretRef = "kitsu_refresh_token",
                    expiresAtEpochSeconds = 1234L,
                    includeNsfw = true
                ),
                traktAuth = TraktAuthSyncSettings(connected = true, username = "trakt-user", userSlug = "trakt-slug"),
                simklAuth = SimklAuthSyncSettings(connected = true, username = "simkl-user", accountId = 51, accountType = "vip")
            ),
            heroCatalogKeys = listOf("hero-a"),
            homeCatalogOrderKeys = listOf("row-a", "row-b"),
            disabledHomeCatalogKeys = listOf("row-c"),
            traktCatalogEnabledSet = listOf("trakt_up_next"),
            traktCatalogOrder = listOf("trakt_up_next", "trakt_recommended_movies"),
            traktSelectedPopularListKeys = listOf("popular-a"),
            traktPinnedListOptions = listOf(
                TraktPinnedListOptionSync(
                    key = "user/list-a",
                    userId = "user",
                    listId = "list-a",
                    catalogIdBase = "trakt_list_user_list_a",
                    title = "List A",
                    itemCount = 12
                )
            ),
            simklCatalogEnabledSet = listOf("simkl_tv_trending_today", "simkl_movie_trending_today"),
            simklCatalogOrder = listOf("simkl_tv_trending_today", "simkl_movie_trending_today"),
            mdbListHiddenPersonalListKeys = listOf("personal-hidden"),
            mdbListSelectedTopListKeys = listOf("top-selected"),
            mdbListPinnedTopListOptions = listOf(
                MDBListPinnedListOptionSync(
                    key = "top:owner/list-b",
                    owner = "owner",
                    listId = "list-b",
                    title = "List B",
                    itemCount = 7
                )
            ),
            mdbListCatalogOrder = listOf("mdb-top", "mdb-personal"),
            trackingProvider = TrackingProvider.SIMKL,
            formatter = FormatterSyncSettings(
                enabled = false,
                selectedTemplateId = "custom",
                customTemplate = CustomFormatterSyncTemplate(
                    id = "custom",
                    label = "My Formatter",
                    nameTemplate = "{stream.title}",
                    descriptionTemplate = "{stream.quality}",
                    badgeRowTemplate = "[[chip:cached]]"
                )
            )
        )

        val json = Json.encodeToJsonElement(AccountConfigSyncPayload.serializer(), payload) as JsonObject

        assertEquals(setOf("schemaVersion", "integrations", "catalogs", "playback", "formatter"), json.keys)
        assertEquals(9, json["schemaVersion"]?.toString()?.toInt())
        assertEquals("\"custom\"", json["formatter"]?.jsonObject?.get("selectedTemplateId")?.toString())
        assertEquals(
            "\"[[chip:cached]]\"",
            json["formatter"]?.jsonObject
                ?.get("customTemplate")?.jsonObject
                ?.get("badgeRowTemplate")
                ?.toString()
        )
        assertCustomFormatterPayloadAllowedBySchema(json)
        assertEquals(
            "\"SIMKL\"",
            json["playback"]?.jsonObject?.get("streamSelection")?.jsonObject?.get("trackingProvider")?.toString()
        )
        assertEquals("true", json["integrations"]?.jsonObject?.get("omdb")?.jsonObject?.get("enabled")?.toString())
        val subtitleTranslation = json["integrations"]!!
            .jsonObject["subtitleTranslation"]!!
            .jsonObject
        assertEquals("\"OPENAI\"", subtitleTranslation["provider"].toString())
        assertEquals("\"openai/gpt-5.2\"", subtitleTranslation["model"].toString())
        assertEquals("\"https://openrouter.ai/api/v1\"", subtitleTranslation["baseUrl"].toString())
        assertEquals(null, json["integrations"]?.jsonObject?.get("gemini")?.jsonObject?.get("enabled"))
        val tvdb = json["integrations"]?.jsonObject?.get("tvdb")?.jsonObject
        assertEquals("true", tvdb?.get("enabled").toString())
        assertEquals("true", tvdb?.get("configured").toString())
        assertEquals("\"VALID\"", tvdb?.get("validationStatus").toString())
        assertFalse(tvdb?.containsKey("apiKey") == true)
        assertFalse(tvdb?.containsKey("pin") == true)
        assertFalse(tvdb?.containsKey("token") == true)
        assertTrue(json["integrations"]?.jsonObject?.get("debrid")?.jsonObject?.containsKey("torBox") == true)
        assertTrue(json["integrations"]?.jsonObject?.get("debrid")?.jsonObject?.containsKey("easyDebrid") == true)
        assertEquals(
            "\"List A\"",
            json["catalogs"]?.jsonObject?.get("trakt")?.jsonObject
                ?.get("pinnedListOptions")?.jsonArray?.first()?.jsonObject
                ?.get("title")?.toString()
        )
        assertEquals(
            "\"List B\"",
            json["catalogs"]?.jsonObject?.get("mdblist")?.jsonObject
                ?.get("pinnedTopListOptions")?.jsonArray?.first()?.jsonObject
                ?.get("title")?.toString()
        )
        assertFalse(json.containsKey("appearance"))
        assertFalse(json.containsKey("layout"))
        assertFalse(json.containsKey("trakt"))
        assertFalse(json.containsKey("debug"))
    }

    @Test
    fun `buildAccountConfigSyncPayload mirrors only gemini subtitle translation into legacy gemini`() {
        val payload = buildAccountConfigSyncPayload(
            integrations = IntegrationSettings(
                subtitleTranslation = SubtitleTranslationSyncSettings(
                    enabled = true,
                    provider = "DASHSCOPE",
                    model = "qwen-mt-flash",
                    baseUrl = "https://dashscope-intl.aliyuncs.com/api/v1"
                ),
                gemini = GeminiSyncSettings(enabled = true)
            ),
            heroCatalogKeys = emptyList(),
            homeCatalogOrderKeys = emptyList(),
            disabledHomeCatalogKeys = emptyList(),
            traktCatalogEnabledSet = emptyList(),
            traktCatalogOrder = emptyList(),
            traktSelectedPopularListKeys = emptyList(),
            simklCatalogEnabledSet = emptyList(),
            simklCatalogOrder = emptyList(),
            mdbListHiddenPersonalListKeys = emptyList(),
            mdbListSelectedTopListKeys = emptyList(),
            mdbListCatalogOrder = emptyList(),
            trackingProvider = TrackingProvider.TRAKT,
            formatter = FormatterSyncSettings()
        )

        assertFalse(payload.integrations.gemini.enabled)

        val geminiPayload = buildAccountConfigSyncPayload(
            integrations = IntegrationSettings(
                subtitleTranslation = SubtitleTranslationSyncSettings(
                    enabled = true,
                    provider = "GEMINI",
                    model = "gemini-2.5-flash",
                    baseUrl = "https://generativelanguage.googleapis.com/v1beta"
                ),
                gemini = GeminiSyncSettings(enabled = false)
            ),
            heroCatalogKeys = emptyList(),
            homeCatalogOrderKeys = emptyList(),
            disabledHomeCatalogKeys = emptyList(),
            traktCatalogEnabledSet = emptyList(),
            traktCatalogOrder = emptyList(),
            traktSelectedPopularListKeys = emptyList(),
            simklCatalogEnabledSet = emptyList(),
            simklCatalogOrder = emptyList(),
            mdbListHiddenPersonalListKeys = emptyList(),
            mdbListSelectedTopListKeys = emptyList(),
            mdbListCatalogOrder = emptyList(),
            trackingProvider = TrackingProvider.TRAKT,
            formatter = FormatterSyncSettings()
        )

        assertTrue(geminiPayload.integrations.gemini.enabled)
    }

    @Test
    fun `tvdb public sync omits credential fields`() {
        val payload = buildAccountConfigSyncPayload(
            integrations = IntegrationSettings(
                tvdb = TvdbSyncSettings(
                    enabled = true,
                    configured = true,
                    validationStatus = "INVALID",
                    lastFailure = "Invalid credentials"
                )
            ),
            heroCatalogKeys = emptyList(),
            homeCatalogOrderKeys = emptyList(),
            disabledHomeCatalogKeys = emptyList(),
            traktCatalogEnabledSet = emptyList(),
            traktCatalogOrder = emptyList(),
            traktSelectedPopularListKeys = emptyList(),
            simklCatalogEnabledSet = emptyList(),
            simklCatalogOrder = emptyList(),
            mdbListHiddenPersonalListKeys = emptyList(),
            mdbListSelectedTopListKeys = emptyList(),
            mdbListCatalogOrder = emptyList(),
            trackingProvider = TrackingProvider.TRAKT,
            formatter = FormatterSyncSettings()
        )

        val json = Json.encodeToJsonElement(AccountConfigSyncPayload.serializer(), payload) as JsonObject
        val tvdb = json["integrations"]!!.jsonObject["tvdb"]!!.jsonObject

        assertEquals("true", tvdb["enabled"].toString())
        assertEquals("true", tvdb["configured"].toString())
        assertEquals("\"INVALID\"", tvdb["validationStatus"].toString())
        assertEquals("\"Invalid credentials\"", tvdb["lastFailure"].toString())
        assertFalse(tvdb.containsKey("apiKey"))
        assertFalse(tvdb.containsKey("pin"))
        assertFalse(tvdb.containsKey("token"))
    }

    @Test
    fun `build account config sync rpc params includes contract version 9`() {
        val payload = buildAccountConfigSyncPayload(
            integrations = IntegrationSettings(),
            heroCatalogKeys = listOf("hero-a"),
            homeCatalogOrderKeys = listOf("row-a"),
            disabledHomeCatalogKeys = emptyList(),
            traktCatalogEnabledSet = listOf("trakt_up_next"),
            traktCatalogOrder = listOf("trakt_up_next"),
            traktSelectedPopularListKeys = emptyList(),
            simklCatalogEnabledSet = emptyList(),
            simklCatalogOrder = emptyList(),
            mdbListHiddenPersonalListKeys = emptyList(),
            mdbListSelectedTopListKeys = emptyList(),
            mdbListCatalogOrder = emptyList(),
            trackingProvider = TrackingProvider.TRAKT,
            formatter = FormatterSyncSettings()
        )

        val pushParams = buildAccountConfigSyncPushParams(payload)
        val pullParams = buildAccountConfigSyncPullParams()

        assertEquals(ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION, pushParams["p_contract_version"]?.toString()?.toInt())
        assertEquals("\"app\"", pushParams["p_source"].toString())
        assertTrue(pushParams.containsKey("p_settings_payload"))
        assertEquals(ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION, pullParams["p_contract_version"]?.toString()?.toInt())
        assertEquals(9, buildAccountConfigSyncPullParams()["p_contract_version"]?.toString()?.toInt())
    }

    @Test
    fun `current contract emits version 9`() {
        assertEquals(9, ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION)
    }

    @Test
    fun `version 9 payload includes tmdb and kitsu sections when set`() {
        val payload = AccountConfigSyncPayload(
            schemaVersion = ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION,
            catalogs = CatalogSyncSettings(
                tmdb = TmdbCatalogSyncSettings(catalogOrder = listOf("tmdb_popular_movies")),
                kitsu = KitsuCatalogSyncSettings(catalogOrder = listOf("kitsu_trending_anime")),
            ),
        )
        val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }
            .encodeToString(AccountConfigSyncPayload.serializer(), payload)
        assertTrue(json.contains("\"tmdb\""))
        assertTrue(json.contains("\"kitsu\""))
        assertTrue(json.contains("\"schemaVersion\":9"))
    }

    @Test
    fun `older payload without tmdb or kitsu fields is accepted`() {
        val text = """{"schemaVersion":8,"catalogs":{"home":null}}"""
        val payload = Json { ignoreUnknownKeys = true }
            .decodeFromString(AccountConfigSyncPayload.serializer(), text)
        assertNull(payload.catalogs.tmdb)
        assertNull(payload.catalogs.kitsu)
        assertEquals(8, payload.schemaVersion)
    }

    @Test
    fun `buildAccountConfigSyncPushParamsV7 includes base revision and changed paths`() {
        val payload = buildAccountConfigSyncPayload(
            integrations = IntegrationSettings(
                omdb = OmdbSyncSettings(enabled = true)
            ),
            heroCatalogKeys = emptyList(),
            homeCatalogOrderKeys = emptyList(),
            disabledHomeCatalogKeys = emptyList(),
            traktCatalogEnabledSet = emptyList(),
            traktCatalogOrder = emptyList(),
            traktSelectedPopularListKeys = emptyList(),
            simklCatalogEnabledSet = emptyList(),
            simklCatalogOrder = emptyList(),
            mdbListHiddenPersonalListKeys = emptyList(),
            mdbListSelectedTopListKeys = emptyList(),
            mdbListCatalogOrder = emptyList(),
            trackingProvider = TrackingProvider.TRAKT,
            formatter = FormatterSyncSettings()
        )

        val params = buildAccountConfigSyncPushParamsV7(
            payload = payload,
            baseRevision = 123,
            changedPaths = listOf("integrations.omdb.enabled")
        )

        assertEquals("123", params["p_base_revision"].toString())
        assertEquals("\"app\"", params["p_source"].toString())
        assertTrue(params["p_changed_paths"].toString().contains("integrations.omdb.enabled"))
    }

    @Test
    fun `observeAccountConfigSyncChangedPaths emits tvdb path label`() = runTest {
        val tvdbSettings = MutableSharedFlow<Unit>(replay = 1)

        val emission = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            observeAccountConfigSyncChangedPaths(
                heroCatalogSelections = MutableSharedFlow<Unit>(),
                homeCatalogOrderKeys = MutableSharedFlow<Unit>(),
                disabledHomeCatalogKeys = MutableSharedFlow<Unit>(),
                tmdbSettings = MutableSharedFlow<Unit>(),
                tvdbSettings = tvdbSettings,
                mdbListSettings = MutableSharedFlow<Unit>(),
                mdbListCatalogPreferences = MutableSharedFlow<Unit>(),
                omdbSettings = MutableSharedFlow<Unit>(),
                theIntroDbSettings = MutableSharedFlow<Unit>(),
                animeSkipEnabled = MutableSharedFlow<Unit>(),
                subtitleTranslationSettings = MutableSharedFlow<Unit>(),
                wyzieSettings = MutableSharedFlow<Unit>(),
                posterRatingsSettings = MutableSharedFlow<Unit>(),
                premiumizeSettings = MutableSharedFlow<Unit>(),
                premiumizeAccountState = MutableSharedFlow<Unit>(),
                torBoxSettings = MutableSharedFlow<Unit>(),
                torBoxAccountState = MutableSharedFlow<Unit>(),
                easyDebridSettings = MutableSharedFlow<Unit>(),
                easyDebridAccountState = MutableSharedFlow<Unit>(),
                realDebridState = MutableSharedFlow<Unit>(),
                kitsuAuthState = MutableSharedFlow<Unit>(),
                traktAuthState = MutableSharedFlow<Unit>(),
                traktCatalogPreferences = MutableSharedFlow<Unit>(),
                simklCatalogPreferences = MutableSharedFlow<Unit>(),
                simklAuthState = MutableSharedFlow<Unit>(),
                playerSettings = MutableSharedFlow<Unit>()
            ).first()
        }

        tvdbSettings.emit(Unit)
        advanceUntilIdle()

        assertEquals("integrations.tvdb", emission.await())
    }

    @Test
    fun `observeAccountConfigSyncChangedPaths emits kitsu auth path label`() = runTest {
        val kitsuAuthState = MutableSharedFlow<Unit>(replay = 1)

        val emission = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            observeAccountConfigSyncChangedPaths(
                heroCatalogSelections = MutableSharedFlow<Unit>(),
                homeCatalogOrderKeys = MutableSharedFlow<Unit>(),
                disabledHomeCatalogKeys = MutableSharedFlow<Unit>(),
                tmdbSettings = MutableSharedFlow<Unit>(),
                tvdbSettings = MutableSharedFlow<Unit>(),
                mdbListSettings = MutableSharedFlow<Unit>(),
                mdbListCatalogPreferences = MutableSharedFlow<Unit>(),
                omdbSettings = MutableSharedFlow<Unit>(),
                theIntroDbSettings = MutableSharedFlow<Unit>(),
                animeSkipEnabled = MutableSharedFlow<Unit>(),
                subtitleTranslationSettings = MutableSharedFlow<Unit>(),
                wyzieSettings = MutableSharedFlow<Unit>(),
                posterRatingsSettings = MutableSharedFlow<Unit>(),
                premiumizeSettings = MutableSharedFlow<Unit>(),
                premiumizeAccountState = MutableSharedFlow<Unit>(),
                torBoxSettings = MutableSharedFlow<Unit>(),
                torBoxAccountState = MutableSharedFlow<Unit>(),
                easyDebridSettings = MutableSharedFlow<Unit>(),
                easyDebridAccountState = MutableSharedFlow<Unit>(),
                realDebridState = MutableSharedFlow<Unit>(),
                kitsuAuthState = kitsuAuthState,
                traktAuthState = MutableSharedFlow<Unit>(),
                traktCatalogPreferences = MutableSharedFlow<Unit>(),
                simklCatalogPreferences = MutableSharedFlow<Unit>(),
                simklAuthState = MutableSharedFlow<Unit>(),
                playerSettings = MutableSharedFlow<Unit>()
            ).first()
        }

        kitsuAuthState.emit(Unit)
        advanceUntilIdle()

        assertEquals("integrations.kitsuAuth", emission.await())
    }

    @Test
    fun `startup push gate blocks pushes until current user has pulled remote settings`() {
        val gate = AccountConfigStartupPushGate()

        gate.onSessionUserChanged("user-a")

        assertFalse(gate.canPush("user-a"))

        gate.markRemotePullSucceeded("user-a")

        assertTrue(gate.canPush("user-a"))
    }

    @Test
    fun `startup push gate resets when sync user changes`() {
        val gate = AccountConfigStartupPushGate()

        gate.onSessionUserChanged("user-a")
        gate.markRemotePullSucceeded("user-a")
        gate.onSessionUserChanged("user-b")

        assertFalse(gate.canPush("user-a"))
        assertFalse(gate.canPush("user-b"))
    }

    @Test
    fun `selectSubtitleTranslationApiKeySecret prefers generic when it is configured`() {
        assertEquals(
            "generic-key",
            selectSubtitleTranslationApiKeySecret(
                genericTranslationKey = "generic-key",
                legacyGeminiKey = "legacy-key",
                allowLegacyFallback = false
            )
        )
    }

    @Test
    fun `selectSubtitleTranslationApiKeySecret returns null for generic resolve failure`() {
        assertNull(
            selectSubtitleTranslationApiKeySecret(
                genericTranslationKey = null,
                legacyGeminiKey = "legacy-key",
                allowLegacyFallback = true
            )
        )
        assertNull(
            selectSubtitleTranslationApiKeySecret(
                genericTranslationKey = null,
                legacyGeminiKey = "",
                allowLegacyFallback = true
            )
        )
    }

    @Test
    fun `selectSubtitleTranslationApiKeySecret falls back to legacy when generic is blank and fallback is allowed`() {
        assertEquals(
            "legacy-key",
            selectSubtitleTranslationApiKeySecret(
                genericTranslationKey = "",
                legacyGeminiKey = "legacy-key",
                allowLegacyFallback = true
            )
        )
    }

    @Test
    fun `selectSubtitleTranslationApiKeySecret preserves blank generic when fallback is not allowed`() {
        assertEquals(
            "",
            selectSubtitleTranslationApiKeySecret(
                genericTranslationKey = "",
                legacyGeminiKey = "legacy-key",
                allowLegacyFallback = false
            )
        )
    }

    @Test
    fun `selectSubtitleTranslationApiKeySecret preserves blank generic when both remote keys are blank`() {
        assertEquals(
            "",
            selectSubtitleTranslationApiKeySecret(
                genericTranslationKey = "",
                legacyGeminiKey = "",
                allowLegacyFallback = true
            )
        )
    }

    @Test
    fun `legacyGeminiApiKeySecretForPush syncs Gemini provider key to legacy slot`() {
        assertEquals(
            "gemini-key",
            legacyGeminiApiKeySecretForPush(
                providerName = "GEMINI",
                translationApiKey = "gemini-key"
            )
        )
    }

    @Test
    fun `legacyGeminiApiKeySecretForPush clears legacy slot for blank Gemini provider key`() {
        assertEquals(
            "",
            legacyGeminiApiKeySecretForPush(
                providerName = "GEMINI",
                translationApiKey = ""
            )
        )
    }

    @Test
    fun `legacyGeminiApiKeySecretForPush leaves legacy slot untouched for non Gemini providers`() {
        assertNull(
            legacyGeminiApiKeySecretForPush(
                providerName = "OPENAI",
                translationApiKey = "openai-key"
            )
        )
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
        val theIntroDbSettings = MutableSharedFlow<Unit>(replay = 1)
        val animeSkipEnabled = MutableSharedFlow<Unit>(replay = 1)
        val subtitleTranslationSettings = MutableSharedFlow<Unit>(replay = 1)
        val posterRatingsSettings = MutableSharedFlow<Unit>(replay = 1)
        val premiumizeSettings = MutableSharedFlow<Unit>(replay = 1)
        val premiumizeAccountState = MutableSharedFlow<Unit>(replay = 1)
        val torBoxSettings = MutableSharedFlow<Unit>(replay = 1)
        val torBoxAccountState = MutableSharedFlow<Unit>(replay = 1)
        val easyDebridSettings = MutableSharedFlow<Unit>(replay = 1)
        val easyDebridAccountState = MutableSharedFlow<Unit>(replay = 1)
        val realDebridState = MutableSharedFlow<Unit>(replay = 1)
        val traktAuthState = MutableSharedFlow<Unit>(replay = 1)
        val traktCatalogPreferences = MutableSharedFlow<Unit>(replay = 1)
        val simklCatalogPreferences = MutableSharedFlow<Unit>(replay = 1)

        val emission = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            observeAccountConfigSyncChanges(
                heroCatalogSelections = heroCatalogSelections,
                homeCatalogOrderKeys = homeCatalogOrderKeys,
                disabledHomeCatalogKeys = disabledHomeCatalogKeys,
                tmdbSettings = tmdbSettings,
                tvdbSettings = MutableSharedFlow<Unit>(),
                mdbListSettings = mdbListSettings,
                mdbListCatalogPreferences = mdbListCatalogPreferences,
                omdbSettings = omdbSettings,
                theIntroDbSettings = theIntroDbSettings,
                animeSkipEnabled = animeSkipEnabled,
                subtitleTranslationSettings = subtitleTranslationSettings,
                wyzieSettings = MutableSharedFlow<Unit>(),
                posterRatingsSettings = posterRatingsSettings,
                premiumizeSettings = premiumizeSettings,
                premiumizeAccountState = premiumizeAccountState,
                torBoxSettings = torBoxSettings,
                torBoxAccountState = torBoxAccountState,
                easyDebridSettings = easyDebridSettings,
                easyDebridAccountState = easyDebridAccountState,
                realDebridState = realDebridState,
                kitsuAuthState = MutableSharedFlow<Unit>(),
                traktAuthState = traktAuthState,
                traktCatalogPreferences = traktCatalogPreferences,
                simklCatalogPreferences = simklCatalogPreferences,
                simklAuthState = MutableSharedFlow<Unit>(),
                playerSettings = MutableSharedFlow<Unit>()
            ).first()
        }

        heroCatalogSelections.emit(Unit)
        advanceUntilIdle()

        assertEquals(Unit, emission.await())
    }

    @Test
    fun `buildRemoteAddonInstallConfigs preserves v2 snapshot addons for startup reconcile`() = runTest {
        val addons = listOf(
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
            ),
            AccountAddonPayload(
                url = "https://opensubtitlesv3-pro.dexter21767.com",
                parserPreset = "generic",
                enabled = true,
                sortOrder = 3
            )
        )

        val addonConfigs = buildRemoteAddonInstallConfigs(addons) { addon ->
            Result.success("${addon.url}/manifest.json")
        }

        assertEquals(3, addonConfigs.size)
        assertEquals("https://alpha.example/manifest.json", addonConfigs[0].url)
        assertEquals(AddonParserPreset.GENERIC, addonConfigs[0].parserPreset)
        assertEquals("https://beta.example/manifest.json", addonConfigs[1].url)
        assertEquals(AddonParserPreset.TORRENTIO, addonConfigs[1].parserPreset)
        assertEquals("https://opensubtitlesv3-pro.dexter21767.com/manifest.json", addonConfigs[2].url)
        assertEquals(AddonParserPreset.GENERIC, addonConfigs[2].parserPreset)
    }

    @Test
    fun `applyAccountConfigSyncSettings routes only synced settings to the synced stores`() = runTest {
        val layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>(relaxed = true)
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>(relaxed = true)
        val mdbListSettingsDataStore = mockk<MDBListSettingsDataStore>(relaxed = true)
        val omdbSettingsDataStore = mockk<OmdbSettingsDataStore>(relaxed = true)
        val theIntroDbSettingsDataStore = mockk<TheIntroDbSettingsDataStore>(relaxed = true)
        val animeSkipSettingsDataStore = mockk<AnimeSkipSettingsDataStore>(relaxed = true)
        val subtitleTranslationSettingsDataStore = mockk<SubtitleTranslationSettingsDataStore>(relaxed = true)
        val posterRatingsSettingsDataStore = mockk<PosterRatingsSettingsDataStore>(relaxed = true)
        val traktSettingsDataStore = mockk<TraktSettingsDataStore>(relaxed = true)
        val simklSettingsDataStore = mockk<com.nexio.tv.data.local.SimklSettingsDataStore>(relaxed = true)

        val settings = buildAccountConfigSyncPayload(
            integrations = IntegrationSettings(
                theIntroDb = TheIntroDbSyncSettings(
                    enabled = true,
                    showIntroButton = true,
                    showRecapButton = false,
                    showCreditsButton = true,
                    showPreviewButton = false
                ),
                tmdb = TmdbSyncSettings(enabled = true, useArtwork = false, useBasicInfo = false),
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
                animeSkip = com.nexio.tv.data.remote.supabase.AnimeSkipSyncSettings(enabled = true),
                subtitleTranslation = SubtitleTranslationSyncSettings(
                    enabled = true,
                    provider = "OPENAI",
                    model = "openai/gpt-5.2",
                    baseUrl = "https://openrouter.ai/api/v1"
                ),
                gemini = GeminiSyncSettings(enabled = true),
                posterRatings = PosterRatingsSyncSettings(rpdbEnabled = true, topPostersEnabled = false)
            ),
            heroCatalogKeys = listOf("hero-a"),
            homeCatalogOrderKeys = listOf("row-a", "row-b"),
            disabledHomeCatalogKeys = listOf("row-c"),
            traktCatalogEnabledSet = listOf("trakt_up_next"),
            traktCatalogOrder = listOf("trakt_up_next", "trakt_recommended_movies"),
            traktSelectedPopularListKeys = listOf("popular-a"),
            simklCatalogEnabledSet = listOf("simkl_tv_trending_today"),
            simklCatalogOrder = listOf("simkl_tv_trending_today", "simkl_movie_trending_today"),
            mdbListHiddenPersonalListKeys = listOf("personal-hidden"),
            mdbListSelectedTopListKeys = listOf("top-selected"),
            mdbListCatalogOrder = listOf("mdb-top"),
            trackingProvider = TrackingProvider.SIMKL,
            formatter = FormatterSyncSettings(
                enabled = true,
                selectedTemplateId = "custom",
                customTemplate = CustomFormatterSyncTemplate(
                    id = "custom",
                    label = "Custom",
                    nameTemplate = "{stream.title}",
                    descriptionTemplate = "{stream.quality}",
                    badgeRowTemplate = "[[chip:cached]]"
                )
            )
        )

        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>(relaxed = true)
        val tmdbCatalogSettingsDataStore = mockk<TmdbCatalogSettingsDataStore>(relaxed = true)
        val kitsuCatalogSettingsDataStore = mockk<KitsuCatalogSettingsDataStore>(relaxed = true)
        val homeRailOrderStore = mockk<HomeRailOrderStore>(relaxed = true)

        applyAccountConfigSyncSettings(
            settings = settings,
            layoutPreferenceDataStore = layoutPreferenceDataStore,
            tmdbSettingsDataStore = tmdbSettingsDataStore,
            mdbListSettingsDataStore = mdbListSettingsDataStore,
            omdbSettingsDataStore = omdbSettingsDataStore,
            theIntroDbSettingsDataStore = theIntroDbSettingsDataStore,
            animeSkipSettingsDataStore = animeSkipSettingsDataStore,
            subtitleTranslationSettingsDataStore = subtitleTranslationSettingsDataStore,
            posterRatingsSettingsDataStore = posterRatingsSettingsDataStore,
            traktSettingsDataStore = traktSettingsDataStore,
            simklSettingsDataStore = simklSettingsDataStore,
            tmdbCatalogSettingsDataStore = tmdbCatalogSettingsDataStore,
            kitsuCatalogSettingsDataStore = kitsuCatalogSettingsDataStore,
            homeRailOrderStore = homeRailOrderStore,
            playerSettingsDataStore = playerSettingsDataStore
        )

        coVerify(exactly = 1) { layoutPreferenceDataStore.setHeroCatalogKeys(listOf("hero-a")) }
        coVerify(exactly = 1) { layoutPreferenceDataStore.setHomeCatalogOrderKeys(listOf("row-a", "row-b")) }
        coVerify(exactly = 1) { layoutPreferenceDataStore.setDisabledHomeCatalogKeys(listOf("row-c")) }
        coVerify(exactly = 1) { tmdbSettingsDataStore.setEnabled(true) }
        coVerify(exactly = 1) { omdbSettingsDataStore.setEnabled(true) }
        coVerify(exactly = 1) { theIntroDbSettingsDataStore.setEnabled(true) }
        coVerify(exactly = 1) { theIntroDbSettingsDataStore.setShowIntroButton(true) }
        coVerify(exactly = 1) { theIntroDbSettingsDataStore.setShowRecapButton(false) }
        coVerify(exactly = 1) { theIntroDbSettingsDataStore.setShowCreditsButton(true) }
        coVerify(exactly = 1) { theIntroDbSettingsDataStore.setShowPreviewButton(false) }
        coVerify(exactly = 1) {
            subtitleTranslationSettingsDataStore.saveSyncedPublicSettings(
                enabled = true,
                provider = SubtitleTranslationProvider.OPENAI,
                model = "openai/gpt-5.2",
                baseUrl = "https://openrouter.ai/api/v1"
            )
        }
        coVerify(exactly = 1) { mdbListSettingsDataStore.setCatalogPreferences(setOf("personal-hidden"), setOf("top-selected"), listOf("mdb-top")) }
        coVerify(exactly = 1) {
            traktSettingsDataStore.setCatalogPreferences(
                enabledCatalogs = setOf("trakt_up_next"),
                catalogOrder = listOf("trakt_up_next", "trakt_recommended_movies"),
                selectedPopularListKeys = setOf("popular-a")
            )
        }
        coVerify(exactly = 1) {
            simklSettingsDataStore.setCatalogPreferences(
                enabledCatalogs = setOf("simkl_tv_trending_today"),
                catalogOrder = listOf("simkl_tv_trending_today", "simkl_movie_trending_today")
            )
        }
        coVerify(exactly = 1) { playerSettingsDataStore.setTrackingProvider(TrackingProvider.SIMKL) }
        coVerify(exactly = 1) { playerSettingsDataStore.setSyncedFormatterEnabled(true) }
        coVerify(exactly = 1) { playerSettingsDataStore.setSyncedFormatterSelectedTemplateId("custom") }
        coVerify(exactly = 1) {
            playerSettingsDataStore.setSyncedFormatterCustomTemplate(
                label = "Custom",
                nameTemplate = "{stream.title}",
                descriptionTemplate = "{stream.quality}",
                badgeRowTemplate = "[[chip:cached]]"
            )
        }
    }

    private fun assertCustomFormatterPayloadAllowedBySchema(payload: JsonObject) {
        val customTemplatePayload = payload["formatter"]!!
            .jsonObject["customTemplate"]!!
            .jsonObject
        val customTemplateSchema = settingsSyncSchema()
            .getJSONObject("properties")
            .getJSONObject("formatter")
            .getJSONObject("properties")
            .getJSONObject("customTemplate")
            .getJSONArray("anyOf")
            .getJSONObject(1)
        val schemaProperties = customTemplateSchema.getJSONObject("properties")
        val schemaRequired = customTemplateSchema.getJSONArray("required")
        val requiredKeys = (0 until schemaRequired.length())
            .map { schemaRequired.getString(it) }
            .toSet()

        assertFalse(customTemplateSchema.getBoolean("additionalProperties"))
        assertTrue(requiredKeys.contains("badgeRowTemplate"))
        customTemplatePayload.keys.forEach { key ->
            assertTrue("settings sync schema should allow customTemplate.$key", schemaProperties.has(key))
        }
    }

    private fun settingsSyncSchema(): JSONObject {
        val schemaFile = listOf(
            File("docs/settings/settings-sync.schema.json"),
            File("../docs/settings/settings-sync.schema.json")
        ).first { it.isFile }

        return JSONObject(schemaFile.readText())
    }
}
