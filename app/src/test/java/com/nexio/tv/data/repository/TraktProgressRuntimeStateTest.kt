package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.WatchProgress
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktProgressRuntimeStateTest {

    @Test
    fun `profile states are independent`() {
        val source = source()

        assertTrue(source.contains("private class TraktProgressRuntimeRegistry"))
        assertTrue(source.contains("private val states = mutableMapOf<Int, TraktProgressRuntimeState>()"))
        assertTrue(source.contains("states.getOrPut(session.profileId)"))
        assertTrue(source.contains("private val remoteProgress get() = runtimeState().remoteProgress"))
        assertTrue(source.contains("private val myShowsNextUp get() = runtimeState().myShowsNextUp"))
    }

    @Test
    fun `clearing one profile does not clear another profile`() {
        val source = source()

        assertTrue(source.contains("fun clearProfile(profileId: Int)"))
        assertTrue(source.contains("states[profileId]?.clear()"))
        assertTrue(source.contains("fun clear()"))
        assertTrue(source.contains("hasLoadedRemoteProgress.value = false"))
    }

    @Test
    fun `continue watching playback fetches are not age windowed`() {
        val source = source()

        assertTrue(source.contains("val inProgressMovies = getPlayback(\"movies\", force = force)"))
        assertTrue(source.contains("val inProgressEpisodes = getPlayback(\"episodes\", force = force)"))
        assertTrue(!source.contains("recentWatchWindowMs"))
        assertTrue(!source.contains("continueWatchingWindowDays"))
    }

    @Test
    fun `progress refresh is not startup gated`() {
        val source = source()

        assertFalse(source.contains("diskFirstHomeStartupEnabled"))
        assertFalse(source.contains("isStartupRefreshGated"))
        assertFalse(source.contains("refreshNowImmediate: deferred by startup gate"))
    }

    @Test
    fun `show continue watching uses watched shows as progress validation candidates`() {
        val source = source()

        assertTrue(source.contains("deriveNextUpFromWatchedShows("))
        assertTrue(source.contains("watchedShows.values"))
        assertTrue(source.contains("weakDerivation = true"))
        assertFalse(source.contains("deriveNextUpFromHistory("))
        assertFalse(source.contains("determineNextEpisode("))
    }

    @Test
    fun `show continue watching validates enough watched shows to match Trakt web progress`() {
        val source = source()

        assertTrue(source.contains("private val nextUpValidationVisibleCandidateLimit = 200"))
        assertTrue(source.contains("private val nextUpValidationBudget = 200"))
    }

    @Test
    fun `episode history prefers highest episode when season mark timestamps match`() {
        val earlyEpisode = historyProgress(season = 12, episode = 2, lastWatched = 1_000L)
        val seasonFinale = historyProgress(season = 12, episode = 24, lastWatched = 1_000L)

        assertTrue(shouldPreferEpisodeHistoryEntry(existing = earlyEpisode, candidate = seasonFinale))
        assertFalse(shouldPreferEpisodeHistoryEntry(existing = seasonFinale, candidate = earlyEpisode))
    }

    @Test
    fun `episode history prefers newer timestamp before episode coordinates`() {
        val oldFinale = historyProgress(season = 12, episode = 24, lastWatched = 1_000L)
        val newerEarlierEpisode = historyProgress(season = 12, episode = 3, lastWatched = 2_000L)

        assertTrue(shouldPreferEpisodeHistoryEntry(existing = oldFinale, candidate = newerEarlierEpisode))
        assertFalse(shouldPreferEpisodeHistoryEntry(existing = newerEarlierEpisode, candidate = oldFinale))
    }

    private fun source(): String =
        File("app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt").readText()

    private fun historyProgress(
        season: Int,
        episode: Int,
        lastWatched: Long
    ): WatchProgress {
        return WatchProgress(
            contentId = "tt6103712",
            contentType = "series",
            name = "Australian Survivor",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "tt6103712:$season:$episode",
            season = season,
            episode = episode,
            episodeTitle = null,
            position = 1L,
            duration = 1L,
            lastWatched = lastWatched,
            progressPercent = 100f,
            source = WatchProgress.SOURCE_TRAKT_HISTORY
        )
    }
}
