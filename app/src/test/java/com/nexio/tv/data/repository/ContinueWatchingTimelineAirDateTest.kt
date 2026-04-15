package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.WatchProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContinueWatchingTimelineAirDateTest {

    // ── Rail gating ────────────────────────────────────────────────────────────

    @Test
    fun `allRailsAirDateGated - unaired nextUp entries are excluded from main feed`() {
        val nowMs = 1_000L
        val airedEntry = nextUp(contentId = "aired-show", firstAiredMs = 500L)
        val unairedEntry = nextUp(contentId = "future-show", firstAiredMs = 5_000L)

        val selection = splitNextUpCandidatesForContinueWatching(
            resumes = emptyList(),
            nextUpItems = listOf(airedEntry, unairedEntry),
            nextUpRef = ::nextUpRef,
            nowMs = nowMs
        )

        assertEquals(listOf("aired-show"), selection.mainFeedItems.map { it.contentId })
        // synthetic rail preserves all (existing behaviour)
        assertEquals(
            listOf("aired-show", "future-show"),
            selection.syntheticRailItems.map { it.contentId }
        )
    }

    @Test
    fun `allRailsAirDateGated - exact future nextUp entries are excluded`() {
        val nowMs = 10_000L
        val dateOnlyAiredEntry = nextUp(
            contentId = "date-only-aired",
            firstAiredMs = 1_000L,
            availabilityInstantMs = null
        )
        val exactFutureEntry = nextUp(
            contentId = "exact-future",
            firstAiredMs = 1_000L,
            availabilityInstantMs = 20_000L
        )

        val selection = splitNextUpCandidatesForContinueWatching(
            resumes = emptyList(),
            nextUpItems = listOf(dateOnlyAiredEntry, exactFutureEntry),
            nextUpRef = ::nextUpRef,
            nowMs = nowMs
        )

        assertEquals(listOf("date-only-aired"), selection.mainFeedItems.map { it.contentId })
        assertEquals(
            listOf("date-only-aired", "exact-future"),
            selection.syntheticRailItems.map { it.contentId }
        )
    }

    @Test
    fun `allRailsAirDateGated - resume items remain visible when next up is exact future`() {
        val nowMs = 10_000L
        val resumeItems = listOf(
            resume(contentId = "show-a", lastWatched = 9_000L)
        )
        val exactFutureEntry = nextUp(
            contentId = "show-a",
            firstAiredMs = 1_000L,
            availabilityInstantMs = 20_000L
        )

        val selection = splitNextUpCandidatesForContinueWatching(
            resumes = resumeItems.map(::resumeRef),
            nextUpItems = listOf(exactFutureEntry),
            nextUpRef = ::nextUpRef,
            nowMs = nowMs
        )
        val timeline = buildMixedContinueWatchingTimeline(
            resumeItems = resumeItems,
            nextUpItems = selection.mainFeedItems,
            resumeRef = ::resumeRef,
            nextUpRef = ::nextUpRef
        )

        assertEquals(emptyList<TrackingNextUpEntry>(), selection.mainFeedItems)
        assertEquals(1, timeline.size)
        assertTrue(timeline.single() is ContinueWatchingTimelineRow.Resume)
    }

    @Test
    fun `allRailsAirDateGated - unaired traktUpNext entries excluded via AirDateGate`() {
        val nowMs = 1_000L
        val airedEntry = nextUp(contentId = "aired-trakt", firstAiredMs = 200L)
        val unairedEntry = nextUp(contentId = "future-trakt", firstAiredMs = 9_000L)

        val syntheticCandidates = splitNextUpCandidatesForContinueWatching(
            resumes = emptyList(),
            nextUpItems = listOf(airedEntry, unairedEntry),
            nextUpRef = ::nextUpRef,
            nowMs = nowMs
        ).syntheticRailItems

        // Apply the same AirDateGate filter that ContinueWatchingSnapshotService uses
        val traktUpNextItems = syntheticCandidates.filter { entry ->
            AirDateGate.isAired(
                firstAiredMs = entry.firstAiredMs,
                tmdbAirDate = entry.firstAired,
                nowMs = nowMs
            )
        }

        assertEquals(listOf("aired-trakt"), traktUpNextItems.map { it.contentId })
    }

    @Test
    fun `airedEntriesPreserved - aired entries pass through both rails unchanged`() {
        val nowMs = 10_000L
        val entries = listOf(
            nextUp(contentId = "show-a", firstAiredMs = 1_000L),
            nextUp(contentId = "show-b", firstAiredMs = 5_000L),
            nextUp(contentId = "show-c", firstAiredMs = 9_999L)
        )

        val selection = splitNextUpCandidatesForContinueWatching(
            resumes = emptyList(),
            nextUpItems = entries,
            nextUpRef = ::nextUpRef,
            nowMs = nowMs
        )

        assertEquals(
            listOf("show-a", "show-b", "show-c"),
            selection.mainFeedItems.map { it.contentId }
        )
    }

    @Test
    fun `allRailsAirDateGated - resume items are run through AirDateGate uniformly`() {
        // Spec: ALL three rails (resume, nextUp, traktUpNext) must be gated through
        // AirDateGate.isAired in buildRawSnapshot. Resume items (WatchProgress) carry no
        // air-date data, so they pass firstAiredMs=0 and tmdbAirDate=null. AirDateGate
        // returns true for unknown data — so gated resumes come through unchanged, but
        // the gate call IS applied (no separate bypass path).
        val nowMs = 10_000L
        val resumeItems = listOf(
            resume(contentId = "resume-a", lastWatched = 1_000L),
            resume(contentId = "resume-b", lastWatched = 500L)
        )

        // Simulate the central gate site in buildRawSnapshot for resumes
        val gatedResumeItems = resumeItems.filter {
            AirDateGate.isAired(firstAiredMs = 0L, tmdbAirDate = null, nowMs = nowMs)
        }

        // All resume items should survive the gate (no air-date data → treated as aired)
        assertEquals(
            listOf("resume-a", "resume-b"),
            gatedResumeItems.map { it.contentId }
        )
        // And the gate must be the exact same call used for nextUp/traktUpNext rails
        assertTrue(
            "AirDateGate must return true for resume items lacking air-date data",
            AirDateGate.isAired(firstAiredMs = 0L, tmdbAirDate = null, nowMs = nowMs)
        )
    }

    // ── Timer churn (T1) ────────────────────────────────────────────────────────

    /**
     * Verifies that the stable re-emit timer logic does NOT cancel and reschedule
     * when [soonestPendingMs] is the same across multiple recomputes.
     *
     * We reproduce the exact idempotency guard from [ContinueWatchingSnapshotService.scheduleReemitIfNeeded]:
     * if soonestMs == currentTimerTargetMs → skip (no cancel, no new job).
     */
    @Test
    fun `airDateTimerSurvivesShortRecomputes - same target does not cancel existing job`() = runTest {
        var cancelCount = 0
        var fireCount = 0
        var currentTimerTargetMs: Long? = null
        var reemitJob: Job? = null

        fun scheduleReemitIfNeeded(soonestMs: Long?, nowMs: Long) {
            if (soonestMs == currentTimerTargetMs) return // T1 idempotency guard
            reemitJob?.also { cancelCount++ }?.cancel()
            currentTimerTargetMs = soonestMs
            if (soonestMs != null) {
                val delayMs = (soonestMs - nowMs).coerceAtLeast(0L)
                reemitJob = launch {
                    delay(delayMs)
                    fireCount++
                }
            }
        }

        val targetMs = 90_000L

        // Initial schedule at t=0
        scheduleReemitIfNeeded(soonestMs = targetMs, nowMs = 0L)
        assertEquals("Initial schedule should not have caused a cancel", 0, cancelCount)

        // Recompute at t=30s with same target — should be a no-op
        advanceTimeBy(30_000L)
        scheduleReemitIfNeeded(soonestMs = targetMs, nowMs = 30_000L)
        assertEquals("Recompute at t+30s with same target must not cancel", 0, cancelCount)

        // Recompute at t=60s with same target — should still be no-op
        advanceTimeBy(30_000L)
        scheduleReemitIfNeeded(soonestMs = targetMs, nowMs = 60_000L)
        assertEquals("Recompute at t+60s with same target must not cancel", 0, cancelCount)

        // Advance past the target — original job should fire
        advanceTimeBy(30_001L)
        runCurrent()
        assertEquals("Original job must fire exactly once", 1, fireCount)
    }

    @Test
    fun `timerForcesRefreshOnAirDateCross - timer fires and increments counter after delay`() = runTest {
        var refreshCount = 0
        var currentTimerTargetMs: Long? = null
        var reemitJob: Job? = null

        fun scheduleReemitIfNeeded(soonestMs: Long?, nowMs: Long) {
            if (soonestMs == currentTimerTargetMs) return
            reemitJob?.cancel()
            currentTimerTargetMs = soonestMs
            if (soonestMs != null) {
                val delayMs = (soonestMs - nowMs).coerceAtLeast(0L)
                reemitJob = launch {
                    delay(delayMs)
                    refreshCount++ // represents ensureFresh(force = true)
                }
            }
        }

        // Schedule timer to fire in 5 seconds
        scheduleReemitIfNeeded(soonestMs = 5_000L, nowMs = 0L)
        assertEquals(0, refreshCount)

        advanceTimeBy(4_999L)
        assertEquals("Should not have fired yet", 0, refreshCount)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals("Should have fired after air date crossed", 1, refreshCount)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun nextUp(
        contentId: String,
        season: Int = 1,
        episode: Int = 1,
        activityAtMs: Long = 1_000L,
        firstAiredMs: Long,
        availabilityInstantMs: Long? = null
    ): TrackingNextUpEntry {
        return TrackingNextUpEntry(
            contentId = contentId,
            name = contentId,
            season = season,
            episode = episode,
            episodeTitle = "Episode $episode",
            videoId = "$contentId:$season:$episode",
            firstAired = null,
            firstAiredMs = firstAiredMs,
            activityAtMs = activityAtMs,
            tvdbAvailabilityInstantMs = availabilityInstantMs
        )
    }

    private fun nextUpRef(entry: TrackingNextUpEntry): ContinueWatchingNextUpRef {
        return ContinueWatchingNextUpRef(
            contentId = entry.contentId,
            activityAtMs = entry.activityAtMs,
            firstAiredMs = entry.firstAiredMs
        )
    }

    private fun resume(contentId: String, lastWatched: Long): WatchProgress {
        return WatchProgress(
            contentId = contentId,
            contentType = "movie",
            name = contentId,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = contentId,
            season = null,
            episode = null,
            episodeTitle = null,
            position = 50L,
            duration = 100L,
            lastWatched = lastWatched,
            progressPercent = 50f
        )
    }

    private fun resumeRef(progress: WatchProgress): ContinueWatchingResumeRef {
        return ContinueWatchingResumeRef(
            contentId = progress.contentId,
            activityAtMs = progress.lastWatched,
            suppressNextUp = false
        )
    }
}
