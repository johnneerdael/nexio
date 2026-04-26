package com.nexio.tv.data.integration.playback.transport

import java.io.IOException
import java.net.HttpURLConnection
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackProbeTransportTest {

    @Test
    fun `readRange returns requested bytes when server honors range`() {
        val server = MockWebServer()
        val content = ByteArray(131_072) { (it % 251).toByte() }
        server.dispatcher = RangeDispatcher(content)
        server.start()

        try {
            val transport = PlaybackProbeTransport()

            val bytes = transport.readRange(
                url = server.url("/movie.mkv").toString(),
                headers = emptyMap(),
                start = 65_536L,
                endInclusive = 131_071L
            )

            assertArrayEquals(content.copyOfRange(65_536, 131_072), bytes)
        } finally {
            server.shutdown()
        }
    }

    @Test(expected = IOException::class)
    fun `readRange throws when server ignores range request`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("ignored range response")
        )
        server.start()

        try {
            PlaybackProbeTransport().readRange(
                url = server.url("/movie.mkv").toString(),
                headers = emptyMap(),
                start = 65_536L,
                endInclusive = 131_071L
            )
        } finally {
            server.shutdown()
        }
    }

    @Test(expected = IOException::class)
    fun `readRange throws when server returns partial response without matching content-range`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_PARTIAL)
                .setBody("ignored content-range response")
        )
        server.start()

        try {
            PlaybackProbeTransport().readRange(
                url = server.url("/movie.mkv").toString(),
                headers = emptyMap(),
                start = 65_536L,
                endInclusive = 131_071L
            )
        } finally {
            server.shutdown()
        }
    }

    @Test(expected = IOException::class)
    fun `readRange throws when server returns ok with matching content-range`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_OK)
                .setHeader("Content-Range", "bytes 65536-131071/131072")
                .setBody("not a partial response")
        )
        server.start()

        try {
            PlaybackProbeTransport().readRange(
                url = server.url("/movie.mkv").toString(),
                headers = emptyMap(),
                start = 65_536L,
                endInclusive = 131_071L
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `rangeProbe succeeds for honored partial range response`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_PARTIAL)
                .setHeader("Content-Range", "bytes 0-1/13")
                .setBody("ok")
        )
        server.start()

        try {
            val result = PlaybackProbeTransport().rangeProbe(server.url("/movie.mkv").toString())
            assertEquals(PlaybackProbeResult.Success, result)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `rangeProbe fails when server ignores range request`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_OK)
                .setBody("ignored range response")
        )
        server.start()

        try {
            val result = PlaybackProbeTransport().rangeProbe(server.url("/movie.mkv").toString())
            assertEquals(PlaybackProbeResult.HttpError(HttpURLConnection.HTTP_OK), result)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `rangeProbe fails when ok response includes matching content-range`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_OK)
                .setHeader("Content-Range", "bytes 0-1/13")
                .setBody("ok")
        )
        server.start()

        try {
            val result = PlaybackProbeTransport().rangeProbe(server.url("/movie.mkv").toString())
            assertEquals(PlaybackProbeResult.HttpError(HttpURLConnection.HTTP_OK), result)
        } finally {
            server.shutdown()
        }
    }

    private class RangeDispatcher(
        private val content: ByteArray
    ) : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val rangeHeader = request.getHeader("Range") ?: return MockResponse().setResponseCode(400)
            val match = RANGE_REGEX.matchEntire(rangeHeader) ?: return MockResponse().setResponseCode(400)
            val start = match.groupValues[1].toInt()
            val endInclusive = match.groupValues[2].toInt()
            return MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes $start-$endInclusive/${content.size}")
                .setBody(
                    okio.Buffer().write(
                        content,
                        start,
                        endInclusive - start + 1
                    )
                )
        }

        companion object {
            private val RANGE_REGEX = Regex("""bytes=(\d+)-(\d+)""")
        }
    }
}
