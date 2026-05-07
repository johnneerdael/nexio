package com.nexio.tv.ui.screens.detail

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.NextToWatch
import com.nexio.tv.domain.model.Video
import com.nexio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaDetailsNavigationSupportTest {

    @Test
    fun `universal streamer mode is enabled only when no addons are installed`() {
        assertTrue(shouldUseUniversalStreamerMode(0))
        assertFalse(shouldUseUniversalStreamerMode(1))
        assertFalse(shouldUseUniversalStreamerMode(3))
    }

    @Test
    fun `installed official app resolution returns supported streaming apps in stable order`() {
        val resolved = resolveInstalledOfficialStreamingApps(
            setOf(
                "com.wbd.stream",
                "com.netflix.ninja",
                "com.unknown.app"
            ),
            setOf(
                "com.wbd.stream",
                "com.netflix.ninja"
            )
        )

        assertEquals(
            listOf("Netflix", "Max"),
            resolved.map { it.displayName }
        )
    }

    @Test
    fun `installed official app resolution excludes non searchable providers`() {
        val resolved = resolveInstalledOfficialStreamingApps(
            setOf(
                "com.wbd.stream",
                "com.netflix.ninja"
            ),
            setOf("com.netflix.ninja")
        )

        assertEquals(listOf("Netflix"), resolved.map { it.displayName })
    }

    @Test
    fun returnFocusRequestIsLatchedOnceAndMarkedForConsumption() {
        val resolved = resolveDetailReturnFocusRequest(
            returnFocusSeason = 3,
            returnFocusEpisode = 10,
            alreadyConsumed = false
        )

        assertEquals(3, resolved.request?.season)
        assertEquals(10, resolved.request?.episode)
        assertTrue(resolved.shouldConsumeSavedState)
    }

    @Test
    fun consumedReturnFocusRequestIsIgnored() {
        val resolved = resolveDetailReturnFocusRequest(
            returnFocusSeason = 3,
            returnFocusEpisode = 10,
            alreadyConsumed = true
        )

        assertNull(resolved.request)
        assertFalse(resolved.shouldConsumeSavedState)
    }

    @Test
    fun partialReturnFocusRequestIsClearedWithoutBeingUsed() {
        val resolved = resolveDetailReturnFocusRequest(
            returnFocusSeason = 3,
            returnFocusEpisode = null,
            alreadyConsumed = false
        )

        assertNull(resolved.request)
        assertTrue(resolved.shouldConsumeSavedState)
    }

    @Test
    fun userSeasonSelectionMarksManualOverrideAndUpdatesTheSeasonEpisodes() {
        val meta = buildSeriesMeta(
            *episodesForSeasons(1..3, episodeCount = 3).toTypedArray()
        )
        val state = MetaDetailsUiState(
            meta = meta,
            seasons = listOf(1, 2, 3),
            selectedSeason = 1,
            episodesForSeason = buildEpisodesForSeason(meta.videos, 1)
        )

        val updated = state.withManualSeasonSelection(3)

        assertEquals(3, updated.selectedSeason)
        assertEquals(true, updated.manualSeasonOverrideActive)
        assertEquals(3, updated.episodesForSeason.first().season)
    }

    @Test
    fun ctaAutoSwitchesToTheTargetSeasonWhenManualOverrideIsInactive() {
        val meta = buildSeriesMeta(
            *episodesForSeasons(1..3, episodeCount = 3).toTypedArray()
        )
        val state = MetaDetailsUiState(
            meta = meta,
            seasons = listOf(1, 2, 3),
            selectedSeason = 1,
            episodesForSeason = buildEpisodesForSeason(meta.videos, 1)
        )

        val updated = state.withNextToWatch(
            nextToWatch = targetSeasonNextToWatch(season = 3, episode = 3)
        )

        assertEquals(3, updated.selectedSeason)
        assertEquals(3, updated.episodesForSeason.first().season)
        assertEquals(false, updated.manualSeasonOverrideActive)
    }

    @Test
    fun programmaticSeasonSelectionKeepsManualOverrideInactive() {
        val meta = buildSeriesMeta(
            *episodesForSeasons(1..3, episodeCount = 3).toTypedArray()
        )
        val state = MetaDetailsUiState(
            meta = meta,
            seasons = listOf(1, 2, 3),
            selectedSeason = 1,
            episodesForSeason = buildEpisodesForSeason(meta.videos, 1),
            manualSeasonOverrideActive = false
        )

        val updated = state.withProgrammaticSeasonSelection(3)

        assertEquals(3, updated.selectedSeason)
        assertEquals(3, updated.episodesForSeason.first().season)
        assertEquals(false, updated.manualSeasonOverrideActive)
    }

    @Test
    fun ctaKeepsTheUserSelectedSeasonWhenManualOverrideIsActive() {
        val meta = buildSeriesMeta(
            *episodesForSeasons(1..3, episodeCount = 3).toTypedArray()
        )
        val state = MetaDetailsUiState(
            meta = meta,
            seasons = listOf(1, 2, 3),
            selectedSeason = 1,
            episodesForSeason = buildEpisodesForSeason(meta.videos, 1),
            manualSeasonOverrideActive = true
        )

        val updated = state.withNextToWatch(
            nextToWatch = targetSeasonNextToWatch(season = 3, episode = 3)
        )

        assertEquals(1, updated.selectedSeason)
        assertEquals(1, updated.episodesForSeason.first().season)
        assertEquals(true, updated.manualSeasonOverrideActive)
    }

    @Test
    fun metadataRefreshPreservesManualSeasonSelectionWhenTheSeasonStillExists() {
        val meta = buildSeriesMeta(
            *episodesForSeasons(1..3, episodeCount = 3).toTypedArray()
        )
        val state = MetaDetailsUiState(
            meta = meta,
            seasons = listOf(1, 2, 3),
            selectedSeason = 2,
            episodesForSeason = buildEpisodesForSeason(meta.videos, 2),
            manualSeasonOverrideActive = true,
            nextToWatch = targetSeasonNextToWatch(season = 3, episode = 3)
        )

        val updated = state.withRefreshedMeta(meta)

        assertEquals(2, updated.selectedSeason)
        assertEquals(2, updated.episodesForSeason.first().season)
    }

    @Test
    fun metadataRefreshPreservesCtaTargetSeasonWhenManualOverrideIsInactive() {
        val meta = buildSeriesMeta(
            *episodesForSeasons(1..3, episodeCount = 3).toTypedArray()
        )
        val state = MetaDetailsUiState(
            meta = meta,
            seasons = listOf(1, 2, 3),
            selectedSeason = 1,
            episodesForSeason = buildEpisodesForSeason(meta.videos, 1),
            nextToWatch = targetSeasonNextToWatch(season = 3, episode = 3)
        )

        val updated = state.withRefreshedMeta(meta)

        assertEquals(3, updated.selectedSeason)
        assertEquals(3, updated.episodesForSeason.first().season)
    }

    @Test
    fun metadataRefreshFallsBackToTheDefaultSeasonWhenTheCurrentChoiceIsUnavailable() {
        val meta = buildSeriesMeta(
            *episodesForSeasons(1..3, episodeCount = 3).toTypedArray()
        )
        val state = MetaDetailsUiState(
            meta = meta,
            seasons = listOf(1, 2, 3),
            selectedSeason = 4,
            episodesForSeason = emptyList(),
            manualSeasonOverrideActive = true,
            nextToWatch = targetSeasonNextToWatch(season = 4, episode = 1)
        )

        val updated = state.withRefreshedMeta(meta)

        assertEquals(1, updated.selectedSeason)
        assertEquals(1, updated.episodesForSeason.first().season)
    }

    @Test
    fun recentCompletedEpisodeBeatsEarlierGapFallback() {
        val result = buildSeriesNextToWatchCandidate(
            episodes = episodesForSeasons(1..3, episodeCount = 10),
            progressMap = mapOf(
                1 to 1 to completedProgress("show:1:1", 1, 1, 1_000L),
                3 to 9 to completedProgress("show:3:9", 3, 9, 2_000L)
            ),
            metaId = "show"
        )

        assertEquals("show:3:10", result.nextVideoId)
        assertEquals(3, result.nextSeason)
        assertEquals(10, result.nextEpisode)
    }

    @Test
    fun equalTimestampCompletedEpisodesUseFurthestEpisodeAsTheWatchAnchor() {
        val completedSeasonOne = (1..6).associate { episode ->
            1 to episode to completedProgress("show:1:$episode", 1, episode, 1_000L)
        }

        val result = buildSeriesNextToWatchCandidate(
            episodes = episodesForSeasons(1..2, episodeCount = 6),
            progressMap = completedSeasonOne,
            metaId = "show"
        )

        assertEquals("show:2:1", result.nextVideoId)
        assertEquals(2, result.nextSeason)
        assertEquals(1, result.nextEpisode)
    }

    @Test
    fun newerCompletedEpisodeBeatsAnOlderInProgressResumeTarget() {
        val result = buildSeriesNextToWatchCandidate(
            episodes = episodesForSeasons(1..1, episodeCount = 3),
            progressMap = mapOf(
                1 to 1 to inProgressProgress("show:1:1", 1, 1, 1_000L),
                1 to 2 to completedProgress("show:1:2", 1, 2, 2_000L)
            ),
            metaId = "show"
        )

        assertEquals("show:1:3", result.nextVideoId)
        assertEquals(1, result.nextSeason)
        assertEquals(3, result.nextEpisode)
    }

    @Test
    fun terminalCompletedEpisodeFallsBackToOlderInProgressBeforeGapFallback() {
        val result = buildSeriesNextToWatchCandidate(
            episodes = episodesForSeasons(1..1, episodeCount = 4),
            progressMap = mapOf(
                1 to 2 to inProgressProgress("show:1:2", 1, 2, 1_000L),
                1 to 4 to completedProgress("show:1:4", 1, 4, 2_000L)
            ),
            metaId = "show"
        )

        assertEquals("show:1:2", result.nextVideoId)
        assertEquals(1, result.nextSeason)
        assertEquals(2, result.nextEpisode)
        assertEquals(true, result.isResume)
    }

    @Test
    fun autoTargetedSeasonUsesTheCtaEpisodeWhenThereIsNoStoredPerSeasonFocus() {
        val meta = buildSeriesMeta(
            *episodesForSeasons(1..3, episodeCount = 10).toTypedArray()
        )
        val nextToWatch = buildSeriesNextToWatchCandidate(
            episodes = meta.videos,
            progressMap = mapOf(
                3 to 9 to completedProgress("show:3:9", 3, 9, 2_000L)
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

        assertEquals("show:3:10", resolved)
    }

    @Test
    fun manualOverrideReturnsTheSelectedSeasonsFirstEpisodeInsteadOfTheCtaTarget() {
        val meta = buildSeriesMeta(
            *episodesForSeasons(1..3, episodeCount = 10).toTypedArray()
        )
        val nextToWatch = buildSeriesNextToWatchCandidate(
            episodes = meta.videos,
            progressMap = mapOf(
                3 to 9 to completedProgress("show:3:9", 3, 9, 2_000L)
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

        assertEquals("show:1:1", resolved)
    }

    @Test
    fun storedPerSeasonFocusBeatsCtaTargeting() {
        val meta = buildSeriesMeta(
            *episodesForSeasons(1..3, episodeCount = 10).toTypedArray()
        )
        val nextToWatch = buildSeriesNextToWatchCandidate(
            episodes = meta.videos,
            progressMap = mapOf(
                3 to 9 to completedProgress("show:3:9", 3, 9, 2_000L)
            ),
            metaId = meta.id
        )

        val resolved = resolveSeasonEntryEpisodeId(
            meta = meta,
            selectedSeason = 3,
            nextToWatch = nextToWatch,
            lastFocusedEpisodeIdBySeason = mapOf(3 to "show:3:8"),
            manualSeasonOverride = false
        )

        assertEquals("show:3:8", resolved)
    }

    @Test
    fun nextToWatchTargetBeatsStoredFocusUntilInitialSeasonScrollIsHandled() {
        val meta = buildSeriesMeta(
            *episodesForSeasons(1..3, episodeCount = 10).toTypedArray()
        )
        val nextToWatch = buildSeriesNextToWatchCandidate(
            episodes = meta.videos,
            progressMap = mapOf(
                3 to 9 to completedProgress("show:3:9", 3, 9, 2_000L)
            ),
            metaId = meta.id
        )

        val resolved = resolveSeasonEntryEpisodeId(
            meta = meta,
            selectedSeason = 3,
            nextToWatch = nextToWatch,
            lastFocusedEpisodeIdBySeason = mapOf(3 to "show:3:8"),
            manualSeasonOverride = false,
            preferStoredEpisodeFocus = false
        )

        assertEquals("show:3:10", resolved)
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
    fun specialsCanAnchorRecentWatchContextWhenRegularEpisodesExist() {
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

        assertEquals("show:1:1", result.nextVideoId)
        assertEquals(1, result.nextSeason)
        assertEquals(1, result.nextEpisode)
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

    private fun inProgressProgress(
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
            position = 30L,
            duration = 100L,
            lastWatched = lastWatched,
            progressPercent = 30f
        )
    }

    private fun targetSeasonNextToWatch(
        season: Int,
        episode: Int
    ): NextToWatch {
        return NextToWatch(
            watchProgress = null,
            isResume = false,
            nextVideoId = "show:${season}:${episode}",
            nextSeason = season,
            nextEpisode = episode,
            displayText = "Next S${season}E${episode}"
        )
    }
}
