package com.nexio.tv.data.repository.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontierTrackerTest {

    private val pageSize = 128 * 1024 // 128KB
    private val chunkSize = 16L * 1024L * 1024L // 16MB

    @Test
    fun `sequential page completion advances frontier linearly`() {
        val tracker = FrontierTracker(pageSize = pageSize)
        val readSize = pageSize // one full page per read

        for (i in 0 until 10) {
            tracker.onBytesDownloaded(
                chunkIndex = 0,
                chunkSize = chunkSize,
                offsetInChunk = i.toLong() * readSize,
                bytesRead = readSize,
                tMs = (i + 1) * 100L
            )
        }

        val events = tracker.frontierEvents()
        assertEquals(10, events.size)
        assertEquals(10L * pageSize, events.last().contiguousFrontierBytes)
    }

    @Test
    fun `out-of-order pages block frontier until gap filled`() {
        val tracker = FrontierTracker(pageSize = pageSize)

        // Write page 1 (skip page 0)
        tracker.onBytesDownloaded(0, chunkSize, pageSize.toLong(), pageSize, tMs = 100)
        // Frontier should still be 0 since page 0 is missing
        assertTrue(tracker.frontierEvents().isEmpty())

        // Write page 2
        tracker.onBytesDownloaded(0, chunkSize, 2L * pageSize, pageSize, tMs = 200)
        assertTrue(tracker.frontierEvents().isEmpty())

        // Fill the gap (page 0)
        tracker.onBytesDownloaded(0, chunkSize, 0, pageSize, tMs = 300)
        // Now frontier should jump to cover pages 0, 1, 2
        val events = tracker.frontierEvents()
        assertEquals(1, events.size)
        assertEquals(3L * pageSize, events[0].contiguousFrontierBytes)
        assertEquals(3L * pageSize, events[0].deltaContiguousBytes)
    }

    @Test
    fun `parallel chunks with gap produces frontier jump when gap fills`() {
        val tracker = FrontierTracker(pageSize = pageSize)

        // Chunk 0 starts downloading (pages 0-3)
        tracker.onBytesDownloaded(0, chunkSize, 0, pageSize, tMs = 100)
        tracker.onBytesDownloaded(0, chunkSize, pageSize.toLong(), pageSize, tMs = 110)

        // Chunk 1 downloads ahead (but frontier can't advance past chunk 0)
        val chunk1Offset = chunkSize // chunk 1 starts here
        tracker.onBytesDownloaded(1, chunkSize, 0, pageSize, tMs = 120)
        tracker.onBytesDownloaded(1, chunkSize, pageSize.toLong(), pageSize, tMs = 130)

        val eventsBeforeGapFill = tracker.frontierEvents()
        // Frontier should be at 2 pages (end of what chunk 0 delivered contiguously)
        assertEquals(2L * pageSize, eventsBeforeGapFill.last().contiguousFrontierBytes)

        // Fill remaining pages of chunk 0
        val pagesInChunk = (chunkSize / pageSize).toInt()
        for (i in 2 until pagesInChunk) {
            tracker.onBytesDownloaded(0, chunkSize, i.toLong() * pageSize, pageSize, tMs = 200 + i.toLong())
        }

        val eventsAfterFill = tracker.frontierEvents()
        // Frontier should jump past chunk 0 and include chunk 1's cached pages
        val lastEvent = eventsAfterFill.last()
        assertTrue(lastEvent.contiguousFrontierBytes >= chunkSize + 2L * pageSize)
    }

    @Test
    fun `zero bytesRead is a no-op`() {
        val tracker = FrontierTracker(pageSize = pageSize)
        tracker.onBytesDownloaded(0, chunkSize, 0, 0, tMs = 100)
        assertTrue(tracker.frontierEvents().isEmpty())
    }

    @Test
    fun `idempotent page marking`() {
        val tracker = FrontierTracker(pageSize = pageSize)

        // Mark page 0 twice
        tracker.onBytesDownloaded(0, chunkSize, 0, pageSize, tMs = 100)
        tracker.onBytesDownloaded(0, chunkSize, 0, pageSize, tMs = 200)

        val events = tracker.frontierEvents()
        // Should only have one event (second mark doesn't advance frontier)
        assertEquals(1, events.size)
        assertEquals(pageSize.toLong(), events[0].contiguousFrontierBytes)
    }

    @Test
    fun `computeFrontierMetrics produces correct values`() {
        val tracker = FrontierTracker(pageSize = pageSize)

        // 4 pages over 1000ms
        for (i in 0 until 4) {
            tracker.onBytesDownloaded(0, chunkSize, i.toLong() * pageSize, pageSize, tMs = i * 250L)
        }

        val metrics = tracker.computeFrontierMetrics(elapsedMs = 1000L)
        assertEquals(4, metrics.totalFrontierEvents)
        assertEquals(4L * pageSize, metrics.finalFrontierBytes)
        assertTrue(metrics.frontierAdvanceRateMbps!! > 0.0)
        assertEquals(0, metrics.frontierStallCount)
    }

    @Test
    fun `stall detection in frontier metrics`() {
        val tracker = FrontierTracker(pageSize = pageSize)

        tracker.onBytesDownloaded(0, chunkSize, 0, pageSize, tMs = 0)
        // 3 second gap (stall > 2000ms threshold)
        tracker.onBytesDownloaded(0, chunkSize, pageSize.toLong(), pageSize, tMs = 3000)

        val metrics = tracker.computeFrontierMetrics(elapsedMs = 3000L)
        assertEquals(1, metrics.frontierStallCount)
        assertEquals(3000L, metrics.maxFrontierStallMs)
    }

    @Test
    fun `thread safety with concurrent calls`() {
        val tracker = FrontierTracker(pageSize = pageSize)
        val threads = (0 until 4).map { threadIndex ->
            Thread {
                for (i in 0 until 100) {
                    val chunkIndex = threadIndex.toLong()
                    tracker.onBytesDownloaded(
                        chunkIndex = chunkIndex,
                        chunkSize = chunkSize,
                        offsetInChunk = i.toLong() * pageSize,
                        bytesRead = pageSize,
                        tMs = System.currentTimeMillis()
                    )
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Should not throw and events should be consistent
        val events = tracker.frontierEvents()
        assertTrue(events.isNotEmpty())
        // Each event should have positive delta
        events.forEach { assertTrue(it.deltaContiguousBytes > 0) }
        // Frontier should be monotonically increasing
        for (i in 1 until events.size) {
            assertTrue(events[i].contiguousFrontierBytes > events[i - 1].contiguousFrontierBytes)
        }
    }
}
