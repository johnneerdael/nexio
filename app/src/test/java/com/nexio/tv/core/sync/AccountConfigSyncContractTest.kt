package com.nexio.tv.core.sync

import com.nexio.tv.data.local.AnimeSkipSettingsDataStore
import com.nexio.tv.data.local.KitsuCatalogSettingsDataStore
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.data.local.OmdbSettingsDataStore
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.PosterRatingsSettingsDataStore
import com.nexio.tv.data.local.SubtitleTranslationSettingsDataStore
import com.nexio.tv.data.local.SyncWatermarkDataStore
import com.nexio.tv.data.local.TmdbCatalogSettingsDataStore
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.ui.screens.home.order.HomeRailOrderStore
import com.nexio.tv.data.remote.supabase.AccountAddonPayload
import com.nexio.tv.data.remote.supabase.AccountConfigSnapshotRpcResponse
import com.nexio.tv.data.remote.supabase.AccountConfigSyncPayload
import com.nexio.tv.data.remote.supabase.CatalogSyncSettings
import com.nexio.tv.data.remote.supabase.CustomFormatterSyncTemplate
import com.nexio.tv.data.remote.supabase.HomeCatalogSyncSettings
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
import io.mockk.coEvery
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
import kotlinx.serialization.json.jsonPrimitive
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
            "v13 pull must resolve sparse snapshot secrets plus preserved dirty secret sections",
            pullBlock.contains("resolveRemoteSecretsForApply(settings, sectionKeysToResolveSecretsFor)")
        )
        assertTrue(
            "v13 pull must pass section presence into local settings application",
            pullBlock.contains("sectionKeys = sectionKeysToApply")
        )
        assertTrue(
            "v13 pull must allow stale recovery to preserve locally dirty sections",
            pullBlock.contains("val sectionKeysToApply = appliedSectionKeys - preserveLocalSectionKeys")
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
            pullBlock.contains("refreshDebridAccountStatesForAppliedSections(sectionKeysToApply)")
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
    fun `sectionPayload extracts representative v13 sections and skips unsupported integrations kitsu`() {
        val payload = AccountConfigSyncPayload(
            schemaVersion = ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION,
            integrations = IntegrationSettings(
                debrid = DebridSyncSettings(
                    premiumize = PremiumizeSyncSettings(configured = true, customerId = 42),
                    realDebrid = RealDebridSyncSettings(connected = true, username = "rd-user")
                ),
                tmdb = TmdbSyncSettings(enabled = true, useArtwork = false),
                subtitleTranslation = SubtitleTranslationSyncSettings(
                    enabled = true,
                    provider = "DASHSCOPE",
                    model = "qwen-mt-flash",
                    baseUrl = "https://dashscope-intl.aliyuncs.com/api/v1"
                ),
                kitsuAuth = KitsuAuthSyncSettings(connected = true, username = "kitsu-user")
            ),
            catalogs = CatalogSyncSettings(
                home = HomeCatalogSyncSettings(
                    heroCatalogKeys = listOf("hero-a"),
                    homeCatalogOrderKeys = listOf("row-a"),
                    disabledHomeCatalogKeys = listOf("row-b")
                ),
                tmdb = TmdbCatalogSyncSettings(catalogOrder = listOf("tmdb-popular")),
                kitsu = KitsuCatalogSyncSettings(catalogOrder = listOf("kitsu-trending"))
            ),
            playback = com.nexio.tv.data.remote.supabase.PlaybackConfigSyncSettings(
                streamSelection = com.nexio.tv.data.remote.supabase.StreamSelectionConfigSyncSettings(
                    trackingProvider = "SIMKL"
                )
            ),
            formatter = FormatterSyncSettings(enabled = false, selectedTemplateId = "compact")
        )

        val tmdb = payload.sectionPayload(AccountSettingsSectionKey.INTEGRATIONS_TMDB)!!.jsonObject
        val subtitle = payload.sectionPayload(AccountSettingsSectionKey.INTEGRATIONS_SUBTITLE_TRANSLATION)!!.jsonObject
        val premiumize = payload.sectionPayload(AccountSettingsSectionKey.INTEGRATIONS_DEBRID_PREMIUMIZE)!!.jsonObject
        val home = payload.sectionPayload(AccountSettingsSectionKey.CATALOGS_HOME)!!.jsonObject
        val playback = payload.sectionPayload(AccountSettingsSectionKey.PLAYBACK_STREAM_SELECTION)!!.jsonObject
        val formatter = payload.sectionPayload(AccountSettingsSectionKey.FORMATTER)!!.jsonObject

        assertEquals("false", tmdb["useArtwork"].toString())
        assertEquals("\"DASHSCOPE\"", subtitle["provider"].toString())
        assertEquals("42", premiumize["customerId"].toString())
        assertEquals("\"hero-a\"", home["heroCatalogKeys"]!!.jsonArray.first().toString())
        assertEquals("\"SIMKL\"", playback["trackingProvider"].toString())
        assertEquals("\"compact\"", formatter["selectedTemplateId"].toString())
        assertNull(payload.sectionPayload(AccountSettingsSectionKey.INTEGRATIONS_KITSU))
    }

    @Test
    fun `sectionPayload serializes default-valued reset sections as objects`() {
        val payload = AccountConfigSyncPayload(schemaVersion = ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION)

        val formatter = payload.sectionPayload(AccountSettingsSectionKey.FORMATTER)
        val streamSelection = payload.sectionPayload(AccountSettingsSectionKey.PLAYBACK_STREAM_SELECTION)

        assertTrue(formatter is JsonObject)
        assertTrue(streamSelection is JsonObject)
        assertTrue(formatter!!.jsonObject.isEmpty())
        assertTrue(streamSelection!!.jsonObject.isEmpty())
        assertNull(payload.sectionPayload(AccountSettingsSectionKey.CATALOGS_HOME))
        assertNull(payload.sectionPayload(AccountSettingsSectionKey.INTEGRATIONS_KITSU))
    }

    @Test
    fun `buildAccountSettingsSectionsPushParamsV13 serializes dirty baseline sections`() = runTest {
        val payload = AccountConfigSyncPayload(
            schemaVersion = ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION,
            integrations = IntegrationSettings(
                tmdb = TmdbSyncSettings(useArtwork = false, useDetails = false)
            ),
            catalogs = CatalogSyncSettings(
                home = HomeCatalogSyncSettings(
                    heroCatalogKeys = listOf("hero-a"),
                    homeCatalogOrderKeys = listOf("row-a"),
                    disabledHomeCatalogKeys = emptyList()
                )
            ),
            playback = com.nexio.tv.data.remote.supabase.PlaybackConfigSyncSettings(
                streamSelection = com.nexio.tv.data.remote.supabase.StreamSelectionConfigSyncSettings(
                    trackingProvider = "SIMKL"
                )
            )
        )
        val watermarkStore = mockk<SyncWatermarkDataStore>()
        coEvery { watermarkStore.getAccountSettingsSection(AccountSettingsSectionKey.INTEGRATIONS_TMDB) } returns 111L
        coEvery { watermarkStore.getAccountSettingsSection(AccountSettingsSectionKey.CATALOGS_HOME) } returns 222L
        coEvery { watermarkStore.getAccountSettingsSection(AccountSettingsSectionKey.PLAYBACK_STREAM_SELECTION) } returns 333L

        val params = buildAccountSettingsSectionsPushParamsV13(
            payload = payload,
            sectionKeys = linkedSetOf(
                AccountSettingsSectionKey.INTEGRATIONS_TMDB,
                AccountSettingsSectionKey.CATALOGS_HOME,
                AccountSettingsSectionKey.INTEGRATIONS_KITSU,
                AccountSettingsSectionKey.PLAYBACK_STREAM_SELECTION
            ),
            watermarkStore = watermarkStore
        )

        val sections = params["p_sections"]!!.jsonArray.map { it.jsonObject }

        assertEquals("\"android-v13\"", params["p_source"].toString())
        assertEquals(
            listOf(
                AccountSettingsSectionKey.INTEGRATIONS_TMDB.key,
                AccountSettingsSectionKey.CATALOGS_HOME.key,
                AccountSettingsSectionKey.PLAYBACK_STREAM_SELECTION.key
            ),
            sections.map { it["section_key"]!!.jsonPrimitive.content }
        )
        assertEquals("111", sections[0]["base_updated_at_ms"].toString())
        assertEquals("false", sections[0]["payload"]!!.jsonObject["useArtwork"].toString())
        assertEquals("222", sections[1]["base_updated_at_ms"].toString())
        assertEquals("\"hero-a\"", sections[1]["payload"]!!.jsonObject["heroCatalogKeys"]!!.jsonArray.first().toString())
        assertEquals("333", sections[2]["base_updated_at_ms"].toString())
        assertEquals("\"SIMKL\"", sections[2]["payload"]!!.jsonObject["trackingProvider"].toString())
    }

    @Test
    fun `dirty account settings sections are derived from section baselines`() {
        val baselinePayload = AccountConfigSyncPayload(
            schemaVersion = ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION,
            integrations = IntegrationSettings(
                tmdb = TmdbSyncSettings(useArtwork = true, useDetails = false)
            ),
            catalogs = CatalogSyncSettings(
                home = HomeCatalogSyncSettings(
                    heroCatalogKeys = listOf("hero-a"),
                    homeCatalogOrderKeys = listOf("row-a"),
                    disabledHomeCatalogKeys = emptyList()
                )
            )
        )
        val baseline = accountSettingsSectionBaselinePayloads(baselinePayload)

        assertEquals(
            emptySet<AccountSettingsSectionKey>(),
            dirtyAccountSettingsSectionKeys(baselinePayload, baseline)
        )

        val current = baselinePayload.copy(
            integrations = baselinePayload.integrations.copy(
                tmdb = baselinePayload.integrations.tmdb.copy(useArtwork = false)
            ),
            catalogs = baselinePayload.catalogs.copy(
                home = baselinePayload.catalogs.home?.copy(homeCatalogOrderKeys = listOf("row-b"))
            )
        )

        assertEquals(
            setOf(
                AccountSettingsSectionKey.INTEGRATIONS_TMDB,
                AccountSettingsSectionKey.CATALOGS_HOME
            ),
            dirtyAccountSettingsSectionKeys(current, baseline)
        )
    }

    @Test
    fun `account settings push routes through v13 section batch rpc and handles partial outcomes`() {
        val source = File("app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt").readText()
        val pushStart = source.indexOf("suspend fun pushToRemote")
        val pullStart = source.indexOf("suspend fun pullFromRemoteAndApply", startIndex = pushStart)
        val pushBlock = source.substring(pushStart, pullStart)

        assertTrue(pushBlock.contains("sync_push_account_settings_sections_v13"))
        assertTrue(pushBlock.contains("decodeAs<V13BatchPushResult>()"))
        assertFalse(pushBlock.contains("sync_push_account_settings_v10"))
        assertTrue(pushBlock.contains("preserveLocalSectionKeys = preserveLocalSectionKeys"))
        assertTrue(pushBlock.contains("currentUpdatedAtMs != null"))
        assertTrue(pushBlock.contains("setAccountSettingsSection(sectionKey, result.currentUpdatedAtMs)"))
        assertTrue(pushBlock.contains("dirtySettingsSectionKeys = dirtyAccountSettingsSectionKeys("))
        assertTrue(pushBlock.contains("getAccountSettingsSectionBaselines()"))
        assertTrue(pushBlock.contains("setAccountSettingsSectionBaselines("))
        assertTrue(pushBlock.contains("changedPathsBySection"))
        assertTrue(pushBlock.contains("clearAppliedChangedPathsForGeneration("))
        assertTrue(pushBlock.contains("appliedChangedPaths = appliedChangedPaths"))
        assertFalse(pushBlock.contains("pendingChangedPaths.removeAll(snapshot.changedPaths.toSet())"))
    }

    @Test
    fun `mixed v13 stale push clears applied section paths before stale pull`() {
        val source = File("app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt").readText()
        val pushStart = source.indexOf("suspend fun pushToRemote")
        val pullStart = source.indexOf("suspend fun pullFromRemoteAndApply", startIndex = pushStart)
        val pushBlock = source.substring(pushStart, pullStart)

        val clearIndex = pushBlock.indexOf("clearAppliedChangedPathsForGeneration(")
        val staleIndex = pushBlock.indexOf("if (hasStaleSection)")
        val staleFollowUpIndex = pushBlock.indexOf("if (scheduleFollowUpPush)", startIndex = staleIndex)
        val preserveIndex = pushBlock.indexOf("buildStaleRecoveryPreserveLocalSectionKeys(", startIndex = staleIndex)
        val stalePullIndex = pushBlock.indexOf("pullFromRemoteAndApply(", startIndex = staleIndex)

        assertTrue("v13 push must attempt applied-path clearing", clearIndex >= 0)
        assertTrue("applied paths must be cleared before stale recovery returns", clearIndex < staleIndex)
        assertTrue("stale recovery must compute local sections to preserve after applied-path clearing", preserveIndex in (staleIndex + 1)..<stalePullIndex)
        assertTrue("generation races and stale sections must schedule a follow-up after stale recovery", staleFollowUpIndex > stalePullIndex)
        assertTrue("stale recovery must pull without clearing stale pending paths", stalePullIndex > staleIndex)
        assertTrue(
            "stale recovery must preserve dirty value sections and dirty secret-backed sections, not every applied section",
            pushBlock.contains("dirtySettingsSectionKeys = dirtyAccountSettingsSectionKeys(") &&
            pushBlock.contains("dirtySecretSectionKeys = dirtyAccountSecretSectionKeys(snapshot.secrets)")
        )
        assertFalse(
            "stale recovery must not treat every applied section as secret-dirty",
            pushBlock.contains("appliedSectionKeysWithPendingSecrets = appliedSectionKeys")
        )
    }

    @Test
    fun `all stale v13 push checks generation race before stale pull`() {
        val source = File("app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt").readText()
        val pushStart = source.indexOf("suspend fun pushToRemote")
        val pullStart = source.indexOf("suspend fun pullFromRemoteAndApply", startIndex = pushStart)
        val pushBlock = source.substring(pushStart, pullStart)

        val staleIndex = pushBlock.indexOf("if (hasStaleSection)")
        val generationCheckIndex = pushBlock.indexOf(
            "if (pendingChangedPathsGeneration != snapshot.changedPathsGeneration)",
            startIndex = staleIndex
        )
        val staleFollowUpIndex = pushBlock.indexOf("if (scheduleFollowUpPush)", startIndex = staleIndex)
        val stalePullIndex = pushBlock.indexOf("pullFromRemoteAndApply(", startIndex = staleIndex)

        assertTrue("v13 push must handle stale sections", staleIndex >= 0)
        assertTrue(
            "all-stale pushes must compare the live generation with the push snapshot before stale recovery",
            generationCheckIndex in (staleIndex + 1)..<staleFollowUpIndex
        )
        assertTrue("generation race follow-up must be scheduled after stale recovery pull", staleFollowUpIndex > stalePullIndex)
    }

    @Test
    fun `stale recovery follow-up is scheduled only after recovery pull succeeds`() {
        val source = File("app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt").readText()
        val pushStart = source.indexOf("suspend fun pushToRemote")
        val pullStart = source.indexOf("suspend fun pullFromRemoteAndApply", startIndex = pushStart)
        val pushBlock = source.substring(pushStart, pullStart)
        val staleIndex = pushBlock.indexOf("if (hasStaleSection)")
        val stalePullIndex = pushBlock.indexOf("val staleRecoveryResult = pullFromRemoteAndApply(", startIndex = staleIndex)
        val failedReturnIndex = pushBlock.indexOf("if (staleRecoveryResult.isFailure)", startIndex = stalePullIndex)
        val followUpIndex = pushBlock.indexOf("if (scheduleFollowUpPush)", startIndex = failedReturnIndex)

        assertTrue("stale recovery must capture pull result", stalePullIndex > staleIndex)
        assertTrue("failed stale recovery pull must return before scheduling a retry loop", failedReturnIndex > stalePullIndex)
        assertTrue("follow-up push must be considered only after successful stale recovery pull", followUpIndex > failedReturnIndex)
    }

    @Test
    fun `stale secret v10 handlers keep old base instead of advancing without remote payload`() {
        val source = File("app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt").readText()
        val setStart = source.indexOf("private suspend fun setAccountSecretV10")
        val deleteStart = source.indexOf("private suspend fun deleteAccountSecretV10")
        val jwtStart = source.indexOf("private suspend fun <T> withJwtRefreshRetry", startIndex = deleteStart)
        val setBlock = source.substring(setStart, deleteStart)
        val deleteBlock = source.substring(deleteStart, jwtStart)
        val setStaleBlock = setBlock.substring(
            setBlock.indexOf("is V10PushOutcome.StaleBase"),
            setBlock.indexOf("is V10PushOutcome.Failed")
        )
        val deleteStaleBlock = deleteBlock.substring(
            deleteBlock.indexOf("is V10PushOutcome.StaleBase"),
            deleteBlock.indexOf("is V10PushOutcome.Failed")
        )

        assertFalse(setStaleBlock.contains("syncWatermarkStore.set(SyncWatermarkSurface.ACCOUNT_SECRETS"))
        assertFalse(deleteStaleBlock.contains("syncWatermarkStore.set(SyncWatermarkSurface.ACCOUNT_SECRETS"))
        assertFalse(setBlock.contains("handleStaleAccountSecretPush("))
        assertFalse(deleteBlock.contains("handleStaleAccountSecretPush("))
        assertFalse(setBlock.contains("pullFromRemoteAndApply()"))
        assertFalse(deleteBlock.contains("pullFromRemoteAndApply()"))
    }

    @Test
    fun `pull schedules follow up when remote resolve preserves newer local secrets`() {
        val source = File("app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt").readText()
        val pullStart = source.indexOf("suspend fun pullFromRemoteAndApply")
        val refreshStart = source.indexOf("private suspend fun refreshDebridAccountStatesForAppliedSections", startIndex = pullStart)
        val pullBlock = source.substring(pullStart, refreshStart)
        val resolveStart = source.indexOf("private suspend fun resolveRemoteSecretsForApply")
        val applyStart = source.indexOf("private suspend fun applyResolvedRemoteSecrets", startIndex = resolveStart)
        val resolveBlock = source.substring(resolveStart, applyStart)

        assertTrue(pullBlock.contains("val scheduleSecretFollowUpPush = resolvedSecrets.followUpLocalSecretSectionKeys.isNotEmpty() &&"))
        assertTrue(pullBlock.contains("resolvedSecrets.unresolvedRemoteSecretSectionKeys.isEmpty()"))
        assertTrue(pullBlock.contains("if (scheduleSecretFollowUpPush && hasLiveFullAccountSession())"))
        assertTrue(resolveBlock.contains("followUpLocalSecretSections += AccountSettingsSectionKey.INTEGRATIONS_TRAKT_AUTH"))
        assertTrue(resolveBlock.contains("preservedLocalSecretSections += AccountSettingsSectionKey.INTEGRATIONS_TRAKT_AUTH"))
        assertTrue(resolveBlock.contains("resolvedSimkl?.preserveLocalTokens == true"))
        assertTrue(resolveBlock.contains("followUpLocalSecretSections += AccountSettingsSectionKey.INTEGRATIONS_SIMKL_AUTH"))
        assertTrue(resolveBlock.contains("preservedLocalSecretSections += AccountSettingsSectionKey.INTEGRATIONS_SIMKL_AUTH"))
        assertTrue(resolveBlock.contains("resolvedRealDebrid?.preserveLocalTokens == true"))
        assertTrue(resolveBlock.contains("followUpLocalSecretSections += AccountSettingsSectionKey.INTEGRATIONS_DEBRID_REAL_DEBRID"))
        assertTrue(resolveBlock.contains("preservedLocalSecretSections += AccountSettingsSectionKey.INTEGRATIONS_DEBRID_REAL_DEBRID"))
        assertTrue(resolveBlock.contains("resolvedKitsu.preserveLocalTokens"))
        assertTrue(resolveBlock.contains("followUpLocalSecretSections += AccountSettingsSectionKey.INTEGRATIONS_KITSU_AUTH"))
        assertTrue(resolveBlock.contains("preservedLocalSecretSections += AccountSettingsSectionKey.INTEGRATIONS_KITSU_AUTH"))
    }

    @Test
    fun `pull advances account secrets watermark only after all remote secrets are known`() {
        val source = File("app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt").readText()
        val pullStart = source.indexOf("suspend fun pullFromRemoteAndApply")
        val refreshStart = source.indexOf("private suspend fun refreshDebridAccountStatesForAppliedSections", startIndex = pullStart)
        val pullBlock = source.substring(pullStart, refreshStart)
        val resolveIndex = pullBlock.indexOf("val resolvedSecrets = resolveRemoteSecretsForApply(settings, sectionKeysToResolveSecretsFor)")
        val secretsWatermarkIndex = pullBlock.indexOf("syncWatermarkStore.set(SyncWatermarkSurface.ACCOUNT_SECRETS")

        assertTrue("account secrets watermark must be decided after secret resolution", secretsWatermarkIndex > resolveIndex)
        assertTrue(pullBlock.contains("val sectionKeysToResolveSecretsFor = sectionKeysToApply + preservedPullSecretSectionKeys"))
        assertTrue(pullBlock.contains("preserveLocalSectionKeys.intersect(ACCOUNT_SECRET_SECTION_KEYS)"))
        assertTrue(pullBlock.contains("resolvedSecrets.unresolvedRemoteSecretSectionKeys.isEmpty()"))
        assertTrue(pullBlock.contains("applyResolvedRemoteSecrets(resolvedSecrets, sectionKeysToApply)"))
    }

    @Test
    fun `secret resolve failures preserve baseline dirty state`() {
        val source = File("app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt").readText()
        val resolveStart = source.indexOf("private suspend fun resolveRemoteSecretsForApply")
        val applyStart = source.indexOf("private suspend fun applyResolvedRemoteSecrets", startIndex = resolveStart)
        val resolveBlock = source.substring(resolveStart, applyStart)

        assertTrue(resolveBlock.contains("if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_MDBLIST) && mdbListApiKey == null)"))
        assertTrue(resolveBlock.contains("if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_OMDB) && omdbApiKey == null)"))
        assertTrue(resolveBlock.contains("genericTranslationKey == null"))
        assertTrue(resolveBlock.contains("subtitleTranslationSecretUnresolved"))
        assertTrue(resolveBlock.contains("subtitleTranslationApiKey = if (subtitleTranslationSecretUnresolved)"))
        assertTrue(resolveBlock.contains("selectSubtitleTranslationApiKeySecret("))
        assertTrue(resolveBlock.contains("rpdbApiKey == null || topPostersApiKey == null"))
        assertTrue(resolveBlock.contains("resolvedRealDebrid == null"))
        assertTrue(resolveBlock.contains("resolvedTrakt == null"))
        assertTrue(resolveBlock.contains("resolvedSimkl == null"))
        assertTrue(resolveBlock.contains("resolvedKitsu == null"))
        assertTrue(resolveBlock.contains("preservedLocalSectionKeys = preservedLocalSecretSections"))
        assertTrue(resolveBlock.contains("unresolvedRemoteSecretSectionKeys = unresolvedRemoteSecretSections"))
    }

    @Test
    fun `blank connected remote auth secrets preserve local tokens for repair push`() {
        val source = File("app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt").readText()
        val traktStart = source.indexOf("private suspend fun resolveRemoteTraktSecrets")
        val simklStart = source.indexOf("private suspend fun resolveRemoteSimklSecrets")
        val kitsuStart = source.indexOf("private suspend fun resolveRemoteKitsuSecrets")
        val realDebridStart = source.indexOf("private suspend fun resolveRemoteRealDebridSecrets")
        val traktBlock = source.substring(traktStart, simklStart)
        val simklBlock = source.substring(simklStart, kitsuStart)
        val kitsuBlock = source.substring(kitsuStart, realDebridStart)
        val realDebridEnd = source.indexOf("private suspend fun applyResolvedRemoteRealDebridSecrets")
        val realDebridBlock = source.substring(realDebridStart, realDebridEnd)

        assertTrue(traktBlock.contains("accessToken.isBlank() || refreshToken.isBlank()"))
        assertTrue(traktBlock.contains("remote.connected || remote.pending"))
        assertTrue(simklBlock.contains("accessToken.isBlank()"))
        assertTrue(simklBlock.contains("remote.connected || remote.pending"))
        assertTrue(kitsuBlock.contains("!remoteHasTokens && remote.connected"))
        assertTrue(kitsuBlock.contains("preserveLocalTokens = preserveLocalTokens"))
        assertTrue(realDebridBlock.contains("!remoteHasTokens"))
        assertTrue(realDebridBlock.contains("localState.isAuthenticated"))
        assertTrue(realDebridBlock.contains("remote.connected || remote.pending"))
        assertTrue(realDebridBlock.contains("preserveLocalTokens = preserveLocalTokens"))
    }

    @Test
    fun `account secret push syncs only baseline dirty secret sections`() {
        val source = File("app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt").readText()
        val pushStart = source.indexOf("suspend fun pushToRemote")
        val pullStart = source.indexOf("suspend fun pullFromRemoteAndApply", startIndex = pushStart)
        val pushBlock = source.substring(pushStart, pullStart)
        val syncStart = source.indexOf("private suspend fun syncAccountSecretPushSnapshotToRemote")
        val syncEnd = source.indexOf("private suspend fun syncApiKeySecretToRemote", startIndex = syncStart)
        val syncBlock = source.substring(syncStart, syncEnd)

        assertTrue(pushBlock.contains("val dirtySecretSectionKeys = dirtyAccountSecretSectionKeys(snapshot.secrets)"))
        assertTrue(pushBlock.contains("syncAccountSecretPushSnapshotToRemote(snapshot.secrets, dirtySecretSectionKeys)"))
        assertFalse(pushBlock.contains("else {\n                scheduleFollowUpPush = true\n            }"))
        assertTrue(pushBlock.contains("val baseBeforeRecovery = syncWatermarkStore.get(SyncWatermarkSurface.ACCOUNT_SECRETS"))
        assertTrue(pushBlock.contains("scheduleFollowUpPush = baseAfterRecovery > baseBeforeRecovery"))
        assertTrue(syncBlock.contains("dirtySectionKeys: Set<AccountSettingsSectionKey>"))
        assertTrue(syncBlock.contains("syncKitsuSecretsToRemote(snapshot.kitsu)"))
        assertFalse(
            "secret sync must not blindly push every account secret",
            syncBlock.contains("syncApiKeySecretToRemote(MDBLIST_SECRET_TYPE, MDBLIST_SECRET_REF, snapshot.mdbListApiKey)\n" +
                "        syncApiKeySecretToRemote(OMDB_SECRET_TYPE, OMDB_SECRET_REF, snapshot.omdbApiKey)")
        )
    }

    @Test
    fun `dirty account secret sections are derived from baseline changes`() {
        val baseline = accountSecretSnapshot()
        val current = baseline.copy(
            mdbListApiKey = "local-mdblist",
            subtitleTranslationApiKey = "local-translation",
            legacyGeminiApiKey = "local-gemini",
            topPostersApiKey = "local-top-posters",
            realDebrid = baseline.realDebrid.copy(refreshToken = "local-rd-refresh"),
            trakt = baseline.trakt.copy(accessToken = "local-trakt-access")
        )

        assertEquals(
            setOf(
                AccountSettingsSectionKey.INTEGRATIONS_MDBLIST,
                AccountSettingsSectionKey.INTEGRATIONS_SUBTITLE_TRANSLATION,
                AccountSettingsSectionKey.INTEGRATIONS_POSTER_RATINGS,
                AccountSettingsSectionKey.INTEGRATIONS_DEBRID_REAL_DEBRID,
                AccountSettingsSectionKey.INTEGRATIONS_TRAKT_AUTH
            ),
            dirtyAccountSecretSectionKeys(current, baseline)
        )
    }

    @Test
    fun `dirty account secret sections include configured local secrets without baseline`() {
        assertEquals(
            setOf(
                AccountSettingsSectionKey.INTEGRATIONS_MDBLIST,
                AccountSettingsSectionKey.INTEGRATIONS_OMDB,
                AccountSettingsSectionKey.INTEGRATIONS_SUBTITLE_TRANSLATION,
                AccountSettingsSectionKey.INTEGRATIONS_ANIME_SKIP,
                AccountSettingsSectionKey.INTEGRATIONS_POSTER_RATINGS,
                AccountSettingsSectionKey.INTEGRATIONS_DEBRID_PREMIUMIZE,
                AccountSettingsSectionKey.INTEGRATIONS_DEBRID_TOR_BOX,
                AccountSettingsSectionKey.INTEGRATIONS_DEBRID_EASY_DEBRID,
                AccountSettingsSectionKey.INTEGRATIONS_DEBRID_REAL_DEBRID,
                AccountSettingsSectionKey.INTEGRATIONS_TRAKT_AUTH,
                AccountSettingsSectionKey.INTEGRATIONS_SIMKL_AUTH,
                AccountSettingsSectionKey.INTEGRATIONS_KITSU_AUTH
            ),
            dirtyAccountSecretSectionKeys(accountSecretSnapshot(), baseline = null)
        )
    }

    @Test
    fun `dirty account secret sections are empty without baseline when local secrets are blank`() {
        assertEquals(
            emptySet<AccountSettingsSectionKey>(),
            dirtyAccountSecretSectionKeys(emptyAccountSecretPushSnapshot(), baseline = null)
        )
    }

    @Test
    fun `account secret baseline after pull keeps preserved local sections dirty`() {
        val existing = accountSecretSnapshot(
            mdbListApiKey = "old-mdblist",
            subtitleTranslationApiKey = "old-translation"
        )
        val current = accountSecretSnapshot(
            mdbListApiKey = "remote-mdblist-new",
            subtitleTranslationApiKey = "local-translation-new",
            legacyGeminiApiKey = "local-gemini-new"
        )

        val baseline = accountSecretBaselineAfterPull(
            current = current,
            existing = existing,
            appliedSectionKeys = setOf(
                AccountSettingsSectionKey.INTEGRATIONS_MDBLIST,
                AccountSettingsSectionKey.INTEGRATIONS_SUBTITLE_TRANSLATION
            ),
            preserveLocalSectionKeys = setOf(AccountSettingsSectionKey.INTEGRATIONS_SUBTITLE_TRANSLATION)
        )

        assertEquals(
            setOf(AccountSettingsSectionKey.INTEGRATIONS_SUBTITLE_TRANSLATION),
            dirtyAccountSecretSectionKeys(current, baseline)
        )
    }

    @Test
    fun `account secret baseline after first pull starts from blank for unpulled sections`() {
        val current = accountSecretSnapshot(
            mdbListApiKey = "remote-mdblist",
            subtitleTranslationApiKey = "local-translation"
        )

        val baseline = accountSecretBaselineAfterPull(
            current = current,
            existing = null,
            appliedSectionKeys = setOf(AccountSettingsSectionKey.INTEGRATIONS_MDBLIST),
            preserveLocalSectionKeys = emptySet()
        )

        assertEquals(
            setOf(
                AccountSettingsSectionKey.INTEGRATIONS_OMDB,
                AccountSettingsSectionKey.INTEGRATIONS_SUBTITLE_TRANSLATION,
                AccountSettingsSectionKey.INTEGRATIONS_ANIME_SKIP,
                AccountSettingsSectionKey.INTEGRATIONS_POSTER_RATINGS,
                AccountSettingsSectionKey.INTEGRATIONS_DEBRID_PREMIUMIZE,
                AccountSettingsSectionKey.INTEGRATIONS_DEBRID_TOR_BOX,
                AccountSettingsSectionKey.INTEGRATIONS_DEBRID_EASY_DEBRID,
                AccountSettingsSectionKey.INTEGRATIONS_DEBRID_REAL_DEBRID,
                AccountSettingsSectionKey.INTEGRATIONS_TRAKT_AUTH,
                AccountSettingsSectionKey.INTEGRATIONS_SIMKL_AUTH,
                AccountSettingsSectionKey.INTEGRATIONS_KITSU_AUTH
            ),
            dirtyAccountSecretSectionKeys(current, baseline)
        )
    }

    @Test
    fun `stale recovery preserve set combines live pending and dirty secret sections`() {
        val preserveLocalSectionKeys = buildStaleRecoveryPreserveLocalSectionKeys(
            pendingChangedPaths = setOf(
                "catalogs.home.heroCatalogKeys",
                "integrations.tmdb.useArtwork",
                "unknown.path"
            ),
            dirtySettingsSectionKeys = setOf(
                AccountSettingsSectionKey.FORMATTER
            ),
            dirtySecretSectionKeys = setOf(
                AccountSettingsSectionKey.INTEGRATIONS_SUBTITLE_TRANSLATION,
                AccountSettingsSectionKey.PLAYBACK_STREAM_SELECTION
            )
        )

        assertEquals(
            setOf(
                AccountSettingsSectionKey.CATALOGS_HOME,
                AccountSettingsSectionKey.INTEGRATIONS_TMDB,
                AccountSettingsSectionKey.FORMATTER,
                AccountSettingsSectionKey.INTEGRATIONS_SUBTITLE_TRANSLATION,
                AccountSettingsSectionKey.PLAYBACK_STREAM_SELECTION
            ),
            preserveLocalSectionKeys
        )
    }

    @Test
    fun `applied path clearing removes only applied paths when generation matches`() {
        val pending = linkedSetOf(
            "integrations.tmdb.useArtwork",
            "catalogs.home.heroCatalogKeys",
            "playback.streamSelection.trackingProvider"
        )

        val cleared = clearAppliedChangedPathsForGeneration(
            pendingChangedPaths = pending,
            pendingChangedPathsGeneration = 7L,
            snapshotChangedPathsGeneration = 7L,
            appliedChangedPaths = setOf(
                "integrations.tmdb.useArtwork",
                "playback.streamSelection.trackingProvider"
            )
        )

        assertTrue(cleared)
        assertEquals(listOf("catalogs.home.heroCatalogKeys"), pending.toList())
    }

    @Test
    fun `applied path clearing preserves paths when generation changed`() {
        val pending = linkedSetOf(
            "integrations.tmdb.useArtwork",
            "catalogs.home.heroCatalogKeys"
        )

        val cleared = clearAppliedChangedPathsForGeneration(
            pendingChangedPaths = pending,
            pendingChangedPathsGeneration = 8L,
            snapshotChangedPathsGeneration = 7L,
            appliedChangedPaths = setOf("integrations.tmdb.useArtwork")
        )

        assertFalse(cleared)
        assertEquals(
            listOf("integrations.tmdb.useArtwork", "catalogs.home.heroCatalogKeys"),
            pending.toList()
        )
    }

    private fun accountSecretSnapshot(
        mdbListApiKey: String = "remote-mdblist",
        omdbApiKey: String = "remote-omdb",
        subtitleTranslationApiKey: String = "remote-translation",
        legacyGeminiApiKey: String? = null,
        animeSkipClientId: String = "remote-anime-skip",
        rpdbApiKey: String = "remote-rpdb",
        topPostersApiKey: String = "remote-top-posters",
        premiumizeApiKey: String = "remote-premiumize",
        torBoxApiKey: String = "remote-torbox",
        easyDebridApiKey: String = "remote-easydebrid",
    ): AccountSecretPushSnapshot {
        return AccountSecretPushSnapshot(
            mdbListApiKey = mdbListApiKey,
            omdbApiKey = omdbApiKey,
            subtitleTranslationApiKey = subtitleTranslationApiKey,
            legacyGeminiApiKey = legacyGeminiApiKey,
            animeSkipClientId = animeSkipClientId,
            rpdbApiKey = rpdbApiKey,
            topPostersApiKey = topPostersApiKey,
            premiumizeApiKey = premiumizeApiKey,
            torBoxApiKey = torBoxApiKey,
            easyDebridApiKey = easyDebridApiKey,
            realDebrid = RealDebridSecretPushSnapshot(
                accessToken = "remote-rd-access",
                refreshToken = "remote-rd-refresh",
                tokenType = "Bearer",
                expiresIn = 3600,
                userClientId = "remote-rd-client",
                userClientSecret = "remote-rd-secret"
            ),
            trakt = TraktSecretPushSnapshot(
                accessToken = "remote-trakt-access",
                refreshToken = "remote-trakt-refresh",
                tokenType = "bearer",
                createdAt = 1000L,
                expiresIn = 7200
            ),
            simkl = SimklSecretPushSnapshot(accessToken = "remote-simkl-access"),
            kitsu = KitsuSecretPushSnapshot(
                accessToken = "remote-kitsu-access",
                refreshToken = "remote-kitsu-refresh",
                expiresAtEpochSeconds = 9999L
            )
        )
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
    fun `kitsu auth account sync is default profile scoped`() {
        val source = File("app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt").readText()
        val primaryPathStart = source.indexOf("private fun isPrimaryProfileAccountPath")
        val primaryPathEnd = source.indexOf("private fun isDefaultLegacyActive", startIndex = primaryPathStart)
        val primaryPathBlock = source.substring(primaryPathStart, primaryPathEnd)
        val publicApplyIndex = source.indexOf("if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_KITSU_AUTH))")
        val publicApplyEnd = source.indexOf("// Moved to v8 per-profile blob sync: Trakt catalog preferences", startIndex = publicApplyIndex)
        val publicApplyBlock = source.substring(publicApplyIndex, publicApplyEnd)
        val resolvedApplyIndex = source.indexOf("private suspend fun applyResolvedRemoteKitsuSecrets")
        val realDebridIndex = source.indexOf("private suspend fun resolveRemoteRealDebridSecrets", startIndex = resolvedApplyIndex)
        val resolvedApplyBlock = source.substring(resolvedApplyIndex, realDebridIndex)

        assertTrue(primaryPathBlock.contains("path == \"integrations.kitsuAuth\""))
        assertTrue(publicApplyBlock.contains("kitsuAuthDataStore.saveForProfile("))
        assertTrue(resolvedApplyBlock.contains("kitsuAuthDataStore.saveForProfile("))
        assertFalse(source.contains("kitsuAuthDataStore.save(\n"))
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
    fun `selectSubtitleTranslationApiKeySecret returns null for blank generic with failed legacy fallback`() {
        assertNull(
            selectSubtitleTranslationApiKeySecret(
                genericTranslationKey = "",
                legacyGeminiKey = null,
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
