package com.nexio.tv.ui.screens.home

import android.util.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private const val FOREGROUND_REFRESH_THROTTLE_MS = 20_000L

internal fun HomeViewModel.observeAccountSyncRefreshPipeline() {
    viewModelScope.launch {
        accountSyncRefreshNotifier.events.collect {
            startupRefreshPending = true

            if (shouldRefreshTraktDiscoveryForState(traktCatalogPreferences, traktDiscoverySnapshot)) {
                traktDiscoveryRefreshInProgress = true
                runCatching { traktDiscoveryService.ensureFresh(force = false) }
                    .onFailure { error ->
                        Log.w(HomeViewModel.TAG, "Failed to refresh Trakt discovery after account sync", error)
                    }
                traktDiscoveryRefreshInProgress = false
            }
            if (shouldRefreshMDBListDiscoveryForState(mdbListCatalogPreferences, mdbListDiscoverySnapshot)) {
                mdbListDiscoveryRefreshInProgress = true
                runCatching { mdbListDiscoveryService.ensureFresh(force = false) }
                    .onFailure { error ->
                        Log.w(HomeViewModel.TAG, "Failed to refresh MDBList discovery after account sync", error)
                    }
                mdbListDiscoveryRefreshInProgress = false
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

internal fun HomeViewModel.onForegroundPipeline() {
    viewModelScope.launch {
        val now = System.currentTimeMillis()
        if (now - lastForegroundRefreshMs < FOREGROUND_REFRESH_THROTTLE_MS) {
            return@launch
        }
        lastForegroundRefreshMs = now
        startupRefreshPending = true

        traktDiscoveryRefreshInProgress = true
        runCatching { traktDiscoveryService.ensureFresh(force = false) }
            .onFailure { error ->
                Log.w(HomeViewModel.TAG, "Failed to refresh Trakt discovery on foreground", error)
            }
        traktDiscoveryRefreshInProgress = false

        mdbListDiscoveryRefreshInProgress = true
        runCatching { mdbListDiscoveryService.ensureFresh(force = false) }
            .onFailure { error ->
                Log.w(HomeViewModel.TAG, "Failed to refresh MDBList discovery on foreground", error)
            }
        mdbListDiscoveryRefreshInProgress = false

        if (addonsCache.isNotEmpty()) {
            loadAllCatalogsPipeline(addonsCache, forceReload = true)
        } else {
            scheduleUpdateCatalogRows()
        }
    }
}
