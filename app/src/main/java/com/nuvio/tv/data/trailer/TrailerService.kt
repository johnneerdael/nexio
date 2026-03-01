package com.nuvio.tv.data.trailer

import android.util.Log
import com.nuvio.tv.data.remote.api.TrailerApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TrailerService"

@Singleton
class TrailerService @Inject constructor(
    private val trailerApi: TrailerApi,
    private val inAppYouTubeExtractor: InAppYouTubeExtractor
) {
    // Cache: "title|year|tmdbId|type" -> trailer playback source (null for negative cache)
    private val cache = ConcurrentHashMap<String, TrailerPlaybackSource?>()

    /**
     * Search for a trailer by title, year, tmdbId, and type.
     * Returns the trailer playback source (video URL + optional separate audio URL) or null.
     */
    suspend fun getTrailerPlaybackSource(
        title: String,
        year: String? = null,
        tmdbId: String? = null,
        type: String? = null
    ): TrailerPlaybackSource? = withContext(Dispatchers.IO) {
        val cacheKey = "$title|$year|$tmdbId|$type"

        if (cache.containsKey(cacheKey)) {
            val cached = cache[cacheKey]
            Log.d(TAG, "Cache hit for $cacheKey: ${cached != null}")
            return@withContext cached
        }

        try {
            Log.d(TAG, "Searching trailer: title=$title, year=$year, tmdbId=$tmdbId, type=$type")
            val response = trailerApi.searchTrailer(
                title = title,
                year = year,
                tmdbId = tmdbId,
                type = type
            )

            if (response.isSuccessful) {
                val url = response.body()?.url
                val source = resolvePlaybackSource(url, title, year)
                if (source != null) {
                    Log.d(TAG, "Found trailer playback source for $title")
                    cache[cacheKey] = source
                    return@withContext source
                }
            }

            Log.w(TAG, "No trailer found for $title: ${response.code()}")
            cache[cacheKey] = null
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching trailer for $title: ${e.message}", e)
            null
        }
    }

    /**
     * Search for a trailer and return its primary video URL for existing call sites.
     */
    suspend fun getTrailerUrl(
        title: String,
        year: String? = null,
        tmdbId: String? = null,
        type: String? = null
    ): String? {
        return getTrailerPlaybackSource(
            title = title,
            year = year,
            tmdbId = tmdbId,
            type = type
        )?.videoUrl
    }

    /**
     * Resolve a YouTube trailer URL to a playback source (prefers in-app extraction).
     */
    suspend fun getTrailerPlaybackSourceFromYouTubeUrl(
        youtubeUrl: String,
        title: String? = null,
        year: String? = null
    ): TrailerPlaybackSource? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Attempting in-app YouTube extraction for ${summarizeUrl(youtubeUrl)}")
            val localSource = inAppYouTubeExtractor.extractPlaybackSource(youtubeUrl)
            if (localSource != null) {
                Log.d(
                    TAG,
                    "Using in-app YouTube source for ${summarizeUrl(youtubeUrl)} " +
                        "(audioPresent=${!localSource.audioUrl.isNullOrBlank()})"
                )
                return@withContext localSource
            }

            // Fallback to remote trailer resolver if in-app extraction fails.
            Log.w(TAG, "In-app extraction failed, falling back to backend resolver for ${summarizeUrl(youtubeUrl)}")
            val response = trailerApi.getTrailer(youtubeUrl = youtubeUrl, title = title, year = year)
            if (!response.isSuccessful) {
                Log.w(TAG, "Backend trailer fallback failed (${response.code()}) for ${summarizeUrl(youtubeUrl)}")
                return@withContext null
            }

            val fallbackUrl = response.body()?.url ?: return@withContext null
            if (!isValidUrl(fallbackUrl)) return@withContext null

            Log.d(TAG, "Using backend fallback source for ${summarizeUrl(youtubeUrl)}")
            TrailerPlaybackSource(videoUrl = fallbackUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting trailer from YouTube: ${e.message}", e)
            null
        }
    }

    /**
     * Compatibility method for existing callers expecting a single URL.
     */
    suspend fun getTrailerFromYouTubeUrl(
        youtubeUrl: String,
        title: String? = null,
        year: String? = null
    ): String? {
        return getTrailerPlaybackSourceFromYouTubeUrl(
            youtubeUrl = youtubeUrl,
            title = title,
            year = year
        )?.videoUrl
    }

    private suspend fun resolvePlaybackSource(
        rawUrl: String?,
        title: String?,
        year: String?
    ): TrailerPlaybackSource? {
        if (!isValidUrl(rawUrl)) return null
        val url = rawUrl!!

        if (isLikelyYouTubeUrl(url)) {
            Log.d(TAG, "Trailer URL is YouTube, extracting in-app: ${summarizeUrl(url)}")
            val extracted = getTrailerPlaybackSourceFromYouTubeUrl(
                youtubeUrl = url,
                title = title,
                year = year
            )
            if (extracted != null) return extracted
        }

        Log.d(TAG, "Using direct trailer URL (non-YouTube or extraction unavailable): ${summarizeUrl(url)}")
        return TrailerPlaybackSource(videoUrl = url)
    }

    private fun isLikelyYouTubeUrl(url: String): Boolean {
        return runCatching {
            val host = URI(url).host?.lowercase()?.removePrefix("www.") ?: return@runCatching false
            host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be"
        }.getOrDefault(false)
    }

    private fun isValidUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return url.startsWith("http://") || url.startsWith("https://")
    }

    private fun summarizeUrl(url: String): String {
        return runCatching {
            val uri = URI(url)
            val host = uri.host ?: "unknown-host"
            val path = uri.path ?: "/"
            "$host$path"
        }.getOrDefault(url.take(80))
    }

    fun clearCache() {
        cache.clear()
    }
}
