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
        assertTrue(source.contains("private val states = java.util.concurrent.ConcurrentHashMap<Int, TraktProgressRuntimeState>()"))
        assertTrue(source.contains("states.computeIfAbsent(session.profileId)"))
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
    fun `show continue watching limits progress validation to recent watched shows`() {
        val source = source()

        assertTrue(source.contains("private val nextUpValidationVisibleCandidateLimit = 30"))
        assertTrue(source.contains("private val nextUpValidationBudget = 30"))
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

    @Test
    fun `episode progress maps Trakt shows with show id precedence`() {
        val source = source()
        val historyMapper = source.section(
            start = "private fun mapEpisodeHistoryItem",
            end = "private suspend fun fetchEpisodeProgressSnapshot"
        )
        val playbackMapper = source.section(
            start = "private suspend fun mapPlaybackEpisode",
            end = "private fun mapSeasonProgress"
        )

        assertTrue(historyMapper.contains("normalizeContentId(show.ids, kind = MediaKind.SHOW)"))
        assertFalse(historyMapper.contains("normalizeContentId(show.ids)"))
        assertTrue(playbackMapper.contains("normalizeContentId(show.ids, kind = MediaKind.SHOW)"))
        assertFalse(playbackMapper.contains("normalizeContentId(show.ids)"))
    }

    private fun source(): String =
        File("app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt").readText()

    private fun String.section(start: String, end: String): String =
        substringAfter(start).substringBefore(end)

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
