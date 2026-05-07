package com.nexio.tv.core.image

import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.SensitiveArtworkUrl
import com.nexio.tv.core.trace.RuntimeTraceSink
import com.nexio.tv.core.trace.TraceEventEnvelope
import com.nexio.tv.data.integration.posters.transport.PosterTransport
import com.nexio.tv.data.integration.posters.transport.PosterTransportResult
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class LegacyRemoteArtworkFetcherTest {
    @Test
    fun `success emits start and success without raw url or model key`() = runTest {
        val traceSink = RecordingTraceSink()
        val model = model()
        val transport = mockk<PosterTransport>()
        every { transport.execute(RAW_URL) } returns PosterTransportResult(
            statusCode = 200,
            isSuccessful = true,
            body = "image-bytes".toByteArray()
        )

        val result = LegacyRemoteArtworkFetcher(model, transport, traceSink).fetch()

        assertNotNull(result)
        assertEquals(
            listOf(
                "legacy_remote_artwork.fetch_start",
                "legacy_remote_artwork.fetch_success"
            ),
            traceSink.events.map { it.eventType }
        )
        val successPayload = traceSink.events.last().payload as Map<*, *>
        assertEquals("success", successPayload["reason"])
        assertEquals(200, successPayload["statusCode"])
        assertEquals("image-bytes".toByteArray().size, successPayload["byteCount"])
        assertPayloadsDoNotLeakSensitiveValues(traceSink)
    }

    @Test
    fun `http failure emits start and failed`() = runTest {
        val traceSink = RecordingTraceSink()
        val model = model()
        val transport = mockk<PosterTransport>()
        every { transport.execute(RAW_URL) } returns PosterTransportResult(
            statusCode = 404,
            isSuccessful = false,
            body = null
        )

        val result = LegacyRemoteArtworkFetcher(model, transport, traceSink).fetch()

        assertNull(result)
        assertEquals(
            listOf(
                "legacy_remote_artwork.fetch_start",
                "legacy_remote_artwork.fetch_failed"
            ),
            traceSink.events.map { it.eventType }
        )
        val failurePayload = traceSink.events.last().payload as Map<*, *>
        assertEquals("http_failure", failurePayload["reason"])
        assertEquals(404, failurePayload["statusCode"])
        assertPayloadsDoNotLeakSensitiveValues(traceSink)
    }

    @Test
    fun `null successful body emits start and failed`() = runTest {
        val traceSink = RecordingTraceSink()
        val model = model()
        val transport = mockk<PosterTransport>()
        every { transport.execute(RAW_URL) } returns PosterTransportResult(
            statusCode = 200,
            isSuccessful = true,
            body = null
        )

        val result = LegacyRemoteArtworkFetcher(model, transport, traceSink).fetch()

        assertNull(result)
        assertEquals(
            listOf(
                "legacy_remote_artwork.fetch_start",
                "legacy_remote_artwork.fetch_failed"
            ),
            traceSink.events.map { it.eventType }
        )
        val failurePayload = traceSink.events.last().payload as Map<*, *>
        assertEquals("null_body", failurePayload["reason"])
        assertEquals(200, failurePayload["statusCode"])
        assertPayloadsDoNotLeakSensitiveValues(traceSink)
    }

    @Test
    fun `transport exception emits start and failed without exception message`() = runTest {
        val traceSink = RecordingTraceSink()
        val model = model()
        val transport = mockk<PosterTransport>()
        every { transport.execute(RAW_URL) } throws IOException("failed to fetch $RAW_URL")

        val result = LegacyRemoteArtworkFetcher(model, transport, traceSink).fetch()

        assertNull(result)
        assertEquals(
            listOf(
                "legacy_remote_artwork.fetch_start",
                "legacy_remote_artwork.fetch_failed"
            ),
            traceSink.events.map { it.eventType }
        )
        val failurePayload = traceSink.events.last().payload as Map<*, *>
        assertEquals("transport_exception", failurePayload["reason"])
        assertEquals(IOException::class.java.name, failurePayload["errorClass"])
        assertFalse(failurePayload.containsKey("message"))
        assertPayloadsDoNotLeakSensitiveValues(traceSink)
    }

    @Test
    fun `cancellation still throws`() = runTest {
        val traceSink = RecordingTraceSink()
        val model = model()
        val transport = mockk<PosterTransport>()
        every { transport.execute(RAW_URL) } throws CancellationException("cancelled $RAW_URL")

        try {
            LegacyRemoteArtworkFetcher(model, transport, traceSink).fetch()
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            // Expected.
        }
    }

    private fun model(): LegacyRemoteArtworkModel =
        LegacyRemoteArtworkModel(
            key = "legacy-artwork:poster:movie:poster:$SECRET",
            imageType = ArtworkType.POSTER,
            url = SensitiveArtworkUrl.of(RAW_URL)
        )

    private fun assertPayloadsDoNotLeakSensitiveValues(traceSink: RecordingTraceSink) {
        traceSink.events.forEach { event ->
            val payloadText = event.payload.toString()
            assertFalse(payloadText, payloadText.contains(RAW_URL))
            assertFalse(payloadText, payloadText.contains(SECRET))
        }
    }

    private class RecordingTraceSink : RuntimeTraceSink {
        val events = mutableListOf<TraceEventEnvelope<*>>()

        override fun activeTraceSessionId(): String = "legacy-trace-test"

        override fun emit(event: TraceEventEnvelope<*>) {
            events += event
        }

        override fun eventsWritten(): Long = events.size.toLong()

        override fun eventsDropped(): Long = 0L
    }

    private companion object {
        const val SECRET = "secret-token"
        const val RAW_URL = "https://image.tmdb.org/t/p/w500/poster.jpg?token=$SECRET"
    }
}
