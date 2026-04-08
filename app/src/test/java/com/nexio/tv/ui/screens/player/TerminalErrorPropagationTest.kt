package com.nexio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the manager → `onTerminalError` → `lastDownloadError` → `readInternal` typed
 * throw path. Chunk 1 (past the 1 MiB bootstrap window) permanently fails, so
 * reading past the bootstrap must surface [ChunkDownloadException] — NOT a generic
 * [java.io.IOException]. Phase 4 regressed this by swallowing the typed error; this
 * test keeps that regression from coming back.
 */
@RunWith(RobolectricTestRunner::class)
class TerminalErrorPropagationTest {

    @Test(timeout = 15_000L)
    fun `terminal chunk error surfaces typed ChunkDownloadException`() {
        val contentSize = 8 * 1024 * 1024
        val content = ByteArray(contentSize) { (it % 251).toByte() }
        val chunkSize = 2L * 1024L * 1024L

        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val rangeHeader = request.getHeader("Range")
                    ?: return MockResponse()
                        .setResponseCode(200)
                        .setHeader("Accept-Ranges", "bytes")
                        .setHeader("Content-Length", content.size)
                        .setBody(Buffer().write(content))
                val match = Regex("""bytes=(\d+)-(\d+)?""").matchEntire(rangeHeader)
                    ?: return MockResponse().setResponseCode(400)
                val start = match.groupValues[1].toLong()
                val requestedEnd = match.groupValues[2].takeIf { it.isNotBlank() }?.toLong()
                val endExclusive = minOf(
                    (requestedEnd?.plus(1L)) ?: content.size.toLong(),
                    content.size.toLong()
                )
                val length = (endExclusive - start).toInt()
                val chunkIndex = start / chunkSize
                if (chunkIndex == 1L) {
                    val partial = minOf(length, 4 * 1024)
                    return MockResponse()
                        .setResponseCode(206)
                        .setHeader("Accept-Ranges", "bytes")
                        .setHeader("Content-Range", "bytes $start-${endExclusive - 1}/${content.size}")
                        .setHeader("Content-Length", length)
                        .setBody(Buffer().write(content, start.toInt(), partial))
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
        server.start()

        try {
            val factory = OkHttpDataSource.Factory(
                OkHttpClient.Builder()
                    .connectTimeout(1, TimeUnit.SECONDS)
                    .readTimeout(1, TimeUnit.SECONDS)
                    .writeTimeout(1, TimeUnit.SECONDS)
                    .build()
            )
            val ds = ParallelRangeDataSource(
                upstreamFactory = factory,
                parallelConnections = 2,
                chunkSize = chunkSize
            )
            ds.open(
                DataSpec.Builder()
                    .setUri(server.url("/media.bin").toString())
                    .setPosition(0L)
                    .setLength(C.LENGTH_UNSET.toLong())
                    .build()
            )
            val buf = ByteArray(32 * 1024)
            var thrown: Throwable? = null
            try {
                while (true) {
                    val read = ds.read(buf, 0, buf.size)
                    if (read == C.RESULT_END_OF_INPUT) {
                        fail("expected ChunkDownloadException before EOF")
                    }
                }
            } catch (t: Throwable) {
                thrown = t
            } finally {
                ds.close()
            }
            assertTrue(
                "expected ChunkDownloadException, got ${thrown.javaClass.name}: ${thrown.message}",
                thrown is ChunkDownloadException
            )
        } finally {
            server.shutdown()
        }
    }
}
