package com.nexio.tv.data.trailer.helper

import android.util.Log
import com.nexio.tv.data.local.YouTubeTrailerAuthDataStore
import com.nexio.tv.data.trailer.TrailerPlaybackSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private const val TAG = "TrailerAvailability"

@Singleton
class TrailerAvailabilityService @Inject constructor(
    private val authDataStore: YouTubeTrailerAuthDataStore,
    private val cookieStore: YouTubeTrailerCookieStore,
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

        val cookieHeader = cookieStore.currentYouTubeCookieHeader()
        if (cookieHeader.isNullOrBlank()) {
            Log.w(TAG, "Signed-in helper had no current YouTube cookie header")
            helperCache.storeMiss(cacheKey)
            return null
        }

        val result = bundledTrailerHelper.resolve(
            TrailerHelperRequest(
                youtubeUrl = youtubeUrl,
                cookieHeader = cookieHeader
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
}
