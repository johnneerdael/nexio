package com.nexio.tv.data.repository

import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import java.util.Locale

data class ProjectedContinueWatchingEpisode(
    val season: Int,
    val episode: Int,
    val episodeTitle: String?,
    val firstAired: String?
)

object ContinueWatchingEpisodeCoordinateProjector {

    fun projectFromEpisodeMap(
        contentType: String,
        requestedSeason: Int,
        requestedEpisode: Int,
        requestedTitle: String?,
        episodes: Map<Pair<Int, Int>, TvEpisodeMetadata>
    ): ProjectedContinueWatchingEpisode? {
        if (contentType.trim().equals("anime", ignoreCase = true)) return null

        val normalizedRequestedTitle = normalizeTitle(requestedTitle)
        if (normalizedRequestedTitle != null) {
            for ((coordinate, metadata) in episodes) {
                if (normalizeTitle(metadata.title) == normalizedRequestedTitle) {
                    return metadata.toProjectedEpisode(coordinate)
                }
            }
        }

        val exactCoordinate = requestedSeason to requestedEpisode
        return episodes[exactCoordinate]?.toProjectedEpisode(exactCoordinate)
    }

    private fun normalizeTitle(title: String?): String? {
        val normalized = title
            ?.lowercase(Locale.ROOT)
            ?.replace(NON_TITLE_TOKEN, " ")
            ?.trim()
            ?.replace(WHITESPACE, " ")

        return normalized?.takeIf { it.isNotEmpty() }
    }

    private fun TvEpisodeMetadata.toProjectedEpisode(
        fallbackCoordinate: Pair<Int, Int>
    ): ProjectedContinueWatchingEpisode {
        return ProjectedContinueWatchingEpisode(
            season = seasonNumber ?: fallbackCoordinate.first,
            episode = episodeNumber ?: fallbackCoordinate.second,
            episodeTitle = title,
            firstAired = airDate
        )
    }

    private val NON_TITLE_TOKEN = Regex("[^a-z0-9]+")
    private val WHITESPACE = Regex("\\s+")
}
