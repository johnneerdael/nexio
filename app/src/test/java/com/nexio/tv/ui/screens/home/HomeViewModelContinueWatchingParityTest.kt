package com.nexio.tv.ui.screens.home

import com.nexio.tv.data.repository.ContinueWatchingSnapshot
import com.nexio.tv.data.repository.TraktProgressService
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.domain.model.WatchedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeViewModelContinueWatchingParityTest {

    @Test
    fun `buildWatchedSeriesCandidateStates uses local watched items beyond hot cw seeds`() {
        val watchedItems = listOf(
            WatchedItem(
                contentId = "show-fully-watched",
                contentType = "series",
                title = "Show Fully Watched",
                season = 1,
                episode = 8,
                watchedAt = 1_000L
            ),
            WatchedItem(
                contentId = "show-with-next-up",
                contentType = "series",
                title = "Show With Next Up",
                season = 2,
                episode = 4,
                watchedAt = 2_000L
            )
        )
        val snapshot = ContinueWatchingSnapshot(
            nextUpItems = listOf(
                TraktProgressService.NextUpEntry(
                    contentId = "show-with-next-up",
                    name = "Show With Next Up",
                    season = 3,
                    episode = 1,
                    episodeTitle = "Premiere",
                    videoId = "show-with-next-up:3:1",
                    firstAired = "2026-03-30T00:00:00.000Z",
                    firstAiredMs = 1_500L,
                    activityAtMs = 2_500L
                )
            ),
            resumeItems = listOf(
                WatchProgress(
                    contentId = "show-in-progress",
                    contentType = "series",
                    name = "Show In Progress",
                    poster = null,
                    backdrop = null,
                    logo = null,
                    videoId = "show-in-progress:1:5",
                    season = 1,
                    episode = 5,
                    episodeTitle = "Episode 5",
                    position = 10L,
                    duration = 100L,
                    lastWatched = 3_000L,
                    progressPercent = 10f
                )
            )
        )

        val states = buildWatchedSeriesCandidateStates(
            watchedItems = watchedItems,
            snapshot = snapshot
        )
        val byId = states.associateBy { it.ids.single() }

        assertEquals(setOf("show-fully-watched", "show-with-next-up"), byId.keys)
        assertTrue(byId.getValue("show-fully-watched").watched)
        assertFalse(byId.getValue("show-with-next-up").watched)
    }
}
