package com.nexio.tv.ui.screens.player.spool

import androidx.media3.common.C
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiskSpoolSessionTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `constructor creates empty spool file without preallocating capacity`() {
        val spoolFile = File(temp.root, "movie.spool")
        val session = DiskSpoolSession(spoolFile, capacityBytes = 1024L, waitTimeoutMs = 1_000L)

        assertEquals(0L, spoolFile.length())

        session.close()
    }

    @Test
    fun `spool file grows only as bytes are written`() {
        val spoolFile = File(temp.root, "movie.spool")
        val session = DiskSpoolSession(spoolFile, capacityBytes = 1024L, waitTimeoutMs = 1_000L)

        session.writeRange(start = 0L, bytes = byteArrayOf(1, 2, 3, 4), length = 4)

        assertEquals(4L, spoolFile.length())

        session.close()
    }

    @Test
    fun `spool file does not grow beyond ring capacity`() {
        val spoolFile = File(temp.root, "movie.spool")
        val session = DiskSpoolSession(spoolFile, capacityBytes = 16L, waitTimeoutMs = 1_000L)

        session.writeRange(start = 0L, bytes = ByteArray(64) { it.toByte() }, length = 64)

        assertEquals(16L, spoolFile.length())

        session.close()
    }

    @Test
    fun `write read advances frontier`() {
        val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 1024L, waitTimeoutMs = 1_000L)

        session.writeRange(start = 0L, bytes = byteArrayOf(1, 2, 3, 4), length = 4)

        val buffer = ByteArray(4)
        val read = session.read(position = 0L, buffer = buffer, offset = 0, length = 4)

        assertEquals(4, read)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), buffer)
        assertEquals(4L, session.contiguousFrontierBytes())

        session.close()
    }

    @Test
    fun `constructor deletes spool file when reader open fails after writer open`() {
        val realSpoolFile = File(temp.root, "movie.spool")
        val readerFailurePath = temp.newFolder("reader-failure")
        val spoolFile = ReaderOpenFailingFile(realSpoolFile, readerFailurePath)

        val failure = runCatching {
            DiskSpoolSession(spoolFile, capacityBytes = 1024L, waitTimeoutMs = 1_000L)
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertFalse(realSpoolFile.exists())
    }

    @Test
    fun `overlapping writes are ignored in sequential spool mode`() {
        val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 1024L, waitTimeoutMs = 1_000L)

        session.writeRange(start = 0L, bytes = ByteArray(100) { 1 }, length = 100)
        session.writeRange(start = 50L, bytes = ByteArray(150) { 2 }, length = 150)

        assertEquals(100L, session.contiguousFrontierBytes())

        session.close()
    }

    @Test
    fun `sequential writes advance frontier without range bookkeeping`() {
        val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 1024L, waitTimeoutMs = 1_000L)

        session.writeRange(start = 0L, bytes = ByteArray(100) { 1 }, length = 100)
        session.writeRange(start = 100L, bytes = ByteArray(150) { 2 }, length = 150)

        assertEquals(250L, session.contiguousFrontierBytes())

        session.close()
    }

    @Test
    fun `out of order future writes are ignored in sequential spool mode`() {
        val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 1024L, waitTimeoutMs = 1_000L)

        session.writeRange(start = 128L, bytes = ByteArray(64) { 9 }, length = 64)

        assertEquals(0L, session.contiguousFrontierBytes())

        session.writeRange(start = 0L, bytes = ByteArray(128) { 1 }, length = 128)

        assertEquals(128L, session.contiguousFrontierBytes())

        session.close()
    }

    @Test
    fun `wrap boundary aligned preserves second window`() {
        val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 16L, waitTimeoutMs = 1_000L)

        session.writeRange(start = 0L, bytes = ByteArray(16) { it.toByte() }, length = 16)
        session.writeRange(start = 16L, bytes = byteArrayOf(16, 17, 18, 19, 20, 21, 22, 23), length = 8)

        val buffer = ByteArray(8)
        val read = session.read(position = 16L, buffer = buffer, offset = 0, length = 8)

        assertEquals(8, read)
        assertArrayEquals(byteArrayOf(16, 17, 18, 19, 20, 21, 22, 23), buffer)
        assertEquals(8L, session.windowStartBytes())

        session.close()
    }

    @Test
    fun `wrap boundary straddle splits across ring`() {
        val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 16L, waitTimeoutMs = 1_000L)

        session.writeRange(start = 0L, bytes = ByteArray(14) { it.toByte() }, length = 14)
        session.writeRange(start = 14L, bytes = byteArrayOf(90, 91, 92, 93), length = 4)

        val buffer = ByteArray(4)
        val read = session.read(position = 14L, buffer = buffer, offset = 0, length = 4)

        assertEquals(4, read)
        assertArrayEquals(byteArrayOf(90, 91, 92, 93), buffer)

        session.close()
    }

    @Test
    fun `stale write does not overwrite live ring bytes`() {
        val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 16L, waitTimeoutMs = 1_000L)

        session.writeRange(start = 0L, bytes = ByteArray(16) { it.toByte() }, length = 16)
        session.writeRange(start = 16L, bytes = ByteArray(16) { (100 + it).toByte() }, length = 16)

        session.writeRange(start = 0L, bytes = byteArrayOf(9, 9, 9, 9), length = 4)

        val buffer = ByteArray(4)
        val read = session.read(position = 16L, buffer = buffer, offset = 0, length = 4)

        assertEquals(4, read)
        assertArrayEquals(byteArrayOf(100, 101, 102, 103), buffer)

        session.close()
    }

    @Test
    fun `metadata and seek priority rebases window`() {
        val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 1024L, waitTimeoutMs = 1_000L)

        assertEquals(C.LENGTH_UNSET.toLong(), session.contentLengthBytes())

        session.setSourceMetadata(4096L, true)

        assertEquals(4096L, session.contentLengthBytes())
        assertTrue(session.supportsRanges())

        assertEquals(-1, session.read(position = 2_048L, buffer = ByteArray(1), offset = 0, length = 1))
        assertEquals(2_048L, session.consumePriorityPosition())

        session.rebaseTo(2_048L)

        assertEquals(2_048L, session.windowStartBytes())
        assertEquals(2_048L, session.contiguousFrontierBytes())

        session.close()
    }

    @Test
    fun `read position only advances forward`() {
        val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 1024L, waitTimeoutMs = 1_000L)

        session.updateReadPosition(256L)
        session.updateReadPosition(128L)

        assertEquals(256L, session.currentReadPositionBytes())

        session.close()
    }

    @Test
    fun `adaptive target uses startup target until read headroom catches up`() {
        val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 1024L, waitTimeoutMs = 1_000L)
        session.setSourceMetadata(contentLength = 10_000L, supportsRanges = true)

        session.updateReadPosition(100L)

        assertEquals(
            2_000L,
            session.adaptiveTargetFrontierBytes(
                maxFrontierBytes = 8_000L,
                startupPrebufferBytes = 2_000L,
                headroomBytes = 500L
            )
        )

        session.close()
    }

    @Test
    fun `adaptive target follows read position plus headroom after startup target`() {
        val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 1024L, waitTimeoutMs = 1_000L)
        session.setSourceMetadata(contentLength = 10_000L, supportsRanges = true)

        session.updateReadPosition(3_000L)

        assertEquals(
            3_500L,
            session.adaptiveTargetFrontierBytes(
                maxFrontierBytes = 8_000L,
                startupPrebufferBytes = 2_000L,
                headroomBytes = 500L
            )
        )

        session.close()
    }

    @Test
    fun `adaptive target never exceeds content length or max frontier`() {
        val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 1024L, waitTimeoutMs = 1_000L)
        session.setSourceMetadata(contentLength = 4_000L, supportsRanges = true)

        session.updateReadPosition(3_800L)

        assertEquals(
            4_000L,
            session.adaptiveTargetFrontierBytes(
                maxFrontierBytes = 8_000L,
                startupPrebufferBytes = 2_000L,
                headroomBytes = 1_000L
            )
        )

        assertEquals(
            3_900L,
            session.adaptiveTargetFrontierBytes(
                maxFrontierBytes = 3_900L,
                startupPrebufferBytes = 2_000L,
                headroomBytes = 1_000L
            )
        )

        session.close()
    }

    @Test
    fun `close unblocks waiting reader`() {
        val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 16L, waitTimeoutMs = 5_000L)
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)

        try {
            val future = executor.submit(Callable {
                started.countDown()
                session.read(position = 0L, buffer = ByteArray(1), offset = 0, length = 1)
            })

            assertTrue(started.await(1, TimeUnit.SECONDS))
            session.close()

            assertEquals(-1, future.get(1, TimeUnit.SECONDS).toInt())
        } finally {
            executor.shutdownNow()
            session.close()
        }
    }

    @Test
    fun `close does not race with active writer`() {
        val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 16L, waitTimeoutMs = 1_000L)
        val started = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val writerThread = Thread {
            try {
                started.countDown()
                session.writeRange(start = 0L, bytes = ByteArray(1 shl 20) { 1 }, length = 1 shl 20)
            } catch (throwable: Throwable) {
                failure.set(throwable)
            }
        }

        writerThread.start()

        try {
            assertTrue(started.await(1, TimeUnit.SECONDS))
            Thread.sleep(25L)
            session.close()
            writerThread.join(1_000L)

            assertFalse(writerThread.isAlive)
            assertEquals(null, failure.get())
            assertTrue(session.isClosed())
        } finally {
            writerThread.join(1_000L)
            session.close()
        }
    }

    private class ReaderOpenFailingFile(
        private val delegate: File,
        private val readerFailurePath: File
    ) : File(delegate.path) {
        private val pathRequestCount = AtomicInteger(0)

        override fun getPath(): String {
            return if (pathRequestCount.incrementAndGet() == 1) {
                delegate.path
            } else {
                readerFailurePath.path
            }
        }

        override fun exists(): Boolean = delegate.exists()

        override fun createNewFile(): Boolean = delegate.createNewFile()

        override fun delete(): Boolean = delegate.delete()

        override fun getAbsolutePath(): String = delegate.absolutePath
    }
}
