package com.nexio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.IOException
import java.net.SocketException
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ParallelRangeDataSourceTest {

    @Test(timeout = 5_000L)
    fun `parallel range datasource recovers from a single transient chunk reset`() {
        val fixture = RangeServerFixture(
            content = ByteArray(512 * 1024) { (it % 251).toByte() },
            chunkSize = 64 * 1024L,
            transientFailuresByChunkIndex = mutableMapOf(3L to 1)
        )

        fixture.use { server ->
            val dataSource = server.createDataSource()
            val bytes = readAll(dataSource, server.dataSpec())

            assertArrayEquals(server.content, bytes)
            assertEquals(2, server.requestsForChunk(3L))
        }
    }

    @Test(timeout = 5_000L)
    fun `parallel range datasource recovers from repeated transient resets on the same chunk`() {
        val fixture = RangeServerFixture(
            content = ByteArray(512 * 1024) { (it % 251).toByte() },
            chunkSize = 64 * 1024L,
            transientFailuresByChunkIndex = mutableMapOf(3L to 3)
        )

        fixture.use { server ->
            val dataSource = server.createDataSource()
            val bytes = readAll(dataSource, server.dataSpec())

            assertArrayEquals(server.content, bytes)
            assertTrue(server.requestsForChunk(3L) >= 4)
        }
    }

    @Test(timeout = 5_000L)
    fun `parallel range datasource reproduces repeated chunk resets locally`() {
        val fixture = RangeServerFixture(
            content = ByteArray(512 * 1024) { (it % 251).toByte() },
            chunkSize = 64 * 1024L,
            transientFailuresByChunkIndex = mutableMapOf(3L to 8)
        )

        fixture.use { server ->
            val dataSource = server.createDataSource()
            val error = try {
                readAll(dataSource, server.dataSpec())
                null
            } catch (error: IOException) {
                error
            }

            assertTrue(error is ParallelRangeDataSource.ChunkDownloadException)
            assertNotNull(error)
            assertTrue(server.requestsForChunk(3L) >= 2)
        }
    }


    @Test(timeout = 5_000L)
    fun `parallel range datasource resumes a failed chunk from the partial offset`() {
        val fixture = RangeServerFixture(
            content = ByteArray(512 * 1024) { (it % 251).toByte() },
            chunkSize = 64 * 1024L,
            transientFailuresByChunkIndex = mutableMapOf(3L to 1),
            transientFailureBytesByChunkIndex = mutableMapOf(3L to 48 * 1024)
        )

        fixture.use { server ->
            val dataSource = server.createDataSource()
            val bytes = readAll(dataSource, server.dataSpec())

            assertArrayEquals(server.content, bytes)
            val requestStarts = server.requestStartsForChunk(3L)
            assertEquals(2, requestStarts.size)
            assertEquals(3L * 64 * 1024L, requestStarts.first())
            assertTrue(requestStarts[1] > requestStarts.first())
            assertTrue(requestStarts[1] < ((3L + 1L) * 64 * 1024L))
        }
    }

    @Test(timeout = 5_000L)
    fun `parallel range datasource reports transport bytes for bootstrap reads`() {
        val content = ByteArray(512 * 1024) { (it % 251).toByte() }
        val fixture = RangeServerFixture(
            content = content,
            chunkSize = 64 * 1024L,
            transientFailuresByChunkIndex = mutableMapOf()
        )
        val transportBytesTotal = java.util.concurrent.atomic.AtomicLong(0L)

        fixture.use { server ->
            val dataSource = server.createDataSource(
                onTransportBytesDownloaded = { bytesRead, _ ->
                    transportBytesTotal.addAndGet(bytesRead)
                }
            )

            val bytes = readAll(dataSource, server.dataSpec())

            assertArrayEquals(content, bytes)
            assertEquals(
                "Bootstrap reads should contribute transport bytes",
                content.size.toLong(),
                transportBytesTotal.get()
            )
        }
    }

    private fun readAll(
        dataSource: ParallelRangeDataSource,
        dataSpec: DataSpec
    ): ByteArray {
        val sink = Buffer()
        val buffer = ByteArray(32 * 1024)
        dataSource.open(dataSpec)
        try {
            while (true) {
                val read = dataSource.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) break
                if (read > 0) {
                    sink.write(buffer, 0, read)
                }
            }
        } finally {
            dataSource.close()
        }
        return sink.readByteArray()
    }

    private class RangeServerFixture(
        val content: ByteArray,
        private val chunkSize: Long,
        private val transientFailuresByChunkIndex: MutableMap<Long, Int>,
        private val transientFailureBytesByChunkIndex: MutableMap<Long, Int> = mutableMapOf()
    ) : AutoCloseable {
        private val server = MockWebServer()
        private val chunkRequestCounts = ConcurrentHashMap<Long, Int>()
        private val chunkRequestStarts = ConcurrentHashMap<Long, MutableList<Long>>()

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val rangeHeader = request.getHeader("Range")
                    return if (rangeHeader == null) {
                        fullResponse()
                    } else {
                        rangedResponse(rangeHeader)
                    }
                }
            }
            server.start()
        }

        fun createDataSource(
            onTransportBytesDownloaded: (Long, Long) -> Unit = { _, _ -> }
        ): ParallelRangeDataSource {
            val upstreamFactory = OkHttpDataSource.Factory(
                OkHttpClient.Builder()
                    .connectTimeout(1, TimeUnit.SECONDS)
                    .readTimeout(1, TimeUnit.SECONDS)
                    .writeTimeout(1, TimeUnit.SECONDS)
                    .build()
            )
            return ParallelRangeDataSource(
                upstreamFactory = upstreamFactory,
                parallelConnections = 4,
                chunkSize = chunkSize,
                onTransportBytesDownloaded = onTransportBytesDownloaded
            )
        }

        fun dataSpec(): DataSpec {
            return DataSpec.Builder()
                .setUri(server.url("/media.bin").toString())
                .setPosition(0L)
                .setLength(C.LENGTH_UNSET.toLong())
                .build()
        }

        fun requestsForChunk(chunkIndex: Long): Int = chunkRequestCounts[chunkIndex] ?: 0

        fun requestStartsForChunk(chunkIndex: Long): List<Long> = chunkRequestStarts[chunkIndex]?.toList() ?: emptyList()

        override fun close() {
            server.shutdown()
        }

        private fun fullResponse(): MockResponse {
            return MockResponse()
                .setResponseCode(200)
                .setHeader("Accept-Ranges", "bytes")
                .setHeader("Content-Length", content.size)
                .setBody(Buffer().write(content))
        }

        private fun rangedResponse(rangeHeader: String): MockResponse {
            val match = Regex("""bytes=(\d+)-(\d+)?""").matchEntire(rangeHeader)
                ?: error("Unexpected Range header: $rangeHeader")
            val start = match.groupValues[1].toLong()
            val requestedEnd = match.groupValues[2].takeIf { it.isNotBlank() }?.toLong()
            val endExclusive = minOf((requestedEnd?.plus(1L)) ?: content.size.toLong(), content.size.toLong())
            val length = (endExclusive - start).toInt()
            val chunkIndex = start / chunkSize
            chunkRequestCounts.merge(chunkIndex, 1, Int::plus)
            chunkRequestStarts.compute(chunkIndex) { _, starts ->
                (starts ?: mutableListOf()).apply { add(start) }
            }

            val remainingFailures = transientFailuresByChunkIndex[chunkIndex] ?: 0
            if (remainingFailures > 0) {
                transientFailuresByChunkIndex[chunkIndex] = remainingFailures - 1
                val configuredPartialLength = transientFailureBytesByChunkIndex[chunkIndex]
                val partialLength = minOf(length, configuredPartialLength ?: (8 * 1024))
                val partialBuffer = Buffer().write(content, start.toInt(), partialLength)
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Accept-Ranges", "bytes")
                    .setHeader("Content-Range", "bytes $start-${endExclusive - 1}/${content.size}")
                    .setHeader("Content-Length", length)
                    .setBody(partialBuffer)
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            }

            return MockResponse()
                .setResponseCode(206)
                .setHeader("Accept-Ranges", "bytes")
                .setHeader("Content-Range", "bytes $start-${endExclusive - 1}/${content.size}")
                .setHeader("Content-Length", length)
                .setBody(Buffer().write(content, start.toInt(), length))
        }
    }
}
