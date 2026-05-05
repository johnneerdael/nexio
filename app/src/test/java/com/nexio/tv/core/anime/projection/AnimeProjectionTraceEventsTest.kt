package com.nexio.tv.core.anime.projection

import com.nexio.tv.core.trace.AnimeProjectionTraceEvents
import com.nexio.tv.core.trace.RuntimeTraceSink
import com.nexio.tv.core.trace.TraceEventEnvelope
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AnimeProjectionTraceEventsTest {

    private class RecordingTraceSink : RuntimeTraceSink {
        val events = mutableListOf<TraceEventEnvelope<*>>()
        override fun emit(event: TraceEventEnvelope<*>) { events += event }
        override fun eventsWritten(): Long = events.size.toLong()
        override fun eventsDropped(): Long = 0L
    }

    @Test
    fun `emitWorkResolved emits to sink when session is active`() {
        val rawSink = RecordingTraceSink()
        val metadataEvents = TraceMetadataEvents(rawSink, sessionId = { "trace-session-001" })
        val traceEvents = AnimeProjectionTraceEvents(metadataEvents)

        val identity = AnimeWorkIdentity(
            groupKey = AnimeWorkGroupKey("anime-work:tvdb:305074"),
            primaryKitsuId = "11469",
            memberKitsuIds = setOf("11469", "12268", "13881"),
            providerIds = ProviderIds(tvdb = "305074"),
            confidence = AnimeGroupingConfidence.HIGH,
            evidence = listOf("kitsu.tvdb=305074"),
        )

        traceEvents.emitWorkResolved(identity)

        assertEquals(1, rawSink.events.size)
        val envelope = rawSink.events.first()
        assertEquals("trace-session-001", envelope.traceSessionId)
        assertEquals("anime.work_resolved", envelope.eventType)
        val payload = envelope.payload as Map<*, *>
        assertEquals("anime-work:tvdb:305074", payload["groupKey"])
        assertEquals("11469", payload["sourceKitsuId"])
        assertEquals(3, payload["memberCount"])
        assertEquals("HIGH", payload["confidence"])
        @Suppress("UNCHECKED_CAST")
        val evidence = payload["evidence"] as List<String>
        assertNotNull(evidence)
        assertEquals(listOf("kitsu.tvdb=305074"), evidence)
    }

    @Test
    fun `emitWorkResolved still emits with logcat-only session id when no active session`() {
        val rawSink = RecordingTraceSink()
        val metadataEvents = TraceMetadataEvents(rawSink, sessionId = { null })
        val traceEvents = AnimeProjectionTraceEvents(metadataEvents)

        val identity = AnimeWorkIdentity(
            groupKey = AnimeWorkGroupKey("anime-work:kitsu:99"),
            primaryKitsuId = "99",
            memberKitsuIds = setOf("99"),
            providerIds = ProviderIds(kitsu = "99"),
            confidence = AnimeGroupingConfidence.LOW,
            evidence = emptyList(),
        )

        traceEvents.emitWorkResolved(identity)

        assertEquals(1, rawSink.events.size)
        assertEquals("logcat-only", rawSink.events.first().traceSessionId)
    }
}
