package com.nexio.tv.data.repository

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TraktProgressServiceBootBudgetTest {
    private val source = File("app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt").readText()

    @Test
    fun `trakt boot critical progress does not fetch episode history pages`() {
        assertTrue(source.contains("TraktProgressRefreshMode.BOOT_CRITICAL"))
        assertTrue(source.contains("mode == TraktProgressRefreshMode.INCREMENTAL"))
        assertTrue(source.contains("emptyList()"))
        assertTrue(source.contains("fetchRecentEpisodeHistorySnapshot()"))
    }

    @Test
    fun `trakt incremental account sync does not paginate episode history`() {
        val function = source.substringAfter("private suspend fun fetchAllProgressSnapshot(")
            .substringBefore("private suspend fun fetchRecentEpisodeHistorySnapshot()")

        assertTrue(function.contains("TraktProgressRefreshMode.INCREMENTAL"))
        assertTrue(function.contains("emptyList()"))
    }

    @Test
    fun `trakt post initial refresh uses incremental playback refresh only`() {
        val function = source.substringAfter("private suspend fun refreshRemoteSnapshot()")
            .substringBefore("private suspend fun hasActivityChanged()")

        assertTrue(function.contains("val initialLoad = !hasLoadedRemoteProgress.value"))
        assertTrue(function.contains("fetchIncrementalProgressSnapshot(force = force)"))
        assertTrue(function.contains("watchedShowsIndex"))
        assertTrue(function.contains("getWatchedShowsSnapshot(forceRefresh = activityChanged || force)"))
    }

    @Test
    fun `trakt last activities does not invalidate full watched snapshots after initial sync`() {
        val function = source.substringAfter("private suspend fun hasActivityChanged()")
            .substringBefore("private suspend fun invalidateEpisodeProgressCache(")

        assertTrue(!function.contains("invalidateWatchedSnapshot"))
    }

    @Test
    fun `trakt progress mutations do not invalidate full watched snapshots`() {
        val mutationRegion = source.substringAfter("suspend fun markAsWatched(")
            .substringBefore("private suspend fun refreshRemoteSnapshot()")

        assertTrue(!mutationRegion.contains("invalidateWatchedSnapshot"))
    }

    @Test
    fun `trakt boot critical progress does not force per show watched progress validation`() {
        assertTrue(source.contains("forceValidation = activityChanged && refreshMode != TraktProgressRefreshMode.BOOT_CRITICAL"))
    }

    @Test
    fun `trakt scrobble reconciliation refreshes playback only without watched or next up sync`() {
        val function = source.substringAfter("suspend fun refreshPlaybackOnly()")
            .substringBefore("/**\n     * Immediate refresh")

        assertTrue(function.contains("fetchIncrementalProgressSnapshot(force = true)"))
        assertTrue(!function.contains("refreshSignals.emit"))
        assertTrue(!function.contains("getWatchedShowsSnapshot"))
        assertTrue(!function.contains("deriveNextUpFromWatchedShows"))
        assertTrue(!function.contains("forceValidation"))
    }

    @Test
    fun `trakt watched show sync does not fan out per show progress validation`() {
        val function = source.substringAfter("private suspend fun deriveNextUpFromWatchedShows(")
            .substringBefore("private suspend fun validateNextUpCandidates(")

        assertTrue(!function.contains("validateNextUpCandidates("))
        assertTrue(!function.contains("getShowProgressWatched"))
    }
}
