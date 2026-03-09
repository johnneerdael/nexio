package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.dto.trakt.TraktSeasonEpisodeDto
import com.nuvio.tv.data.remote.dto.trakt.TraktSeasonSummaryDto
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeOrderingMapperTest {

    @Test
    fun `maps split season ordering by absolute position`() {
        val addonVideos = listOf(
            addonEpisode(season = 1, episode = 1, title = "One"),
            addonEpisode(season = 1, episode = 2, title = "Two"),
            addonEpisode(season = 1, episode = 3, title = "Three"),
            addonEpisode(season = 1, episode = 4, title = "Four")
        )
        val traktSeasons = listOf(
            traktSeason(
                1,
                traktEpisode(1, 1, "One"),
                traktEpisode(1, 2, "Two")
            ),
            traktSeason(
                2,
                traktEpisode(2, 1, "Three"),
                traktEpisode(2, 2, "Four")
            )
        )
        val progressMap = linkedMapOf(
            (1 to 1) to traktProgress(1, 1, lastWatched = 10L),
            (2 to 1) to traktProgress(2, 1, lastWatched = 20L)
        )

        val mapping = EpisodeOrderingMapper.buildTraktToAddonKeyMap(addonVideos, traktSeasons)
        val remapped = EpisodeOrderingMapper.remapProgressToAddonKeys(progressMap, mapping)

        assertEquals(setOf(1 to 1, 1 to 3), remapped.keys)
        assertEquals(1 to 3, remapped[1 to 3]?.let { it.season to it.episode })
    }

    @Test
    fun `prefers title mapping when same season episode exists but title differs`() {
        val addonVideos = listOf(
            addonEpisode(season = 1, episode = 1, title = "Pilot Part 1"),
            addonEpisode(season = 1, episode = 2, title = "Pilot Part 2")
        )
        val traktSeasons = listOf(
            traktSeason(
                1,
                traktEpisode(1, 1, "Pilot Part 2"),
                traktEpisode(1, 2, "Pilot Part 1")
            )
        )
        val progressMap = linkedMapOf(
            (1 to 1) to traktProgress(1, 1, lastWatched = 10L)
        )

        val mapping = EpisodeOrderingMapper.buildTraktToAddonKeyMap(addonVideos, traktSeasons)
        val remapped = EpisodeOrderingMapper.remapProgressToAddonKeys(progressMap, mapping)

        assertEquals(setOf(1 to 2), remapped.keys)
        assertEquals("trakt:show:1:1", remapped[1 to 2]?.videoId)
    }

    private fun addonEpisode(season: Int, episode: Int, title: String): Video {
        return Video(
            id = "addon:$season:$episode",
            title = title,
            released = null,
            thumbnail = null,
            season = season,
            episode = episode,
            overview = null
        )
    }

    private fun traktSeason(number: Int, vararg episodes: TraktSeasonEpisodeDto): TraktSeasonSummaryDto {
        return TraktSeasonSummaryDto(
            number = number,
            episodes = episodes.toList()
        )
    }

    private fun traktEpisode(season: Int, episode: Int, title: String): TraktSeasonEpisodeDto {
        return TraktSeasonEpisodeDto(
            season = season,
            number = episode,
            title = title
        )
    }

    private fun traktProgress(season: Int, episode: Int, lastWatched: Long): WatchProgress {
        return WatchProgress(
            contentId = "show",
            contentType = "series",
            name = "Show",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "trakt:show:$season:$episode",
            season = season,
            episode = episode,
            episodeTitle = null,
            position = 1L,
            duration = 1L,
            lastWatched = lastWatched,
            progressPercent = 100f,
            source = WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS
        )
    }
}
