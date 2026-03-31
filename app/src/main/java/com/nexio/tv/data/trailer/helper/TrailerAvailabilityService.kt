package com.nexio.tv.data.trailer.helper

import android.util.Log
import com.nexio.tv.data.local.YouTubeTrailerAuthDataStore
import com.nexio.tv.data.trailer.TrailerPlaybackSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private const val TAG = "TrailerAvailability"
private const val TOKEN_REFRESH_LEEWAY_MS = 60_000L

@Singleton
class TrailerAvailabilityService @Inject constructor(
    private val authDataStore: YouTubeTrailerAuthDataStore,
    private val tokenStore: YouTubeTrailerTokenStore,
    private val authService: YouTubeDeviceCodeAuthService,
    private val bundledTrailerHelper: BundledTrailerHelper,
    private val helperCache: TrailerHelperCache
) {
    suspend fun isSignedIn(): Boolean {
        return authDataStore.settings.first().isSignedIn
    }

    suspend fun resolveAuthenticatedYouTubePlayback(youtubeUrl: String): TrailerPlaybackSource? {
        if (!isSignedIn()) {
            Log.d(TAG, "Skipping helper playback while signed out for $youtubeUrl")
            return null
        }

        val cacheKey = youtubeUrl.trim()
        helperCache.get(cacheKey)?.let {
            Log.d(TAG, "Using cached helper playback source for $youtubeUrl")
            return it
        }
        if (helperCache.containsRecentMiss(cacheKey)) {
            Log.d(TAG, "Skipping recent helper miss for $youtubeUrl")
            return null
        }

        val authState = ensureFreshAuthorizationState()
        if (authState == null) {
            Log.w(TAG, "Signed-in helper had no valid bearer authorization header")
            helperCache.storeMiss(cacheKey)
            return null
        }

        val result = bundledTrailerHelper.resolve(
            TrailerHelperRequest(
                youtubeUrl = youtubeUrl,
                authorizationHeader = authState.authorizationHeader,
                pageId = authState.pageId,
                authUser = authState.authUser
            )
        )

        return when (result) {
            is TrailerHelperResult.Playback -> {
                val source = TrailerPlaybackSource(
                    videoUrl = result.playback.videoUrl,
                    audioUrl = result.playback.audioUrl
                )
                Log.d(TAG, "Helper resolved direct playback for $youtubeUrl")
                helperCache.storeHit(
                    key = cacheKey,
                    source = source,
                    expiresAtEpochMs = result.playback.expiresAtEpochMs
                )
                source
            }

            is TrailerHelperResult.Failure -> {
                Log.w(
                    TAG,
                    "Helper failed for $youtubeUrl reason=${result.reason} excerpt=${result.stderrExcerpt.orEmpty()}"
                )
                helperCache.storeMiss(cacheKey)
                null
            }
        }
    }

    private suspend fun ensureFreshAuthorizationState(): ResolvedAuthState? {
        var tokenState = tokenStore.state.first()
        val expiresAtEpochMs = tokenState.expiresAtEpochMs
        val now = System.currentTimeMillis()
        val isExpiring = expiresAtEpochMs != null && expiresAtEpochMs <= now + TOKEN_REFRESH_LEEWAY_MS

        if ((tokenState.accessToken.isNullOrBlank() || isExpiring) && !tokenState.refreshToken.isNullOrBlank()) {
            val refreshed = authService.refreshAccessToken(tokenState.refreshToken)
            refreshed.getOrNull()?.let { session ->
                tokenStore.saveSession(
                    session.copy(
                        refreshToken = session.refreshToken ?: tokenState.refreshToken
                    )
                )
                authDataStore.markSignedIn("Session refreshed")
                tokenState = tokenStore.state.first()
            } ?: refreshed.exceptionOrNull()?.message?.let { message ->
                Log.w(TAG, "Failed to refresh YouTube trailer auth token: $message")
                authDataStore.updateSessionStatusMessage(message)
            }
        }

        val resolvedExpiryEpochMs = tokenState.expiresAtEpochMs
        if (isExpiring && resolvedExpiryEpochMs != null && resolvedExpiryEpochMs <= now + TOKEN_REFRESH_LEEWAY_MS) {
            return null
        }

        val authorizationHeader = tokenState.accessToken
            ?.takeIf { it.isNotBlank() }
            ?.let { "Bearer $it" }
            ?: return null

        return ResolvedAuthState(
            authorizationHeader = authorizationHeader,
            pageId = tokenState.delegatedPageId,
            authUser = tokenState.authUser ?: "0"
        )
    }
}

private data class ResolvedAuthState(
    val authorizationHeader: String,
    val pageId: String?,
    val authUser: String?
)
