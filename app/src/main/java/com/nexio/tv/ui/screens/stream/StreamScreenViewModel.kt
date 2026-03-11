package com.nexio.tv.ui.screens.stream

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.R
import com.nexio.tv.core.stream.StreamFeatureFlags
import com.nexio.tv.core.stream.StreamPresentationEngine
import com.nexio.tv.core.stream.StreamRequestContext
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.core.player.StreamAutoPlaySelector
import com.nexio.tv.data.local.PlayerPreference
import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.StreamAutoPlayMode
import com.nexio.tv.data.local.StreamLinkCacheDataStore
import com.nexio.tv.data.local.DebugSettingsDataStore
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

private const val TAG = "StreamScreenViewModel"
private const val EMBEDDED_STREAM_GROUP_NAME = "Embedded Streams"
private const val EMBEDDED_STREAM_FALLBACK_NAME = "Embed Stream"

@HiltViewModel
class StreamScreenViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val streamRepository: StreamRepository,
    private val addonRepository: AddonRepository,
    private val metaRepository: MetaRepository,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val streamLinkCacheDataStore: StreamLinkCacheDataStore,
    private val debugSettingsDataStore: DebugSettingsDataStore,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private var autoPlayHandledForSession = false
    private var directAutoPlayModeInitializedForSession = false
    private var directAutoPlayFlowEnabledForSession = false
    private var streamLoadJob: Job? = null
    private var sourceChipErrorDismissJob: Job? = null
    private var activeStreamSearchRequestId: String? = null
    private var streamFeatureFlags: StreamFeatureFlags = StreamFeatureFlags()
    private var streamDiagnosticsEnabled: Boolean = false

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
    private val genres: String? = savedStateHandle.getOptionalString("genres")
    private val year: String? = savedStateHandle.getOptionalString("year")
    private val contentId: String? = savedStateHandle.getOptionalString("contentId")
    private val contentName: String? = savedStateHandle.getOptionalString("contentName")
    private val manualSelection: Boolean = savedStateHandle.get<String>("manualSelection")
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
            year = year
        )
    )
    val uiState: StateFlow<StreamScreenUiState> = _uiState.asStateFlow()

    val playerPreference = playerSettingsDataStore.playerSettings
        .map { it.playerPreference }
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
                    directAutoPlayMessage = null
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
    }

    private fun shouldUseDirectAutoPlayFlow(
        playerPreference: PlayerPreference,
        streamAutoPlayMode: StreamAutoPlayMode
    ): Boolean {
        return playerPreference == PlayerPreference.INTERNAL &&
            streamAutoPlayMode != StreamAutoPlayMode.MANUAL
    }

    private fun loadStreams() {
        cancelActiveStreamSearch()
        val requestId = UUID.randomUUID().toString()
        activeStreamSearchRequestId = requestId
        streamLoadJob = viewModelScope.launch {
            try {
                val playerSettings = playerSettingsDataStore.playerSettings.first()
                streamFeatureFlags = playerSettings.toStreamFeatureFlags()
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
                            directAutoPlayMessage = context.getString(R.string.stream_finding_source)
                        )
                    }
                }

                if (!autoPlayHandledForSession && playerSettings.streamReuseLastLinkEnabled) {
                    val cached = streamLinkCacheDataStore.getValid(
                        contentKey = streamCacheKey,
                        maxAgeMs = playerSettings.streamReuseLastLinkCacheHours * 60L * 60L * 1000L
                    )
                    if (cached != null) {
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
                                poster = poster,
                                backdrop = backdrop,
                                logo = logo,
                                videoId = videoId,
                                season = season,
                                episode = episode,
                                episodeTitle = episodeName,
                                bingeGroup = null,
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

                updateUiStateIfChanged {
                    it.copy(
                        isLoading = true,
                        error = null,
                        showAddonFilters = !streamFeatureFlags.groupAcrossAddonsEnabled,
                        showDirectAutoPlayOverlay = if (directFlowActive) true else it.showDirectAutoPlayOverlay
                    )
                }

                val installedAddons = addonRepository.getInstalledAddons().first()
                val installedAddonOrder = installedAddons.map { it.displayName }

            suspend fun applySuccess(addonStreamGroups: List<AddonStreams>) {
                val selectedFilter = _uiState.value.selectedAddonFilter
                val organizedResult = withContext(Dispatchers.Default) {
                    val orderedAddonStreams = StreamAutoPlaySelector.orderAddonStreams(
                        addonStreamGroups,
                        installedAddonOrder
                    )
                    val allStreams = orderedAddonStreams.flatMap { it.streams }
                    val availableAddons = orderedAddonStreams.map { it.addonName }
                    val selectedAutoPlayStream = if (autoPlayHandledForSession) {
                        null
                    } else {
                        StreamAutoPlaySelector.selectAutoPlayStream(
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
                        requestContext = buildStreamRequestContext()
                    )
                    OrganizedStreamPayload(
                        orderedAddonStreams = orderedAddonStreams,
                        allStreams = allStreams,
                        availableAddons = availableAddons,
                        organizedStreams = organizedStreams,
                        selectedAutoPlayStream = selectedAutoPlayStream
                    )
                }
                logPresentationDiagnostics(
                    origin = "stream_screen",
                    organizedResult = organizedResult.organizedStreams
                )

                val selectedAutoPlayStream = organizedResult.selectedAutoPlayStream
                if (selectedAutoPlayStream != null) {
                    resolvedAutoPlayTarget = true
                }

                updateUiStateIfChanged {
                    it.copy(
                        isLoading = false,
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
                        error = null,
                        showDirectAutoPlayOverlay = if (directAutoPlayFlowEnabledForSession) {
                            true
                        } else {
                            false
                        }
                    )
                }
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
                        }
                        return@launch
                    }
                }

                updateSourceChipsForFetchStart(installedAddons)

                streamRepository.getStreamsFromAllAddons(
                    type = contentType,
                    videoId = videoId,
                    season = season,
                    episode = episode,
                    requestOrigin = "stream_screen",
                    requestId = requestId
                ).collectLatest { result ->
                    when (result) {
                        is NetworkResult.Success -> {
                            applySuccess(result.data)
                        }
                        is NetworkResult.Error -> {
                            if (directAutoPlayFlowEnabledForSession) {
                                directAutoPlayFlowEnabledForSession = false
                            }
                            updateUiStateIfChanged {
                                it.copy(
                                    isLoading = false,
                                    error = result.message,
                                    isDirectAutoPlayFlow = false,
                                    showDirectAutoPlayOverlay = false,
                                    directAutoPlayMessage = null
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

                markRemainingSourceChipsAsError()

                if (directAutoPlayFlowEnabledForSession && !resolvedAutoPlayTarget) {
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
        if (season != null && episode != null) {
            return meta.videos.firstOrNull { it.season == season && it.episode == episode }?.runtime
        }
        return meta.runtime
            ?.let { Regex("(\\d+)").find(it)?.groupValues?.getOrNull(1) }
            ?.toIntOrNull()
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
                    requestContext = buildStreamRequestContext()
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
    fun getStreamForPlayback(stream: Stream): StreamPlaybackInfo {
        val playbackInfo = StreamPlaybackInfo(
            url = stream.getStreamUrl(),
            title = _uiState.value.title,
            streamName = stream.name ?: stream.addonName,
            year = year,
            isExternal = stream.isExternal(),
            isTorrent = stream.isTorrent(),
            infoHash = stream.infoHash,
            ytId = stream.ytId,
            headers = stream.behaviorHints?.proxyHeaders?.request,
            contentId = contentId ?: videoId.substringBefore(":"),  // Use explicit contentId or extract from videoId
            contentType = contentType,
            contentName = contentName ?: title,
            poster = poster,
            backdrop = backdrop,
            logo = logo,
            videoId = videoId,
            season = season,
            episode = episode,
            episodeTitle = episodeName,
            bingeGroup = stream.behaviorHints?.bingeGroup,
            rememberedAudioLanguage = null,
            rememberedAudioName = null,
            filename = stream.behaviorHints?.filename,
            videoHash = stream.behaviorHints?.videoHash,
            videoSize = stream.behaviorHints?.videoSize
        )

        val url = playbackInfo.url
        if (!url.isNullOrBlank()) {
            viewModelScope.launch {
                streamLinkCacheDataStore.save(
                    contentKey = streamCacheKey,
                    url = url,
                    streamName = playbackInfo.streamName,
                    headers = playbackInfo.headers,
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
            episodeTitle = episodeName
        )
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
    val selectedAutoPlayStream: Stream?
)

private fun PlayerSettings.toStreamFeatureFlags(): StreamFeatureFlags {
    return StreamFeatureFlags(
        uniformFormattingEnabled = uniformStreamFormattingEnabled,
        groupAcrossAddonsEnabled = groupStreamsAcrossAddonsEnabled,
        deduplicateGroupedStreamsEnabled = deduplicateGroupedStreamsEnabled,
        filterWebDolbyVisionStreamsEnabled = filterWebDolbyVisionStreamsEnabled,
        filterEpisodeMismatchStreamsEnabled = filterEpisodeMismatchStreamsEnabled,
        filterMovieYearMismatchStreamsEnabled = filterMovieYearMismatchStreamsEnabled
    )
}


data class StreamPlaybackInfo(
    val url: String?,
    val title: String,
    val streamName: String,
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
    val videoSize: Long? = null
)
