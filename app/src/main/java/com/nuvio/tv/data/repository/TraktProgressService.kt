package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktEpisodeDto
import com.nuvio.tv.data.remote.dto.trakt.TraktHistoryEpisodeRemoveDto
import com.nuvio.tv.data.remote.dto.trakt.TraktHistoryRemoveRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktHistorySeasonRemoveDto
import com.nuvio.tv.data.remote.dto.trakt.TraktHistoryShowRemoveDto
import com.nuvio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nuvio.tv.data.remote.dto.trakt.TraktPlaybackItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktShowSeasonProgressDto
import com.nuvio.tv.data.remote.dto.trakt.TraktUserEpisodeHistoryItemDto
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.MetaRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class TraktProgressService @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService,
    private val metaRepository: MetaRepository
) {
    private val refreshSignals = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val episodeVideoIdCache = mutableMapOf<String, String>()

    suspend fun refreshNow() {
        refreshSignals.emit(Unit)
    }

    fun observeAllProgress(): Flow<List<WatchProgress>> {
        return refreshEvents()
            .mapLatest { fetchAllProgressSnapshot() }
            .distinctUntilChanged()
    }

    fun observeEpisodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> {
        return refreshEvents()
            .mapLatest { fetchEpisodeProgressSnapshot(contentId) }
            .distinctUntilChanged()
    }

    suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) {
        val playbackMovies = getPlayback("movies")
        val playbackEpisodes = getPlayback("episodes")

        val target = contentId.trim()
        playbackMovies
            .filter { normalizeContentId(it.movie?.ids) == target }
            .forEach { item ->
                item.id?.let { playbackId ->
                    traktAuthService.executeAuthorizedRequest { authHeader ->
                        traktApi.deletePlayback(authHeader, playbackId)
                    }
                }
            }

        playbackEpisodes
            .filter { item ->
                val sameContent = normalizeContentId(item.show?.ids) == target
                val sameEpisode = if (season != null && episode != null) {
                    item.episode?.season == season && item.episode.number == episode
                } else {
                    true
                }
                sameContent && sameEpisode
            }
            .forEach { item ->
                item.id?.let { playbackId ->
                    traktAuthService.executeAuthorizedRequest { authHeader ->
                        traktApi.deletePlayback(authHeader, playbackId)
                    }
                }
            }

        val parsed = parseContentIds(contentId)
        val ids = toTraktIds(parsed)
        if (!ids.hasAnyId()) {
            refreshNow()
            return
        }

        val likelySeries = season != null && episode != null || playbackEpisodes.any {
            normalizeContentId(it.show?.ids) == target
        }

        val removeBody = if (likelySeries) {
            val seasons = if (season != null && episode != null) {
                listOf(
                    TraktHistorySeasonRemoveDto(
                        number = season,
                        episodes = listOf(TraktHistoryEpisodeRemoveDto(number = episode))
                    )
                )
            } else {
                null
            }
            TraktHistoryRemoveRequestDto(
                shows = listOf(
                    TraktHistoryShowRemoveDto(
                        ids = ids,
                        seasons = seasons
                    )
                )
            )
        } else {
            TraktHistoryRemoveRequestDto(
                movies = listOf(
                    TraktMovieDto(ids = ids)
                )
            )
        }

        traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.removeHistory(authHeader, removeBody)
        }

        refreshNow()
    }

    private fun refreshTicker(): Flow<Unit> = flow {
        while (true) {
            delay(60_000)
            emit(Unit)
        }
    }

    private fun refreshEvents(): Flow<Unit> {
        return merge(refreshTicker(), refreshSignals).onStart { emit(Unit) }
    }

    private suspend fun fetchAllProgressSnapshot(): List<WatchProgress> {
        val inProgressMovies = getPlayback("movies").mapNotNull { mapPlaybackMovie(it) }
        val inProgressEpisodes = getPlayback("episodes").mapNotNull { mapPlaybackEpisode(it) }
        val historyEpisodes = getEpisodeHistory(limit = 100).mapNotNull { mapEpisodeHistory(it) }

        val mergedByKey = linkedMapOf<String, WatchProgress>()
        historyEpisodes
            .sortedByDescending { it.lastWatched }
            .forEach { progress ->
                mergedByKey[progressKey(progress)] = progress
            }

        (inProgressMovies + inProgressEpisodes)
            .sortedByDescending { it.lastWatched }
            .forEach { progress ->
                mergedByKey[progressKey(progress)] = progress
            }

        return mergedByKey.values.sortedByDescending { it.lastWatched }
    }

    private suspend fun fetchEpisodeProgressSnapshot(
        contentId: String
    ): Map<Pair<Int, Int>, WatchProgress> {
        val pathId = toTraktPathId(contentId)
        val completed = mutableMapOf<Pair<Int, Int>, WatchProgress>()

        val response = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.getShowProgressWatched(
                authorization = authHeader,
                id = pathId
            )
        }

        if (response?.isSuccessful == true) {
            val seasons = response.body()?.seasons.orEmpty()
            seasons.forEach { season ->
                mapSeasonProgress(contentId, season).forEach { progress ->
                    val seasonNum = progress.season ?: return@forEach
                    val episodeNum = progress.episode ?: return@forEach
                    completed[seasonNum to episodeNum] = progress
                }
            }
        }

        val inProgress = getPlayback("episodes")
            .mapNotNull { mapPlaybackEpisode(it) }
            .filter { it.contentId == contentId }

        inProgress.forEach { progress ->
            val seasonNum = progress.season ?: return@forEach
            val episodeNum = progress.episode ?: return@forEach
            completed[seasonNum to episodeNum] = progress
        }

        return completed
    }

    private suspend fun getPlayback(type: String): List<TraktPlaybackItemDto> {
        val response = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.getPlayback(authHeader, type)
        } ?: return emptyList()

        return if (response.isSuccessful) response.body().orEmpty() else emptyList()
    }

    private suspend fun getEpisodeHistory(limit: Int): List<TraktUserEpisodeHistoryItemDto> {
        val response = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.getEpisodeHistory(authHeader, page = 1, limit = limit)
        } ?: return emptyList()

        return if (response.isSuccessful) response.body().orEmpty() else emptyList()
    }

    private suspend fun mapPlaybackMovie(item: TraktPlaybackItemDto): WatchProgress? {
        val movie = item.movie ?: return null
        val contentId = normalizeContentId(movie.ids)
        if (contentId.isBlank()) return null

        return WatchProgress(
            contentId = contentId,
            contentType = "movie",
            name = movie.title ?: contentId,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = contentId,
            season = null,
            episode = null,
            episodeTitle = null,
            position = 0L,
            duration = 0L,
            lastWatched = parseIsoToMillis(item.pausedAt),
            progressPercent = item.progress?.coerceIn(0f, 100f),
            source = WatchProgress.SOURCE_TRAKT_PLAYBACK,
            traktPlaybackId = item.id,
            traktMovieId = movie.ids?.trakt
        )
    }

    private suspend fun mapPlaybackEpisode(item: TraktPlaybackItemDto): WatchProgress? {
        val show = item.show ?: return null
        val episode = item.episode ?: return null
        val season = episode.season ?: return null
        val number = episode.number ?: return null

        val contentId = normalizeContentId(show.ids)
        if (contentId.isBlank()) return null
        val videoId = resolveEpisodeVideoId(contentId, season, number)

        return WatchProgress(
            contentId = contentId,
            contentType = "series",
            name = show.title ?: contentId,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = videoId,
            season = season,
            episode = number,
            episodeTitle = episode.title,
            position = 0L,
            duration = 0L,
            lastWatched = parseIsoToMillis(item.pausedAt),
            progressPercent = item.progress?.coerceIn(0f, 100f),
            source = WatchProgress.SOURCE_TRAKT_PLAYBACK,
            traktPlaybackId = item.id,
            traktShowId = show.ids?.trakt,
            traktEpisodeId = episode.ids?.trakt
        )
    }

    private suspend fun mapEpisodeHistory(item: TraktUserEpisodeHistoryItemDto): WatchProgress? {
        val show = item.show ?: return null
        val episode = item.episode ?: return null
        val season = episode.season ?: return null
        val number = episode.number ?: return null

        val contentId = normalizeContentId(show.ids)
        if (contentId.isBlank()) return null
        val videoId = resolveEpisodeVideoId(contentId, season, number)

        return WatchProgress(
            contentId = contentId,
            contentType = "series",
            name = show.title ?: contentId,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = videoId,
            season = season,
            episode = number,
            episodeTitle = episode.title,
            position = 1L,
            duration = 1L,
            lastWatched = parseIsoToMillis(item.watchedAt),
            progressPercent = 100f,
            source = WatchProgress.SOURCE_TRAKT_HISTORY,
            traktShowId = show.ids?.trakt,
            traktEpisodeId = episode.ids?.trakt
        )
    }

    private fun mapSeasonProgress(
        contentId: String,
        season: TraktShowSeasonProgressDto
    ): List<WatchProgress> {
        val seasonNumber = season.number ?: return emptyList()
        return season.episodes.orEmpty()
            .filter { it.completed == true }
            .mapNotNull { episode ->
                val episodeNumber = episode.number ?: return@mapNotNull null
                WatchProgress(
                    contentId = contentId,
                    contentType = "series",
                    name = contentId,
                    poster = null,
                    backdrop = null,
                    logo = null,
                    videoId = "$contentId:$seasonNumber:$episodeNumber",
                    season = seasonNumber,
                    episode = episodeNumber,
                    episodeTitle = null,
                    position = 1L,
                    duration = 1L,
                    lastWatched = parseIsoToMillis(episode.lastWatchedAt),
                    progressPercent = 100f,
                    source = WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS
                )
            }
    }

    private suspend fun resolveEpisodeVideoId(
        contentId: String,
        season: Int,
        episode: Int
    ): String {
        val key = "$contentId:$season:$episode"
        episodeVideoIdCache[key]?.let { return it }

        val candidates = buildList {
            add(contentId)
            if (contentId.startsWith("tmdb:")) add(contentId.substringAfter(':'))
            if (contentId.startsWith("trakt:")) add(contentId.substringAfter(':'))
        }.distinct()

        for (candidate in candidates) {
            for (type in listOf("series", "tv")) {
                val result = withTimeoutOrNull(2500) {
                    metaRepository.getMetaFromAllAddons(type = type, id = candidate)
                        .first { it !is NetworkResult.Loading }
                } ?: continue

                val meta = (result as? NetworkResult.Success)?.data ?: continue
                val videoId = meta.videos.firstOrNull {
                    it.season == season && it.episode == episode
                }?.id

                if (!videoId.isNullOrBlank()) {
                    episodeVideoIdCache[key] = videoId
                    return videoId
                }
            }
        }

        return "$contentId:$season:$episode"
    }

    private fun progressKey(progress: WatchProgress): String {
        return if (progress.season != null && progress.episode != null) {
            "${progress.contentId}_s${progress.season}e${progress.episode}"
        } else {
            progress.contentId
        }
    }
}
