package com.nexio.tv.ui.screens.home

import com.nexio.tv.data.local.WatchedSeriesEntry
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

    @Test
    fun `buildWatchedSeriesCandidateStates includes remote-hydrated show level watched items`() {
        val watchedItems = listOf(
            WatchedItem(
                contentId = "tmdb:101",
                contentType = "series",
                title = "Remote Hydrated Show",
                season = null,
                episode = null,
                watchedAt = 1_000L
            )
        )

        val states = buildWatchedSeriesCandidateStates(
            watchedItems = watchedItems,
            snapshot = ContinueWatchingSnapshot()
        )

        assertEquals(1, states.size)
        assertEquals(setOf("tmdb:101"), states.first().ids)
        assertTrue(states.first().watched)
    }

    @Test
    fun `buildSeriesNextUpLookupCandidatesFromWatchedItems includes remote-hydrated old shows`() {
        val watchedItems = listOf(
            WatchedItem(
                contentId = "tmdb:101",
                contentType = "series",
                title = "Remote Hydrated Show",
                season = null,
                episode = null,
                watchedAt = 1_000L
            )
        )

        val candidates = buildSeriesNextUpLookupCandidatesFromWatchedItems(
            watchedItems = watchedItems,
            snapshot = ContinueWatchingSnapshot(),
            currentEntries = emptyList(),
            discoveredEntryKeys = emptySet()
        )

        assertEquals(1, candidates.size)
        assertEquals("tmdb:101", candidates.first().contentId)
        assertEquals("Remote Hydrated Show", candidates.first().title)
    }

    @Test
    fun `resolveWatchedSeriesBootstrapWindow preserves persisted cache on first empty bootstrap read`() {
        val decision = resolveWatchedSeriesBootstrapWindow(
            watchedItems = emptyList(),
            currentEntries = listOf(WatchedSeriesEntry(ids = setOf("tmdb:101", "tt1234567"))),
            preserveOnFirstEmptyRead = true
        )

        assertTrue(decision.preservePersistedCache)
        assertFalse(decision.preserveOnFirstEmptyRead)
    }

    @Test
    fun `resolveWatchedSeriesBootstrapWindow treats second empty read as authoritative`() {
        val firstRead = resolveWatchedSeriesBootstrapWindow(
            watchedItems = emptyList(),
            currentEntries = listOf(WatchedSeriesEntry(ids = setOf("tmdb:101", "tt1234567"))),
            preserveOnFirstEmptyRead = true
        )
        val secondRead = resolveWatchedSeriesBootstrapWindow(
            watchedItems = emptyList(),
            currentEntries = listOf(WatchedSeriesEntry(ids = setOf("tmdb:101", "tt1234567"))),
            preserveOnFirstEmptyRead = firstRead.preserveOnFirstEmptyRead
        )

        assertFalse(secondRead.preservePersistedCache)
        assertFalse(secondRead.preserveOnFirstEmptyRead)
    }

    @Test
    fun `resolveWatchedSeriesBootstrapWindow closes bootstrap window after non-empty read`() {
        val decision = resolveWatchedSeriesBootstrapWindow(
            watchedItems = listOf(
                WatchedItem(
                    contentId = "show-fully-watched",
                    contentType = "series",
                    title = "Show Fully Watched",
                    season = 1,
                    episode = 8,
                    watchedAt = 1_000L
                )
            ),
            currentEntries = listOf(WatchedSeriesEntry(ids = setOf("tmdb:101", "tt1234567"))),
            preserveOnFirstEmptyRead = true
        )

        assertFalse(decision.preservePersistedCache)
        assertFalse(decision.preserveOnFirstEmptyRead)
    }

    @Test
    fun `shouldDiscardSeriesNextUpLookupResults drops results after session switch`() {
        assertTrue(
            shouldDiscardSeriesNextUpLookupResults(
                launchedForSessionKey = "alice",
                activeSessionKey = "bob",
                launchedGeneration = 2L,
                activeGeneration = 2L
            )
        )
        assertFalse(
            shouldDiscardSeriesNextUpLookupResults(
                launchedForSessionKey = "alice",
                activeSessionKey = "alice",
                launchedGeneration = 2L,
                activeGeneration = 2L
            )
        )
    }

    @Test
    fun `shouldDiscardSeriesNextUpLookupResults drops stale same-session generations`() {
        assertTrue(
            shouldDiscardSeriesNextUpLookupResults(
                launchedForSessionKey = "alice",
                activeSessionKey = "alice",
                launchedGeneration = 2L,
                activeGeneration = 3L
            )
        )
        assertFalse(
            shouldDiscardSeriesNextUpLookupResults(
                launchedForSessionKey = "alice",
                activeSessionKey = "alice",
                launchedGeneration = 3L,
                activeGeneration = 3L
            )
        )
    }
}
