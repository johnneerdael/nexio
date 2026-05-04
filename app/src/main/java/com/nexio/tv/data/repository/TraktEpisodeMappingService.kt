package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.data.remote.api.TraktApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Core-only port of NuvioTV's TraktEpisodeMappingService.
 *
 * Fetches Trakt's bulk season tree (see `TraktApi.getShowSeasons`) and caches
 * it per show, so the player can confirm a (season, episode) pair against
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
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService
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
        val traktEpisodes = getTraktEpisodes(showLookupId)
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

    private suspend fun getTraktEpisodes(showLookupId: String): List<EpisodeMappingEntry> {
        cacheMutex.withLock {
            traktEpisodesCache[showLookupId]?.let { return it }
        }

        // Dedup: if another coroutine is already fetching this show, await its result.
        val existingDeferred = cacheMutex.withLock { traktEpisodesInFlight[showLookupId] }
        if (existingDeferred != null) {
            return try { existingDeferred.await() } catch (_: Exception) { emptyList() }
        }

        val deferred = kotlinx.coroutines.CompletableDeferred<List<EpisodeMappingEntry>>()
        val weOwn = cacheMutex.withLock {
            traktEpisodesCache[showLookupId]?.let { return it }
            if (traktEpisodesInFlight.containsKey(showLookupId)) {
                false
            } else {
                traktEpisodesInFlight[showLookupId] = deferred
                true
            }
        }
        if (!weOwn) {
            val other = cacheMutex.withLock { traktEpisodesInFlight[showLookupId] }
            return try { other?.await() ?: emptyList() } catch (_: Exception) { emptyList() }
        }

        return try {
            val episodes = fetchTraktEpisodes(showLookupId)
            if (episodes.isNotEmpty()) {
                cacheMutex.withLock { traktEpisodesCache[showLookupId] = episodes }
            }
            deferred.complete(episodes)
            episodes
        } catch (e: Exception) {
            deferred.completeExceptionally(e)
            emptyList()
        } finally {
            cacheMutex.withLock { traktEpisodesInFlight.remove(showLookupId) }
        }
    }

    private suspend fun fetchTraktEpisodes(showLookupId: String): List<EpisodeMappingEntry> {
        val session = traktAuthService.accountScopedSession()
        val seasonsResponse = traktAuthService.executeAuthorizedRequestWithinRuntimeCall(session) { authHeader ->
            traktApi.getShowSeasons(
                authorization = authHeader,
                id = showLookupId,
                extended = "episodes"
            )
        } ?: return emptyList()
        if (!seasonsResponse.isSuccessful) {
            Log.w(
                TAG,
                "fetchTraktEpisodes: seasons request failed code=${seasonsResponse.code()} id=$showLookupId"
            )
            return emptyList()
        }

        return seasonsResponse.body()
            .orEmpty()
            .asSequence()
            .filter { (it.number ?: 0) > 0 }
            .sortedBy { it.number }
            .flatMap { seasonDto ->
                seasonDto.episodes.orEmpty().asSequence()
                    .mapNotNull { episodeDto ->
                        val seasonNumber = episodeDto.season ?: seasonDto.number ?: return@mapNotNull null
                        val episodeNumber = episodeDto.number ?: return@mapNotNull null
                        EpisodeMappingEntry(
                            season = seasonNumber,
                            episode = episodeNumber,
                            title = episodeDto.title
                        )
                    }
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
