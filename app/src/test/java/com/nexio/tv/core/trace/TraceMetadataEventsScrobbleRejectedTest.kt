package com.nexio.tv.core.trace

import com.nexio.tv.core.integration.RecordingTraceSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceMetadataEventsScrobbleRejectedTest {
    @Test
    fun `emitScrobbleRejected emits playback_scrobble_rejected envelope with payload`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })

        events.emitScrobbleRejected(
            envelopeProfileId = 1,
            activeProfileId = 2,
            operation = "scrobble.start",
            reason = "STALE_SESSION_WRITE_REJECTED"
        )

        assertEquals(1, sink.events.size)
        val envelope = sink.events.first()
        assertEquals("playback.scrobble_rejected", envelope.eventType)
        val payload = envelope.payload as Map<*, *>
        assertEquals(1, payload["envelopeProfileId"])
        assertEquals(2, payload["activeProfileId"])
        assertEquals("scrobble.start", payload["operation"])
        assertEquals("STALE_SESSION_WRITE_REJECTED", payload["reason"])
    }

    @Test
    fun `emitScrobbleRejected is a no-op when sessionId returns null`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { null })

        events.emitScrobbleRejected(
            envelopeProfileId = 1,
            activeProfileId = 2,
            operation = "scrobble.start",
            reason = "STALE_SESSION_WRITE_REJECTED"
        )

        assertTrue("must not emit when no active session", sink.events.isEmpty())
    }
}
