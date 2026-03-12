package com.nexio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.nexio.tv.core.stream.StreamFeatureFlags
import com.nexio.tv.core.stream.StreamPresentationEngine
import com.nexio.tv.core.stream.StreamRequestContext
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.core.player.StreamAutoPlaySelector
import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.StreamAutoPlayMode
import com.nexio.tv.data.local.StreamAutoPlaySource
import com.nexio.tv.domain.model.Stream
import com.nexio.tv.domain.model.Video
import com.nexio.tv.ui.components.SourceChipItem
import com.nexio.tv.ui.components.SourceChipStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun PlayerRuntimeController.showEpisodesPanel() {
    _uiState.update {
        it.copy(
            showEpisodesPanel = true,
            showControls = true,
            showAudioDialog = false,
            showSubtitleDialog = false,
            showSpeedDialog = false,
            showMoreDialog = false
        )
    }

    
    val desiredSeason = currentSeason ?: _uiState.value.episodesSelectedSeason
    if (_uiState.value.episodesAll.isNotEmpty() && desiredSeason != null) {
        selectEpisodesSeason(desiredSeason)
    } else {
        loadEpisodesIfNeeded()
    }
}

internal fun PlayerRuntimeController.showSourcesPanel() {
    _uiState.update {
        it.copy(
            showSourcesPanel = true,
            showControls = true,
            showAudioDialog = false,
            showSubtitleDialog = false,
            showSpeedDialog = false,
            showMoreDialog = false,
            showEpisodesPanel = false,
            showEpisodeStreams = false
        )
    }
    loadSourceStreams(forceRefresh = false)
}

internal fun PlayerRuntimeController.buildSourceRequestKey(type: String, videoId: String, season: Int?, episode: Int?): String {
    return "$type|$videoId|${season ?: -1}|${episode ?: -1}"
}

internal fun PlayerRuntimeController.loadSourceStreams(forceRefresh: Boolean) {
    val type: String
    val vid: String
    val seasonArg: Int?
    val episodeArg: Int?

    if (contentType in listOf("series", "tv") && currentSeason != null && currentEpisode != null) {
        type = contentType ?: return
        vid = currentVideoId ?: contentId ?: return
        seasonArg = currentSeason
        episodeArg = currentEpisode
    } else {
        type = contentType ?: "movie"
        vid = contentId ?: return
        seasonArg = null
        episodeArg = null
    }

    val requestKey = buildSourceRequestKey(type = type, videoId = vid, season = seasonArg, episode = episodeArg)
    val state = _uiState.value
    val hasCachedPayload = state.sourceAllStreams.isNotEmpty() || state.sourceStreamsError != null
    if (!forceRefresh && requestKey == sourceStreamsCacheRequestKey && hasCachedPayload) {
        return
    }
    if (!forceRefresh && state.isLoadingSourceStreams && requestKey == sourceStreamsCacheRequestKey) {
        return
    }

    val targetChanged = requestKey != sourceStreamsCacheRequestKey
    sourceStreamsJob?.cancel()
    sourceChipErrorDismissJob?.cancel()
    sourceStreamsJob = scope.launch {
        sourceStreamsCacheRequestKey = requestKey
        _uiState.update {
            it.copy(
                isLoadingSourceStreams = true,
                sourceStreamsError = null,
                sourceAllStreams = if (forceRefresh || targetChanged) emptyList() else it.sourceAllStreams,
                sourceSelectedAddonFilter = if (forceRefresh || targetChanged) null else it.sourceSelectedAddonFilter,
                sourceFilteredStreams = if (forceRefresh || targetChanged) emptyList() else it.sourceFilteredStreams,
                sourcePresentedStreams = if (forceRefresh || targetChanged) emptyList() else it.sourcePresentedStreams,
                sourceAvailableAddons = if (forceRefresh || targetChanged) emptyList() else it.sourceAvailableAddons,
                sourceChips = if (forceRefresh || targetChanged) emptyList() else it.sourceChips,
                showSourceAddonFilters = !sourceStreamFeatureFlags.groupAcrossAddonsEnabled && it.showSourceAddonFilters
            )
        }

        val installedAddons = addonRepository.getInstalledAddons().first()
        val installedAddonOrder = installedAddons.map { it.displayName }
        sourceStreamFeatureFlags = playerSettingsDataStore.playerSettings.first().toStreamFeatureFlags()
        updateSourceChipsForFetchStart(type, installedAddons)

        streamRepository.getStreamsFromAllAddons(
            type = type,
            videoId = vid,
            season = seasonArg,
            episode = episodeArg,
            installedAddons = installedAddons,
            requestOrigin = "player_sources"
        ).collect { result ->
            when (result) {
                is NetworkResult.Success -> {
                    val addonStreams = StreamAutoPlaySelector.orderAddonStreams(result.data, installedAddonOrder)
                    val allStreams = addonStreams.flatMap { it.streams }
                    val availableAddons = addonStreams.map { it.addonName }
                    val selectedFilter = _uiState.value.sourceSelectedAddonFilter
                    val organizedStreams = withContext(Dispatchers.Default) {
                        StreamPresentationEngine.organize(
                            streams = allStreams,
                            availableAddons = availableAddons,
                            selectedAddonFilter = selectedFilter,
                            flags = sourceStreamFeatureFlags,
                            requestContext = buildSourceStreamRequestContext(type, seasonArg, episodeArg)
                        )
                    }
                    logSourcePresentationDiagnostics("player_sources", organizedStreams)
                    _uiState.update {
                        it.copy(
                            isLoadingSourceStreams = false,
                            sourceAllStreams = allStreams,
                            sourceSelectedAddonFilter = organizedStreams.selectedAddonFilter,
                            sourceFilteredStreams = organizedStreams.items.map { item -> item.stream },
                            sourcePresentedStreams = organizedStreams.items,
                            sourceAvailableAddons = organizedStreams.availableAddons,
                            sourceChips = mergeSourceChipStatuses(
                                existing = it.sourceChips,
                                succeededNames = addonStreams.map { group -> group.addonName }
                            ),
                            sourceStreamsError = null,
                            showSourceAddonFilters = organizedStreams.showAddonFilters
                        )
                    }
                }

                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoadingSourceStreams = false,
                            sourceStreamsError = result.message
                        )
                    }
                }

                NetworkResult.Loading -> {
                    _uiState.update { it.copy(isLoadingSourceStreams = true) }
                }
            }
        }
        markRemainingSourceChipsAsError()
    }
}

internal fun PlayerRuntimeController.dismissSourcesPanel() {
    _uiState.update {
        it.copy(
            showSourcesPanel = false,
            isLoadingSourceStreams = false,
            sourceChips = emptyList(),
            sourcePresentedStreams = emptyList()
        )
    }
    sourceChipErrorDismissJob?.cancel()
    scheduleHideControls()
}

internal fun PlayerRuntimeController.filterSourceStreamsByAddon(addonName: String?) {
    val state = _uiState.value
    scope.launch {
        val organizedStreams = withContext(Dispatchers.Default) {
            StreamPresentationEngine.organize(
                streams = state.sourceAllStreams,
                availableAddons = state.sourceAvailableAddons,
                selectedAddonFilter = addonName,
                flags = sourceStreamFeatureFlags,
                requestContext = buildSourceStreamRequestContext(
                    type = contentType,
                    season = currentSeason,
                    episode = currentEpisode
                )
            )
        }
        logSourcePresentationDiagnostics("player_sources_filter", organizedStreams)
        _uiState.update {
            it.copy(
                sourceSelectedAddonFilter = organizedStreams.selectedAddonFilter,
                sourceFilteredStreams = organizedStreams.items.map { item -> item.stream },
                sourcePresentedStreams = organizedStreams.items,
                sourceAvailableAddons = organizedStreams.availableAddons,
                showSourceAddonFilters = organizedStreams.showAddonFilters
            )
        }
    }
}

private suspend fun PlayerRuntimeController.updateSourceChipsForFetchStart(
    type: String,
    installedAddons: List<com.nexio.tv.domain.model.Addon>
) {
    val ordered = installedAddons
        .filter { it.supportsStreamResourceForChip(type) }
        .map { it.displayName }
        .distinct()
    _uiState.update {
        it.copy(
            sourceChips = ordered.map { name -> SourceChipItem(name, SourceChipStatus.LOADING) },
            showSourceAddonFilters = !sourceStreamFeatureFlags.groupAcrossAddonsEnabled
        )
    }
}

private fun PlayerRuntimeController.mergeSourceChipStatuses(
    existing: List<SourceChipItem>,
    succeededNames: List<String>
): List<SourceChipItem> {
    if (succeededNames.isEmpty()) return existing
    if (existing.isEmpty()) {
        return succeededNames.distinct().map { SourceChipItem(it, SourceChipStatus.SUCCESS) }
    }

    val successSet = succeededNames.toSet()
    val updated = existing.map { chip ->
        if (chip.name in successSet) chip.copy(status = SourceChipStatus.SUCCESS) else chip
    }.toMutableList()

    val known = updated.map { it.name }.toSet()
    succeededNames.forEach { name ->
        if (name !in known) updated += SourceChipItem(name, SourceChipStatus.SUCCESS)
    }
    return updated
}

private fun PlayerRuntimeController.markRemainingSourceChipsAsError() {
    var markedAnyError = false
    _uiState.update { state ->
        if (!state.sourceChips.any { it.status == SourceChipStatus.LOADING }) return@update state
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
    if (!markedAnyError) return

    sourceChipErrorDismissJob?.cancel()
    sourceChipErrorDismissJob = scope.launch {
        delay(1600L)
        _uiState.update { state ->
            state.copy(
                sourceChips = state.sourceChips.filterNot { it.status == SourceChipStatus.ERROR }
            )
        }
    }
}

private fun com.nexio.tv.domain.model.Addon.supportsStreamResourceForChip(type: String): Boolean {
    return resources.any { resource ->
        resource.name == "stream" &&
            (resource.types.isEmpty() || resource.types.any { it.equals(type, ignoreCase = true) })
    }
}

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

private fun PlayerRuntimeController.buildSourceStreamRequestContext(
    type: String?,
    season: Int?,
    episode: Int?
): StreamRequestContext {
    return StreamRequestContext(
        contentType = type,
        title = contentName ?: _uiState.value.title,
        year = navigationArgs.year,
        season = season,
        episode = episode,
        episodeTitle = navigationArgs.initialEpisodeTitle ?: currentEpisodeTitle
    )
}

private fun PlayerRuntimeController.logSourcePresentationDiagnostics(
    origin: String,
    organizedStreams: com.nexio.tv.core.stream.OrganizedStreams
) {
    if (!streamDiagnosticsEnabled) return
    val d = organizedStreams.diagnostics
    Log.d(
        PlayerRuntimeController.TAG,
        "STREAM_DIAG presentation origin=$origin input=${d.inputCount} droppedEpisode=${d.droppedEpisodeMismatchCount} " +
            "droppedYear=${d.droppedMovieYearMismatchCount} droppedWebDv=${d.droppedWebDolbyVisionCount} " +
            "droppedDedupe=${d.droppedDeduplicateCount} mixedCacheClusters=${d.dedupeMixedCachedUncachedClusterCount} " +
            "cachedDroppedForUncachedClusters=${d.dedupeCachedDroppedForUncachedClusterCount} " +
            "droppedAddonFilter=${d.droppedAddonFilterCount} " +
            "presented=${d.finalPresentedCount}"
    )
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.switchToSourceStream(stream: Stream) {
    val url = stream.getStreamUrl()
    if (url.isNullOrBlank()) {
        _uiState.update { it.copy(sourceStreamsError = "Invalid stream URL") }
        return
    }
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null

    flushPlaybackSnapshotForSwitchOrExit()

    val newHeaders = PlayerMediaSourceFactory.sanitizeHeaders(
        stream.behaviorHints?.proxyHeaders?.request
    )
    
    resetLoadingOverlayForNewStream()
    backendStop()

    currentStreamUrl = url
    currentHeaders = newHeaders
    currentStreamBingeGroup = stream.behaviorHints?.bingeGroup
    currentVideoHash = stream.behaviorHints?.videoHash
    currentVideoSize = stream.behaviorHints?.videoSize
    currentFilename = stream.behaviorHints?.filename ?: navigationArgs.filename
    pendingAddonSubtitleLanguage = null
    pendingAddonSubtitleTrackId = null
    pendingAudioSelectionAfterSubtitleRefresh = null
    attachedAddonSubtitleKeys = emptySet()
    startupAfrPreflightJob?.cancel()
    startupAfrPreflightJob = null
    startupSubtitlePreparationJob?.cancel()
    startupSubtitlePreparationJob = null
    hasRetriedCurrentStreamAfter416 = false
    hasRetriedCurrentStreamAfterUnexpectedNpe = false
    hasRetriedCurrentStreamAfterMediaPeriodHolderCrash = false
    lastSavedPosition = 0L
    resetLoadingOverlayForNewStream()

    _uiState.update {
        it.copy(
            isBuffering = true,
            error = null,
            currentStreamName = stream.name ?: stream.addonName,
            currentStreamUrl = url,
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            addonSubtitles = emptyList(),
            selectedAddonSubtitle = null,
            selectedAudioTrackIndex = -1,
            selectedSubtitleTrackIndex = -1,
            showSourcesPanel = false,
            isLoadingSourceStreams = false,
            sourceStreamsError = null
        )
    }
    showStreamSourceIndicator(stream)
    resetNextEpisodeCardState(clearEpisode = false)

    _exoPlayer?.let { player ->
        scope.launch {
            try {
                val playerSettings = playerSettingsDataStore.playerSettings.first()
                val mediaSource = withContext(Dispatchers.IO) {
                    mediaSourceFactory.createMediaSource(url, newHeaders)
                }
                player.setMediaSource(mediaSource)
                player.playWhenReady = true
                player.prepare()
                launchStartupAfrPreflight(
                    url = url,
                    headers = newHeaders,
                    frameRateMatchingMode = playerSettings.frameRateMatchingMode,
                    resolutionMatchingEnabled = playerSettings.resolutionMatchingEnabled
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Failed to play selected stream") }
            }
        }
    } ?: run {
        initializePlayer(url, newHeaders)
    }

    loadSavedProgressFor(currentSeason, currentEpisode)
}

internal fun PlayerRuntimeController.dismissEpisodesPanel() {
    _uiState.update {
        it.copy(
            showEpisodesPanel = false,
            showEpisodeStreams = false,
            isLoadingEpisodeStreams = false
        )
    }
    scheduleHideControls()
}

internal fun PlayerRuntimeController.selectEpisodesSeason(season: Int) {
    val all = _uiState.value.episodesAll
    if (all.isEmpty()) return

    val seasons = _uiState.value.episodesAvailableSeasons
    if (seasons.isNotEmpty() && season !in seasons) return

    val episodesForSeason = all
        .filter { (it.season ?: -1) == season }
        .sortedWith(compareBy<Video> { it.episode ?: Int.MAX_VALUE }.thenBy { it.title })

    _uiState.update {
        it.copy(
            episodesSelectedSeason = season,
            episodes = episodesForSeason
        )
    }
}

internal fun PlayerRuntimeController.loadEpisodesIfNeeded() {
    val type = contentType
    val id = contentId
    if (type.isNullOrBlank() || id.isNullOrBlank()) return
    if (type !in listOf("series", "tv")) return
    if (_uiState.value.episodesAll.isNotEmpty() || _uiState.value.isLoadingEpisodes) return

    scope.launch {
        _uiState.update { it.copy(isLoadingEpisodes = true, episodesError = null) }

        when (
            val result = metaRepository.getMetaFromAllAddons(type = type, id = id)
                .first { it !is NetworkResult.Loading }
        ) {
            is NetworkResult.Success -> {
                val allEpisodes = result.data.videos
                    .sortedWith(
                        compareBy<Video> { it.season ?: Int.MAX_VALUE }
                            .thenBy { it.episode ?: Int.MAX_VALUE }
                            .thenBy { it.title }
                    )

                applyMetaDetails(result.data)

                val seasons = allEpisodes
                    .mapNotNull { it.season }
                    .distinct()
                    .sorted()

                val preferredSeason = when {
                    currentSeason != null && seasons.contains(currentSeason) -> currentSeason
                    initialSeason != null && seasons.contains(initialSeason) -> initialSeason
                    else -> seasons.firstOrNull { it > 0 } ?: seasons.firstOrNull() ?: 1
                }

                val selectedSeason = preferredSeason ?: 1
                val episodesForSeason = allEpisodes
                    .filter { (it.season ?: -1) == selectedSeason }
                    .sortedWith(compareBy<Video> { it.episode ?: Int.MAX_VALUE }.thenBy { it.title })

                _uiState.update {
                    it.copy(
                        isLoadingEpisodes = false,
                        episodesAll = allEpisodes,
                        episodesAvailableSeasons = seasons,
                        episodesSelectedSeason = selectedSeason,
                        episodes = episodesForSeason,
                        episodesError = null
                    )
                }
            }

            is NetworkResult.Error -> {
                _uiState.update { it.copy(isLoadingEpisodes = false, episodesError = result.message) }
            }

            NetworkResult.Loading -> {
                
            }
        }
    }
}

internal fun PlayerRuntimeController.loadStreamsForEpisode(video: Video) {
    loadStreamsForEpisode(video = video, forceRefresh = false)
}

internal fun PlayerRuntimeController.buildEpisodeRequestKey(type: String, video: Video): String {
    return "$type|${video.id}|${video.season ?: -1}|${video.episode ?: -1}"
}

internal fun PlayerRuntimeController.loadStreamsForEpisode(video: Video, forceRefresh: Boolean) {
    val type = contentType
    if (type.isNullOrBlank()) {
        _uiState.update { it.copy(episodeStreamsError = "Missing content type") }
        return
    }

    val requestKey = buildEpisodeRequestKey(type = type, video = video)
    val state = _uiState.value
    val hasCachedPayload = state.episodeAllStreams.isNotEmpty() || state.episodeStreamsError != null
    if (!forceRefresh && requestKey == episodeStreamsCacheRequestKey && hasCachedPayload) {
        _uiState.update {
            it.copy(
                showEpisodeStreams = true,
                isLoadingEpisodeStreams = false,
                episodeStreamsForVideoId = video.id,
                episodeStreamsSeason = video.season,
                episodeStreamsEpisode = video.episode,
                episodeStreamsTitle = video.title
            )
        }
        return
    }

    val targetChanged = requestKey != episodeStreamsCacheRequestKey
    episodeStreamsJob?.cancel()
    episodeStreamsJob = scope.launch {
        episodeStreamsCacheRequestKey = requestKey
        val previousAddonFilter = _uiState.value.episodeSelectedAddonFilter
        _uiState.update {
            it.copy(
                showEpisodeStreams = true,
                isLoadingEpisodeStreams = true,
                episodeStreamsError = null,
                episodeAllStreams = if (forceRefresh || targetChanged) emptyList() else it.episodeAllStreams,
                episodeSelectedAddonFilter = if (forceRefresh || targetChanged) null else it.episodeSelectedAddonFilter,
                episodeFilteredStreams = if (forceRefresh || targetChanged) emptyList() else it.episodeFilteredStreams,
                episodeAvailableAddons = if (forceRefresh || targetChanged) emptyList() else it.episodeAvailableAddons,
                episodeStreamsForVideoId = video.id,
                episodeStreamsSeason = video.season,
                episodeStreamsEpisode = video.episode,
                episodeStreamsTitle = video.title
            )
        }

        val installedAddons = addonRepository.getInstalledAddons().first()
        val installedAddonOrder = installedAddons.map { it.displayName }

        streamRepository.getStreamsFromAllAddons(
            type = type,
            videoId = video.id,
            season = video.season,
            episode = video.episode,
            installedAddons = installedAddons,
            requestOrigin = "episode_picker"
        ).collect { result ->
            when (result) {
                is NetworkResult.Success -> {
                    val addonStreams = StreamAutoPlaySelector.orderAddonStreams(result.data, installedAddonOrder)
                    val allStreams = addonStreams.flatMap { it.streams }
                    val availableAddons = addonStreams.map { it.addonName }
                    val selectedAddon = previousAddonFilter?.takeIf { it in availableAddons }
                    val filteredStreams = if (selectedAddon == null) {
                        allStreams
                    } else {
                        allStreams.filter { it.addonName == selectedAddon }
                    }
                    _uiState.update {
                        it.copy(
                            isLoadingEpisodeStreams = false,
                            episodeAllStreams = allStreams,
                            episodeSelectedAddonFilter = selectedAddon,
                            episodeFilteredStreams = filteredStreams,
                            episodeAvailableAddons = availableAddons,
                            episodeStreamsError = null
                        )
                    }
                }

                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoadingEpisodeStreams = false,
                            episodeStreamsError = result.message
                        )
                    }
                }

                NetworkResult.Loading -> {
                    _uiState.update { it.copy(isLoadingEpisodeStreams = true) }
                }
            }
        }
    }
}

internal fun PlayerRuntimeController.reloadEpisodeStreams() {
    val state = _uiState.value
    val targetVideoId = state.episodeStreamsForVideoId
    val targetVideo = sequenceOf(
        state.episodes.firstOrNull { it.id == targetVideoId },
        state.episodesAll.firstOrNull { it.id == targetVideoId },
        state.episodes.firstOrNull {
            it.season == state.episodeStreamsSeason && it.episode == state.episodeStreamsEpisode
        },
        state.episodesAll.firstOrNull {
            it.season == state.episodeStreamsSeason && it.episode == state.episodeStreamsEpisode
        }
    ).firstOrNull { it != null }

    if (targetVideo != null) {
        loadStreamsForEpisode(video = targetVideo, forceRefresh = true)
    }
}

internal fun PlayerRuntimeController.switchToEpisodeStream(stream: Stream, forcedTargetVideo: Video? = null) {
    val url = stream.getStreamUrl()
    if (url.isNullOrBlank()) {
        _uiState.update { it.copy(episodeStreamsError = "Invalid stream URL") }
        return
    }
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null

    flushPlaybackSnapshotForSwitchOrExit()

    val newHeaders = PlayerMediaSourceFactory.sanitizeHeaders(
        stream.behaviorHints?.proxyHeaders?.request
    )
    val targetVideo = forcedTargetVideo
        ?: _uiState.value.episodes.firstOrNull { it.id == _uiState.value.episodeStreamsForVideoId }

    // Reset transient playback flags before stopping, so stop callbacks never
    // persist stale positions into the newly selected episode.
    resetLoadingOverlayForNewStream()
    backendStop()

    currentStreamUrl = url
    currentHeaders = newHeaders
    currentStreamBingeGroup = stream.behaviorHints?.bingeGroup
    currentVideoHash = stream.behaviorHints?.videoHash
    currentVideoSize = stream.behaviorHints?.videoSize
    currentFilename = stream.behaviorHints?.filename ?: navigationArgs.filename
    pendingAddonSubtitleLanguage = null
    pendingAddonSubtitleTrackId = null
    pendingAudioSelectionAfterSubtitleRefresh = null
    attachedAddonSubtitleKeys = emptySet()
    startupAfrPreflightJob?.cancel()
    startupAfrPreflightJob = null
    startupSubtitlePreparationJob?.cancel()
    startupSubtitlePreparationJob = null
    hasRetriedCurrentStreamAfter416 = false
    hasRetriedCurrentStreamAfterUnexpectedNpe = false
    hasRetriedCurrentStreamAfterMediaPeriodHolderCrash = false
    currentVideoId = targetVideo?.id ?: _uiState.value.episodeStreamsForVideoId ?: currentVideoId
    currentSeason = targetVideo?.season ?: _uiState.value.episodeStreamsSeason ?: currentSeason
    currentEpisode = targetVideo?.episode ?: _uiState.value.episodeStreamsEpisode ?: currentEpisode
    currentEpisodeTitle = targetVideo?.title ?: _uiState.value.episodeStreamsTitle ?: currentEpisodeTitle
    refreshScrobbleItem()
    lastSavedPosition = 0L
    resetLoadingOverlayForNewStream()

    _uiState.update {
        it.copy(
            isBuffering = true,
            error = null,
            currentSeason = currentSeason,
            currentEpisode = currentEpisode,
            currentEpisodeTitle = currentEpisodeTitle,
            currentStreamName = stream.name ?: stream.addonName,
            currentStreamUrl = url,
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            addonSubtitles = emptyList(),
            selectedAddonSubtitle = null,
            selectedAudioTrackIndex = -1,
            selectedSubtitleTrackIndex = -1,
            showEpisodesPanel = false,
            showEpisodeStreams = false,
            isLoadingEpisodeStreams = false,
            episodeStreamsError = null,
            activeSkipInterval = null,
            skipIntervalDismissed = false,
            showNextEpisodeCard = false,
            nextEpisodeCardDismissed = false,
            nextEpisodeAutoPlaySearching = false,
            nextEpisodeAutoPlaySourceName = null,
            nextEpisodeAutoPlayCountdownSec = null
        )
    }
    showStreamSourceIndicator(stream)
    recomputeNextEpisode(resetVisibility = true)

    updateEpisodeDescription()
    refreshSubtitlesForCurrentEpisode()

    skipIntervals = emptyList()
    skipIntroFetchedKey = null
    lastActiveSkipType = null

    fetchSkipIntervals(contentId, currentSeason, currentEpisode)

    _exoPlayer?.let { player ->
        scope.launch {
            try {
                val playerSettings = playerSettingsDataStore.playerSettings.first()
                val mediaSource = withContext(Dispatchers.IO) {
                    mediaSourceFactory.createMediaSource(url, newHeaders)
                }
                player.setMediaSource(mediaSource)
                player.playWhenReady = true
                player.prepare()
                launchStartupAfrPreflight(
                    url = url,
                    headers = newHeaders,
                    frameRateMatchingMode = playerSettings.frameRateMatchingMode,
                    resolutionMatchingEnabled = playerSettings.resolutionMatchingEnabled
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Failed to play selected stream") }
            }
        }
    } ?: run {
        initializePlayer(url, newHeaders)
    }

    loadSavedProgressFor(currentSeason, currentEpisode)
}

internal fun PlayerRuntimeController.showEpisodeStreamPicker(video: Video, forceRefresh: Boolean = true) {
    _uiState.update {
        it.copy(
            showEpisodesPanel = true,
            showEpisodeStreams = true,
            showSourcesPanel = false,
            showControls = true,
            showAudioDialog = false,
            showSubtitleDialog = false,
            showSpeedDialog = false,
            showMoreDialog = false,
            episodesSelectedSeason = video.season ?: it.episodesSelectedSeason
        )
    }
    loadEpisodesIfNeeded()
    loadStreamsForEpisode(video = video, forceRefresh = forceRefresh)
}

internal fun PlayerRuntimeController.playNextEpisode() {
    val nextVideo = nextEpisodeVideo ?: return
    val type = contentType ?: return

    val state = _uiState.value
    if (state.nextEpisode?.hasAired == false) {
        return
    }
    if (state.nextEpisodeAutoPlaySearching || state.nextEpisodeAutoPlayCountdownSec != null) {
        return
    }

    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = scope.launch {
        try {
            val playerSettings = playerSettingsDataStore.playerSettings.first()
            val shouldAutoSelectInManualMode =
                playerSettings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL &&
                    (
                        playerSettings.streamAutoPlayNextEpisodeEnabled ||
                            playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode
                        )
            if (playerSettings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL && !shouldAutoSelectInManualMode) {
                _uiState.update {
                    it.copy(
                        showNextEpisodeCard = false,
                        nextEpisodeCardDismissed = true,
                        nextEpisodeAutoPlaySearching = false,
                        nextEpisodeAutoPlaySourceName = null,
                        nextEpisodeAutoPlayCountdownSec = null
                    )
                }
                showEpisodeStreamPicker(video = nextVideo, forceRefresh = true)
                return@launch
            }

            _uiState.update {
                it.copy(
                    showNextEpisodeCard = true,
                    nextEpisodeCardDismissed = false,
                    nextEpisodeAutoPlaySearching = true,
                    nextEpisodeAutoPlaySourceName = null,
                    nextEpisodeAutoPlayCountdownSec = null
                )
            }

            val installedAddons = addonRepository.getInstalledAddons().first()
            val installedAddonOrder = installedAddons.map { it.displayName }
            val effectiveMode = if (shouldAutoSelectInManualMode) {
                StreamAutoPlayMode.FIRST_STREAM
            } else {
                playerSettings.streamAutoPlayMode
            }
            val effectiveSource = if (shouldAutoSelectInManualMode) {
                StreamAutoPlaySource.ALL_SOURCES
            } else {
                playerSettings.streamAutoPlaySource
            }
            val effectiveSelectedAddons = if (shouldAutoSelectInManualMode) {
                emptySet()
            } else {
                playerSettings.streamAutoPlaySelectedAddons
            }
            val effectiveRegex = if (shouldAutoSelectInManualMode) {
                ""
            } else {
                playerSettings.streamAutoPlayRegex
            }
            var selectedStream: Stream? = null
            val terminalResult = streamRepository.getStreamsFromAllAddons(
                type = type,
                videoId = nextVideo.id,
                season = nextVideo.season,
                episode = nextVideo.episode,
                installedAddons = installedAddons,
                requestOrigin = "next_episode_autoplay"
            ).firstOrNull { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        val orderedStreams = StreamAutoPlaySelector.orderAddonStreams(result.data, installedAddonOrder)
                        val allStreams = orderedStreams.flatMap { it.streams }
                        selectedStream = StreamAutoPlaySelector.selectAutoPlayStream(
                            streams = allStreams,
                            mode = effectiveMode,
                            regexPattern = effectiveRegex,
                            source = effectiveSource,
                            installedAddonNames = installedAddonOrder.toSet(),
                            selectedAddons = effectiveSelectedAddons,
                            preferredBingeGroup = if (playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode) {
                                currentStreamBingeGroup
                            } else {
                                null
                            }
                        )
                        selectedStream != null
                    }
                    is NetworkResult.Error -> true
                    NetworkResult.Loading -> false
                }
            }

            val streamToPlay = selectedStream
            if (streamToPlay != null) {
                val sourceName = (streamToPlay.name?.takeIf { it.isNotBlank() } ?: streamToPlay.addonName).trim()
                for (remaining in 3 downTo 1) {
                    _uiState.update {
                        it.copy(
                            showNextEpisodeCard = true,
                            nextEpisodeCardDismissed = false,
                            nextEpisodeAutoPlaySearching = false,
                            nextEpisodeAutoPlaySourceName = sourceName,
                            nextEpisodeAutoPlayCountdownSec = remaining
                        )
                    }
                    delay(1000)
                }
                _uiState.update {
                    it.copy(
                        showNextEpisodeCard = false,
                        nextEpisodeCardDismissed = true,
                        nextEpisodeAutoPlaySearching = false,
                        nextEpisodeAutoPlaySourceName = null,
                        nextEpisodeAutoPlayCountdownSec = null
                    )
                }
                switchToEpisodeStream(stream = streamToPlay, forcedTargetVideo = nextVideo)
            } else {
                _uiState.update {
                    it.copy(
                        showNextEpisodeCard = false,
                        nextEpisodeCardDismissed = true,
                        nextEpisodeAutoPlaySearching = false,
                        nextEpisodeAutoPlaySourceName = null,
                        nextEpisodeAutoPlayCountdownSec = null
                    )
                }
                showEpisodeStreamPicker(
                    video = nextVideo,
                    forceRefresh = terminalResult is NetworkResult.Error
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    showNextEpisodeCard = false,
                    nextEpisodeCardDismissed = true,
                    nextEpisodeAutoPlaySearching = false,
                    nextEpisodeAutoPlaySourceName = null,
                    nextEpisodeAutoPlayCountdownSec = null
                )
            }
            showEpisodeStreamPicker(video = nextVideo, forceRefresh = false)
        }
    }
}
