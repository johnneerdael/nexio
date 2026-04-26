package com.nexio.tv.ui.screens.stream

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.R
import com.nexio.tv.core.stream.AioCustomTemplateSelection
import com.nexio.tv.core.stream.AioFormatterSelection
import com.nexio.tv.core.stream.AioStrictFileParser
import com.nexio.tv.core.stream.StreamFeatureFlags
import com.nexio.tv.core.stream.StreamBingeGroupResolver
import com.nexio.tv.core.stream.StreamCardModel
import com.nexio.tv.core.stream.StreamPresentationEngine
import com.nexio.tv.core.stream.StreamRequestContext
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.core.player.CometProxyUrlResolver
import com.nexio.tv.core.player.DolbyVisionAutoPlayGate
import com.nexio.tv.core.player.DolbyVisionAutoPlayDecisionReason
import com.nexio.tv.core.player.DolbyVisionAutoPlayGateResult
import com.nexio.tv.core.player.FfmpegStreamMetadataProbe
import com.nexio.tv.core.player.supportsDolbyVisionDisplay
import com.nexio.tv.core.player.StreamAutoPlaySelector
import com.nexio.tv.data.local.PlayerPreference
import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.SyncedFormatterTemplateSettings
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.StreamAutoPlayMode
import com.nexio.tv.data.local.StreamLinkCacheDataStore
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.repository.benchmark.BenchmarkAwareStreamScorer
import com.nexio.tv.data.repository.benchmark.DeviceCapabilitySnapshot
import com.nexio.tv.data.repository.benchmark.ShadowAutoPlayDecisionEvent
import com.nexio.tv.data.repository.benchmark.ShadowAutoPlayDecisionLogger
import com.nexio.tv.data.repository.benchmark.ShadowRequestContext
import com.nexio.tv.data.repository.benchmark.ShadowStreamDecision
import com.nexio.tv.data.repository.benchmark.normalizedBenchmarkServiceKey
import com.nexio.tv.data.repository.device.DeviceCapabilityRepository
import com.nexio.tv.domain.model.AddonStreams
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.Stream
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.MetaRepository
import com.nexio.tv.domain.repository.StreamRepository
import com.nexio.tv.ui.components.SourceChipItem
import com.nexio.tv.ui.components.SourceChipStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import java.util.Locale
import javax.inject.Inject

private const val TAG = "StreamScreenViewModel"
private const val EMBEDDED_STREAM_GROUP_NAME = "Embedded Streams"
private const val EMBEDDED_STREAM_FALLBACK_NAME = "Embed Stream"
private const val DETERMINISTIC_AUTOPLAY_PREFLIGHT_CANDIDATE_LIMIT = 3
private const val AUTOPLAY_PREFLIGHT_TIMEOUT_MS = 1_500
private const val AUTOPLAY_MIN_CANDIDATE_POOL = 5
private const val AUTOPLAY_MIN_QUALITY_SCORE = 10
private const val AUTOPLAY_EARLY_FINISH_FALLBACK_MS = 15_000L
private const val MAX_FALLBACK_CANDIDATES = 5
private const val DETERMINISTIC_AUTOPLAY_BLOCKED_RELEASE_MARKER = "1winstudio"
@HiltViewModel
class StreamScreenViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val streamRepository: StreamRepository,
    private val addonRepository: AddonRepository,
    private val metaRepository: MetaRepository,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val streamLinkCacheDataStore: StreamLinkCacheDataStore,
    private val debugSettingsDataStore: DebugSettingsDataStore,
    private val deviceCapabilityRepository: DeviceCapabilityRepository,
    private val benchmarkAwareStreamScorer: BenchmarkAwareStreamScorer,
    private val shadowAutoPlayDecisionLogger: ShadowAutoPlayDecisionLogger,
    private val shadowAutoplayCollectionUploader: com.nexio.tv.data.repository.benchmark.ShadowAutoplayCollectionUploader,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private var autoPlayHandledForSession = false
    private var autoPlayFirstScoringAtMs: Long = 0L
    private var directAutoPlayModeInitializedForSession = false
    private var directAutoPlayFlowEnabledForSession = false
    private var streamLoadJob: Job? = null
    private var sourceChipErrorDismissJob: Job? = null
    private var activeStreamSearchRequestId: String? = null
    private var streamFeatureFlags: StreamFeatureFlags = StreamFeatureFlags()
    private var streamDiagnosticsEnabled: Boolean = false
    @Volatile
    private var probeProfilingDiagnosticEnabled: Boolean = false
    private var streamParserCache = StreamPresentationEngine.ParserCache()
    private val shadowAutoPlayReplayCoordinator =
        ShadowAutoPlayReplayCoordinator(benchmarkAwareStreamScorer)
    private val dolbyVisionAutoPlayGate = DolbyVisionAutoPlayGate()

    private val videoId: String = savedStateHandle["videoId"] ?: ""
    private val contentType: String = savedStateHandle["contentType"] ?: ""
    private val title: String = savedStateHandle["title"] ?: ""
    private val poster: String? = savedStateHandle.getOptionalString("poster")
    private val backdrop: String? = savedStateHandle.getOptionalString("backdrop")
    private val logo: String? = savedStateHandle.getOptionalString("logo")
    private val season: Int? = savedStateHandle.get<String>("season")?.toIntOrNull()
    private val episode: Int? = savedStateHandle.get<String>("episode")?.toIntOrNull()
    private val episodeName: String? = savedStateHandle.getOptionalString("episodeName")
    private val runtime: Int? = savedStateHandle.get<String>("runtime")?.toIntOrNull()
    private val originalLanguage: String? = savedStateHandle.getOptionalString("originalLanguage")
    private val genres: String? = savedStateHandle.getOptionalString("genres")
    private val year: String? = savedStateHandle.getOptionalString("year")
    private val contentId: String? = savedStateHandle.getOptionalString("contentId")
    private val contentName: String? = savedStateHandle.getOptionalString("contentName")
    private val resumePositionMs: Long? = savedStateHandle.get<String>("resumePositionMs")?.toLongOrNull()
    private val resumeDurationMs: Long? = savedStateHandle.get<String>("resumeDurationMs")?.toLongOrNull()
    private val resumeProgressPercent: Float? = savedStateHandle.get<String>("resumeProgressPercent")?.toFloatOrNull()
    private val resumeLastWatchedMs: Long? = savedStateHandle.get<String>("resumeLastWatchedMs")?.toLongOrNull()
    private val resumeSource: String? = savedStateHandle.getOptionalString("resumeSource")
    private val manualSelection: Boolean = savedStateHandle.get<String>("manualSelection")
        ?.toBooleanStrictOrNull()
        ?: false
    private val deterministicAutoplay: Boolean = savedStateHandle.get<String>("deterministicAutoplay")
        ?.toBooleanStrictOrNull()
        ?: false
    private val streamCacheKey: String = "${contentType.lowercase()}|$videoId"

    private val _uiState = MutableStateFlow(
        StreamScreenUiState(
            videoId = videoId,
            contentType = contentType,
            title = title,
            poster = poster,
            backdrop = backdrop,
            logo = logo,
            season = season,
            episode = episode,
            episodeName = episodeName,
            runtime = runtime,
            genres = genres,
            year = year,
            isDeterministicAutoplay = deterministicAutoplay
        )
    )
    val uiState: StateFlow<StreamScreenUiState> = _uiState.asStateFlow()

    val playerPreference = playerSettingsDataStore.playerSettings
        .map { it.playerPreference }
        .distinctUntilChanged()

    val preferredExternalPlayerPackageName = playerSettingsDataStore.playerSettings
        .map { it.preferredExternalPlayerPackageName }
        .distinctUntilChanged()

    private inline fun updateUiStateIfChanged(
        transform: (StreamScreenUiState) -> StreamScreenUiState
    ) {
        _uiState.update { state ->
            val next = transform(state)
            if (next == state) state else next
        }
    }

    init {
        viewModelScope.launch {
            debugSettingsDataStore.streamDiagnosticsEnabled.collectLatest { enabled ->
                streamDiagnosticsEnabled = enabled
            }
        }
        viewModelScope.launch {
            debugSettingsDataStore.dolbyVisionDiagnosticsEnabled.collectLatest { enabled ->
                FfmpegStreamMetadataProbe.setDiagnosticsEnabled(enabled)
            }
        }
        if (manualSelection) {
            // Returning from a playback error: keep the user on stream selection.
            autoPlayHandledForSession = true
            directAutoPlayModeInitializedForSession = true
            directAutoPlayFlowEnabledForSession = false
            _uiState.update {
                it.copy(
                    isDirectAutoPlayFlow = false,
                    showDirectAutoPlayOverlay = false,
                    autoPlayStream = null,
                    autoPlayPlaybackInfo = null,
                    directAutoPlayMessage = null,
                    deterministicAutoplayFailureMessage = null
                )
            }
        }
        loadMissingMetaDetailsIfNeeded()
        loadStreams()
    }

    private fun SavedStateHandle.getOptionalString(key: String): String? {
        return get<String>(key)?.takeIf { it.isNotBlank() }
    }

    fun onEvent(event: StreamScreenEvent) {
        when (event) {
            is StreamScreenEvent.OnAddonFilterSelected -> filterByAddon(event.addonName)
            is StreamScreenEvent.OnStreamSelected -> { /* Handle stream selection - will be handled in UI */ }
            StreamScreenEvent.OnAutoPlayConsumed -> {
                if (autoPlayHandledForSession &&
                    _uiState.value.autoPlayStream == null &&
                    _uiState.value.autoPlayPlaybackInfo == null
                ) {
                    return
                }
                autoPlayHandledForSession = true
                updateUiStateIfChanged {
                    it.copy(
                        autoPlayStream = null,
                        autoPlayPlaybackInfo = null
                    )
                }
            }
            StreamScreenEvent.OnRetry -> loadStreams()
            StreamScreenEvent.OnBackPress -> cancelActiveStreamSearch()
        }
    }

    fun cancelActiveStreamSearch() {
        activeStreamSearchRequestId?.let(streamRepository::cancelActiveStreamRequests)
        activeStreamSearchRequestId = null
        streamLoadJob?.cancel()
        streamLoadJob = null
        sourceChipErrorDismissJob?.cancel()
        sourceChipErrorDismissJob = null
        shadowAutoPlayReplayCoordinator.clear()
    }

    private fun shouldUseDirectAutoPlayFlow(
        playerPreference: PlayerPreference,
        streamAutoPlayMode: StreamAutoPlayMode
    ): Boolean {
        return deterministicAutoplay || (playerPreference == PlayerPreference.INTERNAL &&
            streamAutoPlayMode != StreamAutoPlayMode.MANUAL
        )
    }

    fun consumeDeterministicAutoplayFailure() {
        updateUiStateIfChanged { it.copy(deterministicAutoplayFailureMessage = null) }
    }

    private fun loadStreams() {
        cancelActiveStreamSearch()
        val requestId = UUID.randomUUID().toString()
        activeStreamSearchRequestId = requestId
        streamLoadJob = viewModelScope.launch {
            try {
                val playerSettings = playerSettingsDataStore.playerSettings.first()
                streamFeatureFlags = playerSettings.toStreamFeatureFlags()
                probeProfilingDiagnosticEnabled = playerSettings.probeProfilingDiagnosticEnabled
                if (manualSelection) {
                    directAutoPlayModeInitializedForSession = true
                    directAutoPlayFlowEnabledForSession = false
                    autoPlayHandledForSession = true
                } else if (!directAutoPlayModeInitializedForSession) {
                    directAutoPlayFlowEnabledForSession = shouldUseDirectAutoPlayFlow(
                        playerPreference = playerSettings.playerPreference,
                        streamAutoPlayMode = playerSettings.streamAutoPlayMode
                    )
                    directAutoPlayModeInitializedForSession = true
                }

                val rawRegex = playerSettings.streamAutoPlayRegex.orEmpty().trim()
                val isEffectivelyEmptyRegex = rawRegex.isEmpty() || !rawRegex.any { it.isLetterOrDigit() }

                if (playerSettings.streamAutoPlayMode == StreamAutoPlayMode.REGEX_MATCH && isEffectivelyEmptyRegex) {
                    directAutoPlayFlowEnabledForSession = false
                    autoPlayHandledForSession = true
                }

                val directFlowActive = directAutoPlayFlowEnabledForSession
                var resolvedAutoPlayTarget = false

                if (directFlowActive) {
                    updateUiStateIfChanged {
                        it.copy(
                            isDirectAutoPlayFlow = true,
                            showDirectAutoPlayOverlay = true,
                            directAutoPlayMessage = if (deterministicAutoplay) {
                                null
                            } else {
                                context.getString(R.string.stream_finding_source)
                            }
                        )
                    }
                }

                if (!autoPlayHandledForSession && playerSettings.streamReuseLastLinkEnabled) {
                    val cached = streamLinkCacheDataStore.getValid(
                        contentKey = streamCacheKey,
                        maxAgeMs = playerSettings.streamReuseLastLinkCacheHours * 60L * 60L * 1000L
                    )
                    if (cached != null) {
                        if (shouldInvalidateCachedDeterministicAutoPlayLink(cached)) {
                            streamLinkCacheDataStore.invalidate(streamCacheKey)
                            Log.i(
                                TAG,
                                "AUTOPLAY_CACHE_INVALIDATED reason=dv_webdl_requires_reselection " +
                                    "contentKey=$streamCacheKey filename=${cached.filename ?: "none"}"
                            )
                            Log.i(
                                TAG,
                                "AUTOPLAY_CACHE_RESELECTION_TRIGGERED contentKey=$streamCacheKey " +
                                    "mode=deterministic reason=invalidated_cached_dv_webdl"
                            )
                        } else {
                            autoPlayHandledForSession = true
                            resolvedAutoPlayTarget = true
                            updateUiStateIfChanged {
                                it.copy(
                                    autoPlayPlaybackInfo = StreamPlaybackInfo(
                                    url = cached.url,
                                    title = title,
                                    streamName = cached.streamName,
                                    year = year,
                                    isExternal = false,
                                    isTorrent = false,
                                    infoHash = null,
                                    ytId = null,
                                    headers = cached.headers,
                                    contentId = contentId ?: videoId.substringBefore(":"),
                                    contentType = contentType,
                                    contentName = contentName ?: title,
                                    originalLanguage = originalLanguage,
                                    poster = poster,
                                    backdrop = backdrop,
                                    logo = logo,
                                    videoId = videoId,
                                    season = season,
                                    episode = episode,
                                    episodeTitle = episodeName,
                                    bingeGroup = cached.bingeGroup,
                                    rememberedAudioLanguage = cached.rememberedAudioLanguage,
                                    rememberedAudioName = cached.rememberedAudioName,
                                    filename = cached.filename,
                                    videoHash = cached.videoHash,
                                    videoSize = cached.videoSize
                                    )
                                )
                            }
                        }
                    }
                }

                updateUiStateIfChanged {
                    it.copy(
                        isLoading = true,
                        showNoStreamsState = false,
                        error = null,
                        showAddonFilters = !streamFeatureFlags.groupAcrossAddonsEnabled,
                        showDirectAutoPlayOverlay = if (directFlowActive) true else it.showDirectAutoPlayOverlay
                    )
                }
                shadowAutoPlayReplayCoordinator.clear()

                val installedAddons = addonRepository.getInstalledAddons().first()
                val installedAddonOrder = installedAddons.map { it.displayName }
                streamParserCache = StreamPresentationEngine.ParserCache()

                suspend fun buildOrganizedPayload(
                    addonStreamGroups: List<AddonStreams>,
                    isFinalPass: Boolean = false
                ): OrganizedStreamPayload {
                    val selectedFilter = _uiState.value.selectedAddonFilter
                    return withContext(Dispatchers.Default) {
                        val orderedAddonStreams = StreamAutoPlaySelector.orderAddonStreams(
                            addonStreamGroups,
                            installedAddonOrder
                        )
                        val allStreams = orderedAddonStreams.flatMap { it.streams }
                        val availableAddons = orderedAddonStreams
                            .filter { it.streams.isNotEmpty() }
                            .map { it.addonName }
                        val autoPlayCandidates = if (autoPlayHandledForSession) {
                            emptyList()
                        } else {
                            StreamAutoPlaySelector.candidateAutoPlayStreams(
                                streams = allStreams,
                                mode = playerSettings.streamAutoPlayMode,
                                regexPattern = playerSettings.streamAutoPlayRegex,
                                source = playerSettings.streamAutoPlaySource,
                                installedAddonNames = installedAddonOrder.toSet(),
                                selectedAddons = playerSettings.streamAutoPlaySelectedAddons
                            )
                        }
                        val organizedStreams = StreamPresentationEngine.organize(
                            streams = allStreams,
                            availableAddons = availableAddons,
                            selectedAddonFilter = selectedFilter,
                            flags = streamFeatureFlags,
                            requestContext = buildStreamRequestContext(),
                            parserCache = streamParserCache
                        )
                        val deviceSnapshot = deviceCapabilityRepository.snapshotForAutoplay()
                        val manualBitrateCapMbps = playerSettings.manualBitrateLimitMbps
                        val autoPlayPlaybackInfo = if (autoPlayHandledForSession) {
                            null
                        } else {
                            buildDeterministicAutoPlayPlaybackInfo(
                                request = buildShadowRequestContext(requestId),
                                organizedStreams = organizedStreams.items,
                                manualBitrateCapMbps = manualBitrateCapMbps,
                                deviceSnapshot = deviceSnapshot,
                                isFinalPass = isFinalPass
                            )
                        }
                        val selectedAutoPlayStream: com.nexio.tv.domain.model.Stream? = null
                        OrganizedStreamPayload(
                            orderedAddonStreams = orderedAddonStreams,
                            allStreams = allStreams,
                            availableAddons = availableAddons,
                            organizedStreams = organizedStreams,
                            selectedAutoPlayStream = selectedAutoPlayStream,
                            autoPlayPlaybackInfo = autoPlayPlaybackInfo,
                            deterministicFailureMessage = if (deterministicAutoplay &&
                                isFinalPass &&
                                autoPlayPlaybackInfo == null
                            ) {
                                "No eligible links found"
                            } else {
                                null
                            },
                            shadowDecisionFinalPass = isFinalPass,
                            shadowDecisionAllowEarlyFinish = deterministicAutoplay
                        )
                    }
                }

                suspend fun applyOrganizedPayload(organizedResult: OrganizedStreamPayload) {
                    logPresentationDiagnostics(
                        origin = "stream_screen",
                        organizedResult = organizedResult.organizedStreams
                    )
                    val deviceSnapshot = deviceCapabilityRepository.snapshotForAutoplay()
                    updateShadowAutoPlayDecision(
                        request = buildShadowRequestContext(requestId),
                        organizedStreams = organizedResult.organizedStreams.items,
                        manualBitrateCapMbps = playerSettings.manualBitrateLimitMbps,
                        deviceSnapshot = deviceSnapshot,
                        isFinalPass = organizedResult.shadowDecisionFinalPass,
                        allowEarlyFinishTerminal = organizedResult.shadowDecisionAllowEarlyFinish
                    )

                    val selectedAutoPlayStream = organizedResult.selectedAutoPlayStream
                    if (selectedAutoPlayStream != null) {
                        resolvedAutoPlayTarget = true
                    }
                    if (organizedResult.autoPlayPlaybackInfo != null) {
                        resolvedAutoPlayTarget = true
                        prewarmCometProxyCandidates(organizedResult.autoPlayPlaybackInfo)
                    }
                    val deterministicFailureMessage = organizedResult.deterministicFailureMessage

                    updateUiStateIfChanged {
                        it.copy(
                            isLoading = false,
                            showNoStreamsState = if (organizedResult.organizedStreams.items.isNotEmpty()) {
                                false
                            } else {
                                it.showNoStreamsState
                            },
                            addonStreams = organizedResult.orderedAddonStreams,
                            allStreams = organizedResult.allStreams,
                            filteredStreams = organizedResult.organizedStreams.items.map { item -> item.stream },
                            presentedStreams = organizedResult.organizedStreams.items,
                            availableAddons = organizedResult.organizedStreams.availableAddons,
                            selectedAddonFilter = organizedResult.organizedStreams.selectedAddonFilter,
                            showAddonFilters = organizedResult.organizedStreams.showAddonFilters,
                            sourceChips = mergeSourceChipStatuses(
                                existing = _uiState.value.sourceChips,
                                succeededNames = organizedResult.orderedAddonStreams.map { it.addonName }
                            ),
                            autoPlayStream = selectedAutoPlayStream,
                            autoPlayPlaybackInfo = organizedResult.autoPlayPlaybackInfo,
                            deterministicAutoplayFailureMessage = deterministicFailureMessage,
                            error = null,
                            showDirectAutoPlayOverlay = if (directAutoPlayFlowEnabledForSession) {
                                true
                            } else {
                                false
                            }
                        )
                    }
                }

                suspend fun applySuccess(addonStreamGroups: List<AddonStreams>) {
                    val organizedResult = buildOrganizedPayload(addonStreamGroups)
                    applyOrganizedPayload(organizedResult)
                }

                if (shouldAttemptEmbeddedMetaStreamLookup()) {
                    getEmbeddedStreamsFromMeta()?.let { embeddedAddonStreams ->
                        Log.d(
                            TAG,
                            "Using embedded video streams for videoId=$videoId count=${embeddedAddonStreams.streams.size}"
                        )
                        applySuccess(listOf(embeddedAddonStreams))
                        updateSourceChipsForEmbedded(embeddedAddonStreams.addonName)
                        if (directAutoPlayFlowEnabledForSession && !resolvedAutoPlayTarget) {
                            directAutoPlayFlowEnabledForSession = false
                            updateUiStateIfChanged {
                                it.copy(
                                    isDirectAutoPlayFlow = false,
                                    showDirectAutoPlayOverlay = false,
                                    directAutoPlayMessage = null
                                )
                            }
                            shadowAutoPlayReplayCoordinator.clear()
                        }
                        return@launch
                    }
                }

                updateSourceChipsForFetchStart(installedAddons)
                val organizeChannel = Channel<PendingOrganizeRequest>(Channel.UNLIMITED)
                var latestAddonStreamGroups: List<AddonStreams> = emptyList()
                val queuedPresentationVersion = AtomicLong(0L)
                val appliedPresentationVersion = AtomicLong(0L)
                var streamSearchCompletedWithError = false
                var organizerHadFailure = false
                val organizerJob = launch {
                    for (request in organizeChannel) {
                        val organizedResult = try {
                            buildOrganizedPayload(
                                addonStreamGroups = request.addonStreamGroups,
                                isFinalPass = request.isFinalPass
                            )
                        } catch (error: CancellationException) {
                            if (streamDiagnosticsEnabled) {
                                Log.d(
                                    TAG,
                                    "Incremental organize cancelled version=${request.version}: ${error.message}"
                                )
                            }
                            throw error
                        } catch (error: Exception) {
                            organizerHadFailure = true
                            Log.w(TAG, "Failed to organize streams incrementally: ${error.message}")
                            null
                        }
                        if (organizedResult != null) {
                            applyOrganizedPayload(organizedResult)
                            appliedPresentationVersion.set(request.version)
                            organizerHadFailure = false
                        }
                    }
                }

                streamRepository.getStreamsFromAllAddons(
                    type = contentType,
                    videoId = videoId,
                    season = season,
                    episode = episode,
                    installedAddons = installedAddons,
                    requestOrigin = "stream_screen",
                    requestId = requestId
                ).collect { result ->
                    when (result) {
                        is NetworkResult.Success -> {
                            val version = queuedPresentationVersion.incrementAndGet()
                            latestAddonStreamGroups = result.data
                            organizeChannel.trySend(PendingOrganizeRequest(
                                version = version,
                                addonStreamGroups = result.data
                            ))
                        }
                        is NetworkResult.Error -> {
                            streamSearchCompletedWithError = true
                            if (directAutoPlayFlowEnabledForSession) {
                                directAutoPlayFlowEnabledForSession = false
                            }
                            updateUiStateIfChanged {
                                it.copy(
                                    isLoading = false,
                                    showNoStreamsState = false,
                                    error = if (deterministicAutoplay) null else result.message,
                                    isDirectAutoPlayFlow = if (deterministicAutoplay) true else false,
                                    showDirectAutoPlayOverlay = deterministicAutoplay,
                                    directAutoPlayMessage = if (deterministicAutoplay) {
                                        null
                                    } else {
                                        null
                                    },
                                    deterministicAutoplayFailureMessage = if (deterministicAutoplay) {
                                        result.message
                                    } else {
                                        null
                                    }
                                )
                            }
                        }
                        NetworkResult.Loading -> {
                            updateUiStateIfChanged {
                                it.copy(
                                    isLoading = true,
                                    showAddonFilters = !streamFeatureFlags.groupAcrossAddonsEnabled,
                                    showDirectAutoPlayOverlay = if (directAutoPlayFlowEnabledForSession) {
                                        true
                                    } else {
                                        it.showDirectAutoPlayOverlay
                                    }
                                )
                            }
                        }
                    }
                }
                var finalRequestedVersion = queuedPresentationVersion.get()
                if (finalRequestedVersion > 0L &&
                    appliedPresentationVersion.get() < finalRequestedVersion
                ) {
                    finalRequestedVersion = queuedPresentationVersion.incrementAndGet()
                    if (streamDiagnosticsEnabled) {
                        Log.d(
                            TAG,
                            "Queueing final organize version=$finalRequestedVersion " +
                                "applied=${appliedPresentationVersion.get()}"
                        )
                    }
                    organizeChannel.trySend(PendingOrganizeRequest(
                        version = finalRequestedVersion,
                        addonStreamGroups = latestAddonStreamGroups,
                        isFinalPass = true
                    ))
                }
                organizeChannel.close()
                organizerJob.join()
                if (finalRequestedVersion > 0L &&
                    appliedPresentationVersion.get() < finalRequestedVersion
                ) {
                    if (streamDiagnosticsEnabled) {
                        Log.d(
                            TAG,
                            "Running final synchronous organize version=$finalRequestedVersion " +
                                "applied=${appliedPresentationVersion.get()}"
                        )
                    }
                    try {
                        applyOrganizedPayload(buildOrganizedPayload(latestAddonStreamGroups, isFinalPass = true))
                        appliedPresentationVersion.set(finalRequestedVersion)
                        organizerHadFailure = false
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        organizerHadFailure = true
                        Log.w(TAG, "Failed to run final synchronous stream organize: ${error.message}")
                        val organizeErrorMessage = context.getString(R.string.panel_failed_load_streams)
                        updateUiStateIfChanged {
                            it.copy(
                                isLoading = false,
                                showNoStreamsState = false,
                                error = if (deterministicAutoplay) null else organizeErrorMessage,
                                deterministicAutoplayFailureMessage = if (deterministicAutoplay) {
                                    organizeErrorMessage
                                } else {
                                    it.deterministicAutoplayFailureMessage
                                }
                            )
                        }
                    }
                }

                if (!organizerHadFailure && deterministicAutoplay && !resolvedAutoPlayTarget) {
                    applyOrganizedPayload(
                        buildOrganizedPayload(
                            _uiState.value.addonStreams,
                            isFinalPass = true
                        )
                    )
                }
                if (!streamSearchCompletedWithError &&
                    !organizerHadFailure &&
                    _uiState.value.presentedStreams.isEmpty() &&
                    _uiState.value.error == null &&
                    _uiState.value.deterministicAutoplayFailureMessage == null
                ) {
                    updateUiStateIfChanged { it.copy(showNoStreamsState = true) }
                }

                markRemainingSourceChipsAsError()

                if (directAutoPlayFlowEnabledForSession && !resolvedAutoPlayTarget && !deterministicAutoplay) {
                    directAutoPlayFlowEnabledForSession = false
                    updateUiStateIfChanged {
                        it.copy(
                            isDirectAutoPlayFlow = false,
                            showDirectAutoPlayOverlay = false,
                            directAutoPlayMessage = null
                        )
                    }
                }
            } finally {
                if (activeStreamSearchRequestId == requestId) {
                    activeStreamSearchRequestId = null
                }
            }
        }
    }

    private fun shouldAttemptEmbeddedMetaStreamLookup(): Boolean {
        val metaId = contentId?.takeIf { it.isNotBlank() } ?: return false
        if (contentType.isBlank()) return false
        if (contentType.equals("other", ignoreCase = true)) return true

        val canonicalVideoMetaId = videoId.substringBefore(":")
        return !metaId.equals(canonicalVideoMetaId, ignoreCase = true)
    }

    private suspend fun updateSourceChipsForFetchStart(installedAddons: List<com.nexio.tv.domain.model.Addon>) {
        val orderedNames = installedAddons
            .filter { it.supportsStreamResourceForChip(contentType) }
            .map { it.displayName }
            .distinct()
        if (orderedNames.isEmpty()) {
            updateUiStateIfChanged {
                it.copy(
                    sourceChips = emptyList(),
                    showAddonFilters = !streamFeatureFlags.groupAcrossAddonsEnabled
                )
            }
            return
        }

        updateUiStateIfChanged { state ->
            state.copy(
                sourceChips = orderedNames.map { name ->
                    SourceChipItem(name = name, status = SourceChipStatus.LOADING)
                },
                showAddonFilters = !streamFeatureFlags.groupAcrossAddonsEnabled
            )
        }
    }

    private fun updateSourceChipsForEmbedded(name: String) {
        updateUiStateIfChanged { state ->
            val chips = if (state.sourceChips.any { it.name == name }) {
                state.sourceChips.map { chip ->
                    if (chip.name == name) chip.copy(status = SourceChipStatus.SUCCESS) else chip
                }
            } else {
                listOf(SourceChipItem(name = name, status = SourceChipStatus.SUCCESS))
            }
            state.copy(sourceChips = chips)
        }
    }

    private fun mergeSourceChipStatuses(
        existing: List<SourceChipItem>,
        succeededNames: List<String>
    ): List<SourceChipItem> {
        if (succeededNames.isEmpty()) return existing
        if (existing.isEmpty()) {
            return succeededNames.distinct().map { name ->
                SourceChipItem(name = name, status = SourceChipStatus.SUCCESS)
            }
        }

        val successSet = succeededNames.toSet()
        val updated = existing.map { chip ->
            if (chip.name in successSet) chip.copy(status = SourceChipStatus.SUCCESS) else chip
        }.toMutableList()

        val knownNames = updated.map { it.name }.toSet()
        succeededNames.forEach { name ->
            if (name !in knownNames) {
                updated += SourceChipItem(name = name, status = SourceChipStatus.SUCCESS)
            }
        }
        return updated
    }

    private fun markRemainingSourceChipsAsError() {
        var markedAnyError = false
        updateUiStateIfChanged { state ->
            val hasPending = state.sourceChips.any { it.status == SourceChipStatus.LOADING }
            if (!hasPending) return@updateUiStateIfChanged state
            markedAnyError = true
            state.copy(
                sourceChips = state.sourceChips.map { chip ->
                    if (chip.status == SourceChipStatus.LOADING) {
                        chip.copy(status = SourceChipStatus.ERROR)
                    } else {
                        chip
                    }
                }
            )
        }
        if (markedAnyError) {
            scheduleErrorChipRemoval()
        }
    }

    private fun scheduleErrorChipRemoval() {
        sourceChipErrorDismissJob?.cancel()
        sourceChipErrorDismissJob = viewModelScope.launch {
            delay(1600L)
            updateUiStateIfChanged { state ->
                val remaining = state.sourceChips.filterNot { it.status == SourceChipStatus.ERROR }
                if (remaining.size == state.sourceChips.size) state else state.copy(sourceChips = remaining)
            }
        }
    }

    private fun com.nexio.tv.domain.model.Addon.supportsStreamResourceForChip(type: String): Boolean {
        return resources.any { resource ->
            resource.name == "stream" &&
                (resource.types.isEmpty() || resource.types.any { it.equals(type, ignoreCase = true) })
        }
    }

    private suspend fun getEmbeddedStreamsFromMeta(): AddonStreams? {
        val metaId = contentId?.takeIf { it.isNotBlank() } ?: return null
        val result = metaRepository.getMetaFromAllAddons(type = contentType, id = metaId)
            .first { it !is NetworkResult.Loading }
        val meta = (result as? NetworkResult.Success)?.data ?: return null
        val video = meta.videos.firstOrNull { it.id == videoId } ?: return null
        if (video.streams.isEmpty()) return null

        val streams = video.streams.map { stream ->
            stream.copy(
                name = stream.name ?: stream.title ?: stream.description ?: EMBEDDED_STREAM_FALLBACK_NAME,
                addonName = EMBEDDED_STREAM_GROUP_NAME,
                addonLogo = null
            )
        }

        return AddonStreams(
            addonName = EMBEDDED_STREAM_GROUP_NAME,
            addonLogo = null,
            streams = streams
        )
    }

    private fun loadMissingMetaDetailsIfNeeded() {
        val requiresMetadataLookup = genres.isNullOrBlank() || year.isNullOrBlank() || runtime == null
        if (!requiresMetadataLookup) return

        val metaId = contentId ?: videoId.substringBefore(":")
        if (metaId.isBlank() || contentType.isBlank()) return

        viewModelScope.launch {
            val result = metaRepository.getMetaFromAllAddons(type = contentType, id = metaId)
                .first { it !is NetworkResult.Loading }

            if (result !is NetworkResult.Success) return@launch

            val meta = result.data
            val metaGenres = meta.genres.takeIf { it.isNotEmpty() }?.joinToString(" • ")
            val metaYear = meta.releaseInfo
                ?.substringBefore("-")
                ?.takeIf { it.isNotBlank() }
            val metaRuntime = extractRuntimeMinutes(meta)

            _uiState.update { state ->
                val posterValue = state.poster ?: meta.poster
                val backdropValue = state.backdrop ?: meta.background
                val logoValue = state.logo ?: meta.logo
                val genresValue = state.genres?.takeIf { it.isNotBlank() } ?: metaGenres
                val yearValue = state.year?.takeIf { it.isNotBlank() } ?: metaYear
                val runtimeValue = state.runtime ?: metaRuntime
                if (state.poster == posterValue &&
                    state.backdrop == backdropValue &&
                    state.logo == logoValue &&
                    state.genres == genresValue &&
                    state.year == yearValue &&
                    state.runtime == runtimeValue
                ) {
                    state
                } else {
                    state.copy(
                        poster = posterValue,
                        backdrop = backdropValue,
                        logo = logoValue,
                        genres = genresValue,
                        year = yearValue,
                        runtime = runtimeValue
                    )
                }
            }
        }
    }

    private fun extractRuntimeMinutes(meta: Meta): Int? {
        return resolveStreamRuntimeMinutes(
            meta = meta,
            season = season,
            episode = episode
        )
    }

    private fun filterByAddon(addonName: String?) {
        val state = _uiState.value
        if (state.selectedAddonFilter == addonName) return
        viewModelScope.launch {
            val organizedStreams = withContext(Dispatchers.Default) {
                StreamPresentationEngine.organize(
                    streams = state.allStreams,
                    availableAddons = state.availableAddons,
                    selectedAddonFilter = addonName,
                    flags = streamFeatureFlags,
                    requestContext = buildStreamRequestContext(),
                    parserCache = streamParserCache
                )
            }
            logPresentationDiagnostics(
                origin = "stream_screen_filter",
                organizedResult = organizedStreams
            )
            updateUiStateIfChanged {
                it.copy(
                    selectedAddonFilter = organizedStreams.selectedAddonFilter,
                    filteredStreams = organizedStreams.items.map { item -> item.stream },
                    presentedStreams = organizedStreams.items,
                    availableAddons = organizedStreams.availableAddons,
                    showAddonFilters = organizedStreams.showAddonFilters
                )
            }
        }
    }

    /**
     * Gets the selected stream for playback
     */
    fun getStreamForPlayback(item: StreamCardModel): StreamPlaybackInfo {
        return buildStreamPlaybackInfo(item)
    }

    fun getStreamForPlayback(stream: Stream): StreamPlaybackInfo {
        val playbackInfo = StreamPlaybackInfo(
            url = stream.getStreamUrl(),
            title = _uiState.value.title,
            streamName = stream.name ?: stream.addonName,
            serviceKey = normalizedBenchmarkServiceKey(stream.wrappedProviderId),
            year = year,
            isExternal = stream.isExternal(),
            isTorrent = stream.isTorrent(),
            infoHash = stream.infoHash,
            ytId = stream.ytId,
            headers = stream.behaviorHints?.proxyHeaders?.request,
            contentId = contentId ?: videoId.substringBefore(":"),  // Use explicit contentId or extract from videoId
            contentType = contentType,
            contentName = contentName ?: title,
            originalLanguage = originalLanguage,
            poster = poster,
            backdrop = backdrop,
            logo = logo,
            videoId = videoId,
            season = season,
            episode = episode,
            episodeTitle = episodeName,
            bingeGroup = StreamBingeGroupResolver.resolve(stream),
            rememberedAudioLanguage = null,
            rememberedAudioName = null,
            filename = stream.behaviorHints?.filename,
            videoHash = stream.behaviorHints?.videoHash,
            videoSize = stream.behaviorHints?.videoSize,
            streamKey = stream.wrappedOriginalStreamKey,
            resumePositionMs = resumePositionMs,
            resumeDurationMs = resumeDurationMs,
            resumeProgressPercent = resumeProgressPercent,
            resumeLastWatchedMs = resumeLastWatchedMs,
            resumeSource = resumeSource,
            addonBaseUrl = stream.addonBaseUrl
        )

        val url = playbackInfo.url
        if (!url.isNullOrBlank()) {
            viewModelScope.launch {
                streamLinkCacheDataStore.save(
                    contentKey = streamCacheKey,
                    url = url,
                    streamName = playbackInfo.streamName,
                    headers = playbackInfo.headers,
                    bingeGroup = playbackInfo.bingeGroup,
                    filename = playbackInfo.filename,
                    videoHash = playbackInfo.videoHash,
                    videoSize = playbackInfo.videoSize
                )
            }
        }

        return playbackInfo
    }

    suspend fun resolveAutoPlayPlaybackInfo(playbackInfo: StreamPlaybackInfo): StreamPlaybackInfo? {
        Log.i(
            TAG,
            "AUTOPLAY_HERO_GATED_RESOLUTION_START stream=${playbackInfo.streamKey ?: "unknown"} " +
                "overlayActive=true deterministic=${_uiState.value.isDeterministicAutoplay}"
        )
        val result: DolbyVisionAutoPlayGateResult = dolbyVisionAutoPlayGate.resolve(
            context = context,
            playbackInfo = playbackInfo,
            autoPlay = true,
            displaySupportsDolbyVision = supportsDolbyVisionDisplay(context)
        )
        Log.i(
            TAG,
            "AUTOPLAY_DV_PROBE stream=${playbackInfo.streamKey ?: "unknown"} " +
                "selected=${result.playbackInfo.streamKey ?: "unknown"} " +
                "fallback=${result.fallbackApplied} reason=${result.reason} " +
                "profile=${result.probeResult?.profileLabel ?: "none"} " +
                "videoCodec=${result.probeResult?.videoCodec ?: "unknown"} " +
                "audioCodec=${result.probeResult?.audioCodec ?: "unknown"} " +
                "hdrType=${result.probeResult?.hdrType ?: "unknown"}"
        )
        if (result.reason == DolbyVisionAutoPlayDecisionReason.NO_FALLBACK_AVAILABLE) {
            Log.w(
                TAG,
                "AUTOPLAY_DV_BLOCKED stream=${playbackInfo.streamKey ?: "unknown"} " +
                    "reason=no_eligible_non_dv5_stream"
            )
            updateUiStateIfChanged {
                it.copy(
                    deterministicAutoplayFailureMessage = "No eligible streams found, attempt manual stream selection",
                    showDirectAutoPlayOverlay = false
                )
            }
            return null
        }
        result.playbackInfo.url?.let { resolvedUrl ->
            streamLinkCacheDataStore.save(
                contentKey = streamCacheKey,
                url = resolvedUrl,
                streamName = result.playbackInfo.streamName,
                headers = result.playbackInfo.headers,
                bingeGroup = result.playbackInfo.bingeGroup,
                filename = result.playbackInfo.filename,
                videoHash = result.playbackInfo.videoHash,
                videoSize = result.playbackInfo.videoSize
            )
        }
        Log.i(
            TAG,
            "AUTOPLAY_HERO_GATED_RESOLUTION_READY selected=${result.playbackInfo.streamKey ?: "unknown"} " +
                "overlayActive=true readyForPlayback=true"
        )
        return result.playbackInfo
    }

    private fun prewarmCometProxyCandidates(playbackInfo: StreamPlaybackInfo) {
        val primaryAddonHost = CometProxyUrlResolver.hostOfAddonBaseUrl(playbackInfo.addonBaseUrl)
        playbackInfo.url?.let {
            CometProxyUrlResolver.prewarm(it, playbackInfo.headers, primaryAddonHost)
        }
        playbackInfo.autoPlayFallbackCandidates.forEach { candidate ->
            val candidateAddonHost = CometProxyUrlResolver.hostOfAddonBaseUrl(candidate.addonBaseUrl)
            candidate.url?.let {
                CometProxyUrlResolver.prewarm(it, candidate.headers, candidateAddonHost)
            }
        }
    }

    private suspend fun buildDeterministicAutoPlayPlaybackInfo(
        request: ShadowRequestContext,
        organizedStreams: List<StreamCardModel>,
        manualBitrateCapMbps: Double?,
        deviceSnapshot: DeviceCapabilitySnapshot?,
        isFinalPass: Boolean
    ): StreamPlaybackInfo? {
        if (organizedStreams.isEmpty()) return null
        val languageFiltered = applyDeterministicOriginalLanguageGuard(
            originalLanguage = originalLanguage,
            streams = organizedStreams
        )
        val titleFiltered = applyDeterministicTitleGuard(
            contentName = contentName,
            streams = languageFiltered
        )
        val rejectedTitleCount = languageFiltered.size - titleFiltered.size
        if (rejectedTitleCount > 0) {
            Log.i(
                TAG,
                "DETERMINISTIC_TITLE_GUARD contentName=${contentName ?: "none"} " +
                    "in=${languageFiltered.size} out=${titleFiltered.size} " +
                    "rejected=$rejectedTitleCount"
            )
        }
        val eligibleStreams = titleFiltered
            .filterNot { it.hasBlockedDeterministicAutoplayFilename() }
        if (eligibleStreams.isEmpty()) return null

        val event = benchmarkAwareStreamScorer.scoreWithManualCap(
            request = request,
            streams = eligibleStreams,
            manualBitrateCap = manualBitrateCapMbps ?: return null,
            device = deviceSnapshot
        )
        if (autoPlayFirstScoringAtMs == 0L && event.winners.isNotEmpty()) {
            autoPlayFirstScoringAtMs = System.currentTimeMillis()
        }
        val earlyFinishFallbackElapsed = if (autoPlayFirstScoringAtMs > 0L) {
            System.currentTimeMillis() - autoPlayFirstScoringAtMs
        } else {
            0L
        }
        val earlyFinishFallbackReached = earlyFinishFallbackElapsed >= AUTOPLAY_EARLY_FINISH_FALLBACK_MS

        if (!isFinalPass && !earlyFinishFallbackReached && event.winners.size < AUTOPLAY_MIN_CANDIDATE_POOL) {
            val topScore = event.selected?.contentQualityScore ?: 0
            if (topScore < AUTOPLAY_MIN_QUALITY_SCORE) {
                return null
            }
        }
        val earlyFinishDecision = deterministicAutoplayEarlyFinishDecision(event.winners, request)
        if (!isFinalPass) {
            Log.i(
                TAG,
                "DETERMINISTIC_EARLY_FINISH triggered=${earlyFinishDecision.triggered} " +
                    "reason=${earlyFinishDecision.reason} count=${earlyFinishDecision.matchingCount} " +
                    "resolution=${earlyFinishDecision.resolution} releaseType=${earlyFinishDecision.releaseType} " +
                    "hasRemux=${earlyFinishDecision.hasRemux} " +
                    "fallbackElapsedMs=$earlyFinishFallbackElapsed fallbackReached=$earlyFinishFallbackReached"
            )
            Log.i(
                TAG,
                earlyFinishDecision.breakdown.toLogLine(
                    target = "${earlyFinishDecision.resolution}/${earlyFinishDecision.releaseType}"
                )
            )
            // Per-stream rows are noisy (often 100+ lines per evaluation, fired
            // multiple times as addon results stream in). Gate behind the
            // probe-profiling diagnostic flag so we only see them when actively
            // investigating.
            if (probeProfilingDiagnosticEnabled) {
                earlyFinishDecision.breakdown.perStream.forEach { row ->
                    Log.i(TAG, row.toLogLine())
                }
            }
        }
        if (!isFinalPass && !earlyFinishDecision.triggered && !earlyFinishFallbackReached) {
            return null
        }

        val selectedCandidate = selectDeterministicAutoplayCandidate(
            event = event,
            eligibleStreams = eligibleStreams,
            maxCandidates = DETERMINISTIC_AUTOPLAY_PREFLIGHT_CANDIDATE_LIMIT,
            isPlayable = { item ->
                val playable = AutoplayPlaybackUrlPreflight.isPlayable(item.stream.getStreamUrl())
                if (!playable) {
                    Log.i(
                        TAG,
                        "DETERMINISTIC_AUTOPLAY_PREFLIGHT_REJECTED " +
                            "stream=${item.stream.wrappedOriginalStreamKey ?: item.parsed.exactDuplicateKey} " +
                            "reason=stremthru_download_failed"
                    )
                }
                playable
            }
        ) ?: return null

        return buildStreamPlaybackInfo(
            item = selectedCandidate.selectedItem,
            fallbackCandidates = selectedCandidate.fallbackCandidateItems
        )
    }

    private fun buildStreamPlaybackInfo(
        item: StreamCardModel,
        fallbackCandidates: List<StreamCardModel> = emptyList()
    ): StreamPlaybackInfo {
        val stream = item.stream
        val selectedKey = stream.wrappedOriginalStreamKey ?: item.parsed.exactDuplicateKey
        val playbackInfo = StreamPlaybackInfo(
            url = stream.getStreamUrl(),
            title = _uiState.value.title,
            streamName = stream.name ?: stream.addonName,
            serviceKey = normalizedBenchmarkServiceKey(item.parsed.serviceId, stream.wrappedProviderId),
            year = year,
            isExternal = stream.isExternal(),
            isTorrent = stream.isTorrent(),
            infoHash = stream.infoHash,
            ytId = stream.ytId,
            headers = stream.behaviorHints?.proxyHeaders?.request,
            contentId = contentId ?: videoId.substringBefore(":"),
            contentType = contentType,
            contentName = contentName ?: title,
            originalLanguage = originalLanguage,
            poster = poster,
            backdrop = backdrop,
            logo = logo,
            videoId = videoId,
            season = season,
            episode = episode,
            episodeTitle = episodeName,
            bingeGroup = StreamBingeGroupResolver.resolve(stream),
            rememberedAudioLanguage = null,
            rememberedAudioName = null,
            filename = stream.behaviorHints?.filename,
            videoHash = stream.behaviorHints?.videoHash,
            videoSize = stream.behaviorHints?.videoSize,
            streamKey = selectedKey,
            resumePositionMs = resumePositionMs,
            resumeDurationMs = resumeDurationMs,
            resumeProgressPercent = resumeProgressPercent,
            resumeLastWatchedMs = resumeLastWatchedMs,
            resumeSource = resumeSource,
            isWebDl = item.parsed.quality.equals("WEB-DL", ignoreCase = true),
            isDolbyVisionCandidate = item.parsed.visualTags.any { tag ->
                val normalized = tag.lowercase()
                normalized == "dv" || normalized.contains("dolby vision") || normalized.contains("dovi")
            },
            addonBaseUrl = stream.addonBaseUrl,
            autoPlayFallbackCandidates = selectAutoplayFallbackCandidates(
                selectedKey = selectedKey,
                fallbackCandidates = fallbackCandidates
            )
                .map { candidate ->
                    AutoPlayStreamAlternative(
                        streamKey = candidate.stream.wrappedOriginalStreamKey ?: candidate.parsed.exactDuplicateKey,
                        url = candidate.stream.getStreamUrl(),
                        streamName = (candidate.stream.name ?: "").ifEmpty {
                            candidate.stream.addonName
                        },
                        headers = candidate.stream.behaviorHints?.proxyHeaders?.request,
                        filename = candidate.stream.behaviorHints?.filename,
                        videoHash = candidate.stream.behaviorHints?.videoHash,
                        videoSize = candidate.stream.behaviorHints?.videoSize,
                        isWebDl = candidate.parsed.quality.equals("WEB-DL", ignoreCase = true),
                        isDolbyVisionCandidate = candidate.parsed.visualTags.any { tag ->
                            val normalized = tag.lowercase()
                            normalized == "dv" || normalized.contains("dolby vision") || normalized.contains("dovi")
                        },
                        addonBaseUrl = candidate.stream.addonBaseUrl
                    )
                }
        )

        val url = playbackInfo.url
        if (!url.isNullOrBlank()) {
            viewModelScope.launch {
                streamLinkCacheDataStore.save(
                    contentKey = streamCacheKey,
                    url = url,
                    streamName = playbackInfo.streamName,
                    headers = playbackInfo.headers,
                    bingeGroup = playbackInfo.bingeGroup,
                    filename = playbackInfo.filename,
                    videoHash = playbackInfo.videoHash,
                    videoSize = playbackInfo.videoSize
                )
            }
        }

        return playbackInfo
    }

    override fun onCleared() {
        cancelActiveStreamSearch()
        super.onCleared()
    }

    private fun buildStreamRequestContext(): StreamRequestContext {
        return StreamRequestContext(
            contentType = contentType,
            title = title,
            year = year ?: _uiState.value.year,
            season = season,
            episode = episode,
            episodeTitle = episodeName,
            runtimeMinutes = runtime ?: _uiState.value.runtime,
            originalLanguage = originalLanguage
        )
    }

    private fun buildShadowRequestContext(requestId: String): ShadowRequestContext {
        return ShadowRequestContext(
            requestId = requestId,
            videoId = videoId,
            contentType = contentType,
            title = title,
            season = season,
            episode = episode,
            runtimeMinutes = runtime ?: _uiState.value.runtime
        )
    }

    private suspend fun updateShadowAutoPlayDecision(
        request: ShadowRequestContext,
        organizedStreams: List<com.nexio.tv.core.stream.StreamCardModel>,
        manualBitrateCapMbps: Double?,
        deviceSnapshot: DeviceCapabilitySnapshot?,
        isFinalPass: Boolean,
        allowEarlyFinishTerminal: Boolean
    ) {
        shadowAutoPlayReplayCoordinator.updateCandidates(
            request = request,
            organizedStreams = organizedStreams,
            manualBitrateCapMbps = manualBitrateCapMbps,
            deviceSnapshot = deviceSnapshot,
            isFinalPass = isFinalPass,
            allowEarlyFinishTerminal = allowEarlyFinishTerminal
        )
        emitShadowAutoPlayDecisionIfReady()
    }

    private suspend fun emitShadowAutoPlayDecisionIfReady() {
        val startedAtMs = System.currentTimeMillis()
        try {
            val event = withContext(Dispatchers.Default) {
                shadowAutoPlayReplayCoordinator.buildEventIfReady(
                    timingsMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
                )
            } ?: return
            shadowAutoPlayDecisionLogger.log(event)
            shadowAutoplayCollectionUploader.submitIfEnabled(event)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Shadow autoplay scoring failed: ${error.message}")
        }
    }

    private suspend fun replayShadowAutoPlayDecisionIfReady() {
        emitShadowAutoPlayDecisionIfReady()
    }

    private fun shouldInvalidateCachedDeterministicAutoPlayLink(
        cached: com.nexio.tv.data.local.CachedStreamLink
    ): Boolean {
        if (!_uiState.value.isDeterministicAutoplay) return false
        if (supportsDolbyVisionDisplay(context)) return false
        val parseTarget = cached.filename ?: cached.streamName
        if (parseTarget.isBlank()) return false
        val parsed = AioStrictFileParser.parse(parseTarget)
        val isWebDl = parsed.quality.equals("WEB-DL", ignoreCase = true)
        val isDolbyVision = parsed.visualTags.any { tag ->
            val normalized = tag.lowercase()
            normalized == "dv" || normalized.contains("dolby vision") || normalized.contains("dovi")
        }
        return isWebDl && isDolbyVision
    }

    private fun logPresentationDiagnostics(
        origin: String,
        organizedResult: com.nexio.tv.core.stream.OrganizedStreams
    ) {
        if (!streamDiagnosticsEnabled) return
        val d = organizedResult.diagnostics
        Log.d(
            TAG,
            "STREAM_DIAG presentation origin=$origin input=${d.inputCount} droppedEpisode=${d.droppedEpisodeMismatchCount} " +
                "droppedYear=${d.droppedMovieYearMismatchCount} droppedWebDv=${d.droppedWebDolbyVisionCount} " +
                "droppedDedupe=${d.droppedDeduplicateCount} mixedCacheClusters=${d.dedupeMixedCachedUncachedClusterCount} " +
                "cachedDroppedForUncachedClusters=${d.dedupeCachedDroppedForUncachedClusterCount} " +
                "droppedAddonFilter=${d.droppedAddonFilterCount} " +
                "presented=${d.finalPresentedCount}"
        )
    }
}

private data class OrganizedStreamPayload(
    val orderedAddonStreams: List<AddonStreams>,
    val allStreams: List<Stream>,
    val availableAddons: List<String>,
    val organizedStreams: com.nexio.tv.core.stream.OrganizedStreams,
    val selectedAutoPlayStream: Stream?,
    val autoPlayPlaybackInfo: StreamPlaybackInfo?,
    val deterministicFailureMessage: String? = null,
    val shadowDecisionFinalPass: Boolean = false,
    val shadowDecisionAllowEarlyFinish: Boolean = false
)

private data class PendingOrganizeRequest(
    val version: Long,
    val addonStreamGroups: List<AddonStreams>,
    val isFinalPass: Boolean = false
)

internal fun selectAutoplayFallbackCandidatesForTesting(
    selectedKey: String?,
    fallbackCandidates: List<StreamCardModel>,
    maxFallbackCandidates: Int = MAX_FALLBACK_CANDIDATES
): List<StreamCardModel> {
    return selectAutoplayFallbackCandidates(
        selectedKey = selectedKey,
        fallbackCandidates = fallbackCandidates,
        maxFallbackCandidates = maxFallbackCandidates
    )
}

private fun selectAutoplayFallbackCandidates(
    selectedKey: String?,
    fallbackCandidates: List<StreamCardModel>,
    maxFallbackCandidates: Int = MAX_FALLBACK_CANDIDATES
): List<StreamCardModel> {
    val candidates = fallbackCandidates.filter { candidate ->
        val candidateKey = candidate.stream.wrappedOriginalStreamKey ?: candidate.parsed.exactDuplicateKey
        candidateKey != selectedKey
    }
    val nonDv = candidates.filterNot { it.isDolbyVisionCandidateForAutoplay() }
    return (candidates.take(maxFallbackCandidates) + nonDv.take(1)).distinctBy {
        it.stream.wrappedOriginalStreamKey ?: it.parsed.exactDuplicateKey
    }
}

private fun StreamCardModel.isDolbyVisionCandidateForAutoplay(): Boolean {
    return parsed.visualTags.any { tag ->
        val normalized = tag.lowercase()
        normalized == "dv" || normalized.contains("dolby vision") || normalized.contains("dovi")
    }
}

internal class ShadowAutoPlayReplayCoordinator(
    private val scorer: BenchmarkAwareStreamScorer
) {
    private var latestRequest: ShadowRequestContext? = null
    private var latestStreams: List<StreamCardModel> = emptyList()
    private var latestManualBitrateCapMbps: Double? = null
    private var latestDeviceSnapshot: DeviceCapabilitySnapshot? = null
    private var finalPassObserved: Boolean = false
    private var allowEarlyFinishTerminal: Boolean = false
    private var emittedRequestId: String? = null

    fun updateCandidates(
        request: ShadowRequestContext,
        organizedStreams: List<StreamCardModel>,
        manualBitrateCapMbps: Double?,
        deviceSnapshot: DeviceCapabilitySnapshot?,
        isFinalPass: Boolean,
        allowEarlyFinishTerminal: Boolean
    ) {
        if (latestRequest?.requestId != request.requestId) {
            latestRequest = null
            latestStreams = emptyList()
            latestManualBitrateCapMbps = null
            latestDeviceSnapshot = null
            finalPassObserved = false
            this.allowEarlyFinishTerminal = false
            emittedRequestId = null
        }
        latestRequest = request
        latestStreams = organizedStreams
        latestManualBitrateCapMbps = manualBitrateCapMbps
        latestDeviceSnapshot = deviceSnapshot
        finalPassObserved = finalPassObserved || isFinalPass
        this.allowEarlyFinishTerminal = this.allowEarlyFinishTerminal || allowEarlyFinishTerminal
    }

    fun buildEventIfReady(
        timingsMs: Long? = null
    ): com.nexio.tv.data.repository.benchmark.ShadowAutoPlayDecisionEvent? {
        val request = latestRequest ?: return null
        if (latestStreams.isEmpty()) return null
        if (emittedRequestId == request.requestId) return null
        val event = scorer.scoreWithManualCap(
            request = request,
            streams = latestStreams,
            manualBitrateCap = latestManualBitrateCapMbps ?: return null,
            elapsedMs = timingsMs,
            device = latestDeviceSnapshot
        )
        val earlyFinishDecision = deterministicAutoplayEarlyFinishDecision(event.winners, request)
        val terminalDecisionReached = finalPassObserved ||
            (allowEarlyFinishTerminal && earlyFinishDecision.triggered)
        if (!terminalDecisionReached) return null
        emittedRequestId = request.requestId
        return event
    }

    fun clear() {
        latestRequest = null
        latestStreams = emptyList()
        latestManualBitrateCapMbps = null
        latestDeviceSnapshot = null
        finalPassObserved = false
        allowEarlyFinishTerminal = false
        emittedRequestId = null
    }

    fun hasCandidates(): Boolean {
        return latestRequest != null &&
            latestStreams.isNotEmpty() &&
            emittedRequestId != latestRequest?.requestId
    }
}

internal data class DeterministicAutoplayCandidateSelection(
    val selectedItem: StreamCardModel,
    val fallbackCandidateItems: List<StreamCardModel>
)

internal suspend fun selectDeterministicAutoplayCandidate(
    event: ShadowAutoPlayDecisionEvent,
    eligibleStreams: List<StreamCardModel>,
    maxCandidates: Int = DETERMINISTIC_AUTOPLAY_PREFLIGHT_CANDIDATE_LIMIT,
    isPlayable: suspend (StreamCardModel) -> Boolean
): DeterministicAutoplayCandidateSelection? {
    val allowedCandidates = event.winners
        .mapNotNull { decision ->
            val item = eligibleStreams.firstOrNull { item ->
                item.matchesShadowStreamKey(decision.streamKey)
            } ?: return@mapNotNull null

            item.takeUnless { it.hasBlockedDeterministicAutoplayFilename(decision) }
        }
    val candidates = allowedCandidates.take(maxCandidates.coerceAtLeast(1))

    for (candidate in candidates) {
        if (!isPlayable(candidate)) continue
        return DeterministicAutoplayCandidateSelection(
            selectedItem = candidate,
            fallbackCandidateItems = allowedCandidates
        )
    }

    return null
}

private fun StreamCardModel.matchesShadowStreamKey(streamKey: String?): Boolean {
    if (streamKey.isNullOrBlank()) return false
    if (stream.wrappedOriginalStreamKey == streamKey) return true
    if (parsed.exactDuplicateKey == streamKey) return true
    val serviceId = parsed.serviceId
    if (serviceId != null && streamKey == "${parsed.exactDuplicateKey}|$serviceId") return true
    return false
}

private fun StreamCardModel.hasBlockedDeterministicAutoplayFilename(
    decision: ShadowStreamDecision
): Boolean {
    if (hasBlockedDeterministicAutoplayFilename()) return true

    return decision.parsed.filename
        ?.contains(DETERMINISTIC_AUTOPLAY_BLOCKED_RELEASE_MARKER, ignoreCase = true) == true
}

private fun StreamCardModel.hasBlockedDeterministicAutoplayFilename(): Boolean {
    return listOf(
        parsed.filename,
        stream.behaviorHints?.filename
    ).any { filename ->
        filename?.contains(DETERMINISTIC_AUTOPLAY_BLOCKED_RELEASE_MARKER, ignoreCase = true) == true
    }
}

private object AutoplayPlaybackUrlPreflight {
    suspend fun isPlayable(url: String?): Boolean {
        val trimmed = url?.trim()?.takeIf { it.isNotBlank() } ?: return false
        if (!isStremThruUrl(trimmed)) return true
        return withContext(Dispatchers.IO) {
            runCatching { !redirectsToDownloadFailedAsset(trimmed) }
                .getOrElse { true }
        }
    }

    private fun isStremThruUrl(url: String): Boolean {
        return runCatching {
            URL(url).host.orEmpty().lowercase(Locale.US).contains("stremthru")
        }.getOrDefault(false)
    }

    private fun redirectsToDownloadFailedAsset(url: String): Boolean {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = "HEAD"
            connectTimeout = AUTOPLAY_PREFLIGHT_TIMEOUT_MS
            readTimeout = AUTOPLAY_PREFLIGHT_TIMEOUT_MS
        }
        return try {
            val code = connection.responseCode
            if (code !in 300..399) return false
            isStremThruDownloadFailedRedirectLocation(connection.getHeaderField("Location"))
        } finally {
            connection.disconnect()
        }
    }
}

internal fun isStremThruDownloadFailedRedirectLocation(location: String?): Boolean {
    return location
        ?.lowercase(Locale.US)
        ?.contains("/static/download_failed.mp4") == true
}

private fun PlayerSettings.toStreamFeatureFlags(): StreamFeatureFlags {
    return StreamFeatureFlags(
        uniformFormattingEnabled = uniformStreamFormattingEnabled,
        uniformFormattingTemplate = syncedFormatterTemplate.toAioFormatterSelection(),
        groupAcrossAddonsEnabled = true,
        deduplicateGroupedStreamsEnabled = deduplicateGroupedStreamsEnabled,
        filterWebDolbyVisionStreamsEnabled = filterWebDolbyVisionStreamsEnabled,
        filterEpisodeMismatchStreamsEnabled = filterEpisodeMismatchStreamsEnabled,
        filterMovieYearMismatchStreamsEnabled = filterMovieYearMismatchStreamsEnabled
    )
}

internal fun deterministicAutoplayEarlyFinishSatisfied(
    winners: List<com.nexio.tv.data.repository.benchmark.ShadowStreamDecision>,
    request: ShadowRequestContext
): Boolean {
    return deterministicAutoplayEarlyFinishDecision(winners, request).triggered
}

internal data class DeterministicEarlyFinishDecision(
    val triggered: Boolean,
    val reason: String,
    val matchingCount: Int,
    val resolution: String,
    val releaseType: String,
    val hasRemux: Boolean,
    val breakdown: DeterministicEarlyFinishBreakdown = DeterministicEarlyFinishBreakdown.EMPTY
)

internal data class DeterministicEarlyFinishBreakdown(
    val rawWinners: Int,
    val countedWinners: Int,
    val resolutionBuckets: Map<String, Int>,
    val releaseTypeBuckets: Map<String, Int>,
    val bitrateBuckets: Map<String, Int>,
    val zeroBitrate: Int,
    val zeroBitrateMissingSize: Int,
    val zeroBitrateMissingDuration: Int,
    val perStream: List<DeterministicEarlyFinishStreamRow>
) {
    fun toLogLine(target: String): String {
        return "EARLY_FINISH_COUNT_BREAKDOWN target=$target " +
            "raw=$rawWinners counted=$countedWinners " +
            "resolution=${resolutionBuckets.entries.joinToString(",", "{", "}") { "${it.key}:${it.value}" }} " +
            "releaseType=${releaseTypeBuckets.entries.joinToString(",", "{", "}") { "${it.key}:${it.value}" }} " +
            "bitrate=${bitrateBuckets.entries.joinToString(",", "{", "}") { "${it.key}:${it.value}" }} " +
            "zeroBitrate=$zeroBitrate (missingSize=$zeroBitrateMissingSize missingDuration=$zeroBitrateMissingDuration)"
    }

    companion object {
        val EMPTY = DeterministicEarlyFinishBreakdown(
            rawWinners = 0,
            countedWinners = 0,
            resolutionBuckets = emptyMap(),
            releaseTypeBuckets = emptyMap(),
            bitrateBuckets = emptyMap(),
            zeroBitrate = 0,
            zeroBitrateMissingSize = 0,
            zeroBitrateMissingDuration = 0,
            perStream = emptyList()
        )
    }
}

internal data class DeterministicEarlyFinishStreamRow(
    val streamKey: String,
    val provider: String,
    val filename: String?,
    val sizeBytes: Long?,
    val durationMs: Long?,
    val runtimeSource: String?,
    val resolution: String?,
    val releaseType: String,
    val bitrateMbps: Double
) {
    fun toLogLine(): String {
        return "EARLY_FINISH_STREAM provider=$provider " +
            "resolution=${resolution ?: "none"} releaseType=$releaseType bitrate=${"%.2f".format(bitrateMbps)} " +
            "sizeBytes=${sizeBytes ?: "null"} durationMs=${durationMs ?: "null"} " +
            "runtimeSource=${runtimeSource ?: "none"} filename=${filename ?: "none"}"
    }
}

internal fun deterministicAutoplayEarlyFinishDecision(
    winners: List<com.nexio.tv.data.repository.benchmark.ShadowStreamDecision>,
    request: ShadowRequestContext
): DeterministicEarlyFinishDecision {
    val countedWinners = winners.distinctBy(::deterministicEarlyFinishCountKey)
    val breakdown = buildDeterministicEarlyFinishBreakdown(rawWinners = winners.size, countedWinners = countedWinners)

    if (countedWinners.isEmpty()) {
        return DeterministicEarlyFinishDecision(
            triggered = false,
            reason = "no_winners",
            matchingCount = 0,
            resolution = "none",
            releaseType = "none",
            hasRemux = false,
            breakdown = breakdown
        )
    }
    val has4k = countedWinners.any { it.resolution.equals("2160p", ignoreCase = true) }
    val hasRemux = countedWinners.any { it.breakdown.releaseType == "remux" }
    val movie = request.contentType.equals("movie", ignoreCase = true)

    fun countMatching(
        resolution: String,
        releaseType: String,
        minMbps: Double
    ): Int = countedWinners.count { candidate ->
        candidate.resolution.equals(resolution, ignoreCase = true) &&
            candidate.breakdown.releaseType == releaseType &&
            candidate.breakdown.averageBitrateMbps >= minMbps
    }

    fun tryResolution(targetResolution: String, is4k: Boolean): DeterministicEarlyFinishDecision? {
        val remuxThreshold = when {
            is4k && movie -> 45.0
            is4k && !movie -> 30.0
            !is4k && movie -> 25.0
            else -> 18.0
        }
        val webdlThreshold = when {
            is4k && movie -> 15.0
            is4k && !movie -> 10.0
            !is4k && movie -> 10.0
            else -> 6.0
        }

        val remuxCount = countMatching(targetResolution, "remux", remuxThreshold)
        if (remuxCount >= 3) {
            return DeterministicEarlyFinishDecision(
                triggered = true,
                reason = "threshold_met",
                matchingCount = remuxCount,
                resolution = targetResolution,
                releaseType = "remux",
                hasRemux = hasRemux,
                breakdown = breakdown
            )
        }
        val localHasRemux = countedWinners.any {
            it.resolution.equals(targetResolution, ignoreCase = true) &&
                it.breakdown.releaseType == "remux"
        }
        val webdlCount = countMatching(targetResolution, "webdl", webdlThreshold)
        if (!localHasRemux && webdlCount >= 3) {
            return DeterministicEarlyFinishDecision(
                triggered = true,
                reason = "threshold_met",
                matchingCount = webdlCount,
                resolution = targetResolution,
                releaseType = "webdl",
                hasRemux = hasRemux,
                breakdown = breakdown
            )
        }
        return null
    }

    if (has4k) {
        tryResolution("2160p", is4k = true)?.let { return it }
    }
    tryResolution("1080p", is4k = false)?.let { return it }

    val resolution = if (has4k) "2160p" else "1080p"
    return DeterministicEarlyFinishDecision(
        triggered = false,
        reason = if (hasRemux) "insufficient_remux_count" else "insufficient_count",
        matchingCount = 0,
        resolution = resolution,
        releaseType = if (hasRemux) "remux" else "webdl",
        hasRemux = hasRemux,
        breakdown = breakdown
    )
}

private fun buildDeterministicEarlyFinishBreakdown(
    rawWinners: Int,
    countedWinners: List<com.nexio.tv.data.repository.benchmark.ShadowStreamDecision>
): DeterministicEarlyFinishBreakdown {
    val resolutionBuckets = mutableMapOf<String, Int>()
    val releaseTypeBuckets = mutableMapOf<String, Int>()
    val bitrateBuckets = linkedMapOf(
        "zero" to 0,
        "lt6" to 0,
        "6to10" to 0,
        "10to18" to 0,
        "18plus" to 0
    )
    var zeroBitrate = 0
    var zeroBitrateMissingSize = 0
    var zeroBitrateMissingDuration = 0
    val perStream = ArrayList<DeterministicEarlyFinishStreamRow>(countedWinners.size)

    countedWinners.forEach { decision ->
        val resKey = decision.resolution?.lowercase(Locale.US) ?: "none"
        resolutionBuckets[resKey] = (resolutionBuckets[resKey] ?: 0) + 1

        val rtKey = decision.breakdown.releaseType.lowercase(Locale.US).ifBlank { "unknown" }
        releaseTypeBuckets[rtKey] = (releaseTypeBuckets[rtKey] ?: 0) + 1

        val mbps = decision.breakdown.averageBitrateMbps
        val bucket = when {
            mbps <= 0.0 -> "zero"
            mbps < 6.0 -> "lt6"
            mbps < 10.0 -> "6to10"
            mbps < 18.0 -> "10to18"
            else -> "18plus"
        }
        bitrateBuckets[bucket] = (bitrateBuckets[bucket] ?: 0) + 1
        if (mbps <= 0.0) {
            zeroBitrate += 1
            if (decision.parsed.sizeBytes == null) zeroBitrateMissingSize += 1
            if (decision.parsed.durationMs == null) zeroBitrateMissingDuration += 1
        }

        perStream += DeterministicEarlyFinishStreamRow(
            streamKey = decision.streamKey,
            provider = decision.provider.storageKey,
            filename = decision.parsed.filename,
            sizeBytes = decision.parsed.sizeBytes,
            durationMs = decision.parsed.durationMs,
            runtimeSource = decision.parsed.runtimeSource,
            resolution = decision.resolution,
            releaseType = rtKey,
            bitrateMbps = mbps
        )
    }

    return DeterministicEarlyFinishBreakdown(
        rawWinners = rawWinners,
        countedWinners = countedWinners.size,
        resolutionBuckets = resolutionBuckets,
        releaseTypeBuckets = releaseTypeBuckets,
        bitrateBuckets = bitrateBuckets,
        zeroBitrate = zeroBitrate,
        zeroBitrateMissingSize = zeroBitrateMissingSize,
        zeroBitrateMissingDuration = zeroBitrateMissingDuration,
        perStream = perStream
    )
}

private fun deterministicEarlyFinishCountKey(
    decision: com.nexio.tv.data.repository.benchmark.ShadowStreamDecision
): String {
    val providerKey = decision.provider.storageKey
    val familyKey = decision.parsed.filename
        ?.trim()
        ?.lowercase(Locale.US)
        ?.replace(Regex("""\.(mkv|mp4|avi|mov|wmv|flv|webm|m4v|ts|m2ts)$"""), "")
        ?.replace(Regex("""[^\p{L}\p{N}]+"""), "")
        ?.takeIf { it.isNotBlank() }
        ?: decision.streamKey
            .trim()
            .lowercase(Locale.US)
            .replace(Regex("""[^\p{L}\p{N}]+"""), "")
    return "$providerKey::$familyKey"
}

private fun SyncedFormatterTemplateSettings.toAioFormatterSelection(): AioFormatterSelection {
    return AioFormatterSelection(
        selectedTemplateId = selectedTemplateId,
        customTemplate = if (!customNameTemplate.isNullOrBlank() && !customDescriptionTemplate.isNullOrBlank()) {
            AioCustomTemplateSelection(
                label = customTemplateLabel,
                nameTemplate = customNameTemplate,
                descriptionTemplate = customDescriptionTemplate,
                badgeRowTemplate = customBadgeRowTemplate
            )
        } else {
            null
        }
    )
}

internal fun applyDeterministicOriginalLanguageGuard(
    originalLanguage: String?,
    streams: List<StreamCardModel>
): List<StreamCardModel> {
    if (originalLanguage.isNullOrBlank()) return streams
    return streams.filterNot { stream ->
        shouldRejectDeterministicAutoplayForOriginalLanguage(
            originalLanguage = originalLanguage,
            parsedLanguages = stream.parsed.languages
        )
    }
}

/**
 * Reject deterministic-autoplay candidates whose parsed filename title doesn't
 * look like the requested content. Defends against upstream cross-show
 * contamination — addons occasionally label one show's torrent under another
 * show's media id (observed: Comet returning Dune Prophecy torrents under
 * One Piece's tt0388629), which historically leaked all the way through to
 * playback because no layer compared the candidate's title to the requested
 * title before scoring.
 *
 * Match rule is bi-directional token-subset on alphanumeric tokens: accept
 * when the requested title's tokens are a subset of the parsed title's tokens
 * (parsed has extra year/edition tokens) OR vice versa (requested has extra
 * year tokens). No-op when either side is missing — manual selection or
 * unparseable filenames must not be filtered.
 */
internal fun applyDeterministicTitleGuard(
    contentName: String?,
    streams: List<StreamCardModel>
): List<StreamCardModel> {
    if (contentName.isNullOrBlank()) return streams
    return streams.filterNot { stream ->
        shouldRejectDeterministicAutoplayForTitle(
            contentName = contentName,
            parsedTitle = stream.parsed.title
        )
    }
}

internal fun shouldRejectDeterministicAutoplayForTitle(
    contentName: String?,
    parsedTitle: String?
): Boolean {
    if (contentName.isNullOrBlank()) return false
    if (parsedTitle.isNullOrBlank()) return false
    val expected = titleTokens(contentName)
    val candidate = titleTokens(parsedTitle)
    if (expected.isEmpty() || candidate.isEmpty()) return false
    val expectedIsSubset = candidate.containsAll(expected)
    val candidateIsSubset = expected.containsAll(candidate)
    return !(expectedIsSubset || candidateIsSubset)
}

private fun titleTokens(value: String): Set<String> {
    return value
        .lowercase(Locale.US)
        // Strip elision apostrophes so "Marvel's" tokenizes as one word, not
        // two ("marvel" + "s"). Other intra-word marks (hyphens, periods) are
        // legitimate separators and stay split.
        .replace("'", "")
        .replace("’", "")
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotBlank() }
        .toSet()
}

internal fun shouldRejectDeterministicAutoplayForOriginalLanguage(
    originalLanguage: String?,
    parsedLanguages: List<String>
): Boolean {
    if (originalLanguage.isNullOrBlank()) return false
    if (parsedLanguages.isEmpty()) return false
    if (parsedLanguages.any { normalizeLanguageMatchToken(it) == "multi" }) return false

    val expectedTokens = buildOriginalLanguageMatchTokens(originalLanguage)
    if (expectedTokens.isEmpty()) return false

    val parsedTokens = parsedLanguages
        .map(::normalizeLanguageMatchToken)
        .filter { it.isNotBlank() }
    if (parsedTokens.isEmpty()) return false

    return parsedTokens.none { it in expectedTokens }
}

private fun buildOriginalLanguageMatchTokens(originalLanguage: String): Set<String> {
    val trimmed = originalLanguage.trim()
    if (trimmed.isBlank()) return emptySet()

    val locale = Locale.forLanguageTag(trimmed)
    val displayLanguage = runCatching { locale.getDisplayLanguage(Locale.ENGLISH) }.getOrNull()
    return buildSet {
        add(normalizeLanguageMatchToken(trimmed))
        displayLanguage?.let { add(normalizeLanguageMatchToken(it)) }
    }.filter { it.isNotBlank() }.toSet()
}

private fun normalizeLanguageMatchToken(value: String): String {
    return value
        .trim()
        .lowercase(Locale.US)
        .replace(Regex("[^a-z]"), "")
}


data class StreamPlaybackInfo(
    val url: String?,
    val title: String,
    val streamName: String,
    val serviceKey: String? = null,
    val playerBackend: PlayerPreference = PlayerPreference.INTERNAL,
    val year: String?,
    val isExternal: Boolean,
    val isTorrent: Boolean,
    val infoHash: String?,
    val ytId: String?,
    val headers: Map<String, String>?,
    // Watch progress metadata
    val contentId: String?,
    val contentType: String?,
    val contentName: String?,
    val originalLanguage: String?,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String?,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val bingeGroup: String?,
    val rememberedAudioLanguage: String?,
    val rememberedAudioName: String?,
    val filename: String? = null,
    val videoHash: String? = null,
    val videoSize: Long? = null,
    val streamKey: String? = null,
    val resumePositionMs: Long? = null,
    val resumeDurationMs: Long? = null,
    val resumeProgressPercent: Float? = null,
    val resumeLastWatchedMs: Long? = null,
    val resumeSource: String? = null,
    val isWebDl: Boolean = false,
    val isDolbyVisionCandidate: Boolean = false,
    val addonBaseUrl: String? = null,
    val autoPlayFallbackCandidates: List<AutoPlayStreamAlternative> = emptyList()
)

data class AutoPlayStreamAlternative(
    val streamKey: String?,
    val url: String?,
    val streamName: String,
    val headers: Map<String, String>?,
    val filename: String? = null,
    val videoHash: String? = null,
    val videoSize: Long? = null,
    val isWebDl: Boolean = false,
    val isDolbyVisionCandidate: Boolean = false,
    val addonBaseUrl: String? = null
)
