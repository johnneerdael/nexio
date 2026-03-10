package com.nuvio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TraktEpisodeMappingTest {

    @Test
    fun `remaps by unique title before falling back to index`() {
        val addonEpisodes = listOf(
            EpisodeMappingEntry(season = 1, episode = 1, title = "Pilot", videoId = "show:1:1"),
            EpisodeMappingEntry(season = 1, episode = 2, title = "Ghost Train", videoId = "show:1:2"),
            EpisodeMappingEntry(season = 2, episode = 1, title = "Finale", videoId = "show:2:1")
        )
        val traktEpisodes = listOf(
            EpisodeMappingEntry(season = 1, episode = 1, title = "Pilot"),
            EpisodeMappingEntry(season = 1, episode = 2, title = "Finale"),
            EpisodeMappingEntry(season = 1, episode = 3, title = "Ghost Train")
        )

        val result = remapEpisodeByTitleOrIndex(
            requestedSeason = 1,
            requestedEpisode = 2,
            requestedVideoId = "show:1:2",
            addonEpisodes = addonEpisodes,
            traktEpisodes = traktEpisodes
        )

        assertEquals(EpisodeMappingEntry(season = 1, episode = 3, title = "Ghost Train"), result)
    }

    @Test
    fun `falls back to flattened index when title is generic`() {
        val addonEpisodes = listOf(
            EpisodeMappingEntry(season = 1, episode = 1, title = "Episode 1"),
            EpisodeMappingEntry(season = 1, episode = 2, title = "Episode 2"),
            EpisodeMappingEntry(season = 2, episode = 1, title = "Episode 3")
        )
        val traktEpisodes = listOf(
            EpisodeMappingEntry(season = 1, episode = 1, title = "Episode 1"),
            EpisodeMappingEntry(season = 1, episode = 2, title = "Episode 2"),
            EpisodeMappingEntry(season = 1, episode = 3, title = "Episode 3")
        )

        val result = remapEpisodeByTitleOrIndex(
            requestedSeason = 2,
            requestedEpisode = 1,
            requestedVideoId = null,
            addonEpisodes = addonEpisodes,
            traktEpisodes = traktEpisodes
        )

        assertEquals(EpisodeMappingEntry(season = 1, episode = 3, title = "Episode 3"), result)
    }

    @Test
    fun `returns null when addon episode cannot be resolved`() {
        val result = remapEpisodeByTitleOrIndex(
            requestedSeason = 9,
            requestedEpisode = 9,
            requestedVideoId = null,
            addonEpisodes = listOf(
                EpisodeMappingEntry(season = 1, episode = 1, title = "Pilot")
            ),
            traktEpisodes = listOf(
                EpisodeMappingEntry(season = 1, episode = 1, title = "Pilot")
            )
        )

        assertNull(result)
    }
}
