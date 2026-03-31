package com.nexio.tv.data.trailer.helper

import com.nexio.tv.data.local.YouTubeTrailerAuthDataStore
import com.nexio.tv.data.local.YouTubeTrailerAuthSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

enum class YouTubeTrailerAuthMode {
    DISCONNECTED,
    AWAITING_APPROVAL,
    CONNECTED
}

data class YouTubeTrailerAuthUiState(
    val mode: YouTubeTrailerAuthMode = YouTubeTrailerAuthMode.DISCONNECTED,
    val isSignedIn: Boolean = false,
    val userCode: String? = null,
    val verificationUrl: String? = null,
    val verificationUrlComplete: String? = null,
    val deviceCodeExpiresAtEpochMs: Long? = null,
    val pollIntervalSeconds: Int = 5,
    val accessTokenExpiresAtEpochMs: Long? = null,
    val delegatedPageId: String? = null,
    val authUser: String? = null,
    val hasRefreshToken: Boolean = false,
    val sessionStatusMessage: String = "Signed out"
)

sealed interface YouTubeTrailerAuthEvent {
    data object SignIn : YouTubeTrailerAuthEvent
    data object RefreshSession : YouTubeTrailerAuthEvent
    data object SignOut : YouTubeTrailerAuthEvent
    data object DismissEmbeddedLoginSurface : YouTubeTrailerAuthEvent
}

@Singleton
class YouTubeTrailerAuthManager @Inject constructor(
    private val authDataStore: YouTubeTrailerAuthDataStore,
    private val tokenStore: YouTubeTrailerTokenStore,
    private val authService: YouTubeDeviceCodeAuthService
) {
    val uiState: Flow<YouTubeTrailerAuthUiState> = combine(
        authDataStore.settings,
        tokenStore.state
    ) { authSettings, tokenState ->
        authSettings.toUiState(tokenState)
    }.distinctUntilChanged()

    suspend fun startDeviceFlow(): Result<YouTubeDeviceCodeSession> {
        tokenStore.clearSession()
        val result = authService.startDeviceFlow()
        result.getOrNull()?.let { session ->
            authDataStore.saveDeviceFlow(session)
            authDataStore.updateSessionStatusMessage("Approve the code on another device")
        } ?: result.exceptionOrNull()?.message?.let { message ->
            authDataStore.updateSessionStatusMessage(message)
        }
        return result
    }

    suspend fun pollDeviceToken(): YouTubeTrailerTokenPollResult {
        val settings = authDataStore.settings.first()
        val deviceCode = settings.deviceCode
            ?: return YouTubeTrailerTokenPollResult.Failed("No active YouTube device flow")

        return when (val result = authService.pollDeviceToken(deviceCode)) {
            is YouTubeTrailerTokenPollResult.Pending -> {
                authDataStore.updateSessionStatusMessage("Waiting for approval")
                result
            }
            is YouTubeTrailerTokenPollResult.SlowDown -> {
                authDataStore.updatePollInterval(result.pollIntervalSeconds)
                authDataStore.updateSessionStatusMessage(
                    "Polling slowed down to ${result.pollIntervalSeconds}s"
                )
                result
            }
            is YouTubeTrailerTokenPollResult.Expired -> {
                authDataStore.clearDeviceFlow()
                authDataStore.updateSessionStatusMessage("Device code expired")
                result
            }
            is YouTubeTrailerTokenPollResult.Denied -> {
                authDataStore.clearDeviceFlow()
                authDataStore.updateSessionStatusMessage("Device code was denied")
                result
            }
            is YouTubeTrailerTokenPollResult.Approved -> {
                tokenStore.saveSession(result.session)
                authDataStore.markSignedIn("Signed in")
                authDataStore.clearDeviceFlow()
                result
            }
            is YouTubeTrailerTokenPollResult.Failed -> {
                authDataStore.updateSessionStatusMessage(result.reason)
                result
            }
        }
    }

    suspend fun refreshSession() {
        val settings = authDataStore.settings.first()
        val tokenState = tokenStore.state.first()

        when {
            !settings.deviceCode.isNullOrBlank() && !settings.isSignedIn -> {
                pollDeviceToken()
            }
            !tokenState.refreshToken.isNullOrBlank() -> {
                val refreshResult = authService.refreshAccessToken(tokenState.refreshToken)
                refreshResult.getOrNull()?.let { session ->
                    tokenStore.saveSession(session)
                    authDataStore.markSignedIn("Session refreshed")
                } ?: refreshResult.exceptionOrNull()?.message?.let { message ->
                    authDataStore.updateSessionStatusMessage(message)
                }
            }
            else -> {
                authDataStore.updateSessionStatusMessage(
                    "Sign in first to refresh the trailer session."
                )
            }
        }
    }

    suspend fun dismissDeviceFlow() {
        authDataStore.clearDeviceFlow()
        if (!authDataStore.settings.first().isSignedIn) {
            authDataStore.updateSessionStatusMessage("Signed out")
        }
    }

    suspend fun signOut() {
        tokenStore.clearSession()
        authDataStore.clearSession()
    }

    private fun YouTubeTrailerAuthSettings.toUiState(
        tokenState: YouTubeTrailerTokenState
    ): YouTubeTrailerAuthUiState {
        val mode = when {
            isSignedIn -> YouTubeTrailerAuthMode.CONNECTED
            !deviceCode.isNullOrBlank() -> YouTubeTrailerAuthMode.AWAITING_APPROVAL
            else -> YouTubeTrailerAuthMode.DISCONNECTED
        }
        return YouTubeTrailerAuthUiState(
            mode = mode,
            isSignedIn = isSignedIn,
            userCode = userCode,
            verificationUrl = verificationUrl,
            verificationUrlComplete = verificationUrlComplete,
            deviceCodeExpiresAtEpochMs = deviceCodeExpiresAtEpochMs,
            pollIntervalSeconds = pollIntervalSeconds,
            accessTokenExpiresAtEpochMs = tokenState.expiresAtEpochMs,
            delegatedPageId = tokenState.delegatedPageId,
            authUser = tokenState.authUser,
            hasRefreshToken = !tokenState.refreshToken.isNullOrBlank(),
            sessionStatusMessage = sessionStatusMessage
        )
    }
}
