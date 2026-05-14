package com.nexio.tv.data.repository

import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueWatchingLocalNextUpDerivationTest {

    @Test
    fun `completed local episode derives next released episode candidate`() {
        val seed = completedProgress(season = 5, episode = 6)
        val episodes = mapOf(
            (5 to 6) to episode(season = 5, episode = 6, title = "Episode 6", airDate = "2026-05-06"),
            (5 to 7) to episode(season = 5, episode = 7, title = "Episode 7", airDate = "2026-05-13"),
            (5 to 8) to episode(season = 5, episode = 8, title = "Episode 8", airDate = "2026-05-20")
        )

        val entry = deriveLocalNextUpEntry(seed, episodes)

        assertEquals("tt1190634", entry?.contentId)
        assertEquals(5, entry?.season)
        assertEquals(7, entry?.episode)
        assertEquals("Episode 7", entry?.episodeTitle)
        assertEquals("2026-05-13", entry?.firstAired)
        assertEquals("tt1190634:5:7", entry?.videoId)
    }

    @Test
    fun `completed local episode can derive future candidate for snapshot scheduling`() {
        val seed = completedProgress(season = 5, episode = 7)
        val episodes = mapOf(
            (5 to 7) to episode(season = 5, episode = 7, title = "Episode 7", airDate = "2026-05-13"),
            (5 to 8) to episode(season = 5, episode = 8, title = "Episode 8", airDate = "2026-05-20")
        )

        val entry = deriveLocalNextUpEntry(seed, episodes)

        assertEquals(5, entry?.season)
        assertEquals(8, entry?.episode)
        assertEquals("2026-05-20", entry?.firstAired)
    }

    private fun completedProgress(season: Int, episode: Int): WatchProgress {
        return WatchProgress(
            contentId = "tt1190634",
            contentType = "series",
            name = "The Boys",
            poster = "poster",
            backdrop = "backdrop",
            logo = "logo",
            videoId = "tt1190634:$season:$episode",
            season = season,
            episode = episode,
            episodeTitle = "Episode $episode",
            position = 900L,
            duration = 1_000L,
            lastWatched = 100_000L
        )
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
