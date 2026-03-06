package com.nexio.tv.core.sync

import android.content.Context
import android.util.Log
import com.nexio.tv.core.auth.AuthManager
import com.nexio.tv.core.locale.AppLocaleResolver
import com.nexio.tv.data.local.AddonSubtitleStartupMode
import com.nexio.tv.data.local.AnimeSkipSettingsDataStore
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.local.FrameRateMatchingMode
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.data.local.NextEpisodeThresholdMode
import com.nexio.tv.data.local.PlayerPreference
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.PosterRatingsSettingsDataStore
import com.nexio.tv.data.local.StreamAutoPlayMode
import com.nexio.tv.data.local.StreamAutoPlaySource
import com.nexio.tv.data.local.SubtitleOrganizationMode
import com.nexio.tv.data.local.ThemeDataStore
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.local.TraktAuthDataStore
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.data.remote.supabase.AccountSettingsPayload
import com.nexio.tv.data.remote.supabase.AccountSnapshotRpcResponse
import com.nexio.tv.data.remote.supabase.AccountSyncMutationResult
import com.nexio.tv.data.remote.supabase.AnimeSkipSyncSettings
import com.nexio.tv.data.remote.supabase.AppearanceSettings
import com.nexio.tv.data.remote.supabase.AudioTrailerSettings
import com.nexio.tv.data.remote.supabase.BufferNetworkSettings
import com.nexio.tv.data.remote.supabase.DebugSettingsPayload
import com.nexio.tv.data.remote.supabase.IntegrationSettings
import com.nexio.tv.data.remote.supabase.LayoutSettings
import com.nexio.tv.data.remote.supabase.MDBListSyncSettings
import com.nexio.tv.data.remote.supabase.PlaybackGeneralSettings
import com.nexio.tv.data.remote.supabase.PlaybackSettings
import com.nexio.tv.data.remote.supabase.PosterRatingsSyncSettings
import com.nexio.tv.data.remote.supabase.StreamSelectionSettings
import com.nexio.tv.data.remote.supabase.SubtitleSyncSettings
import com.nexio.tv.data.remote.supabase.TmdbSyncSettings
import com.nexio.tv.data.remote.supabase.TraktAuthSyncSettings
import com.nexio.tv.data.remote.supabase.TraktSettingsPayload
import com.nexio.tv.domain.model.AppFont
import com.nexio.tv.domain.model.AppTheme
import com.nexio.tv.domain.model.AuthState
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

@Singleton
class AccountSettingsSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val postgrest: Postgrest,
    private val themeDataStore: ThemeDataStore,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val mdbListSettingsDataStore: MDBListSettingsDataStore,
    private val animeSkipSettingsDataStore: AnimeSkipSettingsDataStore,
    private val posterRatingsSettingsDataStore: PosterRatingsSettingsDataStore,
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
                posterRatingsSettingsDataStore.settings.drop(1).map { Unit },
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
        if (!authManager.isAuthenticated) return

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
            if (authManager.authState.value !is AuthState.FullAccount) {
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

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push account settings to remote", e)
            Result.failure(e)
        }
    }

    suspend fun pullFromRemoteAndApply(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val snapshot = withJwtRefreshRetry {
                postgrest.rpc("sync_pull_account_snapshot").decodeAs<AccountSnapshotRpcResponse>()
            }

            isApplyingRemote = true
            try {
                applyRemoteSettings(snapshot.settings)
            } finally {
                isApplyingRemote = false
            }

            Result.success(
                snapshot.addons
                    .sortedBy { it.sortOrder }
                    .map { it.url.trim().removeSuffix("/") }
                    .filter { it.isNotBlank() }
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
        val posterRatings = posterRatingsSettingsDataStore.settings.first()
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
                tmdb = TmdbSyncSettings(
                    enabled = tmdb.enabled,
                    apiKey = tmdb.apiKey,
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
                    playerPreference = player.playerPreference.name,
                    streamReuseLastLinkEnabled = player.streamReuseLastLinkEnabled,
                    streamReuseLastLinkCacheHours = player.streamReuseLastLinkCacheHours,
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
                audioTrailer = AudioTrailerSettings(
                    preferredAudioLanguage = player.preferredAudioLanguage,
                    secondaryPreferredAudioLanguage = player.secondaryPreferredAudioLanguage,
                    skipSilence = player.skipSilence,
                    decoderPriority = player.decoderPriority,
                    tunnelingEnabled = player.tunnelingEnabled
                ),
                subtitles = SubtitleSyncSettings(
                    preferredLanguage = player.subtitleStyle.preferredLanguage,
                    secondaryPreferredLanguage = player.subtitleStyle.secondaryPreferredLanguage,
                    subtitleOrganizationMode = player.subtitleOrganizationMode.name,
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
        themeDataStore.setTheme(enumValueOrDefault(settings.appearance.theme, AppTheme.WHITE))
        themeDataStore.setFont(enumValueOrDefault(settings.appearance.font, AppFont.INTER))
        AppLocaleResolver.setStoredLocaleTag(
            context,
            settings.appearance.localeTag.takeUnless { it.equals("system", ignoreCase = true) }
        )

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
        tmdbSettingsDataStore.setApiKey(settings.integrations.tmdb.apiKey)
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

        posterRatingsSettingsDataStore.setRpdbEnabled(settings.integrations.posterRatings.rpdbEnabled)
        posterRatingsSettingsDataStore.setTopPostersEnabled(settings.integrations.posterRatings.topPostersEnabled)

        playerSettingsDataStore.setLoadingOverlayEnabled(settings.playback.general.loadingOverlayEnabled)
        playerSettingsDataStore.setPauseOverlayEnabled(settings.playback.general.pauseOverlayEnabled)
        playerSettingsDataStore.setOsdClockEnabled(settings.playback.general.osdClockEnabled)
        playerSettingsDataStore.setSkipIntroEnabled(settings.playback.general.skipIntroEnabled)
        playerSettingsDataStore.setFrameRateMatchingMode(enumValueOrDefault(settings.playback.general.frameRateMatchingMode, FrameRateMatchingMode.OFF))
        playerSettingsDataStore.setResolutionMatchingEnabled(settings.playback.general.resolutionMatchingEnabled)
        playerSettingsDataStore.setPlayerPreference(enumValueOrDefault(settings.playback.streamSelection.playerPreference, PlayerPreference.INTERNAL))
        playerSettingsDataStore.setStreamReuseLastLinkEnabled(settings.playback.streamSelection.streamReuseLastLinkEnabled)
        playerSettingsDataStore.setStreamReuseLastLinkCacheHours(settings.playback.streamSelection.streamReuseLastLinkCacheHours)
        playerSettingsDataStore.setStreamAutoPlayMode(enumValueOrDefault(settings.playback.streamSelection.streamAutoPlayMode, StreamAutoPlayMode.MANUAL))
        playerSettingsDataStore.setStreamAutoPlaySource(enumValueOrDefault(settings.playback.streamSelection.streamAutoPlaySource, StreamAutoPlaySource.ALL_SOURCES))
        playerSettingsDataStore.setStreamAutoPlaySelectedAddons(settings.playback.streamSelection.streamAutoPlaySelectedAddons.toSet())
        playerSettingsDataStore.setStreamAutoPlayRegex(settings.playback.streamSelection.streamAutoPlayRegex)
        playerSettingsDataStore.setStreamAutoPlayNextEpisodeEnabled(settings.playback.streamSelection.streamAutoPlayNextEpisodeEnabled)
        playerSettingsDataStore.setStreamAutoPlayPreferBingeGroupForNextEpisode(settings.playback.streamSelection.streamAutoPlayPreferBingeGroupForNextEpisode)
        playerSettingsDataStore.setNextEpisodeThresholdMode(enumValueOrDefault(settings.playback.streamSelection.nextEpisodeThresholdMode, NextEpisodeThresholdMode.PERCENTAGE))
        playerSettingsDataStore.setNextEpisodeThresholdPercent(settings.playback.streamSelection.nextEpisodeThresholdPercent)
        playerSettingsDataStore.setNextEpisodeThresholdMinutesBeforeEnd(settings.playback.streamSelection.nextEpisodeThresholdMinutesBeforeEnd)
        playerSettingsDataStore.setPreferredAudioLanguage(settings.playback.audioTrailer.preferredAudioLanguage)
        playerSettingsDataStore.setSecondaryPreferredAudioLanguage(settings.playback.audioTrailer.secondaryPreferredAudioLanguage)
        playerSettingsDataStore.setSkipSilence(settings.playback.audioTrailer.skipSilence)
        playerSettingsDataStore.setDecoderPriority(settings.playback.audioTrailer.decoderPriority)
        playerSettingsDataStore.setTunnelingEnabled(settings.playback.audioTrailer.tunnelingEnabled)
        playerSettingsDataStore.setSubtitlePreferredLanguage(settings.playback.subtitles.preferredLanguage)
        playerSettingsDataStore.setSubtitleSecondaryLanguage(settings.playback.subtitles.secondaryPreferredLanguage)
        playerSettingsDataStore.setSubtitleOrganizationMode(enumValueOrDefault(settings.playback.subtitles.subtitleOrganizationMode, SubtitleOrganizationMode.NONE))
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

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T {
        return runCatching { enumValueOf<T>(value.trim().uppercase()) }.getOrDefault(default)
    }
}
