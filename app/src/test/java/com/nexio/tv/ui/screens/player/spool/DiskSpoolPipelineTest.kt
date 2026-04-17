package com.nexio.tv.ui.screens.player.spool

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiskSpoolPipelineTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `reads while writer fills spool and propagates metadata`() {
        val content = ByteArray(128 * 1024) { (it % 251).toByte() }
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val rangeHeader = request.getHeader("Range")
                return if (rangeHeader == null) {
                    MockResponse().setResponseCode(200)
                } else {
                    rangedResponse(content, rangeHeader)
                }
            }
        }
        server.start()

        val session = DiskSpoolSession(
            File(temp.root, "movie.spool"),
            capacityBytes = 256 * 1024L,
            waitTimeoutMs = 2_000L
        )
        val writer = DiskSpoolWriter(
            OkHttpClient.Builder()
                .readTimeout(1, TimeUnit.SECONDS)
                .build(),
            chunkBytes = 32 * 1024,
            ioBufferBytes = 8 * 1024
        )
        val uri = Uri.parse(server.url("/movie.bin").toString())
        val dataSource = DiskSpoolDataSource(
            session = session,
            uri = uri,
            contentLength = C.LENGTH_UNSET.toLong()
        )
        val failure = AtomicReference<Throwable?>(null)
        val writerThread = Thread {
            try {
                writer.downloadUntil(
                    uri.toString(),
                    session,
                    targetFrontierBytes = content.size.toLong()
                )
            } catch (throwable: Throwable) {
                failure.set(throwable)
            }
        }

        try {
            assertEquals(C.LENGTH_UNSET.toLong(), dataSource.open(DataSpec(uri)))

            writerThread.start()

            val actual = ByteArray(content.size)
            var totalRead = 0
            while (totalRead < actual.size) {
                val read = dataSource.read(actual, totalRead, actual.size - totalRead)
                if (read == C.RESULT_END_OF_INPUT) {
                    break
                }
                totalRead += read
            }

            writerThread.join(5_000L)

            assertEquals(null, failure.get())
            assertEquals(content.size, totalRead)
            assertArrayEquals(content, actual)
            assertEquals(content.size.toLong(), session.contentLengthBytes())
        } finally {
            dataSource.close()
            session.close()
            server.shutdown()
        }
    }

    @Test
    fun `single writer feeds datasource while playback reads from spool`() {
        val content = ByteArray(192 * 1024) { (it % 251).toByte() }
        val requestedRanges = CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                range?.let { requestedRanges += it }
                return when (range) {
                    "bytes=0-0" -> MockResponse()
                        .setResponseCode(206)
                        .setHeader("Accept-Ranges", "bytes")
                        .setHeader("Content-Range", "bytes 0-0/${content.size}")
                        .setHeader("Content-Length", 1)
                        .setBody(Buffer().writeByte(0x2A))
                    else -> rangedResponse(content, range ?: error("Missing range"))
                }
            }
        }
        server.start()
        val session = DiskSpoolSession(File(temp.root, "single-writer-pipeline.spool"), capacityBytes = 256 * 1024L)
        val uri = Uri.parse(server.url("/movie.bin").toString())
        val writerThread = Thread {
            DiskSpoolWriter(
                okHttpClient = OkHttpClient(),
                chunkBytes = 64 * 1024,
                ioBufferBytes = 8 * 1024,
                startupPriorityBytes = 64 * 1024L
            ).downloadUntil(uri.toString(), session, content.size.toLong())
        }
        val dataSource = DiskSpoolDataSource(session, uri)

        try {
            writerThread.start()
            dataSource.open(DataSpec(uri))
            val actual = ByteArray(content.size)
            var offset = 0
            while (offset < actual.size) {
                val read = dataSource.read(actual, offset, actual.size - offset)
                if (read == C.RESULT_END_OF_INPUT) break
                offset += read
            }
            assertEquals(content.size, offset)
            assertArrayEquals(content, actual)
            assertEquals(
                listOf("bytes=0-0", "bytes=0-65535", "bytes=65536-131071", "bytes=131072-196607"),
                requestedRanges.toList()
            )
        } finally {
            dataSource.close()
            session.close()
            writerThread.join(5_000L)
            server.shutdown()
        }
    }

    @Test
    fun `writer and datasource maintain bounded adaptive headroom as playback advances`() {
        val content = ByteArray(512 * 1024) { (it % 251).toByte() }
        val rangeRequests = AtomicInteger(0)
        val requestLatch = CountDownLatch(2)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (val range = request.getHeader("Range")) {
                    "bytes=0-0" -> MockResponse()
                        .setResponseCode(206)
                        .setHeader("Accept-Ranges", "bytes")
                        .setHeader("Content-Range", "bytes 0-0/${content.size}")
                        .setHeader("Content-Length", 1)
                        .setBody(Buffer().writeByte(0x2A))
                    else -> {
                        rangeRequests.incrementAndGet()
                        requestLatch.countDown()
                        rangedResponse(content, range ?: error("Missing range"))
                    }
                }
            }
        }
        server.start()

        val session = DiskSpoolSession(
            File(temp.root, "adaptive-pipeline.spool"),
            capacityBytes = 512 * 1024L,
            waitTimeoutMs = 2_000L
        )
        val uri = Uri.parse(server.url("/movie.bin").toString())
        val writerFailure = AtomicReference<Throwable?>(null)
        val writerThread = Thread {
            try {
                DiskSpoolWriter(
                    okHttpClient = OkHttpClient(),
                    chunkBytes = 64 * 1024,
                    ioBufferBytes = 8 * 1024,
                    startupPriorityBytes = 64 * 1024L,
                    adaptiveHeadroomBytes = 128 * 1024L,
                    idlePollMs = 10L
                ).downloadUntil(uri.toString(), session, content.size.toLong())
            } catch (throwable: Throwable) {
                writerFailure.set(throwable)
            }
        }
        val dataSource = DiskSpoolDataSource(session, uri)

        try {
            assertEquals(C.LENGTH_UNSET.toLong(), dataSource.open(DataSpec(uri)))

            writerThread.start()

            assertTrue(requestLatch.await(2, TimeUnit.SECONDS))
            assertTrue(session.awaitFrontierAtLeast(128 * 1024L, timeoutMs = 2_000L))
            assertEquals(2, rangeRequests.get())
            assertEquals(null, writerFailure.get())

            val actual = ByteArray(content.size)
            var offset = 0
            val firstRead = dataSource.read(actual, offset, 128 * 1024)
            assertEquals(128 * 1024, firstRead)
            offset += firstRead
            assertEquals(128 * 1024L, session.currentReadPositionBytes())

            assertTrue(session.awaitFrontierAtLeast(256 * 1024L, timeoutMs = 2_000L))

            while (offset < actual.size) {
                val read = dataSource.read(actual, offset, actual.size - offset)
                if (read == C.RESULT_END_OF_INPUT) break
                offset += read
            }

            writerThread.join(5_000L)

            assertEquals(content.size, offset)
            assertArrayEquals(content, actual)
            assertEquals(content.size.toLong(), session.contiguousFrontierBytes())
            assertEquals(content.size.toLong(), session.currentReadPositionBytes())
            assertEquals(null, writerFailure.get())
        } finally {
            dataSource.close()
            session.close()
            writerThread.join(5_000L)
            server.shutdown()
        }
    }

    private fun rangedResponse(content: ByteArray, rangeHeader: String): MockResponse {
        val match = Regex("""bytes=(\d+)-(\d+)""").matchEntire(rangeHeader)
            ?: error("Unexpected Range header: $rangeHeader")
        val start = match.groupValues[1].toInt()
        val requestedEnd = match.groupValues[2].toInt()
        val endExclusive = minOf(requestedEnd + 1, content.size)
        val length = endExclusive - start

        return MockResponse()
            .setResponseCode(206)
            .setHeader("Accept-Ranges", "bytes")
            .setHeader("Content-Range", "bytes $start-${endExclusive - 1}/${content.size}")
            .setHeader("Content-Length", length)
            .setBody(Buffer().write(content, start, length))
    }
}
