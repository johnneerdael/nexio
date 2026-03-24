package com.nexio.tv.ui.screens.detail

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.NextToWatch
import com.nexio.tv.domain.model.Video
import com.nexio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class MetaDetailsNavigationSupportTest {

    @Test
    fun recentCompletedEpisodeBeatsEarlierGapFallback() {
        val meta = buildSeriesMeta(
            episode(1, 1),
            episode(1, 2),
            episode(3, 9),
            episode(3, 10)
        )
        val episodeProgressMap = mapOf(
            1 to 1 to completedProgress("show-s1e1", 1, 1, 1_000L),
            3 to 9 to completedProgress("show-s3e9", 3, 9, 2_000L)
        )

        val resolved = resolveSeriesCtaEpisodeId(
            meta = meta,
            episodeProgressMap = episodeProgressMap
        )

        assertEquals("show-s3e10", resolved)
    }

    @Test
    fun autoTargetedSeasonUsesTheCtaEpisodeWhenThereIsNoStoredPerSeasonFocus() {
        val meta = buildSeriesMeta(
            episode(3, 9),
            episode(3, 10),
            episode(3, 11)
        )

        val resolved = resolveSeasonEntryEpisodeId(
            meta = meta,
            selectedSeason = 3,
            nextToWatch = nextToWatch("show-s3e10", 3, 10),
            lastFocusedEpisodeIdBySeason = emptyMap(),
            manualSeasonOverride = false
        )

        assertEquals("show-s3e10", resolved)
    }

    @Test
    fun manualOverrideReturnsTheSelectedSeasonsFirstEpisodeInsteadOfTheCtaTarget() {
        val meta = buildSeriesMeta(
            episode(1, 1),
            episode(1, 2),
            episode(3, 9),
            episode(3, 10)
        )

        val resolved = resolveSeasonEntryEpisodeId(
            meta = meta,
            selectedSeason = 1,
            nextToWatch = nextToWatch("show-s3e10", 3, 10),
            lastFocusedEpisodeIdBySeason = emptyMap(),
            manualSeasonOverride = true
        )

        assertEquals("show-s1e1", resolved)
    }

    @Test
    fun storedPerSeasonFocusBeatsCtaTargeting() {
        val meta = buildSeriesMeta(
            episode(3, 9),
            episode(3, 10),
            episode(3, 11)
        )

        val resolved = resolveSeasonEntryEpisodeId(
            meta = meta,
            selectedSeason = 3,
            nextToWatch = nextToWatch("show-s3e10", 3, 10),
            lastFocusedEpisodeIdBySeason = mapOf(3 to "show-s3e11"),
            manualSeasonOverride = false
        )

        assertEquals("show-s3e11", resolved)
    }

    @Test
    fun specialsAreUsedOnlyWhenNoRegularSeasonEpisodesExist() {
        val meta = buildSeriesMeta(
            episode(0, 1, id = "show-s0e1"),
            episode(0, 2, id = "show-s0e2")
        )
        val episodeProgressMap = mapOf(
            0 to 1 to completedProgress("show-s0e1", 0, 1, 1_000L)
        )

        val resolved = resolveSeriesCtaEpisodeId(
            meta = meta,
            episodeProgressMap = episodeProgressMap
        )

        assertEquals("show-s0e2", resolved)
    }

    private fun buildSeriesMeta(vararg videos: Video): Meta {
        return Meta(
            id = "show",
            type = ContentType.SERIES,
            rawType = "series",
            name = "Shrinking",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            runtime = null,
            director = emptyList(),
            cast = emptyList(),
            castMembers = emptyList(),
            videos = videos.toList(),
            productionCompanies = emptyList(),
            networks = emptyList(),
            country = null,
            awards = null,
            language = null,
            links = emptyList(),
            trailerYtIds = emptyList()
        )
    }

    private fun episode(
        season: Int,
        episode: Int,
        id: String = "show-s${season}e${episode}"
    ): Video {
        return Video(
            id = id,
            title = "S${season}E${episode}",
            released = null,
            thumbnail = null,
            season = season,
            episode = episode,
            overview = null,
            runtime = 30
        )
    }

    private fun completedProgress(
        videoId: String,
        season: Int,
        episode: Int,
        lastWatched: Long
    ): WatchProgress {
        return WatchProgress(
            contentId = "show",
            contentType = "series",
            name = "Shrinking",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = videoId,
            season = season,
            episode = episode,
            episodeTitle = null,
            position = 1L,
            duration = 1L,
            lastWatched = lastWatched,
            progressPercent = 100f
        )
    }

    private fun nextToWatch(
        videoId: String,
        season: Int,
        episode: Int
    ): NextToWatch {
        return NextToWatch(
            watchProgress = null,
            isResume = false,
            nextVideoId = videoId,
            nextSeason = season,
            nextEpisode = episode,
            displayText = "Next"
        )
    }

}
