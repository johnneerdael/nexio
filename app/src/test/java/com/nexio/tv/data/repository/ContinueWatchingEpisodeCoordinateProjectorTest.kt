package com.nexio.tv.data.repository

import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContinueWatchingEpisodeCoordinateProjectorTest {

    @Test
    fun `matches provider coordinate to tvdb coordinate by episode title`() {
        val episodes = mapOf(
            (13 to 1) to episode(season = 13, episode = 1, title = "Not The One", airDate = "2025-01-01"),
            (14 to 1) to episode(season = 14, episode = 1, title = "The Multiverse", airDate = "2026-02-03")
        )

        val projected = ContinueWatchingEpisodeCoordinateProjector.projectFromEpisodeMap(
            contentType = "series",
            requestedSeason = 13,
            requestedEpisode = 1,
            requestedTitle = "The Multiverse",
            episodes = episodes
        )

        assertEquals(14, projected?.season)
        assertEquals(1, projected?.episode)
        assertEquals("The Multiverse", projected?.episodeTitle)
        assertEquals("2026-02-03", projected?.firstAired)
    }

    @Test
    fun `keeps exact coordinate when title is blank and tvdb coordinate exists`() {
        val episodes = mapOf(
            (2 to 7) to episode(season = 2, episode = 7, title = "The Seventh", airDate = "2026-03-04")
        )

        val projected = ContinueWatchingEpisodeCoordinateProjector.projectFromEpisodeMap(
            contentType = "series",
            requestedSeason = 2,
            requestedEpisode = 7,
            requestedTitle = null,
            episodes = episodes
        )

        assertEquals(2, projected?.season)
        assertEquals(7, projected?.episode)
        assertEquals("The Seventh", projected?.episodeTitle)
        assertEquals("2026-03-04", projected?.firstAired)
    }

    @Test
    fun `does not force anime through tvdb projection`() {
        val episodes = mapOf(
            (14 to 1) to episode(season = 14, episode = 1, title = "The Multiverse", airDate = "2026-02-03")
        )

        val projected = ContinueWatchingEpisodeCoordinateProjector.projectFromEpisodeMap(
            contentType = "anime",
            requestedSeason = 13,
            requestedEpisode = 1,
            requestedTitle = "The Multiverse",
            episodes = episodes
        )

        assertNull(projected)
    }

    private fun episode(
        season: Int,
        episode: Int,
        title: String,
        airDate: String
    ): TvEpisodeMetadata {
        return TvEpisodeMetadata(
            seasonNumber = season,
            episodeNumber = episode,
            title = title,
            airDate = airDate
        )
    }
}
