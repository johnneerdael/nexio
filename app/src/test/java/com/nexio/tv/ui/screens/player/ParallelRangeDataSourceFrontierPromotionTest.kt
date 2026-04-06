package com.nexio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ParallelRangeDataSourceFrontierPromotionTest {

    @Test(timeout = 15_000L)
    fun `frontier-blocking chunk is re-promoted and eventually succeeds`() {
        // Use 2MB chunks so bootstrap (1MB) only partially covers chunk 0.
        // Chunk 1 starts at 2MB, well past bootstrap, so it must be downloaded by the scheduler.
        // Chunk 1 fails 5 times (exceeds MAX_TRANSIENT=4), then frontier promotion
        // re-queues it with fresh retry budget and it succeeds on the 6th attempt.
        val contentSize = 8 * 1024 * 1024 // 8MB
        val fixture = FrontierPromotionFixture(
            content = ByteArray(contentSize) { (it % 251).toByte() },
            chunkSize = 2L * 1024L * 1024L,
            terminalThenSuccessChunkIndex = 1L,
            failuresBeforeSuccess = 5
        )

        fixture.use { server ->
            val dataSource = server.createDataSource()
            val bytes = readAll(dataSource, server.dataSpec())

            assertArrayEquals(server.content, bytes)
            assertTrue(
                "Chunk 1 should have been re-promoted and retried beyond normal attempts",
                server.requestsForChunk(1L) > 4
            )
        }
    }

    @Test(timeout = 15_000L)
    fun `frontier-blocking chunk exhausts promotions and propagates error`() {
        // Chunk 1 fails permanently. After MAX_FRONTIER_PROMOTIONS re-queues
        // (each with fresh retry budget), the error propagates to the reader.
        val contentSize = 8 * 1024 * 1024
        val fixture = FrontierPromotionFixture(
            content = ByteArray(contentSize) { (it % 251).toByte() },
            chunkSize = 2L * 1024L * 1024L,
            permanentFailureChunkIndex = 1L
        )

        fixture.use { server ->
            val dataSource = server.createDataSource()
            val error = try {
                readAll(dataSource, server.dataSpec())
                null
            } catch (error: IOException) {
                error
            }

            assertNotNull("Should have propagated error after exhausting promotions", error)
            assertTrue(error is ParallelRangeDataSource.ChunkDownloadException)
            assertTrue(
                "Chunk 1 should have been requested across multiple promotion cycles",
                server.requestsForChunk(1L) > 4
            )
        }
    }

    @Test(timeout = 15_000L)
    fun `non-frontier chunk failure is not re-promoted when frontier blocked earlier`() {
        // Chunk 1 fails permanently, blocking the frontier at ~2MB (after 1MB bootstrap).
        // Chunk 2 (at 4-6MB) is scheduled as a prefetch ahead of the frontier.
        // When chunk 2 fails, the frontier is still stuck at ~2MB (inside chunk 1),
        // so chunk 2 is NOT frontier-blocking and should NOT be re-promoted.
        val contentSize = 8 * 1024 * 1024
        val fixture = FrontierPromotionFixture(
            content = ByteArray(contentSize) { (it % 251).toByte() },
            chunkSize = 2L * 1024L * 1024L,
            permanentFailureChunkIndex = 1L,
            secondaryPermanentFailureChunkIndex = 2L
        )

        fixture.use { server ->
            val dataSource = server.createDataSource()
            val error = try {
                readAll(dataSource, server.dataSpec())
                null
            } catch (error: IOException) {
                error
            }

            assertNotNull("Should have propagated error", error)
            // Chunk 2 should only get normal retries — frontier is stuck at chunk 1,
            // so chunk 2 is not frontier-blocking and should not be promoted.
            assertTrue(
                "Non-frontier chunk 2 should only get normal retries",
                server.requestsForChunk(2L) <= 6
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

    private class FrontierPromotionFixture(
        val content: ByteArray,
        private val chunkSize: Long,
        private val terminalThenSuccessChunkIndex: Long = -1L,
        private val failuresBeforeSuccess: Int = 0,
        private val permanentFailureChunkIndex: Long = -1L,
        private val secondaryPermanentFailureChunkIndex: Long = -1L
    ) : AutoCloseable {
        private val server = MockWebServer()
        private val chunkRequestCounts = ConcurrentHashMap<Long, Int>()
        private val failureCounters = ConcurrentHashMap<Long, Int>()

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

        fun createDataSource(): ParallelRangeDataSource {
            val upstreamFactory = OkHttpDataSource.Factory(
                OkHttpClient.Builder()
                    .connectTimeout(1, TimeUnit.SECONDS)
                    .readTimeout(1, TimeUnit.SECONDS)
                    .writeTimeout(1, TimeUnit.SECONDS)
                    .build()
            )
            return ParallelRangeDataSource(
                upstreamFactory = upstreamFactory,
                parallelConnections = 2,
                chunkSize = chunkSize
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

        override fun close() {
            server.shutdown()
        }

        private fun fullResponse(): MockResponse {
            return MockResponse()
                .setResponseCode(200)
                .setHeader("Accept-Ranges", "bytes")
                .setHeader("Connection", "keep-alive")
                .setHeader("Content-Length", content.size)
                .setBody(Buffer().write(content))
        }

        private fun rangedResponse(rangeHeader: String): MockResponse {
            val match = Regex("""bytes=(\d+)-(\d+)?""").matchEntire(rangeHeader)
                ?: error("Unexpected Range header: $rangeHeader")
            val start = match.groupValues[1].toLong()
            val requestedEnd = match.groupValues[2].takeIf { it.isNotBlank() }?.toLong()
            val endExclusive = minOf(
                (requestedEnd?.plus(1L)) ?: content.size.toLong(),
                content.size.toLong()
            )
            val length = (endExclusive - start).toInt()
            val chunkIndex = start / chunkSize
            chunkRequestCounts.merge(chunkIndex, 1, Int::plus)

            // Permanent failure: always disconnect after partial data
            if (chunkIndex == permanentFailureChunkIndex || chunkIndex == secondaryPermanentFailureChunkIndex) {
                val partialLength = minOf(length, 4 * 1024)
                val partialBuffer = Buffer().write(content, start.toInt(), partialLength)
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Accept-Ranges", "bytes")
                    .setHeader("Connection", "keep-alive")
                    .setHeader("Content-Range", "bytes $start-${endExclusive - 1}/${content.size}")
                    .setHeader("Content-Length", length)
                    .setBody(partialBuffer)
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            }

            // Terminal-then-success: fail N times, then succeed
            if (chunkIndex == terminalThenSuccessChunkIndex) {
                val failCount = failureCounters.merge(chunkIndex, 1, Int::plus) ?: 1
                if (failCount <= failuresBeforeSuccess) {
                    val partialLength = minOf(length, 4 * 1024)
                    val partialBuffer = Buffer().write(content, start.toInt(), partialLength)
                    return MockResponse()
                        .setResponseCode(206)
                        .setHeader("Accept-Ranges", "bytes")
                        .setHeader("Connection", "keep-alive")
                        .setHeader("Content-Range", "bytes $start-${endExclusive - 1}/${content.size}")
                        .setHeader("Content-Length", length)
                        .setBody(partialBuffer)
                        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                }
            }

            return MockResponse()
                .setResponseCode(206)
                .setHeader("Accept-Ranges", "bytes")
                .setHeader("Connection", "keep-alive")
                .setHeader("Content-Range", "bytes $start-${endExclusive - 1}/${content.size}")
                .setHeader("Content-Length", length)
                .setBody(Buffer().write(content, start.toInt(), length))
        }
    }
}
