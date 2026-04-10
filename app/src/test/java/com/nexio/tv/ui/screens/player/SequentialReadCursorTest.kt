package com.nexio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase 4 façade tests for [DefaultSequentialReadCursor]. The cursor is the sole
 * Media3 read boundary; these tests pin the contract rules from the Phase 4 plan.
 */
@RunWith(RobolectricTestRunner::class)
class SequentialReadCursorTest {

    private fun session(start: Long, length: Long): OpenSession = OpenSession(
        requestSpec = DataSpec.Builder()
            .setUri(Uri.parse("http://h/f"))
            .setPosition(start)
            .setLength(length)
            .build(),
        resolvedUri = Uri.parse("http://h/f"),
        startPosition = start,
        openLength = length,
        totalFileLength = start + length,
        acceptsRanges = true,
        responseHeaders = emptyMap()
    )

    /** In-memory store whose "available" window can be grown on demand. */
    private class FakeStore(val content: ByteArray, var available: Long = 0L) : AbsoluteByteStore {
        var readCallCount: Int = 0
        override val frontier: Long get() = available
        override fun writeAt(absoluteOffset: Long, data: ByteArray, dataOffset: Int, length: Int) {
            System.arraycopy(data, dataOffset, content, absoluteOffset.toInt(), length)
            if (absoluteOffset + length > available) available = absoluteOffset + length
        }
        override fun publishStartupWindow(absoluteOffset: Long, data: ByteArray, dataOffset: Int, length: Int) {
            writeAt(absoluteOffset, data, dataOffset, length)
        }
        override fun read(position: Long, dest: ByteArray, destOffset: Int, length: Int): Int {
            readCallCount += 1
            if (position >= available) return 0
            val n = minOf(length.toLong(), available - position).toInt()
            System.arraycopy(content, position.toInt(), dest, destOffset, n)
            return n
        }
        override fun readableContiguousBytesFrom(position: Long): Long =
            (available - position).coerceAtLeast(0L)
        override fun publishCompleteChunk(absoluteStart: Long, chunk: ByteArray, length: Int) {
            System.arraycopy(chunk, 0, content, absoluteStart.toInt(), length)
            if (absoluteStart + length > available) available = absoluteStart + length
        }
        override fun setTotalLength(length: Long) {}
        override fun setBasePosition(position: Long) {}
        override fun evictBefore(position: Long) {}
        override fun reset() { available = 0 }
    }

    private fun cursor(
        store: FakeStore,
        start: Long = 0,
        length: Long = store.content.size.toLong(),
        wait: (Long, Long) -> WaitOutcome = { _, _ -> WaitOutcome.DATA_AVAILABLE }
    ) = DefaultSequentialReadCursor(
        session = session(start, length),
        store = store,
        waitForBytes = wait,
        onPositionAdvanced = {},
        keepBehindBytes = 0,
        chunkWaitTimeoutMs = 1_000
    )

    @Test
    fun `length zero returns zero`() {
        val store = FakeStore(ByteArray(16) { it.toByte() }, available = 16)
        assertEquals(0, cursor(store).read(ByteArray(8), 0, 0))
    }

    @Test
    fun `emits contiguous bytes from cursor and advances position`() {
        val store = FakeStore(ByteArray(32) { it.toByte() }, available = 32)
        val c = cursor(store)
        val buf = ByteArray(16)
        assertEquals(16, c.read(buf, 0, 16))
        assertArrayEquals(ByteArray(16) { it.toByte() }, buf)
        assertEquals(16L, c.position)
        assertEquals(16L, c.bytesRemaining)
    }

    @Test
    fun `onPositionAdvanced reports the true cursor position`() {
        val store = FakeStore(ByteArray(32) { it.toByte() }, available = 32)
        val advancedPositions = mutableListOf<Long>()
        val c = DefaultSequentialReadCursor(
            session = session(5, 20),
            store = store,
            waitForBytes = { _, _ -> WaitOutcome.DATA_AVAILABLE },
            onPositionAdvanced = { advancedPositions.add(it) },
            keepBehindBytes = 0,
            chunkWaitTimeoutMs = 1_000
        )

        assertEquals(8, c.read(ByteArray(8), 0, 8))
        assertEquals(listOf(13L), advancedPositions)
    }

    @Test
    fun `EOF only at range end`() {
        val store = FakeStore(ByteArray(8) { it.toByte() }, available = 8)
        val c = cursor(store)
        val buf = ByteArray(16)
        assertEquals(8, c.read(buf, 0, 16))
        assertEquals(C.RESULT_END_OF_INPUT, c.read(buf, 0, 16))
    }

    @Test
    fun `respects output buffer offset`() {
        val store = FakeStore(ByteArray(8) { (it + 1).toByte() }, available = 8)
        val c = cursor(store)
        val buf = ByteArray(16) { 0x7F.toByte() }
        assertEquals(8, c.read(buf, 4, 8))
        for (i in 0 until 4) assertEquals(0x7F.toByte(), buf[i])
        for (i in 0 until 8) assertEquals((i + 1).toByte(), buf[4 + i])
        for (i in 12 until 16) assertEquals(0x7F.toByte(), buf[i])
    }

    @Test
    fun `wait ERROR surfaces as IOException`() {
        val store = FakeStore(ByteArray(8) { it.toByte() }, available = 0)
        val c = cursor(store, wait = { _, _ -> WaitOutcome.ERROR })
        try {
            c.read(ByteArray(8), 0, 8)
            fail("expected IOException from wait ERROR")
        } catch (_: IOException) {
            // expected
        }
    }

    @Test
    fun `wait TIMEOUT surfaces as ChunkWaitTimeoutException`() {
        val store = FakeStore(ByteArray(8) { it.toByte() }, available = 0)
        val c = cursor(store, wait = { _, _ -> WaitOutcome.TIMEOUT })
        try {
            c.read(ByteArray(8), 0, 8)
            fail("expected ChunkWaitTimeoutException from wait TIMEOUT")
        } catch (_: ChunkWaitTimeoutException) {
            // expected
        }
    }

    @Test
    fun `wait CLOSED surfaces as end of input`() {
        val store = FakeStore(ByteArray(8) { it.toByte() }, available = 0)
        val c = cursor(store, wait = { _, _ -> WaitOutcome.CLOSED })
        assertEquals(C.RESULT_END_OF_INPUT, c.read(ByteArray(8), 0, 8))
    }

    @Test
    fun `blocks via waitForBytes instead of returning zero`() {
        val store = FakeStore(ByteArray(8) { it.toByte() }, available = 0)
        var waits = 0
        val c = cursor(store, wait = { _, _ ->
            waits += 1
            store.available = 8
            WaitOutcome.DATA_AVAILABLE
        })
        assertEquals(8, c.read(ByteArray(8), 0, 8))
        assertTrue("cursor must have waited at least once", waits >= 1)
    }

    @Test
    fun `tiny reads are served from a staged window`() {
        val store = FakeStore(ByteArray(64 * 1024) { 7 }, available = 64L * 1024L)
        val c = cursor(store, length = 64L * 1024L)
        val one = ByteArray(1)

        repeat(512) {
            assertEquals(1, c.read(one, 0, 1))
            assertEquals(7.toByte(), one[0])
        }

        assertTrue("expected staged reads to reduce store calls, got ${store.readCallCount}", store.readCallCount < 64)
    }
}
