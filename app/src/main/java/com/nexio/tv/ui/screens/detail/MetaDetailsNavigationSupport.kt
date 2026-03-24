package com.nexio.tv.ui.screens.detail

import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.Video
import com.nexio.tv.domain.model.WatchProgress

internal data class SeriesNextToWatchCandidate(
    val watchProgress: WatchProgress?,
    val isResume: Boolean,
    val nextVideoId: String?,
    val nextSeason: Int?,
    val nextEpisode: Int?
)

internal fun buildSeriesNextToWatchCandidate(
    episodes: List<Video>,
    progressMap: Map<Pair<Int, Int>, WatchProgress>,
    metaId: String
): SeriesNextToWatchCandidate {
    val orderedEpisodes = episodes
        .filter { it.season != null && it.episode != null }
        .sortedWith(compareBy<Video> { it.season }.thenBy { it.episode })

    if (orderedEpisodes.isEmpty()) {
        return SeriesNextToWatchCandidate(
            watchProgress = null,
            isResume = false,
            nextVideoId = metaId,
            nextSeason = null,
            nextEpisode = null
        )
    }

    val regularEpisodes = orderedEpisodes.filter { (it.season ?: 0) > 0 }
    val episodePool = if (regularEpisodes.isNotEmpty()) regularEpisodes else orderedEpisodes

    val latestInProgress = progressMap.values
        .filter { it.isInProgress() }
        .maxByOrNull { it.lastWatched }

    val latestCompleted = progressMap.values
        .filter { it.isCompleted() }
        .maxByOrNull { it.lastWatched }

    val preferCompletedContext = latestCompleted != null &&
        (latestInProgress == null || latestCompleted.lastWatched >= latestInProgress.lastWatched)

    if (preferCompletedContext) {
        val nextEpisode = nextEpisodeAfter(
            episodes = orderedEpisodes,
            season = latestCompleted.season,
            episode = latestCompleted.episode
        )
        if (nextEpisode != null) {
            return SeriesNextToWatchCandidate(
                watchProgress = null,
                isResume = false,
                nextVideoId = nextEpisode.id,
                nextSeason = nextEpisode.season,
                nextEpisode = nextEpisode.episode
            )
        }
    } else if (latestInProgress != null) {
        return buildResumeCandidate(
            progress = latestInProgress,
            episodes = orderedEpisodes,
            fallbackVideoId = latestInProgress.videoId
        )
    }

    val nextUnwatchedEpisode = episodePool.firstOrNull { episode ->
        progressMap[episode.season to episode.episode]?.isCompleted() != true
    } ?: episodePool.firstOrNull()

    return SeriesNextToWatchCandidate(
        watchProgress = null,
        isResume = false,
        nextVideoId = nextUnwatchedEpisode?.id ?: metaId,
        nextSeason = nextUnwatchedEpisode?.season,
        nextEpisode = nextUnwatchedEpisode?.episode
    )
}

internal fun shouldAutoSwitchToTargetSeason(
    selectedSeason: Int,
    targetSeason: Int?,
    manualOverrideActive: Boolean,
    availableSeasons: List<Int>
): Boolean {
    if (manualOverrideActive) return false
    if (targetSeason == null) return false
    if (targetSeason !in availableSeasons) return false
    return selectedSeason != targetSeason
}

internal fun resolveSeasonEntryEpisodeId(
    meta: Meta,
    selectedSeason: Int,
    nextToWatch: SeriesNextToWatchCandidate,
    lastFocusedEpisodeIdBySeason: Map<Int, String>,
    manualSeasonOverride: Boolean
): String? {
    lastFocusedEpisodeIdBySeason[selectedSeason]?.let { return it }

    val seasonEpisodes = meta.videos
        .asSequence()
        .filter { it.season == selectedSeason && it.episode != null }
        .sortedBy { it.episode }
        .toList()

    if (seasonEpisodes.isEmpty()) return null

    if (manualSeasonOverride) {
        return seasonEpisodes.first().id
    }

    val targetSeason = nextToWatch.nextSeason
    val targetEpisode = nextToWatch.nextEpisode
    if (targetSeason == selectedSeason && targetEpisode != null) {
        seasonEpisodes.firstOrNull { it.episode == targetEpisode }?.let { return it.id }
    }

    return seasonEpisodes.first().id
}

private fun buildResumeCandidate(
    progress: WatchProgress,
    episodes: List<Video>,
    fallbackVideoId: String
): SeriesNextToWatchCandidate {
    val matchedEpisode = progress.season?.let { season ->
        progress.episode?.let { episode ->
            episodes.firstOrNull { it.season == season && it.episode == episode }
        }
    }

    return SeriesNextToWatchCandidate(
        watchProgress = progress,
        isResume = true,
        nextVideoId = matchedEpisode?.id ?: fallbackVideoId,
        nextSeason = progress.season,
        nextEpisode = progress.episode
    )
}

private fun nextEpisodeAfter(
    episodes: List<Video>,
    season: Int?,
    episode: Int?
): Video? {
    if (season == null || episode == null) return null
    val currentIndex = episodes.indexOfFirst { it.season == season && it.episode == episode }
    if (currentIndex < 0) return null
    return episodes.getOrNull(currentIndex + 1)
}
