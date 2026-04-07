package com.nexio.tv.ui.screens.player

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer

/**
 * A shared MockWebServer wrapper that serves a fixed byte payload with proper
 * HTTP range-request support (206 Partial Content, Accept-Ranges, Content-Range).
 *
 * Used by both ParallelRangeDataSourceParityTest and ParallelRangeDataSourceContractTest
 * to avoid duplicating the dispatcher logic.
 */
internal object MockRangeServer {

    /**
     * Start a MockWebServer that serves [content] with range-request support.
     * The returned server is already started; caller is responsible for shutting it down.
     */
    fun start(content: ByteArray): MockWebServer {
        val server = MockWebServer()
        server.dispatcher = buildDispatcher(content)
        server.start()
        return server
    }

    private fun buildDispatcher(content: ByteArray): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            // 404 path for contract test "not found" resource
            if (request.path?.startsWith("/notfound") == true) {
                return MockResponse().setResponseCode(404)
            }
            // 302 redirect path: hands the client off to /media.bin
            if (request.path?.startsWith("/redirect") == true) {
                return MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "/media.bin")
            }
            val rangeHeader = request.getHeader("Range")
            return if (rangeHeader == null) {
                fullResponse(content)
            } else {
                rangedResponse(content, rangeHeader)
            }
        }
    }

    private fun fullResponse(content: ByteArray): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Accept-Ranges", "bytes")
            .setHeader("Content-Length", content.size)
            .setBody(Buffer().write(content))

    private fun rangedResponse(content: ByteArray, rangeHeader: String): MockResponse {
        // Supports `bytes=N-`, `bytes=N-M`, and the suffix form `bytes=-N` (last N bytes).
        val full = Regex("""bytes=(\d+)-(\d+)?""").matchEntire(rangeHeader)
        val suffix = Regex("""bytes=-(\d+)""").matchEntire(rangeHeader)
        val start: Long
        val endExclusive: Long
        when {
            full != null -> {
                start = full.groupValues[1].toLong()
                val requestedEnd = full.groupValues[2].takeIf { it.isNotBlank() }?.toLong()
                endExclusive = minOf(
                    (requestedEnd?.plus(1L)) ?: content.size.toLong(),
                    content.size.toLong()
                )
            }
            suffix != null -> {
                val n = suffix.groupValues[1].toLong()
                start = maxOf(0L, content.size.toLong() - n)
                endExclusive = content.size.toLong()
            }
            else -> return MockResponse().setResponseCode(400)
        }
        val length = (endExclusive - start).toInt()
        if (length <= 0 || start >= content.size) {
            // RFC 7233 §4.4: 416 responses MUST include `Content-Range: bytes */<total>`.
            return MockResponse()
                .setResponseCode(416)
                .setHeader("Content-Range", "bytes */${content.size}")
        }
        return MockResponse()
            .setResponseCode(206)
            .setHeader("Accept-Ranges", "bytes")
            .setHeader("Content-Range", "bytes $start-${endExclusive - 1}/${content.size}")
            .setHeader("Content-Length", length)
            .setBody(Buffer().write(content, start.toInt(), length))
    }
}
