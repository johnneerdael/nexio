package com.nexio.tv.core.sync

import com.nexio.tv.data.local.AnimeSkipSettingsDataStore
import com.nexio.tv.data.local.KitsuCatalogSettingsDataStore
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.data.local.OmdbSettingsDataStore
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.PosterRatingsSettingsDataStore
import com.nexio.tv.data.local.SubtitleTranslationSettingsDataStore
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
import com.nexio.tv.data.remote.supabase.TmdbSyncSettings
import com.nexio.tv.data.remote.supabase.TraktAuthSyncSettings
import com.nexio.tv.data.remote.supabase.TraktPinnedListOptionSync
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkTypeKey
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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.decodeFromString
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
    fun `addon is_anime migration persists addon sync field end to end`() {
        val migration = File("supabase/migrations/20260506000000_add_account_addon_is_anime.sql").readText()

        assertTrue(migration.contains("add column if not exists is_anime boolean not null default false"))
        assertTrue(migration.contains("coalesce((entry->>'is_anime')::boolean, false)"))
        assertTrue(migration.contains("'is_anime', coalesce(is_anime, false)"))
        assertTrue(migration.contains("not in (1, 2, 5, 6, 7, 8, 9)"))
        assertTrue(migration.contains("if coalesce(p_contract_version, 1) not in (1, 2, 5, 6, 7, 9) then"))
        assertTrue(migration.contains("if v_contract_version not in (1, 2, 5, 6, 7, 9) then"))
    }

    @Test
    fun `addon sync service push payload includes is_anime wire field`() {
        val source = File("app/src/main/java/com/nexio/tv/core/sync/AddonSyncService.kt").readText()

        assertTrue(source.contains("put(\"is_anime\", isAnime)"))
        assertTrue(source.contains("Triple(parseStoredAddonInstallUrl(addon.url), addon.parserPreset, addon.isAnime)"))
    }

    @Test
    fun `subtitle translation sync defaults use OpenRouter free route`() {
        val settings = SubtitleTranslationSyncSettings()

        assertEquals("OPENAI", settings.provider)
        assertEquals("openrouter/free", settings.model)
        assertEquals("https://openrouter.ai/api/v1", settings.baseUrl)
    }

    @Test
    fun `v13 setting sections apply to account config payload without resetting unrelated settings`() {
        val current = AccountConfigSyncPayload(
            schemaVersion = ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION,
            integrations = IntegrationSettings(
                tmdb = TmdbSyncSettings(useArtwork = false),
                omdb = OmdbSyncSettings(enabled = true),
                subtitleTranslation = SubtitleTranslationSyncSettings(
                    enabled = false,
                    provider = "OPENAI",
                    model = "openrouter/free",
                    baseUrl = "https://openrouter.ai/api/v1"
                )
            ),
            catalogs = CatalogSyncSettings(
                home = com.nexio.tv.data.remote.supabase.HomeCatalogSyncSettings(
                    heroCatalogKeys = listOf("old-hero"),
                    homeCatalogOrderKeys = listOf("old-row"),
                    disabledHomeCatalogKeys = emptyList()
                )
            ),
            playback = com.nexio.tv.data.remote.supabase.PlaybackConfigSyncSettings(
                streamSelection = com.nexio.tv.data.remote.supabase.StreamSelectionConfigSyncSettings(
                    trackingProvider = "TRAKT"
                )
            ),
            formatter = FormatterSyncSettings(enabled = true)
        )

        val translated = AccountSettingsSectionKey.INTEGRATIONS_SUBTITLE_TRANSLATION.applyToPayload(
            current = current,
            sectionPayload = buildJsonObject {
                put("enabled", true)
                put("provider", "DASHSCOPE")
                put("model", "qwen-mt-flash")
                put("baseUrl", "https://dashscope-intl.aliyuncs.com/api/v1")
            }
        )
        val withPlayback = AccountSettingsSectionKey.PLAYBACK_STREAM_SELECTION.applyToPayload(
            current = translated,
            sectionPayload = buildJsonObject {
                put("trackingProvider", "SIMKL")
            }
        )
        val withCatalog = AccountSettingsSectionKey.CATALOGS_HOME.applyToPayload(
            current = withPlayback,
            sectionPayload = buildJsonObject {
                put("heroCatalogKeys", buildJsonArray { add("new-hero") })
                put("homeCatalogOrderKeys", buildJsonArray { add("new-row") })
                put("disabledHomeCatalogKeys", buildJsonArray {})
            }
        )

        assertEquals(true, withCatalog.integrations.subtitleTranslation.enabled)
        assertEquals("DASHSCOPE", withCatalog.integrations.subtitleTranslation.provider)
        assertEquals("qwen-mt-flash", withCatalog.integrations.subtitleTranslation.model)
        assertEquals("SIMKL", withCatalog.playback.streamSelection.trackingProvider)
        assertEquals(listOf("new-hero"), withCatalog.catalogs.home?.heroCatalogKeys)
        assertEquals(listOf("new-row"), withCatalog.catalogs.home?.homeCatalogOrderKeys)
        assertEquals(false, withCatalog.integrations.tmdb.useArtwork)
        assertEquals(true, withCatalog.integrations.omdb.enabled)
        assertEquals(true, withCatalog.formatter.enabled)
    }

    @Test
    fun `v13 pull applies sparse account settings with section presence markers`() {
        val source = File("app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt").readText()
        val pullStart = source.indexOf("suspend fun pullFromRemoteAndApply")
        val debridRefreshStart = source.indexOf("private suspend fun refreshDebridAccountStatesForAppliedSections", startIndex = pullStart)
        val pullBlock = source.substring(pullStart, debridRefreshStart)

        assertTrue(
            "v13 pull must derive known present section keys from the snapshot rows",
            pullBlock.contains("val appliedSectionKeys = appliedSections.map { it.first }.toSet()")
        )
        assertTrue(
            "v13 pull must resolve secrets only for sections present in the sparse snapshot",
            pullBlock.contains("resolveRemoteSecretsForApply(settings, appliedSectionKeys)")
        )
        assertTrue(
            "v13 pull must pass section presence into local settings application",
            pullBlock.contains("sectionKeys = appliedSectionKeys")
        )
        assertFalse(
            "v13 pull must not apply the default-backed sparse payload as a complete payload",
            pullBlock.contains("applySharedAccountConfigSyncSettings(settings)")
        )
    }

    @Test
    fun `v13 pull refreshes debrid account state only for present sparse sections`() {
        val source = File("app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt").readText()
        val pullStart = source.indexOf("suspend fun pullFromRemoteAndApply")
        val debridRefreshStart = source.indexOf("private suspend fun refreshDebridAccountStatesForAppliedSections", startIndex = pullStart)
        val liveSessionStart = source.indexOf("private fun hasLiveFullAccountSession", startIndex = debridRefreshStart)
        val pullBlock = source.substring(pullStart, debridRefreshStart)
        val refreshBlock = source.substring(debridRefreshStart, liveSessionStart)

        fun assertRefreshGated(sectionKey: String, refreshCall: String, message: String) {
            assertTrue(
                message,
                refreshBlock.contains(
                    "if (sectionKeys.includesSection(AccountSettingsSectionKey.$sectionKey)) {\n" +
                        "            $refreshCall"
                )
            )
        }

        assertTrue(
            "v13 pull must pass sparse section presence to debrid account refreshes",
            pullBlock.contains("refreshDebridAccountStatesForAppliedSections(appliedSectionKeys)")
        )
        assertFalse(
            "v13 pull must not refresh Premiumize unconditionally after sparse apply",
            pullBlock.contains("premiumizeService.refreshAccountState()")
        )
        assertFalse(
            "v13 pull must not refresh TorBox unconditionally after sparse apply",
            pullBlock.contains("torBoxService.refreshAccountState()")
        )
        assertFalse(
            "v13 pull must not refresh EasyDebrid unconditionally after sparse apply",
            pullBlock.contains("easyDebridService.refreshAccountState()")
        )
        assertRefreshGated(
            sectionKey = "INTEGRATIONS_DEBRID_PREMIUMIZE",
            refreshCall = "premiumizeService.refreshAccountState()",
            message = "Premiumize refresh must be gated by the Premiumize debrid section key",
        )
        assertRefreshGated(
            sectionKey = "INTEGRATIONS_DEBRID_TOR_BOX",
            refreshCall = "torBoxService.refreshAccountState()",
            message = "TorBox refresh must be gated by the TorBox debrid section key",
        )
        assertRefreshGated(
            sectionKey = "INTEGRATIONS_DEBRID_EASY_DEBRID",
            refreshCall = "easyDebridService.refreshAccountState()",
            message = "EasyDebrid refresh must be gated by the EasyDebrid debrid section key",
        )
    }

    @Test
    fun `metadata provider sync defaults are core enabled`() {
        val tmdb = TmdbSyncSettings()

        assertTrue(tmdb.enabled)
    }

    @Test
    fun `buildAccountConfigSyncPayload serializes integrations catalogs and formatter`() {
        val payload = buildAccountConfigSyncPayload(
            integrations = IntegrationSettings(
                debrid = DebridSyncSettings(
                    premiumize = PremiumizeSyncSettings(configured = true, customerId = 42),
                    realDebrid = RealDebridSyncSettings(connected = true, username = "rd-user")
                ),
                tmdb = TmdbSyncSettings(enabled = true, useArtwork = false),
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
        assertEquals(ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION, json["schemaVersion"]?.toString()?.toInt())
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
        assertFalse(json["integrations"]?.jsonObject?.containsKey("tvdb") == true)
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
    fun `build account config sync rpc params includes current contract version`() {
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
        assertEquals(
            ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION,
            buildAccountConfigSyncPullParams()["p_contract_version"]?.toString()?.toInt()
        )
    }

    @Test
    fun `current contract emits version 12`() {
        assertEquals(12, ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION)
    }

    @Test
    fun `current version payload includes tmdb and kitsu sections when set`() {
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
        assertTrue(json.contains("\"schemaVersion\":$ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION"))
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
    fun `observeAccountConfigSyncChangedPaths emits kitsu auth path label`() = runTest {
        val kitsuAuthState = MutableSharedFlow<Unit>(replay = 1)

        val emission = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            observeAccountConfigSyncChangedPaths(
                heroCatalogSelections = MutableSharedFlow<Unit>(),
                homeCatalogOrderKeys = MutableSharedFlow<Unit>(),
                disabledHomeCatalogKeys = MutableSharedFlow<Unit>(),
                tmdbSettings = MutableSharedFlow<Unit>(),
                mdbListSettings = MutableSharedFlow<Unit>(),
                mdbListCatalogPreferences = MutableSharedFlow<Unit>(),
                omdbSettings = MutableSharedFlow<Unit>(),
                animeSkipEnabled = MutableSharedFlow<Unit>(),
                subtitleTranslationSettings = MutableSharedFlow<Unit>(),
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
                mdbListSettings = mdbListSettings,
                mdbListCatalogPreferences = mdbListCatalogPreferences,
                omdbSettings = omdbSettings,
                animeSkipEnabled = animeSkipEnabled,
                subtitleTranslationSettings = subtitleTranslationSettings,
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
                isAnime = true,
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
        assertTrue(addonConfigs[1].isAnime)
        assertEquals("https://opensubtitlesv3-pro.dexter21767.com/manifest.json", addonConfigs[2].url)
        assertEquals(AddonParserPreset.GENERIC, addonConfigs[2].parserPreset)
        assertFalse(addonConfigs[2].isAnime)
    }

    @Test
    fun `account addon payload deserializes is_anime from wire shape`() {
        val payload = Json.decodeFromString<AccountAddonPayload>(
            """
                {
                  "url": "https://anime.example",
                  "parser_preset": "GENERIC",
                  "is_anime": true
                }
            """.trimIndent()
        )

        assertTrue(payload.isAnime)
    }

    @Test
    fun `applyAccountConfigSyncSettings routes only synced settings to the synced stores`() = runTest {
        val layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>(relaxed = true)
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>(relaxed = true)
        val mdbListSettingsDataStore = mockk<MDBListSettingsDataStore>(relaxed = true)
        val omdbSettingsDataStore = mockk<OmdbSettingsDataStore>(relaxed = true)
        val animeSkipSettingsDataStore = mockk<AnimeSkipSettingsDataStore>(relaxed = true)
        val subtitleTranslationSettingsDataStore = mockk<SubtitleTranslationSettingsDataStore>(relaxed = true)
        val posterRatingsSettingsDataStore = mockk<PosterRatingsSettingsDataStore>(relaxed = true)
        val traktSettingsDataStore = mockk<TraktSettingsDataStore>(relaxed = true)
        val simklSettingsDataStore = mockk<com.nexio.tv.data.local.SimklSettingsDataStore>(relaxed = true)

        val settings = buildAccountConfigSyncPayload(
            integrations = IntegrationSettings(
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
                posterRatings = PosterRatingsSyncSettings(rpdbEnabled = true, topPostersEnabled = true)
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
        coVerify(exactly = 1) {
            posterRatingsSettingsDataStore.setProviderSelection(
                ArtworkTypeKey.POSTER,
                ArtworkProviderChoiceKey.RPDB
            )
        }
        coVerify(exactly = 0) { posterRatingsSettingsDataStore.setRpdbEnabled(any()) }
        coVerify(exactly = 0) { posterRatingsSettingsDataStore.setTopPostersEnabled(any()) }
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
