package com.nexio.tv.ui.screens.home

import android.util.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private const val PRIORITY_HYDRATION_TAG = "PriorityHydration"

internal fun HomeViewModel.observePriorityHydrationPipeline() {
    viewModelScope.launch {
        catalogPriorityHydrationNotifier.events.collect {
            clearProfileSwitchDiskSnapshotMode("priority_hydration")
            // Run all three concurrently — services short-circuit immediately
            // if auth/API key is missing, so no wasted work.
            launch {
                runCatching { traktDiscoveryService.priorityFetch() }
                    .onFailure { Log.w(PRIORITY_HYDRATION_TAG, "Priority Trakt fetch failed", it) }
            }
            launch {
                runCatching { simklDiscoveryService.priorityFetch() }
                    .onFailure { Log.w(PRIORITY_HYDRATION_TAG, "Priority Simkl fetch failed", it) }
            }
            launch {
                runCatching { mdbListDiscoveryService.priorityFetch() }
                    .onFailure { Log.w(PRIORITY_HYDRATION_TAG, "Priority MDBList fetch failed", it) }
            }
        }
    }
}
