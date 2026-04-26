package com.nexio.tv.core.tmdb

import android.util.Log
import com.nexio.tv.data.integration.tmdb.DefaultTmdbExternalIdLookupProvider
import com.nexio.tv.data.integration.tmdb.TmdbExternalIdLookupProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TmdbService"

/**
 * Service to handle TMDB ID conversions and lookups.
 * Provides caching to avoid redundant API calls.
 */
@Singleton
class TmdbService {
    private val externalIdLookupProvider: TmdbExternalIdLookupProvider

    @Inject
    constructor(externalIdLookupProvider: DefaultTmdbExternalIdLookupProvider) {
        this.externalIdLookupProvider = externalIdLookupProvider
    }

    // Cache: IMDB ID -> TMDB ID
    private val imdbToTmdbCache = ConcurrentHashMap<String, Int>()
    
    // Cache: normalized media type + TMDB ID -> IMDB ID
    private val tmdbToImdbCache = ConcurrentHashMap<String, String>()

    private val imdbToTmdbInFlight = ConcurrentHashMap<String, CompletableDeferred<Int?>>()
    private val tmdbToImdbInFlight = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    
    // Mutex for thread-safe cache operations
    private val cacheMutex = Mutex()
    
    /**
     * Convert an IMDB ID to a TMDB ID.
     * 
     * @param imdbId The IMDB ID (e.g., "tt0133093")
     * @param mediaType The media type ("movie" or "series"/"tv")
     * @return The TMDB ID, or null if not found
     */
    suspend fun imdbToTmdb(imdbId: String, mediaType: String): Int? = withContext(Dispatchers.IO) {
        // Validate IMDB ID format
        if (!imdbId.startsWith("tt")) {
            Log.w(TAG, "Invalid IMDB ID format: $imdbId")
            return@withContext null
        }

        val normalizedType = normalizeMediaType(mediaType)
        val inFlightKey = "$normalizedType:$imdbId"
        
        // Check cache first
        imdbToTmdbCache[imdbId]?.let { cached ->
            Log.d(TAG, "Cache hit: IMDB $imdbId -> TMDB $cached")
            return@withContext cached
        }

        imdbToTmdbInFlight[inFlightKey]?.let { existing ->
            Log.d(TAG, "Joining in-flight TMDB lookup for IMDB: $imdbId (type: $normalizedType)")
            return@withContext existing.await()
        }

        val deferred = CompletableDeferred<Int?>()
        val existingDeferred = imdbToTmdbInFlight.putIfAbsent(inFlightKey, deferred)
        if (existingDeferred != null) {
            Log.d(TAG, "Joining in-flight TMDB lookup for IMDB: $imdbId (type: $normalizedType)")
            return@withContext existingDeferred.await()
        }
        
        try {
            Log.d(TAG, "Looking up TMDB ID for IMDB: $imdbId (type: $mediaType)")

            val tmdbId = externalIdLookupProvider.findTmdbIdByImdbId(imdbId, normalizedType)

            tmdbId?.let { foundId ->
                Log.d(TAG, "Found TMDB ID: $foundId for IMDB: $imdbId")

                // Cache both directions
                cacheMutex.withLock {
                    imdbToTmdbCache[imdbId] = foundId
                    tmdbToImdbCache[tmdbToImdbCacheKey(foundId, normalizedType)] = imdbId
                }
                deferred.complete(foundId)

                return@withContext foundId
            }

            Log.w(TAG, "No TMDB result found for IMDB: $imdbId")
            deferred.complete(null)
            null
            
        } catch (e: CancellationException) {
            Log.w(TAG, "Cancelled TMDB ID lookup for IMDB: $imdbId", e)
            deferred.completeExceptionally(e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error looking up TMDB ID for $imdbId: ${e.message}", e)
            deferred.complete(null)
            null
        } finally {
            imdbToTmdbInFlight.remove(inFlightKey, deferred)
        }
    }
    
    /**
     * Convert a TMDB ID to an IMDB ID.
     * 
     * @param tmdbId The TMDB ID
     * @param mediaType The media type ("movie" or "series"/"tv")
     * @return The IMDB ID, or null if not found
     */
    suspend fun tmdbToImdb(tmdbId: Int, mediaType: String): String? = withContext(Dispatchers.IO) {
        val normalizedType = normalizeMediaType(mediaType)
        val cacheKey = tmdbToImdbCacheKey(tmdbId, normalizedType)

        // Check cache first
        tmdbToImdbCache[cacheKey]?.let { cached ->
            Log.d(TAG, "Cache hit: TMDB $tmdbId (type: $normalizedType) -> IMDB $cached")
            return@withContext cached
        }

        val inFlightKey = "$normalizedType:$tmdbId"

        tmdbToImdbInFlight[inFlightKey]?.let { existing ->
            Log.d(TAG, "Joining in-flight IMDB lookup for TMDB: $tmdbId (type: $normalizedType)")
            return@withContext existing.await()
        }

        val deferred = CompletableDeferred<String?>()
        val existingDeferred = tmdbToImdbInFlight.putIfAbsent(inFlightKey, deferred)
        if (existingDeferred != null) {
            Log.d(TAG, "Joining in-flight IMDB lookup for TMDB: $tmdbId (type: $normalizedType)")
            return@withContext existingDeferred.await()
        }
        
        try {
            Log.d(TAG, "Looking up IMDB ID for TMDB: $tmdbId (type: $mediaType)")

            externalIdLookupProvider.findImdbIdByTmdbId(tmdbId, normalizedType)?.let { imdbId ->
                Log.d(TAG, "Found IMDB ID: $imdbId for TMDB: $tmdbId")

                // Cache both directions
                cacheMutex.withLock {
                    tmdbToImdbCache[cacheKey] = imdbId
                    imdbToTmdbCache[imdbId] = tmdbId
                }
                deferred.complete(imdbId)
                
                return@withContext imdbId
            }
            
            Log.w(TAG, "No IMDB ID found for TMDB: $tmdbId")
            deferred.complete(null)
            null
            
        } catch (e: CancellationException) {
            Log.w(TAG, "Cancelled IMDb ID lookup for TMDB: $tmdbId", e)
            deferred.completeExceptionally(e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error looking up IMDB ID for $tmdbId: ${e.message}", e)
            deferred.complete(null)
            null
        } finally {
            tmdbToImdbInFlight.remove(inFlightKey, deferred)
        }
    }
    
    /**
     * Get a TMDB ID from a video ID string.
     * Handles both IMDB IDs (tt...) and TMDB IDs.
     * 
     * @param videoId The video ID (can be IMDB or TMDB format)
     * @param mediaType The media type
     * @return The TMDB ID as a string, or null if conversion failed
     */
    suspend fun ensureTmdbId(videoId: String, mediaType: String): String? {
        // Check if it's already a TMDB ID (numeric or prefixed)
        val cleanId = videoId
            .removePrefix("tmdb:")
            .removePrefix("movie:")
            .removePrefix("series:")

        // Stremio-style series ids can look like: tt1234567:season:episode
        // Plugins/TMDB lookup need the base external id only.
        val idPart = cleanId
            .substringBefore(':')
            .substringBefore('/')
            .trim()
        
        // If it's an IMDB ID, convert it
        if (idPart.startsWith("tt")) {
            val tmdbId = imdbToTmdb(idPart, normalizeMediaType(mediaType))
            return tmdbId?.toString()
        }
        
        // If it looks like a numeric ID, assume it's already a TMDB ID
        if (idPart.all { it.isDigit() }) {
            return idPart
        }
        
        // Unknown format
        Log.w(TAG, "Unknown video ID format: $videoId")
        return null
    }
    
    /**
     * Normalize media type to consistent format
     */
    private fun normalizeMediaType(mediaType: String): String {
        return when (mediaType.lowercase()) {
            "series", "tv", "show", "tvshow" -> "tv"
            "movie", "film" -> "movie"
            else -> mediaType.lowercase()
        }
    }

    private fun tmdbToImdbCacheKey(tmdbId: Int, normalizedMediaType: String): String {
        return "$normalizedMediaType:$tmdbId"
    }
    
    /**
     * Clear all caches
     */
    fun clearCache() {
        imdbToTmdbCache.clear()
        tmdbToImdbCache.clear()
        Log.d(TAG, "Cache cleared")
    }
    
    /**
     * Pre-populate cache with known mappings
     */
    fun preCacheMapping(imdbId: String, tmdbId: Int, mediaType: String = "movie") {
        val normalizedType = normalizeMediaType(mediaType)
        imdbToTmdbCache[imdbId] = tmdbId
        tmdbToImdbCache[tmdbToImdbCacheKey(tmdbId, normalizedType)] = imdbId
    }
}
