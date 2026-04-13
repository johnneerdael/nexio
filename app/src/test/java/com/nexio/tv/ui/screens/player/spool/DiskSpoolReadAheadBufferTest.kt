package com.nexio.tv.ui.screens.player.spool

import androidx.media3.common.C
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiskSpoolReadAheadBufferTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `background worker fills chunks from disk spool session`() {
        val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 1024L, waitTimeoutMs = 1_000L)
        session.writeRange(0L, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 8)
        val buffer = DiskSpoolReadAheadBuffer(
            session = session,
            capacityBytes = 8L,
            chunkBytes = 4,
            workerName = "test-disk-spool-read-ahead"
        )

        try {
            buffer.start(0L)
            assertTrue(buffer.awaitBufferedBytesForTesting(minBytes = 8L, timeoutMs = 1_000L))

            val first = ByteArray(4)
            assertEquals(4, buffer.read(position = 0L, target = first, offset = 0, length = first.size))
            val second = ByteArray(4)
            assertEquals(4, buffer.read(position = 4L, target = second, offset = 0, length = second.size))

            assertArrayEquals(byteArrayOf(1, 2, 3, 4), first)
            assertArrayEquals(byteArrayOf(5, 6, 7, 8), second)
        } finally {
            buffer.release()
            session.close()
        }
    }

    @Test
    fun `reset clears old chunks and restarts from requested position`() {
        val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 1024L, waitTimeoutMs = 1_000L)
        session.writeRange(0L, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 8)
        val buffer = DiskSpoolReadAheadBuffer(
            session = session,
            capacityBytes = 8L,
            chunkBytes = 4,
            workerName = "test-disk-spool-read-ahead-reset"
        )

        try {
            buffer.start(0L)
            assertTrue(buffer.awaitBufferedBytesForTesting(minBytes = 4L, timeoutMs = 1_000L))
            buffer.reset(4L)
            assertTrue(buffer.awaitBufferedBytesForTesting(minBytes = 4L, timeoutMs = 1_000L))

            val out = ByteArray(4)
            assertEquals(4, buffer.read(position = 4L, target = out, offset = 0, length = out.size))
            assertArrayEquals(byteArrayOf(5, 6, 7, 8), out)
        } finally {
            buffer.release()
            session.close()
        }
    }

    @Test
    fun `worker discards stale read after reset moves requested position`() {
        val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 1024L, waitTimeoutMs = 1_000L)
        val buffer = DiskSpoolReadAheadBuffer(
            session = session,
            capacityBytes = 8L,
            chunkBytes = 4,
            workerName = "test-disk-spool-read-ahead-stale"
        )

        try {
            buffer.start(0L)
            buffer.reset(4L)
            session.writeRange(0L, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 8)
            assertTrue(buffer.awaitBufferedBytesForTesting(minBytes = 4L, timeoutMs = 1_000L))

            val stale = ByteArray(4)
            assertEquals(0, buffer.read(position = 0L, target = stale, offset = 0, length = stale.size))
            val current = ByteArray(4)
            assertEquals(4, buffer.read(position = 4L, target = current, offset = 0, length = current.size))
            assertArrayEquals(byteArrayOf(5, 6, 7, 8), current)
        } finally {
            buffer.release()
            session.close()
        }
    }

    @Test
    fun `background worker does not request priority when bytes are not spooled yet`() {
        val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 1024L, waitTimeoutMs = 50L)
        val buffer = DiskSpoolReadAheadBuffer(
            session = session,
            capacityBytes = 8L,
            chunkBytes = 4,
            workerName = "test-disk-spool-read-ahead-no-priority"
        )

        try {
            buffer.start(0L)
            Thread.sleep(150L)

            assertEquals(C.TIME_UNSET.toLong(), session.consumePriorityPosition())
        } finally {
            buffer.release()
            session.close()
        }
    }

    @Test
    fun `release treats worker wait interruption as normal shutdown`() {
        val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 1024L, waitTimeoutMs = 50L)
        val uncaught = AtomicReference<Throwable?>()
        val buffer = DiskSpoolReadAheadBuffer(
            session = session,
            capacityBytes = 8L,
            chunkBytes = 4,
            workerName = "test-disk-spool-read-ahead-release",
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, throwable ->
                uncaught.set(throwable)
            }
        )

        try {
            buffer.start(0L)
            Thread.sleep(25L)
            buffer.release()
            Thread.sleep(100L)

            assertNull(uncaught.get())
        } finally {
            buffer.release()
            session.close()
        }
    }
}
