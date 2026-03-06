package com.nexio.tv.ui.screens.home

import android.util.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal fun HomeViewModel.observeAccountSyncRefreshPipeline() {
    viewModelScope.launch {
        accountSyncRefreshNotifier.events.collect {
            if (shouldRefreshTraktDiscoveryForState(traktCatalogPreferences, traktDiscoverySnapshot)) {
                runCatching { traktDiscoveryService.ensureFresh(force = false) }
                    .onFailure { error ->
                        Log.w(HomeViewModel.TAG, "Failed to refresh Trakt discovery after account sync", error)
                    }
            }
            if (shouldRefreshMDBListDiscoveryForState(mdbListCatalogPreferences, mdbListDiscoverySnapshot)) {
                runCatching { mdbListDiscoveryService.ensureFresh(force = false) }
                    .onFailure { error ->
                        Log.w(HomeViewModel.TAG, "Failed to refresh MDBList discovery after account sync", error)
                    }
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
