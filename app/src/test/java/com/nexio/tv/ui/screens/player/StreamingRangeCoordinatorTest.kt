package com.nexio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingRangeCoordinatorTest {

    @Test
    fun fallbackRange_overlapsContainedFillRange() {
        val coordinator = StreamingRangeCoordinator()
        val token = coordinator.markFallbackOwned(start = 100, endExclusive = 200)

        assertTrue(coordinator.isOwnedByPlaybackFallback(start = 120, endExclusive = 180))

        coordinator.clearFallbackOwnership(token)
        assertFalse(coordinator.isOwnedByPlaybackFallback(start = 120, endExclusive = 180))
    }

    @Test
    fun fallbackRange_overlapsFillRangeStartingBeforeFallback() {
        val coordinator = StreamingRangeCoordinator()
        coordinator.markFallbackOwned(start = 100, endExclusive = 200)

        assertTrue(coordinator.isOwnedByPlaybackFallback(start = 50, endExclusive = 150))
    }

    @Test
    fun adjacentRanges_doNotOverlap() {
        val coordinator = StreamingRangeCoordinator()
        coordinator.markFallbackOwned(start = 100, endExclusive = 200)

        assertFalse(coordinator.isOwnedByPlaybackFallback(start = 0, endExclusive = 100))
        assertFalse(coordinator.isOwnedByPlaybackFallback(start = 200, endExclusive = 300))
    }

    @Test
    fun earlierLongFallbackRange_overlapsAfterLaterShortFallbackRange() {
        val coordinator = StreamingRangeCoordinator()
        coordinator.markFallbackOwned(start = 0, endExclusive = 1000)
        coordinator.markFallbackOwned(start = 500, endExclusive = 600)

        assertTrue(coordinator.isOwnedByPlaybackFallback(start = 800, endExclusive = 900))
    }
}
