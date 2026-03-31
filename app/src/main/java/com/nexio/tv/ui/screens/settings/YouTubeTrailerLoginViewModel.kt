package com.nexio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.data.trailer.helper.YouTubeTrailerAuthEvent
import com.nexio.tv.data.trailer.helper.YouTubeTrailerAuthManager
import com.nexio.tv.data.trailer.helper.YouTubeTrailerAuthMode
import com.nexio.tv.data.trailer.helper.YouTubeTrailerAuthUiState
import com.nexio.tv.data.trailer.helper.YouTubeTrailerTokenPollResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@HiltViewModel
class YouTubeTrailerLoginViewModel @Inject constructor(
    private val authManager: YouTubeTrailerAuthManager
) : ViewModel() {
    private var pollingJob: Job? = null

    val uiState: StateFlow<YouTubeTrailerAuthUiState> = authManager.uiState.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = YouTubeTrailerAuthUiState()
    )

    init {
        viewModelScope.launch {
            authManager.uiState.collectLatest { state ->
                if (state.mode == YouTubeTrailerAuthMode.AWAITING_APPROVAL) {
                    ensurePolling()
                } else {
                    stopPolling()
                }
            }
        }
    }

    fun onEvent(event: YouTubeTrailerAuthEvent) {
        when (event) {
            YouTubeTrailerAuthEvent.SignIn -> viewModelScope.launch {
                stopPolling()
                authManager.startDeviceFlow()
            }

            YouTubeTrailerAuthEvent.RefreshSession -> viewModelScope.launch {
                authManager.refreshSession()
            }

            YouTubeTrailerAuthEvent.SignOut -> viewModelScope.launch {
                stopPolling()
                authManager.signOut()
            }

            YouTubeTrailerAuthEvent.DismissEmbeddedLoginSurface -> viewModelScope.launch {
                stopPolling()
                authManager.dismissDeviceFlow()
            }
        }
    }

    private fun ensurePolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            while (isActive) {
                val delaySeconds = uiState.value.pollIntervalSeconds.coerceAtLeast(2)
                delay(delaySeconds * 1_000L)
                when (authManager.pollDeviceToken()) {
                    is YouTubeTrailerTokenPollResult.Pending,
                    is YouTubeTrailerTokenPollResult.SlowDown -> Unit

                    is YouTubeTrailerTokenPollResult.Approved,
                    is YouTubeTrailerTokenPollResult.Denied,
                    is YouTubeTrailerTokenPollResult.Expired -> {
                        stopPolling()
                    }

                    is YouTubeTrailerTokenPollResult.Failed -> Unit
                }
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }
}
