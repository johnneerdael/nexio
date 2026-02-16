package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.domain.model.Video

object PlayerNextEpisodeRules {
    fun resolveNextEpisode(
        videos: List<Video>,
        currentSeason: Int,
        currentEpisode: Int
    ): Video? {
        val sortedEpisodes = videos
            .filter { it.season != null && it.episode != null }
            .sortedWith(compareBy<Video> { it.season ?: Int.MAX_VALUE }.thenBy { it.episode ?: Int.MAX_VALUE })

        val currentIndex = sortedEpisodes.indexOfFirst {
            it.season == currentSeason && it.episode == currentEpisode
        }
        if (currentIndex < 0) return null

        return sortedEpisodes.getOrNull(currentIndex + 1)
    }

    fun shouldShowNextEpisodeCard(
        positionMs: Long,
        durationMs: Long,
        skipIntervals: List<SkipInterval>
    ): Boolean {
        val outroInterval = skipIntervals.firstOrNull { it.type == "outro" }
        return if (outroInterval != null) {
            positionMs / 1000.0 >= outroInterval.startTime
        } else {
            durationMs > 0L && (positionMs.toDouble() / durationMs.toDouble()) >= 0.95
        }
    }
}
