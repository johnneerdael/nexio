package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.data.remote.api.SeriesGraphApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImdbEpisodeRatingsRepository @Inject constructor(
    private val api: SeriesGraphApi
) {
    private data class CacheEntry(
        val ratings: Map<Pair<Int, Int>, Double>,
        val expiresAtMs: Long
    )

    private val tag = "ImdbEpisodeRatingsRepo"
    private val cacheTtlMs = 30L * 60L * 1000L
    private val cache = ConcurrentHashMap<Int, CacheEntry>()
    private val inFlight = mutableMapOf<Int, kotlinx.coroutines.Deferred<Map<Pair<Int, Int>, Double>>>()
    private val inFlightMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun getEpisodeRatings(tmdbId: Int): Map<Pair<Int, Int>, Double> {
        if (tmdbId <= 0) return emptyMap()

        val now = System.currentTimeMillis()
        cache[tmdbId]?.let { cached ->
            if (cached.expiresAtMs > now) return cached.ratings
            cache.remove(tmdbId)
        }

        val deferred = inFlightMutex.withLock {
            inFlight[tmdbId] ?: scope.async {
                try {
                    fetchEpisodeRatings(tmdbId).also { result ->
                        cache[tmdbId] = CacheEntry(
                            ratings = result,
                            expiresAtMs = System.currentTimeMillis() + cacheTtlMs
                        )
                    }
                } finally {
                    inFlightMutex.withLock {
                        inFlight.remove(tmdbId)
                    }
                }
            }.also { created ->
                inFlight[tmdbId] = created
            }
        }

        return deferred.await()
    }

    private suspend fun fetchEpisodeRatings(tmdbId: Int): Map<Pair<Int, Int>, Double> {
        return try {
            val response = api.getSeasonRatings(tmdbId)
            if (!response.isSuccessful) {
                Log.w(tag, "Failed season ratings for tmdbId=$tmdbId (${response.code()})")
                return emptyMap()
            }

            val payload = response.body().orEmpty()
            buildMap {
                payload.forEach { season ->
                    season.episodes.orEmpty().forEach { episode ->
                        val seasonNumber = episode.seasonNumber ?: return@forEach
                        val episodeNumber = episode.episodeNumber ?: return@forEach
                        val voteAverage = episode.voteAverage ?: return@forEach
                        put(seasonNumber to episodeNumber, voteAverage)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Error fetching season ratings for tmdbId=$tmdbId", e)
            emptyMap()
        }
    }
}
