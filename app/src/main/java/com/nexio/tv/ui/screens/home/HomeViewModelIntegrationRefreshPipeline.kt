package com.nexio.tv.ui.screens.home

import android.util.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal fun HomeViewModel.observeAccountSyncRefreshPipeline() {
    viewModelScope.launch {
        accountSyncRefreshNotifier.events.collectLatest {
            runCatching { traktDiscoveryService.ensureFresh(force = true) }
                .onFailure { error ->
                    Log.w(HomeViewModel.TAG, "Failed to refresh Trakt discovery after account sync", error)
                }
            runCatching { mdbListDiscoveryService.ensureFresh(force = true) }
                .onFailure { error ->
                    Log.w(HomeViewModel.TAG, "Failed to refresh MDBList discovery after account sync", error)
                }

            catalogRepository.clearCache()
            metaRepository.clearCache()
            tmdbService.clearCache()
            tmdbMetadataService.clearCache()
            lastHeroEnrichmentSignature = null
            lastHeroEnrichedItems = emptyList()

            if (addonsCache.isNotEmpty()) {
                loadAllCatalogsPipeline(addonsCache, forceReload = true)
            } else {
                scheduleUpdateCatalogRows()
            }
        }
    }
}
