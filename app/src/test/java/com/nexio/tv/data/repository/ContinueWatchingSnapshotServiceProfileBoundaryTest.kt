package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueWatchingSnapshotServiceProfileBoundaryTest {

    @Test
    fun `foreign snapshot is not publishable for active profile`() {
        val foreign = ProfileOwnedContinueWatchingSnapshot(
            profileId = 1,
            snapshot = ContinueWatchingSnapshot(
                resumeItems = listOf(sampleProgress("tt-profile-one")),
                updatedAtMs = 10L
            )
        )

        assertEquals(false, foreign.isOwnedBy(2))
    }

    @Test
    fun `owned snapshot is publishable for active profile`() {
        val owned = ProfileOwnedContinueWatchingSnapshot(
            profileId = 2,
            snapshot = ContinueWatchingSnapshot(
                resumeItems = listOf(sampleProgress("tt-profile-two")),
                updatedAtMs = 20L
            )
        )

        assertEquals(true, owned.isOwnedBy(2))
    }

    private fun sampleProgress(id: String): WatchProgress {
        return WatchProgress(
            contentId = id,
            contentType = "movie",
            name = id,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = id,
            season = null,
            episode = null,
            episodeTitle = null,
            position = 100L,
            duration = 1_000L,
            lastWatched = 1L,
            progressPercent = 10f
        )
    }
}
