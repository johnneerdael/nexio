package com.nexio.tv.ui.screens.detail

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.TvdbEpisodeOrder
import com.nexio.tv.domain.model.TvdbSeasonOrderContext
import com.nexio.tv.domain.model.TvdbSeasonTypeSummary
import com.nexio.tv.domain.model.Video
import com.nexio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validation for META-03: detail season tabs and progress key stability
 * when TVDB season-order metadata is present.
 *
 * Tests verify that:
 * - Season tabs are derived from canonical Video.season values, not TVDB default order.
 * - Episode progress maps continue to match canonical (season, episode) pairs.
 * - TVDB season-order metadata is carried but does not affect UI derivations.
 */
class MetaDetailsTvdbSeasonOrderTest {

    // --- Fixture helpers ---

    private fun testVideo(
        season: Int,
        episode: Int,
        tvdbEpisodeOrder: TvdbEpisodeOrder? = null
    ) = Video(
        id = "tt0000001:$season:$episode",
        title = "S${season}E${episode}",
        released = "2025-01-01",
        thumbnail = null,
        season = season,
        episode = episode,
        overview = null,
        tvdbEpisodeOrder = tvdbEpisodeOrder
    )

    private fun testMeta(
        videos: List<Video>,
        tvdbSeasonOrderContext: TvdbSeasonOrderContext? = null
    ) = Meta(
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
        links = emptyList(),
        tvdbSeasonOrderContext = tvdbSeasonOrderContext
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
        // Given: A series with canonical seasons 0, 1, 2.
        // Videos carry TVDB episode order that maps to different seasons,
        // but canonical Video.season drives the UI.
        val tvdbOrder = TvdbEpisodeOrder(
            defaultSeason = 3,
            defaultEpisode = 1,
            absoluteNumber = null,
            airsAfterSeason = null,
            airsBeforeSeason = null,
            airsBeforeEpisode = null
        )
        val videos = listOf(
            testVideo(season = 0, episode = 1),
            testVideo(season = 1, episode = 1, tvdbEpisodeOrder = tvdbOrder),
            testVideo(season = 1, episode = 2),
            testVideo(season = 1, episode = 3),
            testVideo(season = 1, episode = 4),
            testVideo(season = 1, episode = 5),
            testVideo(season = 2, episode = 1),
            testVideo(season = 2, episode = 2),
            testVideo(season = 2, episode = 3)
        )
        val orderContext = TvdbSeasonOrderContext(
            defaultSeasonTypeId = 1,
            defaultSeasonTypeName = "Aired Order",
            defaultSeasonTypeSlug = "official",
            availableSeasonTypes = listOf(
                TvdbSeasonTypeSummary(id = 1, name = "Aired Order", type = "official")
            )
        )
        val meta = testMeta(videos, tvdbSeasonOrderContext = orderContext)

        // When: The UI state is refreshed with this meta.
        val state = MetaDetailsUiState().withRefreshedMeta(meta)

        // Then: Season tabs are derived from canonical Video.season values.
        assertEquals(
            listOf(0, 1, 2),
            state.seasons
        )

        // Default selected season is the first positive season (1).
        assertEquals(1, state.selectedSeason)

        // Episodes for the selected season are sorted by canonical Video.episode.
        val episodesForSeason1 = state.episodesForSeason
        assertEquals(5, episodesForSeason1.size)
        assertEquals(
            listOf(1, 2, 3, 4, 5),
            episodesForSeason1.map { it.episode }
        )

        // TVDB order context is carried on meta.
        assertNotNull(state.meta?.tvdbSeasonOrderContext)
        assertEquals(1, state.meta?.tvdbSeasonOrderContext?.defaultSeasonTypeId)

        // TVDB episode order is carried on the video but does not affect season tabs.
        val s1e1 = episodesForSeason1.first()
        assertNotNull(s1e1.tvdbEpisodeOrder)
        assertEquals(3, s1e1.tvdbEpisodeOrder?.defaultSeason)
    }

    @Test
    fun `episode progress map still matches canonical season episode pairs`() {
        // Given: Videos with TVDB episode order metadata.
        val videos = listOf(
            testVideo(season = 1, episode = 1, tvdbEpisodeOrder = TvdbEpisodeOrder(
                defaultSeason = 2, defaultEpisode = 1,
                absoluteNumber = null, airsAfterSeason = null,
                airsBeforeSeason = null, airsBeforeEpisode = null
            )),
            testVideo(season = 1, episode = 2),
            testVideo(season = 1, episode = 3),
            testVideo(season = 1, episode = 4),
            testVideo(season = 1, episode = 5, tvdbEpisodeOrder = TvdbEpisodeOrder(
                defaultSeason = 2, defaultEpisode = 5,
                absoluteNumber = null, airsAfterSeason = null,
                airsBeforeSeason = null, airsBeforeEpisode = null
            ))
        )

        val progress = testWatchProgress(season = 1, episode = 5, position = 600000L, duration = 2520000L)
        val progressMap = mapOf(
            (1 to 1) to testWatchProgress(season = 1, episode = 1, position = 2520000L, duration = 2520000L),
            (1 to 2) to testWatchProgress(season = 1, episode = 2, position = 1500000L, duration = 2520000L),
            (1 to 5) to progress
        )

        val meta = testMeta(videos)
        val state = MetaDetailsUiState()
            .withRefreshedMeta(meta)
            .copy(episodeProgressMap = progressMap)

        // Then: Progress is found by canonical key (1, 5).
        val progressForS1E5 = state.episodeProgressMap[1 to 5]
        assertTrue(progressForS1E5 != null)
        assertEquals(progress, state.episodeProgressMap[1 to 5])
        assertEquals("tt0000001:1:5", progressForS1E5?.videoId)

        // No progress exists for TVDB default order key (2, 5).
        val noProgressForTvdbOrder = state.episodeProgressMap[2 to 5]
        assertNull(noProgressForTvdbOrder)

        // buildEpisodesForSeason uses canonical season filtering.
        val episodesForSeason1 = buildEpisodesForSeason(videos, 1)
        assertEquals(5, episodesForSeason1.size)
        assertEquals(
            listOf(1, 2, 3, 4, 5),
            episodesForSeason1.map { it.episode }
        )
    }

    @Test
    fun `tvdb episode order preserved on video does not affect canonical keys`() {
        // Given: A video with both canonical and TVDB order fields.
        val tvdbOrder = TvdbEpisodeOrder(
            defaultSeason = 3,
            defaultEpisode = 7,
            absoluteNumber = 42,
            airsAfterSeason = null,
            airsBeforeSeason = null,
            airsBeforeEpisode = null,
            alternateOrderPreservedButNotApplied = false
        )
        val video = testVideo(season = 1, episode = 5, tvdbEpisodeOrder = tvdbOrder)

        // Then: Canonical fields are unchanged.
        assertEquals(1, video.season)
        assertEquals(5, video.episode)

        // And: TVDB order is accessible separately.
        assertEquals(3, video.tvdbEpisodeOrder?.defaultSeason)
        assertEquals(7, video.tvdbEpisodeOrder?.defaultEpisode)
        assertEquals(42, video.tvdbEpisodeOrder?.absoluteNumber)
    }
}
