package com.nexio.tv.ui.screens.player.spool

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.IOException
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
class DiskSpoolDataSourceTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `open publishes initial read position to session`() {
        val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 1024L, waitTimeoutMs = 1_000L)
        session.writeRange(0L, ByteArray(512) { 1 }, 512)
        val uri = Uri.parse("https://example.com/movie.mkv")

        val dataSource = DiskSpoolDataSource(session, uri)
        dataSource.open(DataSpec.Builder().setUri(uri).setPosition(256L).build())

        assertEquals(256L, session.currentReadPositionBytes())

        dataSource.close()
        session.close()
    }

    @Test
    fun `read publishes advanced read position to session`() {
        val session = DiskSpoolSession(File(temp.root, "movie.spool"), capacityBytes = 1024L, waitTimeoutMs = 1_000L)
        session.writeRange(0L, ByteArray(512) { 1 }, 512)
        val uri = Uri.parse("https://example.com/movie.mkv")
        val dataSource = DiskSpoolDataSource(session, uri)

        dataSource.open(DataSpec(uri))
        val buffer = ByteArray(128)
        assertEquals(128, dataSource.read(buffer, 0, 128))

        assertEquals(128L, session.currentReadPositionBytes())

        dataSource.close()
        session.close()
    }

    @Test
    fun `reads bytes without closing shared session`() {
        val session = DiskSpoolSession(
            File(temp.root, "movie.spool"),
            capacityBytes = 1_024L,
            waitTimeoutMs = 1_000L
        )
        val uri = Uri.parse("https://example.com/movie.bin")
        session.writeRange(0L, byteArrayOf(1, 2, 3, 4), 4)

        try {
            val dataSource = DiskSpoolDataSource(
                session = session,
                uri = uri,
                contentLength = 4L
            )

            assertEquals(4L, dataSource.open(DataSpec(uri)))

            val buffer = ByteArray(4)
            assertEquals(4, dataSource.read(buffer, 0, buffer.size))
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), buffer)

            dataSource.close()
            assertFalse(session.isClosed())
        } finally {
            session.close()
        }
    }

    @Test
    fun `seeked open returns remaining length minus position`() {
        val session = DiskSpoolSession(
            File(temp.root, "movie.spool"),
            capacityBytes = 1_024L,
            waitTimeoutMs = 1_000L
        )
        val uri = Uri.parse("https://example.com/movie.bin")

        try {
            val dataSource = DiskSpoolDataSource(
                session = session,
                uri = uri,
                contentLength = 100L
            )

            val dataSpec = DataSpec.Builder()
                .setUri(uri)
                .setPosition(40L)
                .build()

            assertEquals(60L, dataSource.open(dataSpec))
            dataSource.close()
        } finally {
            session.close()
        }
    }

    @Test
    fun `far random access read bypasses spool without rebasing primary window`() {
        val session = DiskSpoolSession(
            File(temp.root, "movie.spool"),
            capacityBytes = 1_024L,
            waitTimeoutMs = 1_000L
        )
        val uri = Uri.parse("https://example.com/movie.bin")
        val fallbackContent = ByteArray(4_096) { (it % 251).toByte() }
        val fallbackFactory = ByteArrayFallbackFactory(fallbackContent)
        session.writeRange(0L, byteArrayOf(1, 2, 3, 4), 4)

        try {
            val dataSource = DiskSpoolDataSource(
                session = session,
                uri = uri,
                contentLength = fallbackContent.size.toLong(),
                randomAccessFallbackFactory = fallbackFactory,
                randomAccessBypassDistanceBytes = 16L
            )
            val dataSpec = DataSpec.Builder()
                .setUri(uri)
                .setPosition(1_024L)
                .setLength(4L)
                .build()

            assertEquals(4L, dataSource.open(dataSpec))

            val buffer = ByteArray(4)
            assertEquals(4, dataSource.read(buffer, 0, buffer.size))
            assertArrayEquals(fallbackContent.copyOfRange(1_024, 1_028), buffer)
            assertEquals(0L, session.windowStartBytes())
            assertEquals(4L, session.contiguousFrontierBytes())
            assertEquals(listOf(1_024L), fallbackFactory.openedPositions)

            dataSource.close()
        } finally {
            session.close()
        }
    }

    @Test
    fun `read caps large remaining length by caller buffer length`() {
        val session = DiskSpoolSession(
            File(temp.root, "movie.spool"),
            capacityBytes = 1_024L,
            waitTimeoutMs = 1_000L
        )
        val uri = Uri.parse("https://example.com/movie.bin")
        session.writeRange(0L, byteArrayOf(1, 2, 3, 4), 4)

        try {
            val dataSource = DiskSpoolDataSource(
                session = session,
                uri = uri,
                contentLength = Int.MAX_VALUE.toLong() + 100L
            )

            assertEquals(Int.MAX_VALUE.toLong() + 100L, dataSource.open(DataSpec(uri)))

            val buffer = ByteArray(4)
            assertEquals(4, dataSource.read(buffer, 0, buffer.size))
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), buffer)

            dataSource.close()
        } finally {
            session.close()
        }
    }

    @Test
    fun `read waits for delayed writer instead of ending early`() {
        val session = DiskSpoolSession(
            File(temp.root, "movie.spool"),
            capacityBytes = 1_024L,
            waitTimeoutMs = 50L
        )
        val uri = Uri.parse("https://example.com/movie.bin")
        val dataSource = DiskSpoolDataSource(
            session = session,
            uri = uri,
            contentLength = C.LENGTH_UNSET.toLong()
        )
        val writerFailure = AtomicReference<Throwable?>(null)

        try {
            assertEquals(C.LENGTH_UNSET.toLong(), dataSource.open(DataSpec(uri)))

            val writerThread = Thread {
                try {
                    Thread.sleep(125L)
                    session.writeRange(0L, byteArrayOf(7, 8, 9, 10), 4)
                } catch (throwable: Throwable) {
                    writerFailure.set(throwable)
                }
            }
            writerThread.start()

            val buffer = ByteArray(4)
            val read = dataSource.read(buffer, 0, buffer.size)

            writerThread.join(1_000L)

            assertEquals(null, writerFailure.get())
            assertEquals(4, read)
            assertArrayEquals(byteArrayOf(7, 8, 9, 10), buffer)
        } finally {
            dataSource.close()
            session.close()
        }
    }

    @Test
    fun `open waits for configured startup prebuffer before playback reads`() {
        val session = DiskSpoolSession(
            File(temp.root, "movie.spool"),
            capacityBytes = 1_024L,
            waitTimeoutMs = 1_000L
        )
        val uri = Uri.parse("https://example.com/movie.bin")
        val dataSource = DiskSpoolDataSource(
            session = session,
            uri = uri,
            contentLength = 8L,
            startupPrebufferBytes = 4L,
            startupPrebufferWaitTimeoutMs = 1_000L
        )
        val writerFailure = AtomicReference<Throwable?>(null)

        try {
            val writerThread = Thread {
                try {
                    Thread.sleep(125L)
                    session.writeRange(0L, byteArrayOf(7, 8, 9, 10), 4)
                } catch (throwable: Throwable) {
                    writerFailure.set(throwable)
                }
            }
            writerThread.start()

            assertEquals(8L, dataSource.open(DataSpec(uri)))
            writerThread.join(1_000L)

            assertEquals(null, writerFailure.get())
            assertEquals(4L, session.contiguousFrontierBytes())
        } finally {
            dataSource.close()
            session.close()
        }
    }

    @Test
    fun `open clamps startup prebuffer to discovered content length`() {
        val session = DiskSpoolSession(
            File(temp.root, "movie.spool"),
            capacityBytes = 1_024L,
            waitTimeoutMs = 1_000L
        )
        val uri = Uri.parse("https://example.com/movie.bin")
        val dataSource = DiskSpoolDataSource(
            session = session,
            uri = uri,
            contentLength = C.LENGTH_UNSET.toLong(),
            startupPrebufferBytes = 10L,
            startupPrebufferWaitTimeoutMs = 1_000L
        )
        val writerFailure = AtomicReference<Throwable?>(null)

        try {
            val writerThread = Thread {
                try {
                    Thread.sleep(125L)
                    session.setSourceMetadata(contentLength = 4L, supportsRanges = true)
                    session.writeRange(0L, byteArrayOf(7, 8, 9, 10), 4)
                } catch (throwable: Throwable) {
                    writerFailure.set(throwable)
                }
            }
            writerThread.start()

            assertEquals(C.LENGTH_UNSET.toLong(), dataSource.open(DataSpec(uri)))
            writerThread.join(1_000L)

            assertEquals(null, writerFailure.get())
            assertEquals(4L, session.contiguousFrontierBytes())
        } finally {
            dataSource.close()
            session.close()
        }
    }

    @Test
    fun `open fails clearly when factory creates data source after session closed`() {
        val session = DiskSpoolSession(
            File(temp.root, "movie.spool"),
            capacityBytes = 1_024L,
            waitTimeoutMs = 1_000L
        )
        val uri = Uri.parse("https://example.com/movie.bin")
        val dataSourceFactory = DiskSpoolDataSource.Factory(
            session = session,
            uri = uri,
            contentLength = 4L
        )

        session.close()

        val failure = runCatching {
            dataSourceFactory.createDataSource().open(DataSpec(uri))
        }.exceptionOrNull()

        assertTrue(failure is IOException)
    }

    @Test
    fun `reopen emits transfer end before second start`() {
        val session = DiskSpoolSession(
            File(temp.root, "movie.spool"),
            capacityBytes = 1_024L,
            waitTimeoutMs = 1_000L
        )
        val uri = Uri.parse("https://example.com/movie.bin")
        val dataSource = DiskSpoolDataSource(
            session = session,
            uri = uri,
            contentLength = 100L
        )
        val events = mutableListOf<String>()

        dataSource.addTransferListener(
            object : TransferListener {
                override fun onTransferInitializing(
                    source: DataSource,
                    dataSpec: DataSpec,
                    isNetwork: Boolean
                ) {
                    events += "init:${dataSpec.position}"
                }

                override fun onTransferStart(
                    source: DataSource,
                    dataSpec: DataSpec,
                    isNetwork: Boolean
                ) {
                    events += "start:${dataSpec.position}"
                }

                override fun onBytesTransferred(
                    source: DataSource,
                    dataSpec: DataSpec,
                    isNetwork: Boolean,
                    bytesTransferred: Int
                ) = Unit

                override fun onTransferEnd(
                    source: DataSource,
                    dataSpec: DataSpec,
                    isNetwork: Boolean
                ) {
                    events += "end:${dataSpec.position}"
                }
            }
        )

        try {
            dataSource.open(
                DataSpec.Builder()
                    .setUri(uri)
                    .setPosition(0L)
                    .build()
            )
            dataSource.open(
                DataSpec.Builder()
                    .setUri(uri)
                    .setPosition(1L)
                    .build()
            )
            dataSource.close()

            assertEquals(
                listOf("init:0", "start:0", "end:0", "init:1", "start:1", "end:1"),
                events
            )
        } finally {
            session.close()
        }
    }

    private class ByteArrayFallbackFactory(
        private val content: ByteArray
    ) : DataSource.Factory {
        val openedPositions = mutableListOf<Long>()

        override fun createDataSource(): DataSource {
            return object : DataSource {
                private var position = 0
                private var remaining = 0

                override fun addTransferListener(transferListener: TransferListener) = Unit

                override fun open(dataSpec: DataSpec): Long {
                    openedPositions += dataSpec.position
                    position = dataSpec.position.toInt()
                    remaining = when {
                        dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length.toInt()
                        else -> content.size - position
                    }
                    return remaining.toLong()
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (remaining == 0) return C.RESULT_END_OF_INPUT
                    val bytesToRead = minOf(length, remaining, content.size - position)
                    if (bytesToRead <= 0) return C.RESULT_END_OF_INPUT
                    System.arraycopy(content, position, buffer, offset, bytesToRead)
                    position += bytesToRead
                    remaining -= bytesToRead
                    return bytesToRead
                }

                override fun getUri(): Uri? = null

                override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

                override fun close() = Unit
            }
        }
    }
}
