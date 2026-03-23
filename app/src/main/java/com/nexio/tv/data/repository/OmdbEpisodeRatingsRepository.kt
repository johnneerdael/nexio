package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.data.local.OmdbSettingsDataStore
import com.nexio.tv.data.remote.api.OmdbApi
import com.nexio.tv.data.remote.api.OmdbSeasonResponse
import com.nexio.tv.domain.model.Meta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "OmdbEpisodeRatings"
internal const val OMDB_EPISODE_RATINGS_TTL_MS = 24L * 60L * 60L * 1000L

@Singleton
class OmdbEpisodeRatingsRepository @Inject constructor(
    private val omdbApi: OmdbApi,
    private val omdbSettingsDataStore: OmdbSettingsDataStore,
    private val tmdbService: com.nexio.tv.core.tmdb.TmdbService
) {
    private data class CacheEntry(
        val ratings: Map<Pair<Int, Int>, Double>,
        val expiresAtMs: Long
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val inFlight = mutableMapOf<String, kotlinx.coroutines.Deferred<Map<Pair<Int, Int>, Double>>>()
    private val inFlightMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO)
    internal var nowMsProvider: () -> Long = { System.currentTimeMillis() }

    suspend fun getEpisodeRatingsForMeta(
        meta: Meta,
        fallbackItemId: String,
        fallbackItemType: String,
        episodesBySeason: Map<Int, Set<Int>>
    ): Map<Pair<Int, Int>, Double> {
        if (episodesBySeason.isEmpty()) return emptyMap()

        val settings = omdbSettingsDataStore.settings.first()
        if (!settings.isActive) return emptyMap()

        val apiKey = settings.apiKey.trim()
        val seriesImdbId = resolveSeriesImdbId(meta, fallbackItemId, fallbackItemType) ?: return emptyMap()
        val normalizedEpisodes = episodesBySeason.mapValues { (_, values) -> values.filter { it > 0 }.toSet() }
            .filterKeys { it > 0 }
            .filterValues { it.isNotEmpty() }
        if (normalizedEpisodes.isEmpty()) return emptyMap()

        return normalizedEpisodes.entries
            .map { (season, requestedEpisodes) ->
                scope.async {
                    getSeasonRatings(
                        seriesImdbId = seriesImdbId,
                        apiKey = apiKey,
                        season = season
                    ).filterKeys { (resolvedSeason, episode) ->
                        resolvedSeason == season && requestedEpisodes.contains(episode)
                    }
                }
            }
            .awaitAll()
            .fold(emptyMap()) { acc, seasonRatings -> acc + seasonRatings }
    }

    private suspend fun getSeasonRatings(
        seriesImdbId: String,
        apiKey: String,
        season: Int
    ): Map<Pair<Int, Int>, Double> {
        val cacheKey = buildCacheKey(seriesImdbId, season, apiKey)
        val now = nowMsProvider()

        cache[cacheKey]?.let { cached ->
            if (cached.expiresAtMs > now) return cached.ratings
        }

        val deferred = inFlightMutex.withLock {
            inFlight[cacheKey] ?: scope.async {
                val stale = cache[cacheKey]?.ratings.orEmpty()
                try {
                    val response = omdbApi.getSeason(
                        apiKey = apiKey,
                        seriesImdbId = seriesImdbId,
                        season = season
                    )
                    val fetched = if (response.isSuccessful) {
                        response.body()?.let { parseSeasonRatings(season, it) }.orEmpty()
                    } else {
                        emptyMap()
                    }
                    val resolved = if (fetched.isNotEmpty()) fetched else stale
                    cache[cacheKey] = CacheEntry(
                        ratings = resolved,
                        expiresAtMs = nowMsProvider() + OMDB_EPISODE_RATINGS_TTL_MS
                    )
                    resolved
                } catch (error: Exception) {
                    runCatching {
                        Log.w(TAG, "Failed OMDB episode ratings refresh for $seriesImdbId season $season: ${error.message}", error)
                    }
                    val fallback = stale
                    cache[cacheKey] = CacheEntry(
                        ratings = fallback,
                        expiresAtMs = nowMsProvider() + OMDB_EPISODE_RATINGS_TTL_MS
                    )
                    fallback
                } finally {
                    inFlightMutex.withLock { inFlight.remove(cacheKey) }
                }
            }.also { created ->
                inFlight[cacheKey] = created
            }
        }

        return deferred.await()
    }

    private suspend fun resolveSeriesImdbId(meta: Meta, fallbackItemId: String, fallbackItemType: String): String? {
        extractImdb(meta.id)?.let { return it }
        extractImdb(fallbackItemId)?.let { return it }

        val tmdbType = when (fallbackItemType.lowercase()) {
            "series", "tv", "show", "tvshow" -> "tv"
            else -> "tv"
        }

        val tmdbId = tmdbService.ensureTmdbId(meta.id, tmdbType)
            ?: tmdbService.ensureTmdbId(fallbackItemId, tmdbType)
            ?: return null

        return tmdbService.tmdbToImdb(tmdbId.toInt(), tmdbType)
    }

    private fun extractImdb(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return Regex("tt\\d+").find(raw)?.value
    }

    private fun buildCacheKey(seriesImdbId: String, season: Int, apiKey: String): String {
        return "$seriesImdbId:$season:${apiKey.hashCode()}"
    }
}

internal fun parseSeasonRatings(
    season: Int,
    response: OmdbSeasonResponse
): Map<Pair<Int, Int>, Double> {
    if (!response.response.equals("True", ignoreCase = true)) return emptyMap()

    return response.episodes.mapNotNull { episode ->
        val episodeNumber = episode.episode?.trim()?.toIntOrNull() ?: return@mapNotNull null
        val rating = episode.imdbRating
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("N/A", ignoreCase = true) }
            ?.toDoubleOrNull()
            ?.takeIf { it > 0.0 }
            ?: return@mapNotNull null
        (season to episodeNumber) to rating
    }.toMap()
}
