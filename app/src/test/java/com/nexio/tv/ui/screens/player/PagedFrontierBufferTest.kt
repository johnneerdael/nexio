package com.nexio.tv.ui.screens.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PagedFrontierBufferTest {

    private val PAGE_SIZE = PagedFrontierBuffer.PAGE_SIZE

    // -------------------------------------------------------------------------
    // Helper: write a full page worth of bytes at the given page index
    // -------------------------------------------------------------------------
    private fun PagedFrontierBuffer.writePage(pageIndex: Int, fillByte: Byte = pageIndex.toByte()) {
        val data = ByteArray(PAGE_SIZE) { fillByte }
        onBytesWritten(pageIndex.toLong() * PAGE_SIZE, data, 0, PAGE_SIZE)
    }

    // -------------------------------------------------------------------------
    // 1. Non-contiguous pages: frontier only advances through filled gaps
    // -------------------------------------------------------------------------
    @Test
    fun `frontier does not advance past gap in completed pages`() {
        val buffer = PagedFrontierBuffer()
        buffer.setTotalLength(4L * PAGE_SIZE)

        // Write pages 0, 1, 3 — skip page 2
        buffer.writePage(0, 0x00)
        buffer.writePage(1, 0x01)
        buffer.writePage(3, 0x03)

        // Frontier should stop at boundary of page 2 (= 2 * PAGE_SIZE)
        assertEquals(2L * PAGE_SIZE, buffer.frontier)

        // Fill the gap
        buffer.writePage(2, 0x02)

        // Now frontier should advance past all four complete pages
        assertEquals(4L * PAGE_SIZE, buffer.frontier)
    }

    // -------------------------------------------------------------------------
    // 2. read() returns data from contiguous region; returns 0 past frontier
    // -------------------------------------------------------------------------
    @Test
    fun `read returns data within contiguous region and zero past frontier`() {
        val buffer = PagedFrontierBuffer()
        buffer.setTotalLength(3L * PAGE_SIZE)

        val expected = ByteArray(PAGE_SIZE) { 0xAB.toByte() }
        buffer.onBytesWritten(0L, expected, 0, PAGE_SIZE)
        buffer.writePage(1, 0x00)
        buffer.writePage(2, 0x00)

        // Read first page
        val dest = ByteArray(PAGE_SIZE)
        val read = buffer.read(0L, dest, 0, PAGE_SIZE)
        assertEquals(PAGE_SIZE, read)
        assertArrayEquals(expected, dest)

        // Reading past the frontier returns 0
        val beyond = buffer.read(3L * PAGE_SIZE, dest, 0, PAGE_SIZE)
        assertEquals(0, beyond)
    }

    // -------------------------------------------------------------------------
    // 3. evictBefore() returns buffers to pool and removes page entries
    // -------------------------------------------------------------------------
    @Test
    fun `evictBefore removes pages before position and returns them to pool`() {
        val buffer = PagedFrontierBuffer()
        buffer.setTotalLength(4L * PAGE_SIZE)

        buffer.writePage(0)
        buffer.writePage(1)
        buffer.writePage(2)
        buffer.writePage(3)

        assertEquals(4L * PAGE_SIZE, buffer.frontier)

        // Evict everything before page 2
        buffer.evictBefore(2L * PAGE_SIZE)

        // Pages 0 and 1 should be gone; reading them returns 0
        val dest = ByteArray(PAGE_SIZE)
        val fromPage0 = buffer.read(0L, dest, 0, PAGE_SIZE)
        assertEquals(0, fromPage0)

        // Pages 2 and 3 are still readable (frontier unchanged)
        val fromPage2 = buffer.read(2L * PAGE_SIZE, dest, 0, PAGE_SIZE)
        assertEquals(PAGE_SIZE, fromPage2)
    }

    // -------------------------------------------------------------------------
    // 4. Partial page writes: page becomes complete only when all bytes arrive
    // -------------------------------------------------------------------------
    @Test
    fun `partial writes complete a page only after all bytes are written`() {
        val buffer = PagedFrontierBuffer()
        buffer.setTotalLength(PAGE_SIZE.toLong())

        val half = PAGE_SIZE / 2
        val firstHalf = ByteArray(half) { 0x11 }
        val secondHalf = ByteArray(half) { 0x22 }

        // Write first half — page should NOT be complete yet
        buffer.onBytesWritten(0L, firstHalf, 0, half)
        assertEquals(0L, buffer.frontier)

        // Write second half — page is now complete
        buffer.onBytesWritten(half.toLong(), secondHalf, 0, half)
        assertEquals(PAGE_SIZE.toLong(), buffer.frontier)

        // Verify the full page content is correct
        val dest = ByteArray(PAGE_SIZE)
        val read = buffer.read(0L, dest, 0, PAGE_SIZE)
        assertEquals(PAGE_SIZE, read)
        for (i in 0 until half) assertEquals(0x11.toByte(), dest[i])
        for (i in half until PAGE_SIZE) assertEquals(0x22.toByte(), dest[i])
    }

    // -------------------------------------------------------------------------
    // 5. reset() clears all state
    // -------------------------------------------------------------------------
    @Test
    fun `reset clears all pages and resets frontier to zero`() {
        val buffer = PagedFrontierBuffer()
        buffer.setTotalLength(2L * PAGE_SIZE)

        buffer.writePage(0)
        buffer.writePage(1)
        assertEquals(2L * PAGE_SIZE, buffer.frontier)

        buffer.reset()

        assertEquals(0L, buffer.frontier)

        // No data should be readable after reset
        val dest = ByteArray(PAGE_SIZE)
        assertEquals(0, buffer.read(0L, dest, 0, PAGE_SIZE))

        // Buffer should be functional after reset
        buffer.setTotalLength(PAGE_SIZE.toLong())
        buffer.writePage(0, 0x55)
        assertEquals(PAGE_SIZE.toLong(), buffer.frontier)

        val dest2 = ByteArray(PAGE_SIZE)
        val read = buffer.read(0L, dest2, 0, PAGE_SIZE)
        assertEquals(PAGE_SIZE, read)
        assertTrue(dest2.all { it == 0x55.toByte() })
    }

    // -------------------------------------------------------------------------
    // 6. readableContiguousBytesFrom returns correct count
    // -------------------------------------------------------------------------
    @Test
    fun `readableContiguousBytesFrom reflects frontier relative to position`() {
        val buffer = PagedFrontierBuffer()
        buffer.setTotalLength(2L * PAGE_SIZE)

        buffer.writePage(0)
        buffer.writePage(1)

        assertEquals(2L * PAGE_SIZE, buffer.readableContiguousBytesFrom(0L))
        assertEquals(PAGE_SIZE.toLong(), buffer.readableContiguousBytesFrom(PAGE_SIZE.toLong()))
        assertEquals(0L, buffer.readableContiguousBytesFrom(2L * PAGE_SIZE))
    }
}
