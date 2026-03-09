package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.dto.trakt.TraktSeasonEpisodeDto
import com.nuvio.tv.data.remote.dto.trakt.TraktSeasonSummaryDto
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import java.util.Locale

internal object EpisodeOrderingMapper {

    fun buildTraktToAddonKeyMap(
        addonVideos: List<Video>,
        traktSeasons: List<TraktSeasonSummaryDto>
    ): Map<Pair<Int, Int>, Pair<Int, Int>> {
        val addonEpisodes = addonVideos.asAddonEpisodeRefs()
        val traktEpisodes = traktSeasons.asTraktEpisodeRefs()
        if (addonEpisodes.isEmpty() || traktEpisodes.isEmpty()) return emptyMap()

        val mappings = linkedMapOf<Pair<Int, Int>, Pair<Int, Int>>()
        val usedAddonKeys = mutableSetOf<Pair<Int, Int>>()

        val addonByTitle = addonEpisodes.uniqueByNormalizedTitle()
        val traktByTitle = traktEpisodes.uniqueByNormalizedTitle()

        traktEpisodes.forEach { traktEpisode ->
            val title = traktEpisode.normalizedTitle ?: return@forEach
            val addonEpisode = addonByTitle[title] ?: return@forEach
            if (traktByTitle[title]?.key != traktEpisode.key) return@forEach
            mappings[traktEpisode.key] = addonEpisode.key
            usedAddonKeys += addonEpisode.key
        }

        val addonRegular = addonEpisodes.filter { it.season > 0 }
        val traktRegular = traktEpisodes.filter { it.season > 0 }
        val regularCount = minOf(addonRegular.size, traktRegular.size)
        for (index in 0 until regularCount) {
            val traktEpisode = traktRegular[index]
            if (mappings.containsKey(traktEpisode.key)) continue
            val addonEpisode = addonRegular[index]
            if (!usedAddonKeys.add(addonEpisode.key)) continue
            mappings[traktEpisode.key] = addonEpisode.key
        }

        traktEpisodes.forEach { traktEpisode ->
            if (mappings.containsKey(traktEpisode.key)) return@forEach
            val addonEpisode = addonEpisodes.firstOrNull { it.key == traktEpisode.key } ?: return@forEach
            if (!usedAddonKeys.add(addonEpisode.key)) return@forEach
            mappings[traktEpisode.key] = addonEpisode.key
        }

        return mappings
    }

    fun remapProgressToAddonKeys(
        progressMap: Map<Pair<Int, Int>, WatchProgress>,
        traktToAddonKeyMap: Map<Pair<Int, Int>, Pair<Int, Int>>
    ): Map<Pair<Int, Int>, WatchProgress> {
        if (progressMap.isEmpty() || traktToAddonKeyMap.isEmpty()) return progressMap

        val remapped = linkedMapOf<Pair<Int, Int>, WatchProgress>()
        progressMap.forEach { (traktKey, progress) ->
            val addonKey = traktToAddonKeyMap[traktKey] ?: traktKey
            val current = remapped[addonKey]
            if (current == null || progress.lastWatched >= current.lastWatched) {
                remapped[addonKey] = progress.copy(
                    season = addonKey.first,
                    episode = addonKey.second
                )
            }
        }
        return remapped
    }

    private data class EpisodeRef(
        val season: Int,
        val episode: Int,
        val normalizedTitle: String?,
        val key: Pair<Int, Int> = season to episode
    )

    private fun List<Video>.asAddonEpisodeRefs(): List<EpisodeRef> {
        return this
            .mapNotNull { video ->
                val season = video.season ?: return@mapNotNull null
                val episode = video.episode ?: return@mapNotNull null
                EpisodeRef(
                    season = season,
                    episode = episode,
                    normalizedTitle = normalizeEpisodeTitle(video.title)
                )
            }
            .sortedWith(compareBy<EpisodeRef> { it.season }.thenBy { it.episode })
    }

    private fun List<TraktSeasonSummaryDto>.asTraktEpisodeRefs(): List<EpisodeRef> {
        return this
            .sortedBy { it.number ?: Int.MAX_VALUE }
            .flatMap { season ->
                val seasonNumber = season.number ?: return@flatMap emptyList()
                season.episodes.orEmpty()
                    .mapNotNull { episode ->
                        episode.asEpisodeRef(defaultSeason = seasonNumber)
                    }
            }
    }

    private fun TraktSeasonEpisodeDto.asEpisodeRef(defaultSeason: Int): EpisodeRef? {
        val seasonNumber = season ?: defaultSeason
        val episodeNumber = number ?: return null
        return EpisodeRef(
            season = seasonNumber,
            episode = episodeNumber,
            normalizedTitle = normalizeEpisodeTitle(title)
        )
    }

    private fun List<EpisodeRef>.uniqueByNormalizedTitle(): Map<String, EpisodeRef> {
        val counts = linkedMapOf<String, Int>()
        forEach { episode ->
            val title = episode.normalizedTitle ?: return@forEach
            counts[title] = (counts[title] ?: 0) + 1
        }

        return buildMap {
            this@uniqueByNormalizedTitle.forEach { episode ->
                val title = episode.normalizedTitle ?: return@forEach
                if (counts[title] == 1) {
                    put(title, episode)
                }
            }
        }
    }

    private fun normalizeEpisodeTitle(title: String?): String? {
        if (title.isNullOrBlank()) return null
        return title.trim()
            .lowercase(Locale.US)
            .replace(Regex("^(episode\\s*\\d+\\s*[:\\-–]?\\s*)"), "")
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .takeIf { it.isNotBlank() }
    }
}
