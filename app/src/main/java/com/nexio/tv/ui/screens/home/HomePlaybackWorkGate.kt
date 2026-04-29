package com.nexio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nexio.tv.ui.screensaver.PlaybackIdleGateSnapshot
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal fun shouldRunHomeBackgroundWork(snapshot: PlaybackIdleGateSnapshot): Boolean =
    !snapshot.hasActiveSession

internal fun HomeViewModel.isNonPlaybackHomeWorkAllowed(): Boolean =
    shouldRunHomeBackgroundWork(playbackIdleGateState.snapshot.value)

internal fun HomeViewModel.observePlaybackWorkGate() {
    viewModelScope.launch {
        playbackIdleGateState.snapshot
            .collectLatest { snapshot ->
                integrationPlaybackGate.setPlaybackActive(snapshot.hasActiveSession)
                if (!shouldRunHomeBackgroundWork(snapshot)) {
                    cancelNonPlaybackHomeWorkForPlayback()
                } else {
                    val rows = _uiState.value.catalogRows
                    if (rows.isNotEmpty()) {
                        refreshTrailerMetadataAvailabilityPipeline(rows)
                        schedulePosterStatusReconcilePipeline(rows)
                    }
                }
            }
    }
}

internal fun HomeViewModel.cancelNonPlaybackHomeWorkForPlayback() {
    continueWatchingEnrichmentJob?.cancel()
    continueWatchingEnrichmentJob = null

    heroEnrichmentJob?.cancel()
    heroEnrichmentJob = null

    tmdbEnrichFocusJob?.cancel()
    tmdbEnrichFocusJob = null

    adjacentItemPrefetchJob?.cancel()
    adjacentItemPrefetchJob = null

    metadataEnrichmentFlushJob?.cancel()
    metadataEnrichmentFlushJob = null

    trailerPreviewJob?.cancel()
    trailerPreviewJob = null

    val availabilityJobs = synchronized(trailerMetadataAvailabilityJobs) {
        trailerMetadataAvailabilityJobs.toList().also { trailerMetadataAvailabilityJobs.clear() }
    }
    availabilityJobs.forEach { it.cancel() }
    trailerMetadataAvailabilityInFlightKeys.clear()

    posterStatusReconcileJob?.cancel()
    posterStatusReconcileJob = null
    posterLibraryObserverJobs.values.forEach { it.cancel() }
    movieWatchedObserverJobs.values.forEach { it.cancel() }
    posterLibraryObserverJobs.clear()
    movieWatchedObserverJobs.clear()

    pendingFocusedItemForEnrichment = null
    pendingAdjacentPrefetchItemId = null
    pendingTmdbEnrichItemId = null
    pendingProviderEnrichmentByItemId.clear()
    setEnrichingItemId(null)
    trailerPreviewLoadingIds.clear()

    cancelInFlightCatalogLoads()
    catalogsLoadInProgress = false
    pendingCatalogLoads = 0
}
