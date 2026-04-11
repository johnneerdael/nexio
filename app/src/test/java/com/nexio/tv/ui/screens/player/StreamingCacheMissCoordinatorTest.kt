package com.nexio.tv.ui.screens.player

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StreamingCacheMissCoordinatorTest {

    @Before
    fun resetMetrics() {
        StreamingMetrics.fallbackReadsTriggered.set(0L)
        StreamingMetrics.urgentFillRequests.set(0L)
    }

    @Test
    fun fallbackOwnership_delegatesToRangeCoordinatorAndClearsByToken() {
        val coordinator = StreamingCacheMissCoordinator(StreamingRangeCoordinator())

        val token = coordinator.markFallbackOwned(start = 100L, endExclusive = 200L)

        assertTrue(coordinator.isOwnedByPlaybackFallback(start = 150L, endExclusive = 160L))

        coordinator.clearFallbackOwnership(token)

        assertFalse(coordinator.isOwnedByPlaybackFallback(start = 150L, endExclusive = 160L))
    }

    @Test
    fun requestUrgentFill_returnsFalseWhenNoHandlerIsAttached() {
        val coordinator = StreamingCacheMissCoordinator(StreamingRangeCoordinator())

        val filled = coordinator.requestUrgentFill(
            cacheKey = "movie",
            position = 4096L,
            minLength = 2L * 1024L * 1024L,
            timeoutMs = 1L
        )

        assertFalse(filled)
        assertEquals(0L, coordinator.estimatedBytesPerSecond())
    }

    @Test
    fun requestUrgentFill_delegatesToAttachedHandler() {
        val coordinator = StreamingCacheMissCoordinator(StreamingRangeCoordinator())
        val prioritizedPosition = AtomicLong(-1L)
        val awaited = AtomicBoolean(false)
        val handler = object : StreamingCacheMissCoordinator.UrgentFillHandler {
            override fun prioritize(position: Long) {
                prioritizedPosition.set(position)
            }

            override fun awaitSpanCommitted(
                cacheKey: String,
                position: Long,
                minLength: Long,
                timeoutMs: Long
            ): Boolean {
                awaited.set(true)
                assertEquals("movie", cacheKey)
                assertEquals(8192L, position)
                assertEquals(2L * 1024L * 1024L, minLength)
                assertEquals(3000L, timeoutMs)
                return true
            }

            override fun estimatedBytesPerSecond(): Long = 16L * 1024L * 1024L
        }

        coordinator.attachUrgentFillHandler(handler)

        val filled = coordinator.requestUrgentFill(
            cacheKey = "movie",
            position = 8192L,
            minLength = 2L * 1024L * 1024L,
            timeoutMs = 3000L
        )

        assertTrue(filled)
        assertTrue(awaited.get())
        assertEquals(8192L, prioritizedPosition.get())
        assertEquals(16L * 1024L * 1024L, coordinator.estimatedBytesPerSecond())
    }

    @Test
    fun detachUrgentFillHandler_onlyDetachesMatchingHandler() {
        val coordinator = StreamingCacheMissCoordinator(StreamingRangeCoordinator())
        val first = handler(bytesPerSecond = 10L)
        val second = handler(bytesPerSecond = 20L)

        coordinator.attachUrgentFillHandler(first)
        coordinator.attachUrgentFillHandler(second)
        coordinator.detachUrgentFillHandler(first)

        assertEquals(20L, coordinator.estimatedBytesPerSecond())

        coordinator.detachUrgentFillHandler(second)

        assertEquals(0L, coordinator.estimatedBytesPerSecond())
    }

    private fun handler(bytesPerSecond: Long): StreamingCacheMissCoordinator.UrgentFillHandler {
        return object : StreamingCacheMissCoordinator.UrgentFillHandler {
            override fun prioritize(position: Long) = Unit

            override fun awaitSpanCommitted(
                cacheKey: String,
                position: Long,
                minLength: Long,
                timeoutMs: Long
            ): Boolean = false

            override fun estimatedBytesPerSecond(): Long = bytesPerSecond
        }
    }
}
