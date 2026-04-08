package com.nexio.tv.ui.screens.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 4 memo invariant I2: atomic visibility via [PagedFrontierBuffer.publishCompleteChunk].
 *
 * A reader thread polling [PagedFrontierBuffer.read] or [PagedFrontierBuffer.frontier] must
 * either see zero bytes for the chunk range OR all bytes — never a partial count.
 */
@RunWith(RobolectricTestRunner::class)
class PagedFrontierBufferPublishCompleteChunkTest {

    private val PAGE_SIZE = PagedFrontierBuffer.PAGE_SIZE

    // -------------------------------------------------------------------------
    // 1. publishCompleteChunk advances frontier atomically across multiple pages
    // -------------------------------------------------------------------------

    @Test
    fun publishCompleteChunk_advancesFrontierAtomically() {
        val buffer = PagedFrontierBuffer()
        val chunkSize = 3 * PAGE_SIZE  // spans 3 pages
        buffer.setTotalLength(chunkSize.toLong())

        val chunk = ByteArray(chunkSize) { i -> (i % 127).toByte() }
        buffer.publishCompleteChunk(0L, chunk, chunkSize)

        // Frontier must jump to chunkSize in one step — no partial page states observable.
        assertEquals("Frontier must equal full chunk size after publish", chunkSize.toLong(), buffer.frontier)
    }

    // -------------------------------------------------------------------------
    // 2. Reader on a separate thread never observes a partial chunk
    // -------------------------------------------------------------------------

    @Test(timeout = 15_000L)
    fun readerNeverObservesPartialChunkDuringPublish() {
        val buffer = PagedFrontierBuffer()
        val chunkSize = 4 * PAGE_SIZE
        buffer.setTotalLength(chunkSize.toLong())

        val partialObservations = AtomicInteger(0)
        val readerDone = CountDownLatch(1)
        val publishStarted = CountDownLatch(1)

        // Reader thread: polls frontier; any value in (0, chunkSize) is a partial observation.
        val readerThread = Thread {
            publishStarted.await(5, TimeUnit.SECONDS)
            val deadline = System.currentTimeMillis() + 3_000L
            while (System.currentTimeMillis() < deadline) {
                val f = buffer.frontier
                if (f > 0L && f < chunkSize.toLong()) {
                    partialObservations.incrementAndGet()
                }
                if (f >= chunkSize.toLong()) break
            }
            readerDone.countDown()
        }
        readerThread.start()

        val chunk = ByteArray(chunkSize) { 0xAB.toByte() }
        publishStarted.countDown()
        buffer.publishCompleteChunk(0L, chunk, chunkSize)

        readerDone.await(8, TimeUnit.SECONDS)
        assertEquals(
            "Reader must never observe a partial chunk frontier (0 < f < chunkSize)",
            0,
            partialObservations.get()
        )
    }

    // -------------------------------------------------------------------------
    // 3. Data written by publishCompleteChunk is readable and correct
    // -------------------------------------------------------------------------

    @Test
    fun publishCompleteChunk_dataIsReadableAndCorrect() {
        val buffer = PagedFrontierBuffer()
        val chunkSize = 2 * PAGE_SIZE
        buffer.setTotalLength(chunkSize.toLong())

        val chunk = ByteArray(chunkSize) { i -> (i % 251).toByte() }
        buffer.publishCompleteChunk(0L, chunk, chunkSize)

        val dest = ByteArray(chunkSize)
        val read = buffer.read(0L, dest, 0, chunkSize)
        assertEquals("Must read full chunk", chunkSize, read)
        assertArrayEquals("Read data must match written chunk", chunk, dest)
    }

    // -------------------------------------------------------------------------
    // 4. publishCompleteChunk respects setBasePosition (partial first-page lowWater)
    // -------------------------------------------------------------------------

    @Test
    fun publishCompleteChunk_respectsBasePosition() {
        val buffer = PagedFrontierBuffer()
        // Start mid-page at offset 512 within page 0.
        val baseOffset = 512L
        val chunkSize = PAGE_SIZE  // one full page starting at baseOffset
        buffer.setTotalLength(baseOffset + chunkSize)
        buffer.setBasePosition(baseOffset)

        val chunk = ByteArray(chunkSize) { 0x7F.toByte() }
        buffer.publishCompleteChunk(baseOffset, chunk, chunkSize)

        val expectedFrontier = baseOffset + chunkSize
        assertEquals(
            "Frontier should advance to baseOffset + chunkSize after publishing from baseOffset",
            expectedFrontier,
            buffer.frontier
        )

        val dest = ByteArray(chunkSize)
        val read = buffer.read(baseOffset, dest, 0, chunkSize)
        assertEquals("Must read full chunk from baseOffset", chunkSize, read)
        assertArrayEquals("Data must match", chunk, dest)
    }

    // -------------------------------------------------------------------------
    // 5. Sequential: write half via onBytesWritten, then publish rest via publishCompleteChunk
    //    — frontier only moves after the second write completes the page
    // -------------------------------------------------------------------------

    @Test
    fun frontierOnlyMovesAfterPageComplete() {
        val buffer = PagedFrontierBuffer()
        val totalSize = PAGE_SIZE * 2
        buffer.setTotalLength(totalSize.toLong())

        // Write only page 1 (second page); frontier stays at 0.
        val page1Data = ByteArray(PAGE_SIZE) { 0x22.toByte() }
        buffer.onBytesWritten(PAGE_SIZE.toLong(), page1Data, 0, PAGE_SIZE)
        assertEquals("Frontier must stay at 0 until page 0 is complete", 0L, buffer.frontier)

        // Now publish page 0 via publishCompleteChunk — frontier must jump to 2 * PAGE_SIZE.
        val page0Data = ByteArray(PAGE_SIZE) { 0x11.toByte() }
        buffer.publishCompleteChunk(0L, page0Data, PAGE_SIZE)
        assertEquals("Frontier must advance through both pages after page 0 is published",
            totalSize.toLong(), buffer.frontier)
    }

    // -------------------------------------------------------------------------
    // 6. publishCompleteChunk is idempotent for already-written pages (no crash, no corruption)
    // -------------------------------------------------------------------------

    @Test
    fun publishCompleteChunk_doesNotCorruptOnOverwrite() {
        val buffer = PagedFrontierBuffer()
        val chunkSize = PAGE_SIZE
        buffer.setTotalLength(chunkSize.toLong())

        val chunk1 = ByteArray(chunkSize) { 0xAA.toByte() }
        buffer.publishCompleteChunk(0L, chunk1, chunkSize)
        assertEquals(chunkSize.toLong(), buffer.frontier)

        // Overwrite with different content — should not crash and frontier stays the same.
        val chunk2 = ByteArray(chunkSize) { 0xBB.toByte() }
        buffer.publishCompleteChunk(0L, chunk2, chunkSize)
        assertEquals(chunkSize.toLong(), buffer.frontier)
    }
}
