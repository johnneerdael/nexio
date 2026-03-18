package com.nexio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private const val FOREGROUND_REFRESH_THROTTLE_MS = 20_000L

internal fun HomeViewModel.observeAccountSyncRefreshPipeline() {
    viewModelScope.launch {
        accountSyncRefreshNotifier.events.collect {
            startupRefreshPending = true
            if (diskFirstHomeStartupEnabled) {
                openStartupDeferralWindowIfNeeded("account_sync")
            }

            if (shouldDeferStartupNetworkWork()) {
                logStartupPerf("catalog_refresh_deferred", "reason=account_sync")
                return@collect
            }
            runSerializedHomeRefreshIfNeeded("account_sync")
        }
    }
}

internal fun HomeViewModel.onForegroundPipeline() {
    viewModelScope.launch {
        if (diskFirstHomeStartupEnabled) {
            openStartupDeferralWindowIfNeeded("home_foreground")
        }

        val now = System.currentTimeMillis()
        if (now - lastForegroundRefreshMs < FOREGROUND_REFRESH_THROTTLE_MS) {
            return@launch
        }
        lastForegroundRefreshMs = now
        startupRefreshPending = true

        if (shouldDeferStartupNetworkWork()) {
            logStartupPerf("catalog_refresh_deferred", "reason=foreground_window")
            return@launch
        }
        runSerializedHomeRefreshIfNeeded("foreground")
    }
}
