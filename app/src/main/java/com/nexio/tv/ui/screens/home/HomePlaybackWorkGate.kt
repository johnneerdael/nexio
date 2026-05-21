package com.nexio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nexio.tv.ui.screensaver.PlaybackIdleGateSnapshot
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal fun shouldRunHomeBackgroundWork(snapshot: PlaybackIdleGateSnapshot): Boolean =
    !snapshot.hasActiveSession && !snapshot.idleTrailerPlaybackActive

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
                    val rows = _internalCatalogRows.value
                    if (rows.isNotEmpty()) {
                        refreshTrailerMetadataAvailabilityPipeline(rows)
                        schedulePosterStatusReconcilePipeline(rows)
                    }
                    // Plan: Bug A — Task A6. When playback ends, re-trigger
                    // the screensaver publish path which re-launches the
                    // warmer (via the publishTmdbTrendingScreensaverSurface
                    // hookup in Task A4). Cheap if MediaClipStore was already
                    // warmed before playback started — fetchTrailer returns
                    // cached results without hitting TMDB again.
                    val profileSession = profileManager.activeProfileSession.value
                    val overlays = hydratedHomeOverlaysByItemKey.value
                    if (overlays.isNotEmpty() || _internalCatalogRows.value.isNotEmpty()) {
                        publishTmdbTrendingScreensaverSurface(
                            profileSession = profileSession,
                            overlaysByItemKey = overlays
                        )
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

    // Plan: Bug A — Task A6. Cancel the screensaver-pool trailer warmer
    // when playback starts so its per-item TMDB calls don't contend with
    // stream playback. Re-triggered when playback ends via the
    // observePlaybackWorkGate branch below.
    screensaverTrailerWarmJob?.cancel()
    screensaverTrailerWarmJob = null

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
