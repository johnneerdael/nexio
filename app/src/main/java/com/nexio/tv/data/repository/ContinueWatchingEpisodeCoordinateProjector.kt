package com.nexio.tv.data.repository

import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
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
        requestedActivityAtMs: Long? = null,
        episodes: Map<Pair<Int, Int>, TvEpisodeMetadata>
    ): ProjectedContinueWatchingEpisode? {
        if (contentType.trim().equals("anime", ignoreCase = true)) return null

        val exactCoordinate = requestedSeason to requestedEpisode
        activityAlignedSameEpisodeMatch(
            requestedSeason = requestedSeason,
            requestedEpisode = requestedEpisode,
            requestedActivityAtMs = requestedActivityAtMs,
            episodes = episodes
        )?.let { (coordinate, metadata) ->
            return metadata.toProjectedEpisode(coordinate)
        }

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

    private fun activityAlignedSameEpisodeMatch(
        requestedSeason: Int,
        requestedEpisode: Int,
        requestedActivityAtMs: Long?,
        episodes: Map<Pair<Int, Int>, TvEpisodeMetadata>
    ): Pair<Pair<Int, Int>, TvEpisodeMetadata>? {
        val activityDate = requestedActivityAtMs
            ?.takeIf { it > 0L }
            ?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
            ?: return null

        val exactAirDate = episodes[requestedSeason to requestedEpisode]
            ?.airDate
            ?.let(::parseAirDate)
        if (exactAirDate != null && daysBetween(exactAirDate, activityDate) <= ACTIVITY_AIR_DATE_WINDOW_DAYS) {
            return null
        }

        var best: Pair<Pair<Int, Int>, TvEpisodeMetadata>? = null
        var bestDistance = Long.MAX_VALUE
        for ((coordinate, metadata) in episodes) {
            if (coordinate.first <= requestedSeason) continue
            if (coordinate.second != requestedEpisode) continue
            val airDate = parseAirDate(metadata.airDate) ?: continue
            val distance = daysBetween(airDate, activityDate)
            if (distance > ACTIVITY_AIR_DATE_WINDOW_DAYS) continue
            if (
                distance < bestDistance ||
                (distance == bestDistance && coordinate.first > (best?.first?.first ?: Int.MIN_VALUE))
            ) {
                best = coordinate to metadata
                bestDistance = distance
            }
        }
        return best
    }

    private fun normalizeAirDate(value: String?): String? {
        return value
            ?.trim()
            ?.takeIf { it.length >= 10 }
            ?.take(10)
            ?.takeIf { DATE_PREFIX.matches(it) }
    }

    private fun parseAirDate(value: String?): LocalDate? {
        return normalizeAirDate(value)?.let { date ->
            runCatching { LocalDate.parse(date) }.getOrNull()
        }
    }

    private fun daysBetween(left: LocalDate, right: LocalDate): Long {
        return kotlin.math.abs(ChronoUnit.DAYS.between(left, right))
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
    private const val ACTIVITY_AIR_DATE_WINDOW_DAYS = 90L
}
