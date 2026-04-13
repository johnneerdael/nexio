package com.nexio.tv.ui.screens.player.spool

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.File
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
}
