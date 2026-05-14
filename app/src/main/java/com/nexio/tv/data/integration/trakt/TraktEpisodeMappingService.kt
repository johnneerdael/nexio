package com.nexio.tv.data.integration.trakt

import android.util.Log
import com.nexio.tv.core.integration.valueOrNull
import com.nexio.tv.data.repository.EpisodeMappingEntry
import com.nexio.tv.data.repository.hasAnyId
import com.nexio.tv.data.repository.parseContentIds
import com.nexio.tv.data.repository.toTraktIds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Core-only port of NuvioTV's TraktEpisodeMappingService.
 *
 * Fetches Trakt's season tree through IntegrationRuntime and caches it per
 * show/season, so the player can confirm a (season, episode) pair against
 * Trakt's canonical episode list before scrobbling.
 *
 * Unlike the upstream implementation, this port intentionally drops the
 * Stremio addon-metadata fallback (`fetchSeriesMeta` /
 * `MetaRepository.getMetaFromAllAddons`). When Trakt has no matching episode
 * for the requested (season, episode), the service simply returns `null` and
 * callers keep the original absolute episode number — same as today's
 * behaviour.
 */
@Singleton
class TraktEpisodeMappingService @Inject constructor(
    private val traktIntegrationProvider: TraktIntegrationProvider
) {
    companion object {
        private const val TAG = "TraktEpMapSvc"
    }

    private val cacheMutex = Mutex()
    private val mappingCache = mutableMapOf<String, EpisodeMappingEntry>()
    private val traktEpisodesCache = mutableMapOf<String, List<EpisodeMappingEntry>>()
    // In-flight dedup: prevents multiple concurrent coroutines from fetching
    // the same show's Trakt season tree simultaneously.
    private val traktEpisodesInFlight =
        mutableMapOf<String, kotlinx.coroutines.CompletableDeferred<List<EpisodeMappingEntry>>>()

    /**
     * Returns the canonical Trakt entry for the given (season, episode), or
     * `null` when:
     *   - any required argument is missing/blank,
     *   - Trakt has no season data for the show, or
     *   - Trakt's season tree contains no matching episode.
     *
     * Network/auth errors are also surfaced as `null` by design — callers
     * should treat `null` as "use the absolute episode number you started
     * with", never as "retry".
     */
    internal suspend fun prefetchEpisodeMapping(
        contentId: String?,
        contentType: String?,
        videoId: String?,
        season: Int?,
        episode: Int?
    ): EpisodeMappingEntry? {
        val key = cacheKey(contentId, contentType, videoId, season, episode) ?: return null
        cacheMutex.withLock {
            mappingCache[key]?.let { return it }
        }

        val requestedSeason = season ?: return null
        val requestedEpisode = episode ?: return null
        val resolvedContentId = contentId?.takeIf { it.isNotBlank() } ?: return null

        val showLookupId = resolveShowLookupId(contentId = resolvedContentId, videoId = videoId)
            ?: return null
        val traktEpisodes = getTraktEpisodes(showLookupId, requestedSeason)
        if (traktEpisodes.isEmpty()) return null

        val mapped = traktEpisodes.firstOrNull {
            it.season == requestedSeason && it.episode == requestedEpisode
        } ?: return null

        cacheMutex.withLock {
            mappingCache[key] = mapped
        }
        return mapped
    }

    /**
     * Returns the cached mapping for the given key, if any. Does not perform
     * any network I/O.
     */
    internal suspend fun getCachedEpisodeMapping(
        contentId: String?,
        contentType: String?,
        videoId: String?,
        season: Int?,
        episode: Int?
    ): EpisodeMappingEntry? {
        val key = cacheKey(contentId, contentType, videoId, season, episode) ?: return null
        return cacheMutex.withLock { mappingCache[key] }
    }

    private suspend fun getTraktEpisodes(showLookupId: String, season: Int): List<EpisodeMappingEntry> {
        val cacheKey = "$showLookupId|$season"
        val ownDeferred: kotlinx.coroutines.CompletableDeferred<List<EpisodeMappingEntry>>?
        val joinDeferred: kotlinx.coroutines.CompletableDeferred<List<EpisodeMappingEntry>>?
        cacheMutex.withLock {
            traktEpisodesCache[cacheKey]?.let { return it }
            val existing = traktEpisodesInFlight[cacheKey]
            if (existing != null) {
                ownDeferred = null
                joinDeferred = existing
            } else {
                val newDeferred =
                    kotlinx.coroutines.CompletableDeferred<List<EpisodeMappingEntry>>()
                traktEpisodesInFlight[cacheKey] = newDeferred
                ownDeferred = newDeferred
                joinDeferred = null
            }
        }
        if (joinDeferred != null) {
            return try {
                joinDeferred.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }
        val deferred = ownDeferred!!
        return try {
            val episodes = fetchTraktEpisodes(showLookupId, season)
            cacheMutex.withLock {
                traktEpisodesCache[cacheKey] = episodes
                traktEpisodesInFlight.remove(cacheKey)
            }
            deferred.complete(episodes)
            episodes
        } catch (e: kotlinx.coroutines.CancellationException) {
            cacheMutex.withLock { traktEpisodesInFlight.remove(cacheKey) }
            deferred.completeExceptionally(e)
            throw e
        } catch (e: Exception) {
            cacheMutex.withLock { traktEpisodesInFlight.remove(cacheKey) }
            deferred.completeExceptionally(e)
            emptyList()
        }
    }

    private suspend fun fetchTraktEpisodes(showLookupId: String, season: Int): List<EpisodeMappingEntry> {
        val episodes = traktIntegrationProvider.getSeasonEpisodes(
            id = showLookupId,
            season = season,
            extended = "full"
        ).valueOrNull()
        if (episodes == null) {
            Log.w(TAG, "fetchTraktEpisodes: season request failed id=$showLookupId season=$season")
            return emptyList()
        }

        return episodes
            .asSequence()
            .mapNotNull { episodeDto ->
                val seasonNumber = episodeDto.season ?: season
                val episodeNumber = episodeDto.number ?: return@mapNotNull null
                EpisodeMappingEntry(
                    season = seasonNumber,
                    episode = episodeNumber,
                    title = episodeDto.title
                )
            }
            .toList()
    }

    private fun cacheKey(
        contentId: String?,
        contentType: String?,
        videoId: String?,
        season: Int?,
        episode: Int?
    ): String? {
        val resolvedContentId = contentId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val resolvedContentType = contentType?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val resolvedSeason = season ?: return null
        val resolvedEpisode = episode ?: return null
        val resolvedVideoId = videoId?.trim().orEmpty()
        return "$resolvedContentType|$resolvedContentId|$resolvedVideoId|$resolvedSeason|$resolvedEpisode"
    }

    private fun resolveShowLookupId(contentId: String?, videoId: String?): String? {
        val contentIds = toTraktIds(parseContentIds(contentId))
        if (contentIds.hasAnyId()) {
            return when {
                !contentIds.imdb.isNullOrBlank() -> contentIds.imdb
                contentIds.trakt != null -> contentIds.trakt.toString()
                !contentIds.slug.isNullOrBlank() -> contentIds.slug
                else -> null
            }
        }

        val videoIds = toTraktIds(parseContentIds(videoId))
        return when {
            !videoIds.imdb.isNullOrBlank() -> videoIds.imdb
            videoIds.trakt != null -> videoIds.trakt.toString()
            !videoIds.slug.isNullOrBlank() -> videoIds.slug
            else -> null
        }
    }
}
