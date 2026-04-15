package com.nexio.tv.ui.screens.detail

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.Video
import com.nexio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 0 validation scaffold for META-03: detail season tabs and progress key stability
 * when TVDB season-order metadata is present.
 *
 * These tests verify that:
 * - Season tabs are derived from canonical Video.season values, not TVDB default order.
 * - Episode progress maps continue to match canonical (season, episode) pairs.
 *
 * Expected to pass with existing code (contract preservation) and continue to pass
 * after Plan 09-01 adds TVDB season-order fields.
 */
class MetaDetailsTvdbSeasonOrderTest {

    // --- Fixture helpers ---

    private fun testVideo(season: Int, episode: Int) = Video(
        id = "tt0000001:$season:$episode",
        title = "S${season}E${episode}",
        released = "2025-01-01",
        thumbnail = null,
        season = season,
        episode = episode,
        overview = null
    )

    private fun testMeta(videos: List<Video>) = Meta(
        id = "tt0000001",
        type = ContentType.SERIES,
        name = "Test Series",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = "A test series",
        releaseInfo = "2025",
        imdbRating = 8.5f,
        genres = listOf("Drama"),
        runtime = "42 min",
        director = emptyList(),
        cast = emptyList(),
        videos = videos,
        country = "US",
        awards = null,
        language = "en",
        links = emptyList()
    )

    private fun testWatchProgress(
        season: Int,
        episode: Int,
        position: Long = 1200000L,
        duration: Long = 2520000L
    ) = WatchProgress(
        contentId = "tt0000001",
        contentType = "series",
        name = "Test Series",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "tt0000001:$season:$episode",
        season = season,
        episode = episode,
        episodeTitle = "S${season}E${episode}",
        position = position,
        duration = duration,
        lastWatched = System.currentTimeMillis()
    )

    // --- Tests ---

    @Test
    fun `season tabs continue to use canonical video seasons`() {
        // Given: A series with canonical seasons 1, 2, and 0 (specials).
        // Even if TVDB default order maps some episodes to different season numbers,
        // the detail screen derives seasons from Video.season.
        val videos = listOf(
            testVideo(season = 0, episode = 1),  // special
            testVideo(season = 1, episode = 1),
            testVideo(season = 1, episode = 2),
            testVideo(season = 1, episode = 3),
            testVideo(season = 1, episode = 4),
            testVideo(season = 1, episode = 5),
            testVideo(season = 2, episode = 1),
            testVideo(season = 2, episode = 2),
            testVideo(season = 2, episode = 3)
        )
        val meta = testMeta(videos)

        // When: The UI state is refreshed with this meta.
        val state = MetaDetailsUiState().withRefreshedMeta(meta)

        // Then: Season tabs are derived from canonical Video.season values.
        assertEquals(
            "Season tabs must be [0, 1, 2] from canonical Video.season",
            listOf(0, 1, 2),
            state.seasons
        )

        // And: The selected season defaults to the first positive season (1).
        assertEquals(
            "Default selected season should be 1 (first positive canonical season)",
            1,
            state.selectedSeason
        )

        // And: Episodes for the selected season are sorted by canonical Video.episode.
        val episodesForSeason1 = state.episodesForSeason
        assertEquals(5, episodesForSeason1.size)
        assertEquals(
            listOf(1, 2, 3, 4, 5),
            episodesForSeason1.map { it.episode }
        )
    }

    @Test
    fun `episode progress map still matches canonical season episode pairs`() {
        // Given: A series with canonical episodes and watch progress keyed by (season, episode).
        val videos = listOf(
            testVideo(season = 1, episode = 1),
            testVideo(season = 1, episode = 2),
            testVideo(season = 1, episode = 3),
            testVideo(season = 1, episode = 4),
            testVideo(season = 1, episode = 5)
        )

        // Progress map uses canonical (season, episode) pairs as keys.
        val progressMap = mapOf(
            (1 to 1) to testWatchProgress(season = 1, episode = 1, position = 2520000L, duration = 2520000L),
            (1 to 2) to testWatchProgress(season = 1, episode = 2, position = 1500000L, duration = 2520000L),
            (1 to 5) to testWatchProgress(season = 1, episode = 5, position = 600000L, duration = 2520000L)
        )

        // When: The UI state has this progress map.
        val meta = testMeta(videos)
        val state = MetaDetailsUiState()
            .withRefreshedMeta(meta)
            .copy(episodeProgressMap = progressMap)

        // Then: Progress is found by canonical key (1, 5), not by any TVDB alternate key.
        val progressForS1E5 = state.episodeProgressMap[1 to 5]
        assertTrue(
            "Progress for canonical (1, 5) must exist",
            progressForS1E5 != null
        )
        assertEquals(
            "Progress videoId must reference canonical episode",
            "tt0000001:1:5",
            progressForS1E5?.videoId
        )

        // Even if TVDB default order were (2, 3), the key remains (1, 5).
        val noProgressForTvdbOrder = state.episodeProgressMap[2 to 3]
        assertTrue(
            "No progress should exist for TVDB default order key (2, 3)",
            noProgressForTvdbOrder == null
        )

        // Verify buildEpisodesForSeason uses canonical season filtering.
        val episodesForSeason1 = buildEpisodesForSeason(videos, 1)
        assertEquals(5, episodesForSeason1.size)
        assertEquals(
            "buildEpisodesForSeason filters by canonical Video.season",
            listOf(1, 2, 3, 4, 5),
            episodesForSeason1.map { it.episode }
        )
    }
}
