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
        requestedFirstAired: String? = null,
        episodes: Map<Pair<Int, Int>, TvEpisodeMetadata>
    ): ProjectedContinueWatchingEpisode? {
        if (contentType.trim().equals("anime", ignoreCase = true)) return null

        val exactCoordinate = requestedSeason to requestedEpisode
        val normalizedRequestedTitle = normalizeTitle(requestedTitle)
        if (normalizedRequestedTitle != null) {
            val titleMatches = mutableListOf<Pair<Pair<Int, Int>, TvEpisodeMetadata>>()
            for ((coordinate, metadata) in episodes) {
                if (normalizeTitle(metadata.title) == normalizedRequestedTitle) {
                    titleMatches += coordinate to metadata
                }
            }

            val selectedMatch = titleMatches.firstOrNull { (coordinate, _) ->
                coordinate == exactCoordinate
            } ?: titleMatches
                .sortedWith(
                    compareBy<Pair<Pair<Int, Int>, TvEpisodeMetadata>> { (coordinate, _) -> coordinate.first }
                        .thenBy { (coordinate, _) -> coordinate.second }
                )
                .firstOrNull()

            if (selectedMatch != null) {
                val (coordinate, metadata) = selectedMatch
                return metadata.toProjectedEpisode(coordinate)
            }
        }

        val normalizedRequestedAirDate = normalizeAirDate(requestedFirstAired)
        if (normalizedRequestedAirDate != null) {
            val airDateMatches = mutableListOf<Pair<Pair<Int, Int>, TvEpisodeMetadata>>()
            for ((coordinate, metadata) in episodes) {
                if (normalizeAirDate(metadata.airDate) == normalizedRequestedAirDate) {
                    airDateMatches += coordinate to metadata
                }
            }

            val selectedMatch = airDateMatches.firstOrNull { (coordinate, _) ->
                coordinate.second == requestedEpisode
            } ?: airDateMatches
                .sortedWith(
                    compareBy<Pair<Pair<Int, Int>, TvEpisodeMetadata>> { (coordinate, _) -> coordinate.first }
                        .thenBy { (coordinate, _) -> coordinate.second }
                )
                .firstOrNull()

            if (selectedMatch != null) {
                val (coordinate, metadata) = selectedMatch
                return metadata.toProjectedEpisode(coordinate)
            }
        }

        return episodes[exactCoordinate]?.toProjectedEpisode(exactCoordinate)
    }

    private fun normalizeAirDate(value: String?): String? {
        return value
            ?.trim()
            ?.takeIf { it.length >= 10 }
            ?.take(10)
            ?.takeIf { DATE_PREFIX.matches(it) }
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
            season = fallbackCoordinate.first,
            episode = fallbackCoordinate.second,
            episodeTitle = title,
            firstAired = airDate
        )
    }

    private val NON_TITLE_TOKEN = Regex("[^a-z0-9]+")
    private val WHITESPACE = Regex("\\s+")
    private val DATE_PREFIX = Regex("\\d{4}-\\d{2}-\\d{2}")
}
