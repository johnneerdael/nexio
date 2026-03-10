package com.nexio.tv.core.sync

import android.content.Context
import android.util.Log
import com.nexio.tv.core.auth.AuthManager
import com.nexio.tv.core.locale.AppLocaleResolver
import com.nexio.tv.data.local.AddonPreferences
import com.nexio.tv.data.local.AddonSubtitleStartupMode
import com.nexio.tv.data.local.AnimeSkipSettingsDataStore
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.local.FrameRateMatchingMode
import com.nexio.tv.data.local.GeminiSettingsDataStore
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.data.local.NextEpisodeThresholdMode
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.PosterRatingsSettingsDataStore
import com.nexio.tv.data.local.PremiumizeSettingsDataStore
import com.nexio.tv.data.local.RealDebridAuthDataStore
import com.nexio.tv.data.local.StreamAutoPlayMode
import com.nexio.tv.data.local.StreamAutoPlaySource
import com.nexio.tv.data.local.SubtitleOrganizationMode
import com.nexio.tv.data.local.ThemeDataStore
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.local.TraktAuthDataStore
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.data.remote.dto.debrid.RealDebridDeviceCodeResponseDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridDeviceCredentialsResponseDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridTokenResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktTokenResponseDto
import com.nexio.tv.data.remote.supabase.AccountAddonPayload
import com.nexio.tv.data.remote.supabase.AccountAddonSecretPayload
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
import com.nexio.tv.data.remote.supabase.GeminiSyncSettings
import com.nexio.tv.data.remote.supabase.IntegrationSettings
import com.nexio.tv.data.remote.supabase.LayoutSettings
import com.nexio.tv.data.remote.supabase.MDBListSyncSettings
import com.nexio.tv.data.remote.supabase.PlaybackGeneralSettings
import com.nexio.tv.data.remote.supabase.PlaybackSettings
import com.nexio.tv.data.remote.supabase.PosterRatingsSyncSettings
import com.nexio.tv.data.remote.supabase.PremiumizeSyncSettings
import com.nexio.tv.data.remote.supabase.RealDebridSyncSettings
import com.nexio.tv.data.remote.supabase.StreamSelectionSettings
import com.nexio.tv.data.remote.supabase.SubtitleSyncSettings
import com.nexio.tv.data.remote.supabase.TmdbSyncSettings
import com.nexio.tv.data.remote.supabase.TraktAuthSyncSettings
import com.nexio.tv.data.remote.supabase.TraktSettingsPayload
import com.nexio.tv.data.repository.PremiumizeService
import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.AppFont
import com.nexio.tv.domain.model.AppTheme
import com.nexio.tv.domain.model.HomeLayout
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
private const val GEMINI_SECRET_TYPE = "gemini_api_key"
private const val GEMINI_SECRET_REF = "integration:gemini"
private const val RPDB_SECRET_TYPE = "rpdb_api_key"
private const val RPDB_SECRET_REF = "integration:rpdb"
private const val TOP_POSTERS_SECRET_TYPE = "top_posters_api_key"
private const val TOP_POSTERS_SECRET_REF = "integration:topposters"
private const val PREMIUMIZE_SECRET_TYPE = "premiumize_api_key"
private const val PREMIUMIZE_SECRET_REF = "integration:premiumize"
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
    private val animeSkipSettingsDataStore: AnimeSkipSettingsDataStore,
    private val geminiSettingsDataStore: GeminiSettingsDataStore,
    private val posterRatingsSettingsDataStore: PosterRatingsSettingsDataStore,
    private val premiumizeSettingsDataStore: PremiumizeSettingsDataStore,
    private val premiumizeService: PremiumizeService,
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
            merge(
                themeDataStore.selectedTheme.drop(1).map { Unit },
                themeDataStore.selectedFont.drop(1).map { Unit },
                AppLocaleResolver.observeStoredLocaleTag(context).drop(1).map { Unit },
                layoutPreferenceDataStore.selectedLayout.drop(1).map { Unit },
                layoutPreferenceDataStore.heroCatalogSelections.drop(1).map { Unit },
                layoutPreferenceDataStore.homeCatalogOrderKeys.drop(1).map { Unit },
                layoutPreferenceDataStore.disabledHomeCatalogKeys.drop(1).map { Unit },
                layoutPreferenceDataStore.sidebarCollapsedByDefault.drop(1).map { Unit },
                layoutPreferenceDataStore.modernSidebarEnabled.drop(1).map { Unit },
                layoutPreferenceDataStore.modernSidebarBlurEnabled.drop(1).map { Unit },
                layoutPreferenceDataStore.modernLandscapePostersEnabled.drop(1).map { Unit },
                layoutPreferenceDataStore.heroSectionEnabled.drop(1).map { Unit },
                layoutPreferenceDataStore.searchDiscoverEnabled.drop(1).map { Unit },
                layoutPreferenceDataStore.posterLabelsEnabled.drop(1).map { Unit },
                layoutPreferenceDataStore.catalogAddonNameEnabled.drop(1).map { Unit },
                layoutPreferenceDataStore.catalogTypeSuffixEnabled.drop(1).map { Unit },
                layoutPreferenceDataStore.focusedPosterBackdropExpandEnabled.drop(1).map { Unit },
                layoutPreferenceDataStore.focusedPosterBackdropExpandDelaySeconds.drop(1).map { Unit },
                layoutPreferenceDataStore.posterCardWidthDp.drop(1).map { Unit },
                layoutPreferenceDataStore.posterCardCornerRadiusDp.drop(1).map { Unit },
                layoutPreferenceDataStore.blurUnwatchedEpisodes.drop(1).map { Unit },
                layoutPreferenceDataStore.preferExternalMetaAddonDetail.drop(1).map { Unit },
                layoutPreferenceDataStore.hideUnreleasedContent.drop(1).map { Unit },
                tmdbSettingsDataStore.settings.drop(1).map { Unit },
                mdbListSettingsDataStore.settings.drop(1).map { Unit },
                mdbListSettingsDataStore.catalogPreferences.drop(1).map { Unit },
                animeSkipSettingsDataStore.enabled.drop(1).map { Unit },
                animeSkipSettingsDataStore.clientId.drop(1).map { Unit },
                geminiSettingsDataStore.settings.drop(1).map { Unit },
                posterRatingsSettingsDataStore.settings.drop(1).map { Unit },
                premiumizeSettingsDataStore.settings.drop(1).map { Unit },
                realDebridAuthDataStore.state.drop(1).map { Unit },
                traktAuthDataStore.state.drop(1).map { Unit },
                traktSettingsDataStore.continueWatchingDaysCap.drop(1).map { Unit },
                traktSettingsDataStore.showUnairedNextUp.drop(1).map { Unit },
                traktSettingsDataStore.catalogPreferences.drop(1).map { Unit },
                debugSettingsDataStore.accountTabEnabled.drop(1).map { Unit },
                debugSettingsDataStore.syncCodeFeaturesEnabled.drop(1).map { Unit },
                playerSettingsDataStore.playerSettings.drop(1).map { Unit }
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
            val params = buildJsonObject {
                put("p_settings_payload", Json.encodeToJsonElement(AccountSettingsPayload.serializer(), payload))
                put("p_source", "app")
            }

            withJwtRefreshRetry {
                postgrest.rpc("sync_push_account_settings", params).decodeList<AccountSyncMutationResult>()
            }

            syncApiKeySecretToRemote(TMDB_SECRET_TYPE, TMDB_SECRET_REF, tmdbSettingsDataStore.settings.first().apiKey)
            syncApiKeySecretToRemote(MDBLIST_SECRET_TYPE, MDBLIST_SECRET_REF, mdbListSettingsDataStore.settings.first().apiKey)
            syncApiKeySecretToRemote(GEMINI_SECRET_TYPE, GEMINI_SECRET_REF, geminiSettingsDataStore.settings.first().apiKey)
            syncApiKeySecretToRemote(RPDB_SECRET_TYPE, RPDB_SECRET_REF, posterRatingsSettingsDataStore.settings.first().rpdbApiKey)
            syncApiKeySecretToRemote(TOP_POSTERS_SECRET_TYPE, TOP_POSTERS_SECRET_REF, posterRatingsSettingsDataStore.settings.first().topPostersApiKey)
            syncApiKeySecretToRemote(PREMIUMIZE_SECRET_TYPE, PREMIUMIZE_SECRET_REF, premiumizeSettingsDataStore.settings.first().apiKey)
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
                postgrest.rpc("sync_pull_account_snapshot").decodeAs<AccountSnapshotRpcResponse>()
            }

            isApplyingRemote = true
            try {
                applyRemoteSettings(snapshot.settings)
                applyRemoteSecrets(snapshot.settings)
            } finally {
                isApplyingRemote = false
            }

            Result.success(
                snapshot.addons
                    .sortedBy { it.sortOrder }
                    .filter { it.enabled }
                    .mapNotNull { addon ->
                        resolveRemoteAddonUrl(addon).getOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?.let { url ->
                                AddonPreferences.AddonInstallConfig(
                                    url = url,
                                    parserPreset = runCatching {
                                        enumValueOf<AddonParserPreset>(addon.parserPreset.trim().uppercase())
                                    }.getOrDefault(AddonParserPreset.GENERIC)
                                )
                            }
                    }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull account snapshot from remote", e)
            Result.failure(e)
        }
    }

    private suspend fun buildLocalPayload(): AccountSettingsPayload {
        val theme = themeDataStore.selectedTheme.first()
        val font = themeDataStore.selectedFont.first()
        val tmdb = tmdbSettingsDataStore.settings.first()
        val mdbList = mdbListSettingsDataStore.settings.first()
        val mdbListPrefs = mdbListSettingsDataStore.catalogPreferences.first()
        val animeSkipEnabled = animeSkipSettingsDataStore.enabled.first()
        val animeSkipClientId = animeSkipSettingsDataStore.clientId.first()
        val gemini = geminiSettingsDataStore.settings.first()
        val posterRatings = posterRatingsSettingsDataStore.settings.first()
        val premiumize = premiumizeSettingsDataStore.settings.first()
        val premiumizeAccount = premiumizeService.observeAccountState().first()
        val realDebrid = realDebridAuthDataStore.state.first()
        val traktAuth = traktAuthDataStore.state.first()
        val player = playerSettingsDataStore.playerSettings.first()
        val traktCatalogPrefs = traktSettingsDataStore.catalogPreferences.first()

        return AccountSettingsPayload(
            appearance = AppearanceSettings(
                theme = theme.name,
                font = font.name,
                localeTag = AppLocaleResolver.getStoredLocaleTag(context) ?: "system"
            ),
            layout = LayoutSettings(
                selectedLayout = layoutPreferenceDataStore.selectedLayout.first().name,
                modernLandscapePostersEnabled = layoutPreferenceDataStore.modernLandscapePostersEnabled.first(),
                heroCatalogKeys = layoutPreferenceDataStore.heroCatalogSelections.first(),
                homeCatalogOrderKeys = layoutPreferenceDataStore.homeCatalogOrderKeys.first(),
                disabledHomeCatalogKeys = layoutPreferenceDataStore.disabledHomeCatalogKeys.first(),
                sidebarCollapsedByDefault = layoutPreferenceDataStore.sidebarCollapsedByDefault.first(),
                modernSidebarEnabled = layoutPreferenceDataStore.modernSidebarEnabled.first(),
                modernSidebarBlurEnabled = layoutPreferenceDataStore.modernSidebarBlurEnabled.first(),
                heroSectionEnabled = layoutPreferenceDataStore.heroSectionEnabled.first(),
                searchDiscoverEnabled = layoutPreferenceDataStore.searchDiscoverEnabled.first(),
                posterLabelsEnabled = layoutPreferenceDataStore.posterLabelsEnabled.first(),
                catalogAddonNameEnabled = layoutPreferenceDataStore.catalogAddonNameEnabled.first(),
                catalogTypeSuffixEnabled = layoutPreferenceDataStore.catalogTypeSuffixEnabled.first(),
                hideUnreleasedContent = layoutPreferenceDataStore.hideUnreleasedContent.first(),
                blurUnwatchedEpisodes = layoutPreferenceDataStore.blurUnwatchedEpisodes.first(),
                preferExternalMetaAddonDetail = layoutPreferenceDataStore.preferExternalMetaAddonDetail.first(),
                focusedPosterBackdropExpandEnabled = layoutPreferenceDataStore.focusedPosterBackdropExpandEnabled.first(),
                focusedPosterBackdropExpandDelaySeconds = layoutPreferenceDataStore.focusedPosterBackdropExpandDelaySeconds.first(),
                posterCardWidthDp = layoutPreferenceDataStore.posterCardWidthDp.first(),
                posterCardCornerRadiusDp = layoutPreferenceDataStore.posterCardCornerRadiusDp.first()
            ),
            integrations = IntegrationSettings(
                debrid = DebridSyncSettings(
                    premiumize = PremiumizeSyncSettings(
                        configured = premiumize.isConfigured,
                        customerId = premiumizeAccount.customerId
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
                    showMetacritic = mdbList.showMetacritic,
                    hiddenPersonalListKeys = mdbListPrefs.hiddenPersonalListKeys.toList(),
                    selectedTopListKeys = mdbListPrefs.selectedTopListKeys.toList(),
                    catalogOrder = mdbListPrefs.catalogOrder
                ),
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
            playback = PlaybackSettings(
                general = PlaybackGeneralSettings(
                    loadingOverlayEnabled = player.loadingOverlayEnabled,
                    pauseOverlayEnabled = player.pauseOverlayEnabled,
                    osdClockEnabled = player.osdClockEnabled,
                    skipIntroEnabled = player.skipIntroEnabled,
                    frameRateMatchingMode = player.frameRateMatchingMode.name,
                    resolutionMatchingEnabled = player.resolutionMatchingEnabled
                ),
                streamSelection = StreamSelectionSettings(
                    streamReuseLastLinkEnabled = player.streamReuseLastLinkEnabled,
                    streamReuseLastLinkCacheHours = player.streamReuseLastLinkCacheHours,
                    uniformStreamFormattingEnabled = player.uniformStreamFormattingEnabled,
                    groupStreamsAcrossAddonsEnabled = player.groupStreamsAcrossAddonsEnabled,
                    deduplicateGroupedStreamsEnabled = player.deduplicateGroupedStreamsEnabled,
                    filterEpisodeMismatchStreamsEnabled = player.filterEpisodeMismatchStreamsEnabled,
                    filterMovieYearMismatchStreamsEnabled = player.filterMovieYearMismatchStreamsEnabled,
                    streamAutoPlayMode = player.streamAutoPlayMode.name,
                    streamAutoPlaySource = player.streamAutoPlaySource.name,
                    streamAutoPlaySelectedAddons = player.streamAutoPlaySelectedAddons.toList(),
                    streamAutoPlayRegex = player.streamAutoPlayRegex,
                    streamAutoPlayNextEpisodeEnabled = player.streamAutoPlayNextEpisodeEnabled,
                    streamAutoPlayPreferBingeGroupForNextEpisode = player.streamAutoPlayPreferBingeGroupForNextEpisode,
                    nextEpisodeThresholdMode = player.nextEpisodeThresholdMode.name,
                    nextEpisodeThresholdPercent = player.nextEpisodeThresholdPercent,
                    nextEpisodeThresholdMinutesBeforeEnd = player.nextEpisodeThresholdMinutesBeforeEnd
                ),
                audio = AudioSettings(
                    preferredAudioLanguage = player.preferredAudioLanguage,
                    secondaryPreferredAudioLanguage = player.secondaryPreferredAudioLanguage,
                    skipSilence = player.skipSilence,
                    decoderPriority = player.decoderPriority,
                    tunnelingEnabled = player.tunnelingEnabled
                ),
                subtitles = SubtitleSyncSettings(
                    preferredLanguage = player.subtitleStyle.preferredLanguage,
                    secondaryPreferredLanguage = player.subtitleStyle.secondaryPreferredLanguage,
                    subtitleOrganizationMode = SubtitleOrganizationMode.BY_LANGUAGE.name,
                    addonSubtitleStartupMode = player.addonSubtitleStartupMode.name,
                    size = player.subtitleStyle.size,
                    verticalOffset = player.subtitleStyle.verticalOffset,
                    bold = player.subtitleStyle.bold,
                    textColor = player.subtitleStyle.textColor,
                    backgroundColor = player.subtitleStyle.backgroundColor,
                    outlineEnabled = player.subtitleStyle.outlineEnabled,
                    outlineColor = player.subtitleStyle.outlineColor,
                    useLibass = player.useLibass
                ),
                bufferNetwork = BufferNetworkSettings(
                    minBufferMs = player.bufferSettings.minBufferMs,
                    maxBufferMs = player.bufferSettings.maxBufferMs,
                    bufferForPlaybackMs = player.bufferSettings.bufferForPlaybackMs,
                    bufferForPlaybackAfterRebufferMs = player.bufferSettings.bufferForPlaybackAfterRebufferMs,
                    targetBufferSizeMb = player.bufferSettings.targetBufferSizeMb,
                    backBufferDurationMs = player.bufferSettings.backBufferDurationMs,
                    enableBufferLogs = player.enableBufferLogs
                )
            ),
            trakt = TraktSettingsPayload(
                continueWatchingDaysCap = traktSettingsDataStore.continueWatchingDaysCap.first(),
                showUnairedNextUp = traktSettingsDataStore.showUnairedNextUp.first(),
                catalogEnabledSet = traktCatalogPrefs.enabledCatalogs.toList(),
                catalogOrder = traktCatalogPrefs.catalogOrder,
                selectedPopularListKeys = traktCatalogPrefs.selectedPopularListKeys.toList()
            ),
            debug = DebugSettingsPayload(
                accountTabEnabled = debugSettingsDataStore.accountTabEnabled.first(),
                syncCodeFeaturesEnabled = debugSettingsDataStore.syncCodeFeaturesEnabled.first(),
                bufferLogsEnabled = player.enableBufferLogs
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
        playerSettingsDataStore.setGroupStreamsAcrossAddonsEnabled(settings.playback.streamSelection.groupStreamsAcrossAddonsEnabled)
        playerSettingsDataStore.setDeduplicateGroupedStreamsEnabled(settings.playback.streamSelection.deduplicateGroupedStreamsEnabled)
        playerSettingsDataStore.setFilterEpisodeMismatchStreamsEnabled(settings.playback.streamSelection.filterEpisodeMismatchStreamsEnabled)
        playerSettingsDataStore.setFilterMovieYearMismatchStreamsEnabled(settings.playback.streamSelection.filterMovieYearMismatchStreamsEnabled)
        playerSettingsDataStore.setStreamAutoPlayMode(enumValueOrDefault(settings.playback.streamSelection.streamAutoPlayMode, StreamAutoPlayMode.MANUAL))
        playerSettingsDataStore.setStreamAutoPlaySource(enumValueOrDefault(settings.playback.streamSelection.streamAutoPlaySource, StreamAutoPlaySource.ALL_SOURCES))
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

    private suspend fun applyRemoteSecrets(settings: AccountSettingsPayload) {
        tmdbSettingsDataStore.setApiKey(resolveApiKeySecret(TMDB_SECRET_TYPE, TMDB_SECRET_REF))
        mdbListSettingsDataStore.setApiKey(resolveApiKeySecret(MDBLIST_SECRET_TYPE, MDBLIST_SECRET_REF))
        geminiSettingsDataStore.setApiKey(resolveApiKeySecret(GEMINI_SECRET_TYPE, GEMINI_SECRET_REF))
        posterRatingsSettingsDataStore.setRpdbApiKey(resolveApiKeySecret(RPDB_SECRET_TYPE, RPDB_SECRET_REF))
        posterRatingsSettingsDataStore.setTopPostersApiKey(resolveApiKeySecret(TOP_POSTERS_SECRET_TYPE, TOP_POSTERS_SECRET_REF))
        premiumizeSettingsDataStore.setApiKey(resolveApiKeySecret(PREMIUMIZE_SECRET_TYPE, PREMIUMIZE_SECRET_REF))
        premiumizeService.refreshAccountState()
        applyRemoteRealDebridSecrets(settings)
        applyRemoteTraktSecrets(settings)
    }

    private suspend fun resolveApiKeySecret(secretType: String, secretRef: String): String {
        val payload = runCatching {
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
        }.getOrNull()
        return payload?.apiKey?.trim().orEmpty()
    }

    private suspend fun applyRemoteTraktSecrets(settings: AccountSettingsPayload) {
        val accessPayload = runCatching {
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
        }.getOrNull()

        val refreshPayload = runCatching {
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
        }.getOrNull()

        val accessToken = accessPayload?.accessToken?.trim().orEmpty()
        val refreshToken = refreshPayload?.refreshToken?.trim().orEmpty()
        if (accessToken.isBlank() || refreshToken.isBlank()) {
            traktAuthDataStore.clearAuth()
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

    private suspend fun applyRemoteRealDebridSecrets(settings: AccountSettingsPayload) {
        val accessPayload = runCatching {
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
        }.getOrNull()

        val refreshPayload = runCatching {
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
        }.getOrNull()

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
