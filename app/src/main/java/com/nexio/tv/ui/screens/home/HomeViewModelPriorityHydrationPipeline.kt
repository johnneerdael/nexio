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
