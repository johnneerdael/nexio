package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.data.remote.CustomImdbClient
import com.nexio.tv.domain.model.Meta
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val CUSTOM_IMDB_TAG = "CustomImdbEpisodeRatings"
internal const val CUSTOM_IMDB_EPISODE_RATINGS_COMPLETE_TTL_MS = 7L * 24L * 60L * 60L * 1000L
internal const val CUSTOM_IMDB_EPISODE_RATINGS_RETRY_TTL_MS = 30L * 60L * 1000L

@Singleton
class CustomImdbEpisodeRatingsRepository @Inject constructor(
    private val customImdbClient: CustomImdbClient,
    private val tmdbService: TmdbService
) {
    private data class CacheEntry(
        val ratings: Map<Pair<Int, Int>, Double>,
        val expiresAtMs: Long
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val cacheMutex = Mutex()

    internal var nowMsProvider: () -> Long = { System.currentTimeMillis() }

    suspend fun getEpisodeRatingsForMeta(
        meta: Meta,
        fallbackItemId: String,
        fallbackItemType: String,
        episodesBySeason: Map<Int, Set<Int>>
    ): Map<Pair<Int, Int>, Double> {
        if (episodesBySeason.isEmpty()) return emptyMap()

        val seriesImdbId = resolveSeriesImdbId(meta, fallbackItemId, fallbackItemType) ?: return emptyMap()
        val requestedEpisodes = normalizeRequestedEpisodes(episodesBySeason)
        if (requestedEpisodes.isEmpty()) return emptyMap()

        val cacheKey = buildCacheKey(
            seriesImdbId = seriesImdbId,
            requestedEpisodes = requestedEpisodes
        )
        val now = nowMsProvider()

        cache[cacheKey]?.takeIf { it.expiresAtMs > now }?.let { return it.ratings }

        return cacheMutex.withLock {
            cache[cacheKey]?.takeIf { it.expiresAtMs > nowMsProvider() }?.ratings ?: fetchAndCache(
                cacheKey = cacheKey,
                seriesImdbId = seriesImdbId,
                requestedEpisodes = requestedEpisodes
            )
        }
    }

    private suspend fun fetchAndCache(
        cacheKey: String,
        seriesImdbId: String,
        requestedEpisodes: Map<Int, Set<Int>>
    ): Map<Pair<Int, Int>, Double> {
        val stale = cache[cacheKey]?.ratings.orEmpty()
        val expectedCount = requestedEpisodes.values.sumOf { it.size }

        val fetched = runCatching {
            customImdbClient.fetchEpisodeRatings(seriesImdbId).filterKeys { (season, episode) ->
                requestedEpisodes[season]?.contains(episode) == true
            }
        }.getOrElse { error ->
            Log.w(CUSTOM_IMDB_TAG, "Failed custom IMDb episode ratings for $seriesImdbId: ${error.message}", error)
            emptyMap()
        }

        val resolved = if (fetched.isNotEmpty() || stale.isEmpty()) fetched else stale
        val ttlMs = if (expectedCount > 0 && resolved.size == expectedCount) {
            CUSTOM_IMDB_EPISODE_RATINGS_COMPLETE_TTL_MS
        } else {
            CUSTOM_IMDB_EPISODE_RATINGS_RETRY_TTL_MS
        }

        cache[cacheKey] = CacheEntry(
            ratings = resolved,
            expiresAtMs = nowMsProvider() + ttlMs
        )

        return resolved
    }

    private suspend fun resolveSeriesImdbId(meta: Meta, fallbackItemId: String, fallbackItemType: String): String? {
        extractImdbId(meta.id)?.let { return it }
        extractImdbId(fallbackItemId)?.let { return it }

        val tmdbType = when (fallbackItemType.trim().lowercase()) {
            "series", "tv", "show", "tvshow" -> "tv"
            else -> "tv"
        }

        val tmdbId = tmdbService.ensureTmdbId(meta.id, tmdbType)
            ?: tmdbService.ensureTmdbId(fallbackItemId, tmdbType)
            ?: return null

        return tmdbService.tmdbToImdb(tmdbId.toInt(), tmdbType)
    }

    private fun normalizeRequestedEpisodes(
        episodesBySeason: Map<Int, Set<Int>>
    ): Map<Int, Set<Int>> {
        return episodesBySeason
            .filterKeys { it > 0 }
            .mapValues { (_, episodes) -> episodes.filter { it > 0 }.toSet() }
            .filterValues { it.isNotEmpty() }
    }

    private fun buildCacheKey(
        seriesImdbId: String,
        requestedEpisodes: Map<Int, Set<Int>>
    ): String {
        val requestSignature = requestedEpisodes.entries
            .sortedBy { it.key }
            .joinToString(separator = "|") { (season, episodes) ->
                val normalizedEpisodes = episodes.toList().sorted().joinToString(separator = ",")
                "$season:$normalizedEpisodes"
            }

        return "$seriesImdbId:$requestSignature"
    }

    private fun extractImdbId(rawId: String?): String? {
        if (rawId.isNullOrBlank()) return null
        return Regex("tt\\d+").find(rawId)?.value
    }
}
