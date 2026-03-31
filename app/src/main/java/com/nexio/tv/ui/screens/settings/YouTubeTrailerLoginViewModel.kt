package com.nexio.tv.ui.screens.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.data.local.YouTubeTrailerAuthDataStore
import com.nexio.tv.data.trailer.helper.YouTubeTrailerCookieStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class YouTubeTrailerLoginViewModel @Inject constructor(
    private val authDataStore: YouTubeTrailerAuthDataStore,
    private val cookieStore: YouTubeTrailerCookieStore
) : ViewModel() {
    companion object {
        private const val TAG = "YouTubeTrailerAuth"
    }

    private val _uiState = MutableStateFlow(YouTubeTrailerAuthUiState())
    val uiState: StateFlow<YouTubeTrailerAuthUiState> = _uiState.asStateFlow()
    private var authExportInFlight = false

    init {
        viewModelScope.launch {
            authDataStore.settings.collectLatest { settings ->
                _uiState.update { current ->
                    current.copy(
                        isSignedIn = settings.isSignedIn,
                        lastCookieRefreshEpochMs = settings.lastCookieRefreshEpochMs,
                        sessionVersion = settings.sessionVersion,
                        sessionStatusMessage = settings.sessionStatusMessage
                    )
                }
            }
        }
    }

    fun onEvent(event: YouTubeTrailerAuthEvent) {
        when (event) {
            YouTubeTrailerAuthEvent.SignIn -> {
                _uiState.update { it.copy(showEmbeddedLoginSurface = true) }
            }

            YouTubeTrailerAuthEvent.RefreshSession -> {
                refreshSession()
            }

            YouTubeTrailerAuthEvent.SignOut -> {
                signOut()
            }

            YouTubeTrailerAuthEvent.DismissEmbeddedLoginSurface -> {
                _uiState.update { it.copy(showEmbeddedLoginSurface = false) }
            }
        }
    }

    fun onAuthPageFinished(url: String?) {
        if (url.isNullOrBlank()) return
        if (!_uiState.value.showEmbeddedLoginSurface) return
        if (_uiState.value.isSignedIn || authExportInFlight) return
        if (!cookieStore.hasAuthenticatedYouTubeCookies()) return

        Log.d(TAG, "Detected authenticated YouTube cookies from embedded login page=$url")
        authExportInFlight = true
        viewModelScope.launch {
            val snapshot = runCatching { cookieStore.snapshotCurrentSession() }
                .getOrNull()
                .also { authExportInFlight = false }
            if (snapshot == null) {
                Log.w(TAG, "YouTube sign-in detected but session snapshot was unavailable")
                authDataStore.updateSessionStatusMessage(
                    "YouTube sign-in was detected, but Nexio could not read the active trailer session yet."
                )
                return@launch
            }
            Log.d(TAG, "YouTube trailer session established cookieCount=${snapshot.cookieCount}")
            authDataStore.markSignedIn(
                sessionVersion = 1,
                lastCookieRefreshEpochMs = snapshot.refreshedAtEpochMs,
                sessionStatusMessage = "Signed in with ${snapshot.cookieCount} live trailer cookies"
            )
            _uiState.update { it.copy(showEmbeddedLoginSurface = false) }
        }
    }

    private fun refreshSession() {
        viewModelScope.launch {
            if (!_uiState.value.isSignedIn) {
                Log.w(TAG, "Ignoring trailer session refresh while signed out")
                authDataStore.updateSessionStatusMessage(
                    "Sign in first to refresh the trailer session."
                )
                return@launch
            }
            val snapshot = cookieStore.snapshotCurrentSession()
            if (snapshot == null) {
                Log.w(TAG, "Trailer session refresh failed: no current YouTube cookie snapshot")
                authDataStore.updateSessionStatusMessage(
                    "Session refresh failed. Sign in again to restore YouTube trailer playback."
                )
                return@launch
            }
            Log.d(TAG, "Trailer session refreshed cookieCount=${snapshot.cookieCount}")
            authDataStore.updateCookieRefresh(
                refreshedAtEpochMs = snapshot.refreshedAtEpochMs,
                sessionVersion = 1,
                sessionStatusMessage = "Session refreshed with ${snapshot.cookieCount} live trailer cookies"
            )
        }
    }

    private fun signOut() {
        viewModelScope.launch {
            Log.d(TAG, "Clearing embedded YouTube trailer session")
            cookieStore.clearSession()
            authDataStore.clearSession()
        }
        _uiState.update { it.copy(showEmbeddedLoginSurface = false) }
    }
}

data class YouTubeTrailerAuthUiState(
    val isSignedIn: Boolean = false,
    val lastCookieRefreshEpochMs: Long? = null,
    val sessionVersion: Int = 0,
    val sessionStatusMessage: String = "Signed out",
    val showEmbeddedLoginSurface: Boolean = false
)

sealed interface YouTubeTrailerAuthEvent {
    data object SignIn : YouTubeTrailerAuthEvent
    data object RefreshSession : YouTubeTrailerAuthEvent
    data object SignOut : YouTubeTrailerAuthEvent
    data object DismissEmbeddedLoginSurface : YouTubeTrailerAuthEvent
}
