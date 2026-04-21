package com.nexio.tv.ui.screens.player

import android.app.Activity
import android.content.Context
import android.media.audiofx.LoudnessEnhancer
import androidx.lifecycle.SavedStateHandle
import androidx.media3.common.C
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.nexio.tv.core.player.Dv5HardwareToneMapRpuTap
import com.nexio.tv.core.player.PlaybackActivityTracker
import com.nexio.tv.core.stream.StreamFeatureFlags
import com.nexio.tv.core.tvdb.TvMetadataRouter
import com.nexio.tv.data.local.NextEpisodeThresholdMode
import com.nexio.tv.data.local.PlayerPreference
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.StreamLinkCacheDataStore
import com.nexio.tv.data.local.StreamAutoPlayMode
import com.nexio.tv.data.local.TheIntroDbSettingsDataStore
import com.nexio.tv.data.local.AudioLanguageOption
import com.nexio.tv.data.local.SubtitleTranslationSettingsDataStore
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.repository.SkipIntroRepository
import com.nexio.tv.data.repository.SkipInterval
import com.nexio.tv.data.repository.SubtitleTranslationService
import com.nexio.tv.data.repository.TrackingScrobbleItem
import com.nexio.tv.data.repository.TrackingScrobbleService
import okhttp3.OkHttpClient
import com.nexio.tv.domain.model.SubtitleTranslationSettings
import com.nexio.tv.domain.model.Video
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.MetaRepository
import com.nexio.tv.domain.repository.StreamRepository
import com.nexio.tv.domain.repository.WatchProgressRepository
import androidx.media3.session.MediaSession
import com.nexio.tv.ui.screensaver.PlaybackIdleGateState
import com.nexio.tv.ui.screens.player.ass.AssSsaRenderController
import com.nexio.tv.ui.screens.player.ass.AssSsaRenderOverlayView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

class PlayerRuntimeController(
    internal val context: Context,
    internal val watchProgressRepository: WatchProgressRepository,
    internal val metaRepository: MetaRepository,
    internal val streamRepository: StreamRepository,
    internal val addonRepository: AddonRepository,
    internal val subtitleRepository: com.nexio.tv.domain.repository.SubtitleRepository,
    internal val trackingScrobbleService: TrackingScrobbleService,
    internal val skipIntroRepository: SkipIntroRepository,
    internal val playerSettingsDataStore: PlayerSettingsDataStore,
    internal val debugSettingsDataStore: DebugSettingsDataStore,
    internal val subtitleTranslationSettingsDataStore: SubtitleTranslationSettingsDataStore,
    internal val theIntroDbSettingsDataStore: TheIntroDbSettingsDataStore,
    internal val streamLinkCacheDataStore: StreamLinkCacheDataStore,
    internal val layoutPreferenceDataStore: com.nexio.tv.data.local.LayoutPreferenceDataStore,
    internal val subtitleTranslationService: SubtitleTranslationService,
    internal val tvMetadataRouter: TvMetadataRouter,
    internal val playbackIdleGateState: PlaybackIdleGateState,
    internal val playbackActivityTracker: PlaybackActivityTracker,
    internal val playbackOkHttpClient: OkHttpClient,
    savedStateHandle: SavedStateHandle,
    internal val scope: CoroutineScope
) {

    companion object {
        internal const val TAG = "PlayerViewModel"
        internal const val TRACK_FRAME_RATE_GRACE_MS = 1500L
        internal const val FIRST_FRAME_TIMEOUT_MS = 12_000L
        internal const val POST_FIRST_FRAME_BUFFERING_TIMEOUT_MS = 8_000L
        internal const val POST_FIRST_FRAME_STUCK_POSITION_MS = 500L
        internal const val MAX_TIMEOUT_RECOVERY_ATTEMPTS = 2
        internal const val ADDON_SUBTITLE_TRACK_ID_PREFIX = "Nexio-addon-sub:"
        internal val PORTUGUESE_BRAZILIAN_TAGS = listOf(
            "pt-br", "pt_br", "pob", "brazilian", "brazil", "brasil"
        )
        internal val PORTUGUESE_EUROPEAN_TAGS = listOf(
            "pt-pt", "pt_pt", "iberian", "european", "portugal", "europeu"
        )
    }

    internal data class PendingAudioSelection(
        val language: String?,
        val name: String?,
        val streamUrl: String
    )

    internal val navigationArgs = PlayerNavigationArgs.from(savedStateHandle)
    internal val playerBackendPreference: PlayerPreference =
        runCatching { PlayerPreference.valueOf(navigationArgs.playerBackend) }
            .getOrDefault(PlayerPreference.INTERNAL)
    internal val initialStreamUrl: String = navigationArgs.streamUrl
    internal val title: String = navigationArgs.title
    internal val streamName: String? = navigationArgs.streamName
    internal var currentStreamServiceKey: String? = navigationArgs.serviceKey
    internal val year: String? = navigationArgs.year
    internal val headersJson: String? = navigationArgs.headersJson
    internal val contentId: String? = navigationArgs.contentId
    internal val contentType: String? = navigationArgs.contentType
    internal val contentName: String? = navigationArgs.contentName
    internal val originalLanguage: String? = navigationArgs.originalLanguage
    internal val poster: String? = navigationArgs.poster
    internal val backdrop: String? = navigationArgs.backdrop
    internal val logo: String? = navigationArgs.logo
    internal val videoId: String? = navigationArgs.videoId
    internal val initialAddonBaseUrl: String? = navigationArgs.addonBaseUrl
    internal val initialSeason: Int? = navigationArgs.initialSeason
    internal val initialEpisode: Int? = navigationArgs.initialEpisode
    internal val initialEpisodeTitle: String? = navigationArgs.initialEpisodeTitle
    internal val rememberedAudioLanguage: String? = navigationArgs.rememberedAudioLanguage
    internal val rememberedAudioName: String? = navigationArgs.rememberedAudioName
    internal val mediaSourceFactory = PlayerMediaSourceFactory(
        context = context.applicationContext,
        playbackOkHttpClient = playbackOkHttpClient
    )
    internal var currentVideoHash: String? = navigationArgs.videoHash
    internal var currentVideoSize: Long? = navigationArgs.videoSize
    internal var currentFilename: String? = navigationArgs.filename
        ?: initialStreamUrl.substringBefore('?').substringAfterLast('/', "")
            .takeIf { it.isNotBlank() && it.contains('.') }
    /**
     * Parsed release metadata for the currently-playing file. Reused by the
     * subtitle scorer to pick the best-matching addon subtitle without
     * re-parsing the stream filename for every candidate. Populated from
     * [currentFilename] via [com.nexio.tv.core.stream.AioStrictFileParser]
     * at every site that updates [currentFilename] (cold-start, stream
     * selection, stream switch).
     */
    internal var currentParsedRelease: com.nexio.tv.core.stream.AioStrictParsedFile? =
        currentFilename?.let { com.nexio.tv.core.stream.AioStrictFileParser.parse(it) }
    internal var currentStreamUrl: String = initialStreamUrl
    internal var currentHeaders: Map<String, String> =
        PlayerMediaSourceFactory.sanitizeHeaders(PlayerMediaSourceFactory.parseHeaders(headersJson))

    fun getCurrentStreamUrl(): String = currentStreamUrl
    fun getCurrentHeaders(): Map<String, String> = currentHeaders

    fun stopAndRelease() {
        beginPlayerExit()
        endDisplayModeSessionForExit()
        Dv5HardwareToneMapRpuTap.setEnabledForPlayback(enabled = false, streamUrl = currentStreamUrl)
        releasePlayer()
        mediaSourceFactory.clearVodCache()
    }

    internal var currentVideoId: String? = videoId
    internal var currentSeason: Int? = initialSeason
    internal var currentEpisode: Int? = initialEpisode
    internal var currentEpisodeTitle: String? = initialEpisodeTitle
    internal var currentAddonBaseUrl: String? = initialAddonBaseUrl

    internal val _uiState = MutableStateFlow(
        PlayerUiState(
            title = title,
            contentName = contentName,
            currentStreamName = streamName,
            currentStreamUrl = currentStreamUrl,
            releaseYear = year,
            contentType = contentType,
            backdrop = backdrop,
            logo = logo,
            showLoadingOverlay = true,
            currentSeason = currentSeason,
            currentEpisode = currentEpisode,
            currentEpisodeTitle = currentEpisodeTitle
        )
    )
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()
    internal val _progressUiState = MutableStateFlow(PlayerPlaybackProgressUiState())
    val progressUiState: StateFlow<PlayerPlaybackProgressUiState> = _progressUiState.asStateFlow()

    internal val builtInSubtitleCueTranslator = BuiltInSubtitleCueTranslator(
        scope = scope,
        translationService = subtitleTranslationService,
        isEnabledProvider = { shouldUseBuiltInAiTranslation() },
        settingsProvider = { subtitleTranslationSettings },
        targetLanguageProvider = { _uiState.value.subtitleStyle.preferredLanguage },
        onTranslatingChanged = { isTranslating ->
            _uiState.update { state ->
                state.copy(
                    isAiSubtitleTranslating = if (shouldUseBuiltInAiTranslation()) {
                        isTranslating
                    } else {
                        false
                    }
                )
            }
        },
        onTranslationError = { message ->
            _uiState.update { state ->
                if (!shouldUseBuiltInAiTranslation()) {
                    state.copy(
                        isAiSubtitleTranslating = false,
                        aiSubtitleError = null
                    )
                } else {
                    state.copy(aiSubtitleError = message)
                }
            }
        }
    )

    internal var _exoPlayer: ExoPlayer? = null
    val exoPlayer: ExoPlayer?
        get() = _exoPlayer

    internal var progressJob: Job? = null
    internal var vodTelemetryJob: Job? = null
    internal var firstFrameWatchdogJob: Job? = null
    internal var postFirstFrameBufferingWatchdogJob: Job? = null
    internal var hideControlsJob: Job? = null
    internal var hideSeekOverlayJob: Job? = null
    internal var watchProgressSaveJob: Job? = null
    internal var scrobbleHeartbeatJob: Job? = null
    internal var seekProgressSyncJob: Job? = null
    internal var frameRateProbeJob: Job? = null
    internal var startupAfrPreflightJob: Job? = null
    internal var startupSubtitlePreparationJob: Job? = null
    internal var frameRateProbeToken: Long = 0L
    internal var hideAspectRatioIndicatorJob: Job? = null
    internal var hideStreamSourceIndicatorJob: Job? = null
    internal var hideSubtitleDelayOverlayJob: Job? = null
    internal var nextEpisodeAutoPlayJob: Job? = null
    internal var sourceStreamsJob: Job? = null
    internal var sourceChipErrorDismissJob: Job? = null
    internal var aiSubtitleTranslationJob: Job? = null
    internal var builtInAiSubtitleTranslationJob: Job? = null
    internal var aiSubtitleErrorDismissJob: Job? = null
    internal var addonSubtitleOverlayJob: Job? = null
    internal var sourceStreamsCacheRequestKey: String? = null
    internal var hostActivityRef: WeakReference<Activity>? = null
    internal var initialPlaybackStarted: Boolean = false
    
    
    internal var lastSavedPosition: Long = 0L
    internal val saveThresholdMs = 5000L 
    internal var lastKnownDuration: Long = 0L

    
    internal var hasRenderedFirstFrame = false
    internal var shouldEnforceAutoplayOnFirstReady = true
    internal var resumeAutoplayAfterLifecyclePause = false
    internal var metaVideos: List<Video> = emptyList()
    internal var nextEpisodeVideo: Video? = null
    internal var userPausedManually = false

    
    internal var skipIntervals: List<SkipInterval> = emptyList()
    internal var skipIntroEnabled: Boolean = true
    internal var skipIntroFetchedKey: String? = null
    internal var lastActiveSkipType: String? = null
    internal var autoSubtitleSelected: Boolean = false
    internal var autoAudioSelected: Boolean = false
    internal var lastPreferredAudioLanguage: String = AudioLanguageOption.ORIGINAL
    internal var lastSecondaryPreferredAudioLanguage: String? = null
    internal var lastSubtitlePreferredLanguage: String? = null
    internal var lastSubtitleSecondaryLanguage: String? = null
    internal var pendingAddonSubtitleLanguage: String? = null
    internal var pendingAddonSubtitleTrackId: String? = null
    internal var pendingAudioSelectionAfterSubtitleRefresh: PendingAudioSelection? = null
    internal var attachedAddonSubtitleKeys: Set<String> = emptySet()
    internal var hasScannedTextTracksOnce: Boolean = false
    internal var streamReuseLastLinkEnabled: Boolean = false
    internal var streamAutoPlayModeSetting: StreamAutoPlayMode = StreamAutoPlayMode.MANUAL
    internal var streamAutoPlayNextEpisodeEnabledSetting: Boolean = false
    internal var nextEpisodeThresholdModeSetting: NextEpisodeThresholdMode = NextEpisodeThresholdMode.PERCENTAGE
    internal var nextEpisodeThresholdPercentSetting: Float = 98f
    internal var nextEpisodeThresholdMinutesBeforeEndSetting: Float = 2f
    internal var lastTheIntroDbSettingsSignature: String? = null
    internal var theIntroDbEnabledSetting: Boolean = true
    internal var sourceStreamFeatureFlags: StreamFeatureFlags = StreamFeatureFlags()
    internal var currentStreamBingeGroup: String? = navigationArgs.bingeGroup
    internal var hasAppliedRememberedAudioSelection: Boolean = false
    @Volatile
    internal var subtitleTranslationSettings = SubtitleTranslationSettings()
    internal var aiTranslationSelectionGeneration: Long = 0L
    internal var currentCueGroup: CueGroup = CueGroup.EMPTY_TIME_ZERO
    internal var addonSubtitleOverlayGeneration: Long = 0L
    internal var addonSubtitleOverlayCueGroups: List<TimedAddonCueGroup> = emptyList()
    internal val playbackSessionGuard = PlayerPlaybackSessionGuard()

    internal var lastBufferLogTimeMs: Long = 0L
    internal var lastVodTelemetryRefreshTimeMs: Long = 0L
    internal var cachedVodCacheLogState: String = "vod=warming"
    internal var bufferLogsEnabled: Boolean = false
    internal var streamDiagnosticsEnabled: Boolean = false
    @Volatile
    internal var dolbyVisionDiagnosticsEnabled: Boolean = false
    internal var lastProgressUiUpdateUptimeMs: Long = 0L
    internal var lastSkipIntervalEvaluationUptimeMs: Long = 0L
    internal var lastNextEpisodeEvaluationUptimeMs: Long = 0L
    internal var bufferLogJob: Job? = null
    
    internal var loudnessEnhancer: LoudnessEnhancer? = null
    internal var trackSelector: DefaultTrackSelector? = null
    internal var currentMediaSession: MediaSession? = null
    internal var pauseOverlayJob: Job? = null
    internal val pauseOverlayDelayMs = 5000L
    internal val seekProgressSyncDebounceMs = 700L
    internal val scrobbleHeartbeatIntervalMs = 15 * 60_000L
    internal val subtitleDelayUs = AtomicLong(0L)
    internal var pendingPreviewSeekPosition: Long? = null
    internal var pendingResumeProgress: WatchProgress? = null
    internal var hasRetriedCurrentStreamAfter416: Boolean = false
    internal var hasRetriedCurrentStreamAfterUnexpectedNpe: Boolean = false
    internal var hasRetriedCurrentStreamAfterMediaPeriodHolderCrash: Boolean = false
    internal var timeoutRecoveryAttempts: Int = 0
    internal val dv5SoftwareToneMapPreferredStreamUrls: MutableSet<String> = mutableSetOf()
    internal val dv5HardwareToneMapPreferredStreamUrls: MutableSet<String> = mutableSetOf()
    internal val av1FfmpegPreferredStreamUrls: MutableSet<String> = mutableSetOf()
    internal val vc1SoftwarePreferredStreamUrls: MutableSet<String> = mutableSetOf()
    internal val vc1TrackSelectionBypassStreamUrls: MutableSet<String> = mutableSetOf()
    internal val safeAudioForcedStreamUrls: MutableSet<String> = mutableSetOf()
    internal val audioDisabledForcedStreamUrls: MutableSet<String> = mutableSetOf()
    internal var isDv5SoftwareToneMapSettingEnabledForCurrentPlayback: Boolean = false
    internal var isDv5SoftwareToneMapNativeSupportedForCurrentPlayback: Boolean = false
    internal var isDv5HardwareToneMapNativeSupportedForCurrentPlayback: Boolean = false
    internal var isDv5SoftwareToneMapActiveForCurrentPlayback: Boolean = false
    internal var isDv5HardwareToneMapSettingEnabledForCurrentPlayback: Boolean = false
    internal var isDv5HardwareToneMapActiveForCurrentPlayback: Boolean = false
    internal var isCurrentDeviceNvidiaShield: Boolean = false
    internal var isCurrentDisplayDolbyVisionCapable: Boolean = false
    internal var isExperimentalDv7ToDv81ActiveForCurrentPlayback: Boolean = false
    internal var isAv1FfmpegFallbackActiveForCurrentPlayback: Boolean = false
    internal var isVc1SoftwareFallbackActiveForCurrentPlayback: Boolean = false
    internal var isVc1TrackSelectionBypassActiveForCurrentPlayback: Boolean = false
    internal var isSafeAudioModeActiveForCurrentPlayback: Boolean = false
    internal var isAudioDisabledForCurrentPlayback: Boolean = false
    internal var isKodiCustomAudioSinkActiveForCurrentPlayback: Boolean = false
    internal var dv7ToDv81BridgeVersionForCurrentPlayback: String? = null
    internal var dv7ToDv81LastProbeReasonForCurrentPlayback: String? = null
    internal var playerInitializationStartedAtMs: Long = 0L
    internal var pendingSeekTelemetryRequestedAtMs: Long = 0L
    internal var pendingSeekTelemetryTargetMs: Long = -1L
    internal var pendingSeekTelemetryReadyAtMs: Long = 0L
    internal var pendingSeekTelemetryReadyLatencyMs: Long = -1L
    internal var pendingSeekTelemetryAwaitingFirstFrame: Boolean = false
    internal var pendingSeekTelemetryReadyAssumed: Boolean = false
    internal var currentScrobbleItem: TrackingScrobbleItem? = null
    internal var hasSentScrobbleStartForCurrentItem: Boolean = false
    internal var hasRequestedScrobbleStartForCurrentItem: Boolean = false
    internal var scrobbleStartRequestGeneration: Long = 0L
    internal var hasSentCompletionScrobbleForCurrentItem: Boolean = false
    internal var assSsaPipelineOverrideForCurrentStream: Boolean? = null
    internal var activePlayerUsesAssSsaRenderer: Boolean = false
    internal var assSsaPipelineSwitchInFlight: Boolean = false
    internal var assSsaPipelineDecisionStreamUrl: String? = null
    internal var assSsaPipelineFallbackHandledForCurrentStream: Boolean = false
    internal var assSsaRenderController: AssSsaRenderController? = null
    internal var assSsaOverlayViewProvider: (() -> AssSsaRenderOverlayView?)? = null
    internal var trackAfrAppliedForCurrentStream: Boolean = false
    internal var currentStreamHasVideoTrack: Boolean = false
    internal var currentVideoTrackIsLikelyDv5: Boolean = false
    internal var currentVideoTrackIsLikelyVc1: Boolean = false
    internal var currentVideoTrackMimeType: String? = null
    internal var currentVideoTrackCodecs: String? = null
    internal var currentVideoTrackWidth: Int = 0
    internal var currentVideoTrackHeight: Int = 0
    internal var currentVideoTrackSelected: Boolean = false
    internal var currentVideoTrackBestSupport: Int = C.FORMAT_UNSUPPORTED_TYPE
    internal var lastLoggedVideoTrackSignature: String? = null
    internal var lastLoggedAudioTrackSignature: String? = null
    internal var episodeStreamsJob: Job? = null
    internal var episodeStreamsCacheRequestKey: String? = null
    internal val streamCacheKey: String? by lazy {
        val type = contentType?.lowercase()
        val vid = currentVideoId
        if (type.isNullOrBlank() || vid.isNullOrBlank()) null else "$type|$vid"
    }

    init {
        playbackIdleGateState.onPlayerSessionStarted()
        refreshScrobbleItem()
        mediaSourceFactory.warmupVodCacheAsync()
        if (!navigationArgs.startFromBeginning) {
            loadSavedProgressFor(currentSeason, currentEpisode, navigationArgs.toRouteResumeProgress())
        }
        observeDebugSettings()
        observeSubtitleSettings()
        observeSubtitleTranslationSettings()
        observeTheIntroDbSettings()
        fetchMetaDetails(contentId, contentType)
        observeBlurUnwatchedEpisodes()
        observeEpisodeWatchProgress()
    }
    

    fun onCleared() {
        beginPlayerExit()
        endDisplayModeSessionForExit()
        releasePlayer()
        vodTelemetryJob?.cancel()
        mediaSourceFactory.shutdown()
        sourceChipErrorDismissJob?.cancel()
    }

    internal fun beginPlayerExit() {
        playbackSessionGuard.beginPlayerExit()
    }

    internal fun endDisplayModeSessionForExit() {
        val activity = currentHostActivity()
        if (activity != null) {
            com.nexio.tv.core.player.FrameRateUtils.restoreOriginalDisplayMode(activity)
            com.nexio.tv.core.player.FrameRateUtils.endMainPlayerDisplayModeSession()
            com.nexio.tv.core.player.FrameRateUtils.enforceUiPreferredRefreshRate(activity)
        } else {
            com.nexio.tv.core.player.FrameRateUtils.endMainPlayerDisplayModeSession()
            com.nexio.tv.core.player.FrameRateUtils.cleanupDisplayListener()
            com.nexio.tv.core.player.FrameRateUtils.clearOriginalDisplayMode()
        }
    }
}

internal fun PlayerNavigationArgs.toRouteResumeProgress(): WatchProgress? {
    val id = contentId?.takeIf { it.isNotBlank() } ?: return null
    val type = contentType?.takeIf { it.isNotBlank() } ?: return null
    val vid = videoId?.takeIf { it.isNotBlank() } ?: id
    val hasResumePoint = (resumePositionMs ?: 0L) > 0L ||
        resumeProgressPercent?.let { it > 0f } == true
    if (!hasResumePoint) return null

    return WatchProgress(
        contentId = id,
        contentType = type,
        name = contentName?.takeIf { it.isNotBlank() } ?: title,
        poster = poster,
        backdrop = backdrop,
        logo = logo,
        videoId = vid,
        season = initialSeason,
        episode = initialEpisode,
        episodeTitle = initialEpisodeTitle,
        position = resumePositionMs ?: 0L,
        duration = resumeDurationMs ?: 0L,
        lastWatched = resumeLastWatchedMs ?: 0L,
        progressPercent = resumeProgressPercent,
        source = resumeSource ?: WatchProgress.SOURCE_LOCAL
    )
}
