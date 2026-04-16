package com.nexio.tv.ui.screens.player

import com.nexio.tv.domain.model.WatchProgress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerResumeProgressPolicyTest {

    @Test
    fun `local progress below continue watching threshold is still resumeable`() {
        val progress = movieProgress(
            position = 2 * 60_000L,
            duration = 3 * 60 * 60_000L
        )

        assertTrue(shouldUseSavedProgressForResume(progress))
    }

    @Test
    fun `completed progress is not resumeable`() {
        val progress = movieProgress(
            position = 90 * 60_000L,
            duration = 100 * 60_000L
        )

        assertFalse(shouldUseSavedProgressForResume(progress))
    }

    @Test
    fun `low percentage watched-history progress is not treated as a resume point`() {
        val progress = movieProgress(
            position = 30_000L,
            duration = 3 * 60 * 60_000L,
            source = WatchProgress.SOURCE_TRAKT_HISTORY
        )

        assertFalse(shouldUseSavedProgressForResume(progress))
    }

    private fun movieProgress(
        position: Long,
        duration: Long,
        source: String = WatchProgress.SOURCE_LOCAL
    ): WatchProgress {
        return WatchProgress(
            contentId = "tt1234567",
            contentType = "movie",
            name = "Test Movie",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "tt1234567",
            season = null,
            episode = null,
            episodeTitle = null,
            position = position,
            duration = duration,
            lastWatched = 1234L,
            source = source
        )
    }
}
