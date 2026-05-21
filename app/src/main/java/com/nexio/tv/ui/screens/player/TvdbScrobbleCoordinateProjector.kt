package com.nexio.tv.ui.screens.player

import com.nexio.tv.core.tmdb.TmdbEpisodeEnrichment
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import java.util.Locale

internal object TvdbScrobbleCoordinateProjector {
    fun projectDisplayToProviderNative(
        displaySeason: Int,
        displayEpisode: Int,
        displayTitle: String?,
        tvdbEpisodes: Map<Pair<Int, Int>, TvEpisodeMetadata>,
        tmdbEpisodes: Map<Pair<Int, Int>, TmdbEpisodeEnrichment>
    ): Pair<Int, Int>? {
        val displayEpisodeMetadata = tvdbEpisodes[displaySeason to displayEpisode] ?: return null
        val displayAirDate = normalizeAirDate(displayEpisodeMetadata.airDate) ?: return null
        val dateMatches = tmdbEpisodes.entries
            .filter { (_, metadata) -> normalizeAirDate(metadata.airDate) == displayAirDate }

        if (dateMatches.size == 1) return dateMatches.single().key
        val episodeNumberMatches = dateMatches.filter { (coordinate, _) ->
            coordinate.second == displayEpisode
        }
        if (episodeNumberMatches.size == 1) return episodeNumberMatches.single().key

        val normalizedTitle = normalizeTitle(displayTitle)
            ?: normalizeTitle(displayEpisodeMetadata.title)
            ?: return null
        var best: Pair<Int, Int>? = null
        for ((coordinate, metadata) in dateMatches) {
            if (normalizeTitle(metadata.title) != normalizedTitle) continue
            if (best == null || coordinate.first < best.first ||
                (coordinate.first == best.first && coordinate.second < best.second)
            ) {
                best = coordinate
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

    private fun normalizeTitle(title: String?): String? {
        val normalized = title
            ?.lowercase(Locale.ROOT)
            ?.replace("'", "")
            ?.replace("’", "")
            ?.replace(NON_TITLE_TOKEN, " ")
            ?.trim()
            ?.replace(WHITESPACE, " ")
        return normalized?.takeIf { it.isNotEmpty() }
    }

    private val NON_TITLE_TOKEN = Regex("[^a-z0-9]+")
    private val WHITESPACE = Regex("\\s+")
    private val DATE_PREFIX = Regex("\\d{4}-\\d{2}-\\d{2}")
}
