package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolverScheduleTraceTest {
    @Test
    fun `schedule emits resolver_schedule event with scheduled and skipped`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        val orchestrator = ResolverOrchestrator(traceEvents = events)

        orchestrator.schedule(MetadataDepth.DETAIL_CORE)

        val scheds = sink.events.filter { it.eventType == "metadata.resolver_schedule" }
        assertEquals(1, scheds.size)
        val payload = scheds.first().payload as Map<*, *>
        assertEquals("DETAIL_CORE", payload["depth"])
        @Suppress("UNCHECKED_CAST")
        val scheduled = payload["scheduled"] as List<String>
        assertTrue("ADDON_DISPLAY scheduled at DETAIL_CORE", scheduled.contains("ADDON_DISPLAY"))
        assertTrue("RATING scheduled at DETAIL_CORE", scheduled.contains("RATING"))
        assertTrue("ARTWORK scheduled at DETAIL_CORE", scheduled.contains("ARTWORK"))
        @Suppress("UNCHECKED_CAST")
        val skipped = payload["skipped"] as Map<String, String>
        assertTrue("REVIEWS skipped at DETAIL_CORE", skipped.containsKey("REVIEWS"))
    }
}
