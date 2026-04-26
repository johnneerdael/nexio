package com.nexio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private const val FOREGROUND_REFRESH_THROTTLE_MS = 20_000L

internal fun HomeViewModel.observeAccountSyncRefreshPipeline() {
    viewModelScope.launch {
        accountSyncRefreshNotifier.events.collect {
            clearProfileSwitchDiskSnapshotMode("account_sync")
            startupRefreshPending = true
            runSerializedHomeRefreshIfNeeded("account_sync")
        }
    }
}

internal fun HomeViewModel.onForegroundPipeline() {
    viewModelScope.launch {
        if (shouldSuppressProfileSwitchRefresh("foreground")) return@launch
        clearProfileSwitchDiskSnapshotMode("foreground")

        val now = System.currentTimeMillis()
        if (now - lastForegroundRefreshMs < FOREGROUND_REFRESH_THROTTLE_MS) {
            return@launch
        }
        lastForegroundRefreshMs = now
        startupRefreshPending = true

        runSerializedHomeRefreshIfNeeded("foreground")
    }
}
