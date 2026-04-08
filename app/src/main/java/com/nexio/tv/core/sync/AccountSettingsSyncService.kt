package com.nexio.tv.core.sync

import android.content.Context
import android.util.Log
import com.nexio.tv.core.auth.AuthManager
import com.nexio.tv.core.locale.AppLocaleResolver
import com.nexio.tv.data.local.AddonPreferences
import com.nexio.tv.data.local.AddonSubtitleStartupMode
import com.nexio.tv.data.local.AnimeSkipSettingsDataStore
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.local.EasyDebridSettingsDataStore
import com.nexio.tv.data.local.FrameRateMatchingMode
import com.nexio.tv.data.local.GeminiSettingsDataStore
import com.nexio.tv.data.local.ImdbSettingsDataStore
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.data.local.NextEpisodeThresholdMode
import com.nexio.tv.data.local.OmdbSettingsDataStore
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.PosterRatingsSettingsDataStore
import com.nexio.tv.data.local.PremiumizeSettingsDataStore
import com.nexio.tv.data.local.RealDebridAuthDataStore
import com.nexio.tv.data.local.StreamAutoPlayMode
import com.nexio.tv.data.local.StreamAutoPlaySource
import com.nexio.tv.data.local.SubtitleOrganizationMode
import com.nexio.tv.data.local.ThemeDataStore
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.local.TorBoxSettingsDataStore
import com.nexio.tv.data.local.TraktAuthDataStore
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.data.remote.dto.debrid.RealDebridDeviceCodeResponseDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridDeviceCredentialsResponseDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridTokenResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktTokenResponseDto
import com.nexio.tv.data.remote.supabase.AccountAddonPayload
import com.nexio.tv.data.remote.supabase.AccountAddonSecretPayload
import com.nexio.tv.data.remote.supabase.AccountConfigSnapshotRpcResponse
import com.nexio.tv.data.remote.supabase.AccountConfigSyncPayload
import com.nexio.tv.data.remote.supabase.AccountRealDebridAccessSecretPayload
import com.nexio.tv.data.remote.supabase.AccountRealDebridRefreshSecretPayload
import com.nexio.tv.data.remote.supabase.AccountSettingsPayload
import com.nexio.tv.data.remote.supabase.AccountSecretApiKeyPayload
import com.nexio.tv.data.remote.supabase.AccountSnapshotRpcResponse
import com.nexio.tv.data.remote.supabase.AccountSyncMutationResult
import com.nexio.tv.data.remote.supabase.AccountTraktAccessSecretPayload
import com.nexio.tv.data.remote.supabase.AccountTraktRefreshSecretPayload
import com.nexio.tv.data.remote.supabase.AnimeSkipSyncSettings
import com.nexio.tv.data.remote.supabase.AppearanceSettings
import com.nexio.tv.data.remote.supabase.AudioSettings
import com.nexio.tv.data.remote.supabase.BufferNetworkSettings
import com.nexio.tv.data.remote.supabase.DebridSyncSettings
import com.nexio.tv.data.remote.supabase.DebugSettingsPayload
import com.nexio.tv.data.remote.supabase.CustomFormatterSyncTemplate
import com.nexio.tv.data.remote.supabase.EasyDebridSyncSettings
import com.nexio.tv.data.remote.supabase.FormatterSyncSettings
import com.nexio.tv.data.remote.supabase.GeminiSyncSettings
import com.nexio.tv.data.remote.supabase.IntegrationSettings
import com.nexio.tv.data.remote.supabase.LayoutSettings
import com.nexio.tv.data.remote.supabase.MDBListSyncSettings
import com.nexio.tv.data.remote.supabase.OmdbSyncSettings
import com.nexio.tv.data.remote.supabase.PlaybackGeneralSettings
import com.nexio.tv.data.remote.supabase.PlaybackSettings
import com.nexio.tv.data.remote.supabase.PosterRatingsSyncSettings
import com.nexio.tv.data.remote.supabase.PremiumizeSyncSettings
import com.nexio.tv.data.remote.supabase.RealDebridSyncSettings
import com.nexio.tv.data.remote.supabase.StreamSelectionSettings
import com.nexio.tv.data.remote.supabase.SubtitleSyncSettings
import com.nexio.tv.data.remote.supabase.TmdbSyncSettings
import com.nexio.tv.data.remote.supabase.TorBoxSyncSettings
import com.nexio.tv.data.remote.supabase.TraktAuthSyncSettings
import com.nexio.tv.data.remote.supabase.TraktSettingsPayload
import com.nexio.tv.data.repository.EasyDebridService
import com.nexio.tv.data.repository.PremiumizeService
import com.nexio.tv.data.repository.TorBoxService
import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.AppFont
import com.nexio.tv.domain.model.AppTheme
import com.nexio.tv.domain.model.HomeLayout
import com.nexio.tv.domain.model.TrackingProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AccountSettingsSync"
private const val TMDB_SECRET_TYPE = "tmdb_api_key"
private const val TMDB_SECRET_REF = "integration:tmdb"
private const val MDBLIST_SECRET_TYPE = "mdblist_api_key"
private const val MDBLIST_SECRET_REF = "integration:mdblist"
private const val OMDB_SECRET_TYPE = "omdb_api_key"
private const val OMDB_SECRET_REF = "integration:omdb"
private const val IMDB_SECRET_TYPE = "imdb_api_key"
private const val IMDB_SECRET_REF = "integration:imdb"
private const val GEMINI_SECRET_TYPE = "gemini_api_key"
private const val GEMINI_SECRET_REF = "integration:gemini"
private const val RPDB_SECRET_TYPE = "rpdb_api_key"
private const val RPDB_SECRET_REF = "integration:rpdb"
private const val TOP_POSTERS_SECRET_TYPE = "top_posters_api_key"
private const val TOP_POSTERS_SECRET_REF = "integration:topposters"
private const val PREMIUMIZE_SECRET_TYPE = "premiumize_api_key"
private const val PREMIUMIZE_SECRET_REF = "integration:premiumize"
private const val TORBOX_SECRET_TYPE = "torbox_api_key"
private const val TORBOX_SECRET_REF = "integration:torbox"
private const val EASY_DEBRID_SECRET_TYPE = "easydebrid_api_key"
private const val EASY_DEBRID_SECRET_REF = "integration:easydebrid"
private const val REAL_DEBRID_ACCESS_SECRET_TYPE = "realdebrid_access_token"
private const val REAL_DEBRID_REFRESH_SECRET_TYPE = "realdebrid_refresh_token"
private const val REAL_DEBRID_SECRET_REF = "integration:realdebrid"
private const val TRAKT_ACCESS_SECRET_TYPE = "trakt_access_token"
private const val TRAKT_REFRESH_SECRET_TYPE = "trakt_refresh_token"
private const val TRAKT_SECRET_REF = "integration:trakt"

@Singleton
class AccountSettingsSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val postgrest: Postgrest,
    private val themeDataStore: ThemeDataStore,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val mdbListSettingsDataStore: MDBListSettingsDataStore,
    private val omdbSettingsDataStore: OmdbSettingsDataStore,
    private val animeSkipSettingsDataStore: AnimeSkipSettingsDataStore,
    private val geminiSettingsDataStore: GeminiSettingsDataStore,
    private val imdbSettingsDataStore: ImdbSettingsDataStore,
    private val posterRatingsSettingsDataStore: PosterRatingsSettingsDataStore,
    private val premiumizeSettingsDataStore: PremiumizeSettingsDataStore,
    private val premiumizeService: PremiumizeService,
    private val torBoxSettingsDataStore: TorBoxSettingsDataStore,
    private val torBoxService: TorBoxService,
    private val easyDebridSettingsDataStore: EasyDebridSettingsDataStore,
    private val easyDebridService: EasyDebridService,
    private val realDebridAuthDataStore: RealDebridAuthDataStore,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val debugSettingsDataStore: DebugSettingsDataStore,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pushJob: Job? = null

    @Volatile
    private var isApplyingRemote = false

    init {
        observeLocalChanges()
    }

    private fun observeLocalChanges() {
        scope.launch {
            observeAccountConfigSyncChanges(
                heroCatalogSelections = layoutPreferenceDataStore.heroCatalogSelections.drop(1).map { Unit },
                homeCatalogOrderKeys = layoutPreferenceDataStore.homeCatalogOrderKeys.drop(1).map { Unit },
                disabledHomeCatalogKeys = layoutPreferenceDataStore.disabledHomeCatalogKeys.drop(1).map { Unit },
                tmdbSettings = tmdbSettingsDataStore.settings.drop(1).map { Unit },
                mdbListSettings = mdbListSettingsDataStore.settings.drop(1).map { Unit },
                mdbListCatalogPreferences = mdbListSettingsDataStore.catalogPreferences.drop(1).map { Unit },
                omdbSettings = omdbSettingsDataStore.settings.drop(1).map { Unit },
                animeSkipEnabled = animeSkipSettingsDataStore.enabled.drop(1).map { Unit },
                animeSkipClientId = animeSkipSettingsDataStore.clientId.drop(1).map { Unit },
                geminiSettings = geminiSettingsDataStore.settings.drop(1).map { Unit },
                imdbSettings = imdbSettingsDataStore.settings.drop(1).map { Unit },
                posterRatingsSettings = posterRatingsSettingsDataStore.settings.drop(1).map { Unit },
                premiumizeSettings = premiumizeSettingsDataStore.settings.drop(1).map { Unit },
                premiumizeAccountState = premiumizeService.observeAccountState().drop(1).map { Unit },
                torBoxSettings = torBoxSettingsDataStore.settings.drop(1).map { Unit },
                torBoxAccountState = torBoxService.observeAccountState().drop(1).map { Unit },
                easyDebridSettings = easyDebridSettingsDataStore.settings.drop(1).map { Unit },
                easyDebridAccountState = easyDebridService.observeAccountState().drop(1).map { Unit },
                realDebridState = realDebridAuthDataStore.state.drop(1).map { Unit },
                traktAuthState = traktAuthDataStore.state.drop(1).map { Unit },
                traktCatalogPreferences = traktSettingsDataStore.catalogPreferences.drop(1).map { Unit }
            ).collect {
                schedulePush()
            }
        }
    }

    private fun schedulePush() {
        if (isApplyingRemote) return
        if (!authManager.hasSyncSession) return

        pushJob?.cancel()
        pushJob = scope.launch {
            delay(500)
            pushToRemote()
        }
    }

    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            block()
        }
    }

    suspend fun pushToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!authManager.hasSyncSession) {
                return@withContext Result.success(Unit)
            }

            val payload = buildLocalPayload()

            withJwtRefreshRetry {
                postgrest.rpc(
                    "sync_push_account_settings",
                    buildAccountConfigSyncPushParams(payload)
                ).decodeList<AccountSyncMutationResult>()
            }

            syncApiKeySecretToRemote(TMDB_SECRET_TYPE, TMDB_SECRET_REF, tmdbSettingsDataStore.settings.first().apiKey)
            syncApiKeySecretToRemote(MDBLIST_SECRET_TYPE, MDBLIST_SECRET_REF, mdbListSettingsDataStore.settings.first().apiKey)
            syncApiKeySecretToRemote(OMDB_SECRET_TYPE, OMDB_SECRET_REF, omdbSettingsDataStore.settings.first().apiKey)
            syncApiKeySecretToRemote(IMDB_SECRET_TYPE, IMDB_SECRET_REF, imdbSettingsDataStore.settings.first().apiKey)
            syncApiKeySecretToRemote(GEMINI_SECRET_TYPE, GEMINI_SECRET_REF, geminiSettingsDataStore.settings.first().apiKey)
            syncApiKeySecretToRemote(RPDB_SECRET_TYPE, RPDB_SECRET_REF, posterRatingsSettingsDataStore.settings.first().rpdbApiKey)
            syncApiKeySecretToRemote(TOP_POSTERS_SECRET_TYPE, TOP_POSTERS_SECRET_REF, posterRatingsSettingsDataStore.settings.first().topPostersApiKey)
            syncApiKeySecretToRemote(PREMIUMIZE_SECRET_TYPE, PREMIUMIZE_SECRET_REF, premiumizeSettingsDataStore.settings.first().apiKey)
            syncApiKeySecretToRemote(TORBOX_SECRET_TYPE, TORBOX_SECRET_REF, torBoxSettingsDataStore.settings.first().apiKey)
            syncApiKeySecretToRemote(EASY_DEBRID_SECRET_TYPE, EASY_DEBRID_SECRET_REF, easyDebridSettingsDataStore.settings.first().apiKey)
            syncRealDebridSecretsToRemote()
            syncTraktSecretsToRemote()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push account settings to remote", e)
            Result.failure(e)
        }
    }

    suspend fun pullFromRemoteAndApply(): Result<List<AddonPreferences.AddonInstallConfig>> = withContext(Dispatchers.IO) {
        try {
            val snapshot = withJwtRefreshRetry {
                postgrest.rpc(
                    "sync_pull_account_snapshot",
                    buildAccountConfigSyncPullParams()
                ).decodeAs<AccountConfigSnapshotRpcResponse>()
            }

            isApplyingRemote = true
            try {
                applyAccountConfigSyncSettings(
                    settings = snapshot.settings,
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
                applyRemoteSecrets(snapshot.settings)
            } finally {
                isApplyingRemote = false
            }

            Result.success(buildRemoteAddonInstallConfigs(snapshot.addons, ::resolveRemoteAddonUrl))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull account snapshot from remote", e)
            Result.failure(e)
        }
    }

    private suspend fun buildLocalPayload(): AccountConfigSyncPayload {
        val tmdb = tmdbSettingsDataStore.settings.first()
        val mdbList = mdbListSettingsDataStore.settings.first()
        val mdbListPrefs = mdbListSettingsDataStore.catalogPreferences.first()
        val animeSkipEnabled = animeSkipSettingsDataStore.enabled.first()
        val animeSkipClientId = animeSkipSettingsDataStore.clientId.first()
        val gemini = geminiSettingsDataStore.settings.first()
        val posterRatings = posterRatingsSettingsDataStore.settings.first()
        val playerSettings = playerSettingsDataStore.playerSettings.first()
        val premiumize = premiumizeSettingsDataStore.settings.first()
        val premiumizeAccount = premiumizeService.observeAccountState().first()
        val torBox = torBoxSettingsDataStore.settings.first()
        val torBoxAccount = torBoxService.observeAccountState().first()
        val easyDebrid = easyDebridSettingsDataStore.settings.first()
        val easyDebridAccount = easyDebridService.observeAccountState().first()
        val realDebrid = realDebridAuthDataStore.state.first()
        val traktAuth = traktAuthDataStore.state.first()
        val traktCatalogPrefs = traktSettingsDataStore.catalogPreferences.first()

        return buildAccountConfigSyncPayload(
            integrations = IntegrationSettings(
                debrid = DebridSyncSettings(
                    premiumize = PremiumizeSyncSettings(
                        configured = premiumize.isConfigured,
                        customerId = premiumizeAccount.customerId
                    ),
                    torBox = TorBoxSyncSettings(
                        configured = torBox.isConfigured,
                        email = torBoxAccount.email.orEmpty(),
                        plan = torBoxAccount.plan.orEmpty()
                    ),
                    easyDebrid = EasyDebridSyncSettings(
                        configured = easyDebrid.isConfigured,
                        userId = easyDebridAccount.userId.orEmpty(),
                        paidUntil = easyDebridAccount.paidUntil.orEmpty()
                    ),
                    realDebrid = RealDebridSyncSettings(
                        connected = realDebrid.isAuthenticated,
                        username = realDebrid.username.orEmpty(),
                        pending = !realDebrid.isAuthenticated && !realDebrid.deviceCode.isNullOrBlank(),
                        deviceCode = realDebrid.deviceCode.orEmpty(),
                        userCode = realDebrid.userCode.orEmpty(),
                        verificationUrl = realDebrid.verificationUrl.orEmpty(),
                        expiresAt = realDebrid.expiresAt
                    )
                ),
                tmdb = TmdbSyncSettings(
                    enabled = tmdb.enabled,
                    useArtwork = tmdb.useArtwork,
                    useBasicInfo = tmdb.useBasicInfo,
                    useDetails = tmdb.useDetails,
                    useCredits = tmdb.useCredits,
                    useProductions = tmdb.useProductions,
                    useNetworks = tmdb.useNetworks,
                    useEpisodes = tmdb.useEpisodes,
                    useMoreLikeThis = tmdb.useMoreLikeThis,
                    useCollections = tmdb.useCollections
                ),
                mdblist = MDBListSyncSettings(
                    enabled = mdbList.enabled,
                    showTrakt = mdbList.showTrakt,
                    showImdb = mdbList.showImdb,
                    showTmdb = mdbList.showTmdb,
                    showLetterboxd = mdbList.showLetterboxd,
                    showTomatoes = mdbList.showTomatoes,
                    showAudience = mdbList.showAudience,
                    showMetacritic = mdbList.showMetacritic
                ),
                omdb = OmdbSyncSettings(
                    enabled = omdbSettingsDataStore.settings.first().enabled
                ),
                imdb = buildImdbSyncSettings(imdbSettingsDataStore),
                animeSkip = AnimeSkipSyncSettings(
                    enabled = animeSkipEnabled,
                    clientId = animeSkipClientId
                ),
                gemini = GeminiSyncSettings(
                    enabled = gemini.enabled
                ),
                posterRatings = PosterRatingsSyncSettings(
                    rpdbEnabled = posterRatings.rpdbEnabled,
                    topPostersEnabled = posterRatings.topPostersEnabled
                ),
                traktAuth = TraktAuthSyncSettings(
                    connected = traktAuth.isAuthenticated,
                    username = traktAuth.username.orEmpty(),
                    userSlug = traktAuth.userSlug.orEmpty(),
                    connectedAt = null,
                    pending = traktAuth.deviceCode != null && !traktAuth.isAuthenticated
                )
            ),
            heroCatalogKeys = layoutPreferenceDataStore.heroCatalogSelections.first(),
            homeCatalogOrderKeys = layoutPreferenceDataStore.homeCatalogOrderKeys.first(),
            disabledHomeCatalogKeys = layoutPreferenceDataStore.disabledHomeCatalogKeys.first(),
            traktCatalogEnabledSet = traktCatalogPrefs.enabledCatalogs.toList(),
            traktCatalogOrder = traktCatalogPrefs.catalogOrder,
            traktSelectedPopularListKeys = traktCatalogPrefs.selectedPopularListKeys.toList(),
            mdbListHiddenPersonalListKeys = mdbListPrefs.hiddenPersonalListKeys.toList(),
            mdbListSelectedTopListKeys = mdbListPrefs.selectedTopListKeys.toList(),
            mdbListCatalogOrder = mdbListPrefs.catalogOrder,
            formatter = FormatterSyncSettings(
                enabled = playerSettings.syncedFormatterTemplate.enabled,
                selectedTemplateId = playerSettings.syncedFormatterTemplate.selectedTemplateId,
                customTemplate = playerSettings.syncedFormatterTemplate.customNameTemplate
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        CustomFormatterSyncTemplate(
                            id = "custom",
                            label = playerSettings.syncedFormatterTemplate.customTemplateLabel ?: "Custom",
                            nameTemplate = it,
                            descriptionTemplate = playerSettings.syncedFormatterTemplate.customDescriptionTemplate.orEmpty()
                        )
                    }
                    ?.takeIf { it.descriptionTemplate.isNotBlank() }
            )
        )
    }

    private suspend fun applyRemoteSettings(settings: AccountSettingsPayload) {
        Log.d(
            TAG,
            "Applying remote layout order keys count=${settings.layout.homeCatalogOrderKeys.size} disabled count=${settings.layout.disabledHomeCatalogKeys.size}"
        )
        themeDataStore.setTheme(enumValueOrDefault(settings.appearance.theme, AppTheme.WHITE))
        themeDataStore.setFont(enumValueOrDefault(settings.appearance.font, AppFont.INTER))
        // Locale: apply remote only when it is explicitly set to a language.
        // Never allow remote "system" to clear/override a local preference.
        val remoteLocaleTag = settings.appearance.localeTag
            .takeUnless { it.equals("system", ignoreCase = true) }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (remoteLocaleTag != null) {
            AppLocaleResolver.setStoredLocaleTag(context, remoteLocaleTag)
        }

        layoutPreferenceDataStore.setLayout(enumValueOrDefault(settings.layout.selectedLayout, HomeLayout.MODERN))
        layoutPreferenceDataStore.setModernLandscapePostersEnabled(settings.layout.modernLandscapePostersEnabled)
        layoutPreferenceDataStore.setHeroCatalogKeys(settings.layout.heroCatalogKeys)
        layoutPreferenceDataStore.setHomeCatalogOrderKeys(settings.layout.homeCatalogOrderKeys)
        layoutPreferenceDataStore.setDisabledHomeCatalogKeys(settings.layout.disabledHomeCatalogKeys)
        layoutPreferenceDataStore.setSidebarCollapsedByDefault(settings.layout.sidebarCollapsedByDefault)
        layoutPreferenceDataStore.setModernSidebarEnabled(settings.layout.modernSidebarEnabled)
        layoutPreferenceDataStore.setModernSidebarBlurEnabled(settings.layout.modernSidebarBlurEnabled)
        layoutPreferenceDataStore.setHeroSectionEnabled(settings.layout.heroSectionEnabled)
        layoutPreferenceDataStore.setSearchDiscoverEnabled(settings.layout.searchDiscoverEnabled)
        layoutPreferenceDataStore.setPosterLabelsEnabled(settings.layout.posterLabelsEnabled)
        layoutPreferenceDataStore.setCatalogAddonNameEnabled(settings.layout.catalogAddonNameEnabled)
        layoutPreferenceDataStore.setCatalogTypeSuffixEnabled(settings.layout.catalogTypeSuffixEnabled)
        layoutPreferenceDataStore.setHideUnreleasedContent(settings.layout.hideUnreleasedContent)
        layoutPreferenceDataStore.setBlurUnwatchedEpisodes(settings.layout.blurUnwatchedEpisodes)
        layoutPreferenceDataStore.setPreferExternalMetaAddonDetail(settings.layout.preferExternalMetaAddonDetail)
        layoutPreferenceDataStore.setFocusedPosterBackdropExpandEnabled(settings.layout.focusedPosterBackdropExpandEnabled)
        layoutPreferenceDataStore.setFocusedPosterBackdropExpandDelaySeconds(settings.layout.focusedPosterBackdropExpandDelaySeconds)
        layoutPreferenceDataStore.setPosterCardWidthDp(settings.layout.posterCardWidthDp)
        layoutPreferenceDataStore.setPosterCardCornerRadiusDp(settings.layout.posterCardCornerRadiusDp)

        tmdbSettingsDataStore.setEnabled(settings.integrations.tmdb.enabled)
        tmdbSettingsDataStore.setUseArtwork(settings.integrations.tmdb.useArtwork)
        tmdbSettingsDataStore.setUseBasicInfo(settings.integrations.tmdb.useBasicInfo)
        tmdbSettingsDataStore.setUseDetails(settings.integrations.tmdb.useDetails)
        tmdbSettingsDataStore.setUseCredits(settings.integrations.tmdb.useCredits)
        tmdbSettingsDataStore.setUseProductions(settings.integrations.tmdb.useProductions)
        tmdbSettingsDataStore.setUseNetworks(settings.integrations.tmdb.useNetworks)
        tmdbSettingsDataStore.setUseEpisodes(settings.integrations.tmdb.useEpisodes)
        tmdbSettingsDataStore.setUseMoreLikeThis(settings.integrations.tmdb.useMoreLikeThis)
        tmdbSettingsDataStore.setUseCollections(settings.integrations.tmdb.useCollections)

        mdbListSettingsDataStore.setEnabled(settings.integrations.mdblist.enabled)
        mdbListSettingsDataStore.setShowTrakt(settings.integrations.mdblist.showTrakt)
        mdbListSettingsDataStore.setShowImdb(settings.integrations.mdblist.showImdb)
        mdbListSettingsDataStore.setShowTmdb(settings.integrations.mdblist.showTmdb)
        mdbListSettingsDataStore.setShowLetterboxd(settings.integrations.mdblist.showLetterboxd)
        mdbListSettingsDataStore.setShowTomatoes(settings.integrations.mdblist.showTomatoes)
        mdbListSettingsDataStore.setShowAudience(settings.integrations.mdblist.showAudience)
        mdbListSettingsDataStore.setShowMetacritic(settings.integrations.mdblist.showMetacritic)
        mdbListSettingsDataStore.setCatalogPreferences(
            hiddenPersonalListKeys = settings.integrations.mdblist.hiddenPersonalListKeys.toSet(),
            selectedTopListKeys = settings.integrations.mdblist.selectedTopListKeys.toSet(),
            catalogOrder = settings.integrations.mdblist.catalogOrder
        )

        animeSkipSettingsDataStore.setEnabled(settings.integrations.animeSkip.enabled)
        animeSkipSettingsDataStore.setClientId(settings.integrations.animeSkip.clientId)

        geminiSettingsDataStore.setEnabled(settings.integrations.gemini.enabled)

        posterRatingsSettingsDataStore.setRpdbEnabled(settings.integrations.posterRatings.rpdbEnabled)
        posterRatingsSettingsDataStore.setTopPostersEnabled(settings.integrations.posterRatings.topPostersEnabled)

        playerSettingsDataStore.setLoadingOverlayEnabled(settings.playback.general.loadingOverlayEnabled)
        playerSettingsDataStore.setPauseOverlayEnabled(settings.playback.general.pauseOverlayEnabled)
        playerSettingsDataStore.setOsdClockEnabled(settings.playback.general.osdClockEnabled)
        playerSettingsDataStore.setSkipIntroEnabled(settings.playback.general.skipIntroEnabled)
        playerSettingsDataStore.setFrameRateMatchingMode(enumValueOrDefault(settings.playback.general.frameRateMatchingMode, FrameRateMatchingMode.OFF))
        playerSettingsDataStore.setResolutionMatchingEnabled(settings.playback.general.resolutionMatchingEnabled)
        playerSettingsDataStore.setStreamReuseLastLinkEnabled(settings.playback.streamSelection.streamReuseLastLinkEnabled)
        playerSettingsDataStore.setStreamReuseLastLinkCacheHours(settings.playback.streamSelection.streamReuseLastLinkCacheHours)
        playerSettingsDataStore.setUniformStreamFormattingEnabled(settings.playback.streamSelection.uniformStreamFormattingEnabled)
        playerSettingsDataStore.setGroupStreamsAcrossAddonsEnabled(true)
        playerSettingsDataStore.setDeduplicateGroupedStreamsEnabled(settings.playback.streamSelection.deduplicateGroupedStreamsEnabled)
        playerSettingsDataStore.setFilterEpisodeMismatchStreamsEnabled(settings.playback.streamSelection.filterEpisodeMismatchStreamsEnabled)
        playerSettingsDataStore.setFilterMovieYearMismatchStreamsEnabled(settings.playback.streamSelection.filterMovieYearMismatchStreamsEnabled)
        playerSettingsDataStore.setStreamAutoPlayMode(enumValueOrDefault(settings.playback.streamSelection.streamAutoPlayMode, StreamAutoPlayMode.MANUAL))
        playerSettingsDataStore.setStreamAutoPlaySource(enumValueOrDefault(settings.playback.streamSelection.streamAutoPlaySource, StreamAutoPlaySource.ALL_SOURCES))
        playerSettingsDataStore.setTrackingProvider(enumValueOrDefault(settings.playback.streamSelection.trackingProvider, TrackingProvider.TRAKT))
        playerSettingsDataStore.setStreamAutoPlaySelectedAddons(settings.playback.streamSelection.streamAutoPlaySelectedAddons.toSet())
        playerSettingsDataStore.setStreamAutoPlayRegex(settings.playback.streamSelection.streamAutoPlayRegex)
        playerSettingsDataStore.setStreamAutoPlayNextEpisodeEnabled(settings.playback.streamSelection.streamAutoPlayNextEpisodeEnabled)
        playerSettingsDataStore.setStreamAutoPlayPreferBingeGroupForNextEpisode(settings.playback.streamSelection.streamAutoPlayPreferBingeGroupForNextEpisode)
        playerSettingsDataStore.setNextEpisodeThresholdMode(enumValueOrDefault(settings.playback.streamSelection.nextEpisodeThresholdMode, NextEpisodeThresholdMode.PERCENTAGE))
        playerSettingsDataStore.setNextEpisodeThresholdPercent(settings.playback.streamSelection.nextEpisodeThresholdPercent)
        playerSettingsDataStore.setNextEpisodeThresholdMinutesBeforeEnd(settings.playback.streamSelection.nextEpisodeThresholdMinutesBeforeEnd)
        playerSettingsDataStore.setPreferredAudioLanguage(settings.playback.audio.preferredAudioLanguage)
        playerSettingsDataStore.setSecondaryPreferredAudioLanguage(settings.playback.audio.secondaryPreferredAudioLanguage)
        playerSettingsDataStore.setSkipSilence(settings.playback.audio.skipSilence)
        playerSettingsDataStore.setDecoderPriority(settings.playback.audio.decoderPriority)
        playerSettingsDataStore.setTunnelingEnabled(settings.playback.audio.tunnelingEnabled)
        playerSettingsDataStore.setSubtitlePreferredLanguage(settings.playback.subtitles.preferredLanguage)
        playerSettingsDataStore.setSubtitleSecondaryLanguage(settings.playback.subtitles.secondaryPreferredLanguage)
        playerSettingsDataStore.setSubtitleOrganizationMode(SubtitleOrganizationMode.BY_LANGUAGE)
        playerSettingsDataStore.setAddonSubtitleStartupMode(enumValueOrDefault(settings.playback.subtitles.addonSubtitleStartupMode, AddonSubtitleStartupMode.ALL_SUBTITLES))
        playerSettingsDataStore.setSubtitleSize(settings.playback.subtitles.size)
        playerSettingsDataStore.setSubtitleVerticalOffset(settings.playback.subtitles.verticalOffset)
        playerSettingsDataStore.setSubtitleBold(settings.playback.subtitles.bold)
        playerSettingsDataStore.setSubtitleTextColor(settings.playback.subtitles.textColor)
        playerSettingsDataStore.setSubtitleBackgroundColor(settings.playback.subtitles.backgroundColor)
        playerSettingsDataStore.setSubtitleOutlineEnabled(settings.playback.subtitles.outlineEnabled)
        playerSettingsDataStore.setSubtitleOutlineColor(settings.playback.subtitles.outlineColor)
        playerSettingsDataStore.setUseLibass(settings.playback.subtitles.useLibass)
        playerSettingsDataStore.setBufferMinBufferMs(settings.playback.bufferNetwork.minBufferMs)
        playerSettingsDataStore.setBufferMaxBufferMs(settings.playback.bufferNetwork.maxBufferMs)
        playerSettingsDataStore.setBufferForPlaybackMs(settings.playback.bufferNetwork.bufferForPlaybackMs)
        playerSettingsDataStore.setBufferForPlaybackAfterRebufferMs(settings.playback.bufferNetwork.bufferForPlaybackAfterRebufferMs)
        playerSettingsDataStore.setBufferTargetSizeMb(settings.playback.bufferNetwork.targetBufferSizeMb)
        playerSettingsDataStore.setBufferBackBufferDurationMs(settings.playback.bufferNetwork.backBufferDurationMs)
        playerSettingsDataStore.setEnableBufferLogs(settings.playback.bufferNetwork.enableBufferLogs)

        traktSettingsDataStore.setContinueWatchingDaysCap(settings.trakt.continueWatchingDaysCap)
        traktSettingsDataStore.setShowUnairedNextUp(settings.trakt.showUnairedNextUp)
        traktSettingsDataStore.setCatalogPreferences(
            enabledCatalogs = settings.trakt.catalogEnabledSet.toSet(),
            catalogOrder = settings.trakt.catalogOrder,
            selectedPopularListKeys = settings.trakt.selectedPopularListKeys.toSet()
        )

        debugSettingsDataStore.setAccountTabEnabled(settings.debug.accountTabEnabled)
        debugSettingsDataStore.setSyncCodeFeaturesEnabled(settings.debug.syncCodeFeaturesEnabled)
    }

    private suspend fun syncApiKeySecretToRemote(secretType: String, secretRef: String, rawApiKey: String) {
        val apiKey = rawApiKey.trim()

        if (apiKey.isBlank()) {
            withJwtRefreshRetry {
                postgrest.rpc(
                    "sync_delete_account_secret",
                    buildJsonObject {
                        put("p_secret_type", secretType)
                        put("p_secret_ref", secretRef)
                        put("p_source", "app")
                    }
                )
            }
            return
        }

        withJwtRefreshRetry {
            postgrest.rpc(
                "sync_set_account_secret",
                buildJsonObject {
                    put("p_secret_type", secretType)
                    put("p_secret_ref", secretRef)
                    put("p_secret_payload", Json.encodeToJsonElement(AccountSecretApiKeyPayload.serializer(), AccountSecretApiKeyPayload(apiKey)))
                    put("p_masked_preview", "Stored ••••${apiKey.takeLast(4)}")
                    put("p_status", "configured")
                    put("p_source", "app")
                }
            )
        }
    }

    private suspend fun syncRealDebridSecretsToRemote() {
        val state = realDebridAuthDataStore.state.first()
        val accessToken = state.accessToken?.trim().orEmpty()
        val refreshToken = state.refreshToken?.trim().orEmpty()
        val userClientId = state.userClientId?.trim().orEmpty()
        val userClientSecret = state.userClientSecret?.trim().orEmpty()

        if (
            accessToken.isBlank() ||
            refreshToken.isBlank() ||
            userClientId.isBlank() ||
            userClientSecret.isBlank()
        ) {
            withJwtRefreshRetry {
                postgrest.rpc(
                    "sync_delete_account_secret",
                    buildJsonObject {
                        put("p_secret_type", REAL_DEBRID_ACCESS_SECRET_TYPE)
                        put("p_secret_ref", REAL_DEBRID_SECRET_REF)
                        put("p_source", "app")
                    }
                )
                postgrest.rpc(
                    "sync_delete_account_secret",
                    buildJsonObject {
                        put("p_secret_type", REAL_DEBRID_REFRESH_SECRET_TYPE)
                        put("p_secret_ref", REAL_DEBRID_SECRET_REF)
                        put("p_source", "app")
                    }
                )
            }
            return
        }

        withJwtRefreshRetry {
            postgrest.rpc(
                "sync_set_account_secret",
                buildJsonObject {
                    put("p_secret_type", REAL_DEBRID_ACCESS_SECRET_TYPE)
                    put("p_secret_ref", REAL_DEBRID_SECRET_REF)
                    put(
                        "p_secret_payload",
                        Json.encodeToJsonElement(
                            AccountRealDebridAccessSecretPayload.serializer(),
                            AccountRealDebridAccessSecretPayload(
                                accessToken = accessToken,
                                tokenType = state.tokenType ?: "Bearer",
                                expiresIn = state.expiresIn ?: 0,
                                userClientId = userClientId,
                                userClientSecret = userClientSecret
                            )
                        )
                    )
                    put("p_masked_preview", "Connected ••••${accessToken.takeLast(4)}")
                    put("p_status", "configured")
                    put("p_source", "app")
                }
            )
            postgrest.rpc(
                "sync_set_account_secret",
                buildJsonObject {
                    put("p_secret_type", REAL_DEBRID_REFRESH_SECRET_TYPE)
                    put("p_secret_ref", REAL_DEBRID_SECRET_REF)
                    put(
                        "p_secret_payload",
                        Json.encodeToJsonElement(
                            AccountRealDebridRefreshSecretPayload.serializer(),
                            AccountRealDebridRefreshSecretPayload(refreshToken = refreshToken)
                        )
                    )
                    put("p_masked_preview", "Connected ••••${refreshToken.takeLast(4)}")
                    put("p_status", "configured")
                    put("p_source", "app")
                }
            )
        }
    }

    private suspend fun syncTraktSecretsToRemote() {
        val traktState = traktAuthDataStore.state.first()
        val accessToken = traktState.accessToken?.trim().orEmpty()
        val refreshToken = traktState.refreshToken?.trim().orEmpty()

        if (accessToken.isBlank() || refreshToken.isBlank()) {
            withJwtRefreshRetry {
                postgrest.rpc(
                    "sync_delete_account_secret",
                    buildJsonObject {
                        put("p_secret_type", TRAKT_ACCESS_SECRET_TYPE)
                        put("p_secret_ref", TRAKT_SECRET_REF)
                        put("p_source", "app")
                    }
                )
                postgrest.rpc(
                    "sync_delete_account_secret",
                    buildJsonObject {
                        put("p_secret_type", TRAKT_REFRESH_SECRET_TYPE)
                        put("p_secret_ref", TRAKT_SECRET_REF)
                        put("p_source", "app")
                    }
                )
            }
            return
        }

        withJwtRefreshRetry {
            postgrest.rpc(
                "sync_set_account_secret",
                buildJsonObject {
                    put("p_secret_type", TRAKT_ACCESS_SECRET_TYPE)
                    put("p_secret_ref", TRAKT_SECRET_REF)
                    put(
                        "p_secret_payload",
                        Json.encodeToJsonElement(
                            AccountTraktAccessSecretPayload.serializer(),
                            AccountTraktAccessSecretPayload(
                                accessToken = accessToken,
                                tokenType = traktState.tokenType ?: "bearer",
                                createdAt = traktState.createdAt ?: 0L,
                                expiresIn = traktState.expiresIn ?: 0
                            )
                        )
                    )
                    put("p_masked_preview", "Connected ••••${accessToken.takeLast(4)}")
                    put("p_status", "configured")
                    put("p_source", "app")
                }
            )
            postgrest.rpc(
                "sync_set_account_secret",
                buildJsonObject {
                    put("p_secret_type", TRAKT_REFRESH_SECRET_TYPE)
                    put("p_secret_ref", TRAKT_SECRET_REF)
                    put(
                        "p_secret_payload",
                        Json.encodeToJsonElement(
                            AccountTraktRefreshSecretPayload.serializer(),
                            AccountTraktRefreshSecretPayload(refreshToken = refreshToken)
                        )
                    )
                    put("p_masked_preview", "Stored ••••${refreshToken.takeLast(4)}")
                    put("p_status", "configured")
                    put("p_source", "app")
                }
            )
        }
    }

    private suspend fun applyRemoteSecrets(settings: AccountConfigSyncPayload) {
        // Each helper returns null when the resolve RPC fails transiently (network,
        // JWT, decode). Only overwrite the local API key when we have an authoritative
        // response from the server — otherwise we'd wipe valid local credentials on
        // every flaky upgrade-time sync.
        resolveApiKeySecretOrNull(TMDB_SECRET_TYPE, TMDB_SECRET_REF)?.let { tmdbSettingsDataStore.setApiKey(it) }
        resolveApiKeySecretOrNull(MDBLIST_SECRET_TYPE, MDBLIST_SECRET_REF)?.let { mdbListSettingsDataStore.setApiKey(it) }
        resolveApiKeySecretOrNull(OMDB_SECRET_TYPE, OMDB_SECRET_REF)?.let { omdbSettingsDataStore.setApiKey(it) }
        resolveApiKeySecretOrNull(IMDB_SECRET_TYPE, IMDB_SECRET_REF)?.let { imdbSettingsDataStore.setApiKey(it) }
        imdbSettingsDataStore.setBaseUrl(settings.integrations.imdb.baseUrl)
        resolveApiKeySecretOrNull(GEMINI_SECRET_TYPE, GEMINI_SECRET_REF)?.let { geminiSettingsDataStore.setApiKey(it) }
        resolveApiKeySecretOrNull(RPDB_SECRET_TYPE, RPDB_SECRET_REF)?.let { posterRatingsSettingsDataStore.setRpdbApiKey(it) }
        resolveApiKeySecretOrNull(TOP_POSTERS_SECRET_TYPE, TOP_POSTERS_SECRET_REF)?.let { posterRatingsSettingsDataStore.setTopPostersApiKey(it) }
        resolveApiKeySecretOrNull(PREMIUMIZE_SECRET_TYPE, PREMIUMIZE_SECRET_REF)?.let { premiumizeSettingsDataStore.setApiKey(it) }
        resolveApiKeySecretOrNull(TORBOX_SECRET_TYPE, TORBOX_SECRET_REF)?.let { torBoxSettingsDataStore.setApiKey(it) }
        resolveApiKeySecretOrNull(EASY_DEBRID_SECRET_TYPE, EASY_DEBRID_SECRET_REF)?.let { easyDebridSettingsDataStore.setApiKey(it) }
        premiumizeService.refreshAccountState()
        torBoxService.refreshAccountState()
        easyDebridService.refreshAccountState()
        applyRemoteRealDebridSecrets(settings)
        applyRemoteTraktSecrets(settings)
    }

    private suspend fun resolveApiKeySecret(secretType: String, secretRef: String): String {
        return resolveApiKeySecretOrNull(secretType, secretRef).orEmpty()
    }

    /**
     * Returns null if the resolve RPC failed (network/JWT/decode) so callers can
     * leave local credentials untouched. Returns the trimmed key (possibly empty)
     * when the server authoritatively responded.
     */
    private suspend fun resolveApiKeySecretOrNull(secretType: String, secretRef: String): String? {
        val result = runCatching {
            withJwtRefreshRetry {
                postgrest.rpc(
                    "sync_resolve_account_secret",
                    buildJsonObject {
                        put("p_secret_type", secretType)
                        put("p_secret_ref", secretRef)
                        put("p_source", "app")
                    }
                ).decodeAs<AccountSecretApiKeyPayload>()
            }
        }
        if (result.isFailure) return null
        return result.getOrNull()?.apiKey?.trim().orEmpty()
    }

    private suspend fun applyRemoteTraktSecrets(settings: AccountConfigSyncPayload) {
        val accessResult = runCatching {
            withJwtRefreshRetry {
                postgrest.rpc(
                    "sync_resolve_account_secret",
                    buildJsonObject {
                        put("p_secret_type", TRAKT_ACCESS_SECRET_TYPE)
                        put("p_secret_ref", TRAKT_SECRET_REF)
                        put("p_source", "app")
                    }
                ).decodeAs<AccountTraktAccessSecretPayload>()
            }
        }

        val refreshResult = runCatching {
            withJwtRefreshRetry {
                postgrest.rpc(
                    "sync_resolve_account_secret",
                    buildJsonObject {
                        put("p_secret_type", TRAKT_REFRESH_SECRET_TYPE)
                        put("p_secret_ref", TRAKT_SECRET_REF)
                        put("p_source", "app")
                    }
                ).decodeAs<AccountTraktRefreshSecretPayload>()
            }
        }

        // If either secret RPC failed (network/JWT/decode), do NOT touch local auth.
        // Clearing on transient failure is what was logging the user out on every upgrade.
        if (accessResult.isFailure || refreshResult.isFailure) {
            return
        }

        val accessPayload = accessResult.getOrNull()
        val refreshPayload = refreshResult.getOrNull()
        val accessToken = accessPayload?.accessToken?.trim().orEmpty()
        val refreshToken = refreshPayload?.refreshToken?.trim().orEmpty()

        if (accessToken.isBlank() || refreshToken.isBlank()) {
            // Only clear local auth when the remote authoritatively says Trakt is
            // not connected (and not in a pending device-flow). Mirrors RD's logic.
            val remote = settings.integrations.traktAuth
            if (!remote.connected && !remote.pending) {
                traktAuthDataStore.clearAuth()
            }
            return
        }

        // Trakt rotates the refresh token on every refresh — once we've used a
        // refresh token, the previous pair is dead at the auth server. If local
        // has a *newer* token pair than what's stored in Supabase (which can
        // happen when an earlier refresh push was skipped — e.g. because the
        // sync session wasn't yet hydrated when refreshTokenIfNeeded fired), we
        // must NOT overwrite local with the stale remote pair: doing so makes
        // the next API call hit 401 → force-refresh against the already-rotated
        // remote refresh token → invalid_grant → clearAuth(). That's the
        // upgrade-time logout. Instead, keep local and shove it back upstream
        // so both sides converge.
        val localState = traktAuthDataStore.state.first()
        val localCreatedAt = localState.createdAt ?: 0L
        val remoteCreatedAt = accessPayload?.createdAt ?: 0L
        val localHasTokens = !localState.accessToken.isNullOrBlank() &&
            !localState.refreshToken.isNullOrBlank()
        if (localHasTokens && localCreatedAt >= remoteCreatedAt) {
            Log.w(
                TAG,
                "applyRemoteTraktSecrets: local token (createdAt=$localCreatedAt) is newer " +
                    "than remote (createdAt=$remoteCreatedAt); preserving local and pushing upstream"
            )
            runCatching { syncTraktSecretsToRemote() }
                .onFailure { e -> Log.w(TAG, "Failed to push local Trakt tokens after stale-remote detection", e) }
            traktAuthDataStore.saveUser(
                username = settings.integrations.traktAuth.username.takeIf { it.isNotBlank() },
                userSlug = settings.integrations.traktAuth.userSlug.takeIf { it.isNotBlank() }
            )
            if (!settings.integrations.traktAuth.pending) {
                traktAuthDataStore.clearDeviceFlow()
            }
            return
        }

        traktAuthDataStore.saveToken(
            TraktTokenResponseDto(
                accessToken = accessToken,
                tokenType = accessPayload?.tokenType?.ifBlank { "bearer" } ?: "bearer",
                expiresIn = accessPayload?.expiresIn ?: 0,
                refreshToken = refreshToken,
                createdAt = accessPayload?.createdAt ?: 0L
            )
        )
        traktAuthDataStore.saveUser(
            username = settings.integrations.traktAuth.username.takeIf { it.isNotBlank() },
            userSlug = settings.integrations.traktAuth.userSlug.takeIf { it.isNotBlank() }
        )
        if (!settings.integrations.traktAuth.pending) {
            traktAuthDataStore.clearDeviceFlow()
        }
    }

    private suspend fun applyRemoteRealDebridSecrets(settings: AccountConfigSyncPayload) {
        val accessResult = runCatching {
            withJwtRefreshRetry {
                postgrest.rpc(
                    "sync_resolve_account_secret",
                    buildJsonObject {
                        put("p_secret_type", REAL_DEBRID_ACCESS_SECRET_TYPE)
                        put("p_secret_ref", REAL_DEBRID_SECRET_REF)
                        put("p_source", "app")
                    }
                ).decodeAs<AccountRealDebridAccessSecretPayload>()
            }
        }

        val refreshResult = runCatching {
            withJwtRefreshRetry {
                postgrest.rpc(
                    "sync_resolve_account_secret",
                    buildJsonObject {
                        put("p_secret_type", REAL_DEBRID_REFRESH_SECRET_TYPE)
                        put("p_secret_ref", REAL_DEBRID_SECRET_REF)
                        put("p_source", "app")
                    }
                ).decodeAs<AccountRealDebridRefreshSecretPayload>()
            }
        }

        // If either secret RPC failed (network/JWT/decode), do NOT touch local auth.
        // Same upgrade-time logout class of bug as the Trakt path used to have.
        if (accessResult.isFailure || refreshResult.isFailure) {
            return
        }

        val accessPayload = accessResult.getOrNull()
        val refreshPayload = refreshResult.getOrNull()
        val accessToken = accessPayload?.accessToken?.trim().orEmpty()
        val refreshToken = refreshPayload?.refreshToken?.trim().orEmpty()
        val userClientId = accessPayload?.userClientId?.trim().orEmpty()
        val userClientSecret = accessPayload?.userClientSecret?.trim().orEmpty()

        if (
            accessToken.isNotBlank() &&
            refreshToken.isNotBlank() &&
            userClientId.isNotBlank() &&
            userClientSecret.isNotBlank()
        ) {
            realDebridAuthDataStore.saveUserCredentials(
                RealDebridDeviceCredentialsResponseDto(
                    clientId = userClientId,
                    clientSecret = userClientSecret
                )
            )
            realDebridAuthDataStore.saveToken(
                RealDebridTokenResponseDto(
                    accessToken = accessToken,
                    expiresIn = accessPayload?.expiresIn ?: 0,
                    tokenType = accessPayload?.tokenType ?: "Bearer",
                    refreshToken = refreshToken
                )
            )
            realDebridAuthDataStore.saveUsername(
                settings.integrations.debrid.realDebrid.username.takeIf { it.isNotBlank() }
            )
            realDebridAuthDataStore.clearDeviceFlow()
            return
        }

        val remoteFlow = settings.integrations.debrid.realDebrid
        if (remoteFlow.pending && remoteFlow.deviceCode.isNotBlank()) {
            realDebridAuthDataStore.clearAuth()
            val expiresInSeconds = remoteFlow.expiresAt
                ?.let { expiresAt -> ((expiresAt - System.currentTimeMillis()).coerceAtLeast(1_000L) / 1_000L).toInt() }
                ?: 600
            realDebridAuthDataStore.saveDeviceFlow(
                RealDebridDeviceCodeResponseDto(
                    deviceCode = remoteFlow.deviceCode,
                    userCode = remoteFlow.userCode.ifBlank { "PENDING" },
                    expiresIn = expiresInSeconds,
                    verificationUrl = remoteFlow.verificationUrl.ifBlank { "https://real-debrid.com/device" }
                )
            )
            realDebridAuthDataStore.saveUsername(remoteFlow.username.takeIf { it.isNotBlank() })
            return
        }

        realDebridAuthDataStore.clearAuth()
    }

    private suspend fun resolveRemoteAddonUrl(addon: AccountAddonPayload): Result<String> {
        return runCatching {
            val secretPayload = addon.secretRef
                ?.takeIf { it.isNotBlank() }
                ?.let { secretRef ->
                    withJwtRefreshRetry {
                        postgrest.rpc(
                            "sync_resolve_account_secret",
                            buildJsonObject {
                                put("p_secret_type", "addon_credential")
                                put("p_secret_ref", secretRef)
                                put("p_source", "app")
                            }
                        ).decodeAs<AccountAddonSecretPayload>()
                    }
                }

            buildResolvedAddonUrl(
                baseUrl = addon.url,
                manifestUrl = addon.manifestUrl,
                publicQueryParams = addon.publicQueryParams,
                secretPayload = secretPayload
            ).let(::normalizeAddonInstallUrl)
        }
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T {
        return runCatching { enumValueOf<T>(value.trim().uppercase()) }.getOrDefault(default)
    }
}
