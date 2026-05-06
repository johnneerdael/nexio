package com.nexio.tv.core.trace

import org.junit.Assert.assertEquals
import org.junit.Test

class TraceMetadataEventsStreamClassificationTest {

    @Test
    fun `emitStreamRequestClassified emits request classification payload`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })

        events.emitStreamRequestClassified(
            contentId = "kitsu:7442:1:1",
            parentId = "kitsu:7442",
            contentIsAnime = true,
            evidence = "AnimeIdentityIndex"
        )

        assertEquals(1, sink.events.size)
        val envelope = sink.events.single()
        assertEquals("s1", envelope.traceSessionId)
        assertEquals(1L, envelope.sequence)
        assertEquals("stream.request_classified", envelope.eventType)
        val payload = envelope.payload as Map<*, *>
        assertEquals("kitsu:7442:1:1", payload["contentId"])
        assertEquals("kitsu:7442", payload["parentId"])
        assertEquals(true, payload["contentIsAnime"])
        assertEquals("AnimeIdentityIndex", payload["evidence"])
    }

    @Test
    fun `emitStreamAddonBucketed emits addon bucket payload`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })

        events.emitStreamAddonBucketed(
            addonIdHash = "abcdef123456",
            addonIsAnime = true,
            contentIsAnime = true,
            isAnimeBucket = true
        )

        assertEquals(1, sink.events.size)
        val envelope = sink.events.single()
        assertEquals("s1", envelope.traceSessionId)
        assertEquals(1L, envelope.sequence)
        assertEquals("stream.addon_bucketed", envelope.eventType)
        val payload = envelope.payload as Map<*, *>
        assertEquals("abcdef123456", payload["addonIdHash"])
        assertEquals(true, payload["addonIsAnime"])
        assertEquals(true, payload["contentIsAnime"])
        assertEquals(true, payload["isAnimeBucket"])
    }

    @Test
    fun `stream trace event sequence increments`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })

        events.emitStreamRequestClassified(
            contentId = "mal:21",
            parentId = "mal:21",
            contentIsAnime = true,
            evidence = "AnimeIdentityIndex"
        )
        events.emitStreamAddonBucketed(
            addonIdHash = "abcdef123456",
            addonIsAnime = true,
            contentIsAnime = true,
            isAnimeBucket = true
        )

        assertEquals(listOf(1L, 2L), sink.events.map { it.sequence })
    }

    private class RecordingTraceSink : RuntimeTraceSink {
        val events = mutableListOf<TraceEventEnvelope<*>>()

        override fun emit(event: TraceEventEnvelope<*>) {
            events += event
        }

        override fun eventsWritten(): Long = events.size.toLong()

        override fun eventsDropped(): Long = 0L
    }
}
