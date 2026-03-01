package com.nuvio.tv.data.trailer

import android.util.Log
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbVideoResult
import com.nuvio.tv.data.remote.api.TrailerApi
import java.net.URI
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "TrailerService"
private const val TMDB_API_KEY = "439c478a771f35c05022f9feabcca01c"
private const val TMDB_TRAILER_LANGUAGE = "en-US"

@Singleton
class TrailerService @Inject constructor(
    private val trailerApi: TrailerApi,
    private val tmdbApi: TmdbApi,
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

            // 1) TMDB-first path (independent of TMDB enrichment settings).
            val tmdbSource = getTrailerPlaybackSourceFromTmdbId(
                tmdbId = tmdbId,
                type = type,
                title = title,
                year = year
            )
            if (tmdbSource != null) {
                cache[cacheKey] = tmdbSource
                return@withContext tmdbSource
            }
            Log.w(TAG, "TMDB path exhausted; no YouTube trailer key resolved for backend /trailer fallback")
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
     * TMDB-first resolution using /movie/{id}/videos or /tv/{id}/videos.
     */
    suspend fun getTrailerPlaybackSourceFromTmdbId(
        tmdbId: String?,
        type: String?,
        title: String? = null,
        year: String? = null
    ): TrailerPlaybackSource? = withContext(Dispatchers.IO) {
        val numericTmdbId = tmdbId?.toIntOrNull() ?: return@withContext null
        val mediaType = normalizeTmdbMediaType(type)
        Log.d(TAG, "TMDB trailer lookup start: tmdbId=$numericTmdbId type=${mediaType ?: "unknown"}")

        val tmdbResults = when (mediaType) {
            "movie" -> fetchTmdbMovieVideos(numericTmdbId)
            "tv" -> fetchTmdbTvVideos(numericTmdbId)
            else -> fetchTmdbMovieVideos(numericTmdbId) + fetchTmdbTvVideos(numericTmdbId)
        }

        val candidates = rankTmdbVideoCandidates(tmdbResults)
        Log.d(TAG, "TMDB candidate count: ${candidates.size}")

        for (candidate in candidates) {
            val key = candidate.key?.trim().orEmpty()
            if (key.isBlank()) continue
            Log.d(
                TAG,
                "TMDB selected candidate: type=${candidate.type.orEmpty()} " +
                    "official=${candidate.official == true} key=${obfuscateYoutubeKey(key)}"
            )

            val youtubeUrl = "https://www.youtube.com/watch?v=$key"
            val source = getTrailerPlaybackSourceFromYouTubeUrl(
                youtubeUrl = youtubeUrl,
                title = title,
                year = year
            )
            if (source != null) {
                return@withContext source
            }

            Log.d(
                TAG,
                "TMDB candidate extraction failed, trying next: key=${obfuscateYoutubeKey(key)}"
            )
        }

        null
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

    private suspend fun fetchTmdbMovieVideos(tmdbId: Int): List<TmdbVideoResult> {
        return try {
            val response = tmdbApi.getMovieVideos(
                movieId = tmdbId,
                apiKey = TMDB_API_KEY,
                language = TMDB_TRAILER_LANGUAGE
            )
            if (!response.isSuccessful) {
                Log.w(TAG, "TMDB movie videos request failed ($tmdbId): ${response.code()}")
                emptyList()
            } else {
                response.body()?.results.orEmpty()
            }
        } catch (e: Exception) {
            Log.w(TAG, "TMDB movie videos error ($tmdbId): ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchTmdbTvVideos(tmdbId: Int): List<TmdbVideoResult> {
        return try {
            val response = tmdbApi.getTvVideos(
                tvId = tmdbId,
                apiKey = TMDB_API_KEY,
                language = TMDB_TRAILER_LANGUAGE
            )
            if (!response.isSuccessful) {
                Log.w(TAG, "TMDB tv videos request failed ($tmdbId): ${response.code()}")
                emptyList()
            } else {
                response.body()?.results.orEmpty()
            }
        } catch (e: Exception) {
            Log.w(TAG, "TMDB tv videos error ($tmdbId): ${e.message}")
            emptyList()
        }
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

    private fun obfuscateYoutubeKey(key: String): String {
        if (key.length <= 4) return "****"
        return "***${key.takeLast(4)}"
    }

    fun clearCache() {
        cache.clear()
    }
}

internal fun normalizeTmdbMediaType(type: String?): String? {
    return when (type?.lowercase()) {
        "movie", "film" -> "movie"
        "tv", "series", "show", "tvshow" -> "tv"
        else -> null
    }
}

internal fun rankTmdbVideoCandidates(results: List<TmdbVideoResult>): List<TmdbVideoResult> {
    return results
        .asSequence()
        .filter { (it.site ?: "").equals("YouTube", ignoreCase = true) }
        .filter { !it.key.isNullOrBlank() }
        .filter {
            val normalizedType = it.type?.trim()?.lowercase()
            normalizedType == "trailer" || normalizedType == "teaser"
        }
        .sortedWith(
            compareBy<TmdbVideoResult> { videoTypePriority(it.type) }
                .thenBy { if (it.official == true) 0 else 1 }
                .thenByDescending { it.size ?: 0 }
                .thenByDescending { parsePublishedAtEpoch(it.publishedAt) }
        )
        .toList()
}

private fun videoTypePriority(type: String?): Int {
    return when (type?.trim()?.lowercase()) {
        "trailer" -> 0
        "teaser" -> 1
        else -> 2
    }
}

private fun parsePublishedAtEpoch(value: String?): Long {
    if (value.isNullOrBlank()) return Long.MIN_VALUE
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(Long.MIN_VALUE)
}
