package com.nexio.tv.ui.screens.player

import android.app.Activity
import android.content.Context
import android.media.audiofx.LoudnessEnhancer
import androidx.lifecycle.SavedStateHandle
import androidx.media3.common.C
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.nexio.tv.core.mpv.NexioMpvSession
import com.nexio.tv.core.stream.StreamFeatureFlags
import com.nexio.tv.data.local.NextEpisodeThresholdMode
import com.nexio.tv.data.local.PlayerPreference
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.StreamLinkCacheDataStore
import com.nexio.tv.data.local.StreamAutoPlayMode
import com.nexio.tv.data.local.GeminiSettingsDataStore
import com.nexio.tv.data.repository.SkipIntroRepository
import com.nexio.tv.data.repository.SkipInterval
import com.nexio.tv.data.repository.GeminiSubtitleTranslationService
import com.nexio.tv.data.repository.TraktScrobbleItem
import com.nexio.tv.data.repository.TraktScrobbleService
import com.nexio.tv.domain.model.Video
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.MetaRepository
import com.nexio.tv.domain.repository.StreamRepository
import com.nexio.tv.domain.repository.WatchProgressRepository
import androidx.media3.session.MediaSession
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
    internal val traktScrobbleService: TraktScrobbleService,
    internal val skipIntroRepository: SkipIntroRepository,
    internal val playerSettingsDataStore: PlayerSettingsDataStore,
    internal val geminiSettingsDataStore: GeminiSettingsDataStore,
    internal val streamLinkCacheDataStore: StreamLinkCacheDataStore,
    internal val layoutPreferenceDataStore: com.nexio.tv.data.local.LayoutPreferenceDataStore,
    internal val geminiSubtitleTranslationService: GeminiSubtitleTranslationService,
    savedStateHandle: SavedStateHandle,
    internal val scope: CoroutineScope
) {

    companion object {
        internal const val TAG = "PlayerViewModel"
        internal const val TRACK_FRAME_RATE_GRACE_MS = 1500L
        internal const val FIRST_FRAME_TIMEOUT_MS = 12_000L
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
    internal val year: String? = navigationArgs.year
    internal val headersJson: String? = navigationArgs.headersJson
    internal val contentId: String? = navigationArgs.contentId
    internal val contentType: String? = navigationArgs.contentType
    internal val contentName: String? = navigationArgs.contentName
    internal val poster: String? = navigationArgs.poster
    internal val backdrop: String? = navigationArgs.backdrop
    internal val logo: String? = navigationArgs.logo
    internal val videoId: String? = navigationArgs.videoId
    internal val initialSeason: Int? = navigationArgs.initialSeason
    internal val initialEpisode: Int? = navigationArgs.initialEpisode
    internal val initialEpisodeTitle: String? = navigationArgs.initialEpisodeTitle
    internal val rememberedAudioLanguage: String? = navigationArgs.rememberedAudioLanguage
    internal val rememberedAudioName: String? = navigationArgs.rememberedAudioName
    internal val mediaSourceFactory = PlayerMediaSourceFactory(context.applicationContext)

    internal var currentVideoHash: String? = navigationArgs.videoHash
    internal var currentVideoSize: Long? = navigationArgs.videoSize
    internal var currentFilename: String? = navigationArgs.filename
        ?: initialStreamUrl.substringBefore('?').substringAfterLast('/', "")
            .takeIf { it.isNotBlank() && it.contains('.') }
    internal var currentStreamUrl: String = initialStreamUrl
    internal var currentHeaders: Map<String, String> =
        PlayerMediaSourceFactory.sanitizeHeaders(PlayerMediaSourceFactory.parseHeaders(headersJson))

    fun getCurrentStreamUrl(): String = currentStreamUrl
    fun getCurrentHeaders(): Map<String, String> = currentHeaders

    fun stopAndRelease() {
        releasePlayer()
        mediaSourceFactory.clearVodCache()
    }

    internal var currentVideoId: String? = videoId
    internal var currentSeason: Int? = initialSeason
    internal var currentEpisode: Int? = initialEpisode
    internal var currentEpisodeTitle: String? = initialEpisodeTitle

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

    internal val builtInSubtitleCueTranslator = GeminiBuiltInSubtitleCueTranslator(
        scope = scope,
        translationService = geminiSubtitleTranslationService,
        isEnabledProvider = { shouldUseBuiltInAiTranslation() },
        apiKeyProvider = { geminiApiKey },
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
    internal var mpvSession: NexioMpvSession? =
        if (playerBackendPreference == PlayerPreference.LIBMPV) {
            NexioMpvSession(
                context = context.applicationContext,
                externalScope = scope
            )
        } else {
            null
        }

    internal var progressJob: Job? = null
    internal var vodTelemetryJob: Job? = null
    internal var firstFrameWatchdogJob: Job? = null
    internal var hideControlsJob: Job? = null
    internal var hideSeekOverlayJob: Job? = null
    internal var watchProgressSaveJob: Job? = null
    internal var seekProgressSyncJob: Job? = null
    internal var frameRateProbeJob: Job? = null
    internal var frameRateProbeToken: Long = 0L
    internal var hideAspectRatioIndicatorJob: Job? = null
    internal var hideStreamSourceIndicatorJob: Job? = null
    internal var hideSubtitleDelayOverlayJob: Job? = null
    internal var nextEpisodeAutoPlayJob: Job? = null
    internal var sourceStreamsJob: Job? = null
    internal var sourceChipErrorDismissJob: Job? = null
    internal var aiSubtitleTranslationJob: Job? = null
    internal var mpvStateCollectionJob: Job? = null
    internal var mpvSubtitleCueCollectionJob: Job? = null
    internal var observedMpvSession: NexioMpvSession? = null
    internal var builtInAiSubtitleTranslationJob: Job? = null
    internal var sourceStreamsCacheRequestKey: String? = null
    internal var hostActivityRef: WeakReference<Activity>? = null
    internal var initialPlaybackStarted: Boolean = false
    
    
    internal var lastSavedPosition: Long = 0L
    internal val saveThresholdMs = 5000L 
    internal var lastKnownDuration: Long = 0L

    
    internal var hasRenderedFirstFrame = false
    internal var shouldEnforceAutoplayOnFirstReady = true
    internal var metaVideos: List<Video> = emptyList()
    internal var nextEpisodeVideo: Video? = null
    internal var userPausedManually = false

    
    internal var skipIntervals: List<SkipInterval> = emptyList()
    internal var skipIntroEnabled: Boolean = true
    internal var skipIntroFetchedKey: String? = null
    internal var lastActiveSkipType: String? = null
    internal var autoSubtitleSelected: Boolean = false
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
    internal var sourceStreamFeatureFlags: StreamFeatureFlags = StreamFeatureFlags()
    internal var currentStreamBingeGroup: String? = navigationArgs.bingeGroup
    internal var hasAppliedRememberedAudioSelection: Boolean = false
    internal var geminiEnabled: Boolean = false
    internal var geminiApiKey: String = ""
    internal var aiTranslationSelectionGeneration: Long = 0L
    internal var currentCueGroup: CueGroup = CueGroup.EMPTY_TIME_ZERO
    internal var builtInAiCueGeneration: Long = 0L
    internal var lastMpvSubtitleVisibility: Boolean? = null

    internal var lastBufferLogTimeMs: Long = 0L
    internal var lastVodTelemetryRefreshTimeMs: Long = 0L
    internal var cachedVodCacheLogState: String = "vod=warming"
    internal var bufferLogsEnabled: Boolean = false
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
    internal val subtitleDelayUs = AtomicLong(0L)
    internal var pendingPreviewSeekPosition: Long? = null
    internal var pendingResumeProgress: WatchProgress? = null
    internal var hasRetriedCurrentStreamAfter416: Boolean = false
    internal var hasRetriedCurrentStreamAfterUnexpectedNpe: Boolean = false
    internal var hasRetriedCurrentStreamAfterMediaPeriodHolderCrash: Boolean = false
    internal var timeoutRecoveryAttempts: Int = 0
    internal val dv7ToHevcForcedStreamUrls: MutableSet<String> = mutableSetOf()
    internal val vc1SoftwarePreferredStreamUrls: MutableSet<String> = mutableSetOf()
    internal val vc1TrackSelectionBypassStreamUrls: MutableSet<String> = mutableSetOf()
    internal val safeAudioForcedStreamUrls: MutableSet<String> = mutableSetOf()
    internal val audioDisabledForcedStreamUrls: MutableSet<String> = mutableSetOf()
    internal var isMapDv7ToHevcActiveForCurrentPlayback: Boolean = false
    internal var isExperimentalDv7ToDv81ActiveForCurrentPlayback: Boolean = false
    internal var isVc1SoftwareFallbackActiveForCurrentPlayback: Boolean = false
    internal var isVc1TrackSelectionBypassActiveForCurrentPlayback: Boolean = false
    internal var isSafeAudioModeActiveForCurrentPlayback: Boolean = false
    internal var isAudioDisabledForCurrentPlayback: Boolean = false
    internal var hasAttemptedDv7ToDv81ForCurrentPlayback: Boolean = false
    internal var dv7ToDv81BridgeVersionForCurrentPlayback: String? = null
    internal var dv7ToDv81LastProbeReasonForCurrentPlayback: String? = null
    internal var playerInitializationStartedAtMs: Long = 0L
    internal var pendingSeekTelemetryRequestedAtMs: Long = 0L
    internal var pendingSeekTelemetryTargetMs: Long = -1L
    internal var pendingSeekTelemetryReadyAtMs: Long = 0L
    internal var pendingSeekTelemetryReadyLatencyMs: Long = -1L
    internal var pendingSeekTelemetryAwaitingFirstFrame: Boolean = false
    internal var pendingSeekTelemetryReadyAssumed: Boolean = false
    internal var currentScrobbleItem: TraktScrobbleItem? = null
    internal var hasSentScrobbleStartForCurrentItem: Boolean = false
    internal var hasRequestedScrobbleStartForCurrentItem: Boolean = false
    internal var scrobbleStartRequestGeneration: Long = 0L
    internal var hasSentCompletionScrobbleForCurrentItem: Boolean = false
    internal var currentStreamHasVideoTrack: Boolean = false
    internal var currentVideoTrackIsLikelyVc1: Boolean = false
    internal var currentVideoTrackMimeType: String? = null
    internal var currentVideoTrackCodecs: String? = null
    internal var currentVideoTrackWidth: Int = 0
    internal var currentVideoTrackHeight: Int = 0
    internal var currentVideoTrackSelected: Boolean = false
    internal var currentVideoTrackBestSupport: Int = C.FORMAT_UNSUPPORTED_TYPE
    internal var lastLoggedVideoTrackSignature: String? = null
    internal var episodeStreamsJob: Job? = null
    internal var episodeStreamsCacheRequestKey: String? = null
    internal val streamCacheKey: String? by lazy {
        val type = contentType?.lowercase()
        val vid = currentVideoId
        if (type.isNullOrBlank() || vid.isNullOrBlank()) null else "$type|$vid"
    }

    init {
        refreshScrobbleItem()
        mediaSourceFactory.warmupVodCacheAsync()
        if (!navigationArgs.startFromBeginning) {
            loadSavedProgressFor(currentSeason, currentEpisode)
        }
        observeSubtitleSettings()
        observeGeminiSettings()
        fetchMetaDetails(contentId, contentType)
        observeBlurUnwatchedEpisodes()
        observeEpisodeWatchProgress()
    }
    

    fun onCleared() {
        releasePlayer()
        vodTelemetryJob?.cancel()
        mediaSourceFactory.shutdown()
        sourceChipErrorDismissJob?.cancel()
    }
}
