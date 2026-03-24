package com.nexio.tv.ui.screens.detail

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.Video
import com.nexio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class MetaDetailsNavigationSupportTest {

    @Test
    fun recentCompletedEpisodeBeatsEarlierGapFallback() {
        val result = buildSeriesNextToWatchCandidate(
            episodes = episodesForSeasons(1..3, episodeCount = 10),
            progressMap = mapOf(
                1 to 1 to completedProgress("show-s1e1", 1, 1, 1_000L),
                3 to 9 to completedProgress("show-s3e9", 3, 9, 2_000L)
            ),
            metaId = "show"
        )

        assertEquals("show:3:10", result.nextVideoId)
        assertEquals(3, result.nextSeason)
        assertEquals(10, result.nextEpisode)
    }

    @Test
    fun autoTargetedSeasonUsesTheCtaEpisodeWhenThereIsNoStoredPerSeasonFocus() {
        val meta = buildSeriesMeta(
            *episodesForSeasons(1..3, episodeCount = 10).toTypedArray()
        )
        val nextToWatch = buildSeriesNextToWatchCandidate(
            episodes = meta.videos,
            progressMap = mapOf(
                3 to 9 to completedProgress("show-s3e9", 3, 9, 2_000L)
            ),
            metaId = meta.id
        )

        val resolved = resolveSeasonEntryEpisodeId(
            meta = meta,
            selectedSeason = 3,
            nextToWatch = nextToWatch,
            lastFocusedEpisodeIdBySeason = emptyMap(),
            manualSeasonOverride = false
        )

        assertEquals("show-s3e10", resolved)
    }

    @Test
    fun manualOverrideReturnsTheSelectedSeasonsFirstEpisodeInsteadOfTheCtaTarget() {
        val meta = buildSeriesMeta(
            *episodesForSeasons(1..3, episodeCount = 10).toTypedArray()
        )
        val nextToWatch = buildSeriesNextToWatchCandidate(
            episodes = meta.videos,
            progressMap = mapOf(
                3 to 9 to completedProgress("show-s3e9", 3, 9, 2_000L)
            ),
            metaId = meta.id
        )

        val resolved = resolveSeasonEntryEpisodeId(
            meta = meta,
            selectedSeason = 1,
            nextToWatch = nextToWatch,
            lastFocusedEpisodeIdBySeason = emptyMap(),
            manualSeasonOverride = true
        )

        assertEquals("show-s1e1", resolved)
    }

    @Test
    fun storedPerSeasonFocusBeatsCtaTargeting() {
        val meta = buildSeriesMeta(
            *episodesForSeasons(1..3, episodeCount = 10).toTypedArray()
        )
        val nextToWatch = buildSeriesNextToWatchCandidate(
            episodes = meta.videos,
            progressMap = mapOf(
                3 to 9 to completedProgress("show-s3e9", 3, 9, 2_000L)
            ),
            metaId = meta.id
        )

        val resolved = resolveSeasonEntryEpisodeId(
            meta = meta,
            selectedSeason = 3,
            nextToWatch = nextToWatch,
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

        val result = buildSeriesNextToWatchCandidate(
            episodes = meta.videos,
            progressMap = episodeProgressMap,
            metaId = meta.id
        )

        assertEquals("show-s0e2", result.nextVideoId)
        assertEquals(0, result.nextSeason)
        assertEquals(2, result.nextEpisode)
    }

    @Test
    fun specialsDoNotInfluenceRecentWatchAnchorsWhenRegularEpisodesExist() {
        val meta = buildSeriesMeta(
            *episodesForSeasons(1..2, episodeCount = 2).toTypedArray(),
            episode(0, 1, id = "show-s0e1"),
            episode(0, 2, id = "show-s0e2")
        )

        val result = buildSeriesNextToWatchCandidate(
            episodes = meta.videos,
            progressMap = mapOf(
                1 to 1 to completedProgress("show-s1e1", 1, 1, 1_000L),
                0 to 2 to completedProgress("show-s0e2", 0, 2, 2_000L)
            ),
            metaId = meta.id
        )

        assertEquals("show:1:2", result.nextVideoId)
        assertEquals(1, result.nextSeason)
        assertEquals(2, result.nextEpisode)
    }

    @Test
    fun manualOverrideBlocksAutoSwitchingToTheTargetSeason() {
        assertEquals(
            false,
            shouldAutoSwitchToTargetSeason(
                selectedSeason = 1,
                targetSeason = 3,
                manualOverrideActive = true,
                availableSeasons = listOf(1, 2, 3)
            )
        )
    }

    @Test
    fun autoSwitchesWhenTargetSeasonDiffersAndIsAvailable() {
        assertEquals(
            true,
            shouldAutoSwitchToTargetSeason(
                selectedSeason = 1,
                targetSeason = 3,
                manualOverrideActive = false,
                availableSeasons = listOf(1, 2, 3)
            )
        )
    }

    @Test
    fun doesNotAutoSwitchWhenAlreadyOnTheTargetSeason() {
        assertEquals(
            false,
            shouldAutoSwitchToTargetSeason(
                selectedSeason = 3,
                targetSeason = 3,
                manualOverrideActive = false,
                availableSeasons = listOf(1, 2, 3)
            )
        )
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

    private fun episodesForSeasons(
        seasons: IntRange,
        episodeCount: Int
    ): List<Video> {
        return seasons.flatMap { season ->
            (1..episodeCount).map { episode ->
                episode(season, episode, id = "show:${season}:${episode}")
            }
        }
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
}
