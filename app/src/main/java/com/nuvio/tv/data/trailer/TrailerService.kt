package com.nuvio.tv.data.trailer

import android.util.Log
import com.nuvio.tv.data.remote.api.TrailerApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TrailerService"

@Singleton
class TrailerService @Inject constructor(
    private val trailerApi: TrailerApi
) {
    // Cache: "title|year|tmdbId|type" -> streaming URL (null for negative cache)
    private val cache = ConcurrentHashMap<String, String?>()

    /**
     * Search for a trailer by title, year, tmdbId, and type.
     * Returns a direct streaming URL or null.
     */
    suspend fun getTrailerUrl(
        title: String,
        year: String? = null,
        tmdbId: String? = null,
        type: String? = null
    ): String? = withContext(Dispatchers.IO) {
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
                if (isValidUrl(url)) {
                    Log.d(TAG, "Found trailer URL for $title")
                    cache[cacheKey] = url
                    return@withContext url
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
     * Get a direct streaming URL from a YouTube URL.
     */
    suspend fun getTrailerFromYouTubeUrl(
        youtubeUrl: String,
        title: String? = null,
        year: String? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Getting trailer from YouTube URL: $youtubeUrl")
            val response = trailerApi.getTrailer(
                youtubeUrl = youtubeUrl,
                title = title,
                year = year
            )

            if (response.isSuccessful) {
                val url = response.body()?.url
                if (isValidUrl(url)) {
                    return@withContext url
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting trailer from YouTube: ${e.message}", e)
            null
        }
    }

    private fun isValidUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return url.startsWith("http://") || url.startsWith("https://")
    }

    fun clearCache() {
        cache.clear()
    }
}
