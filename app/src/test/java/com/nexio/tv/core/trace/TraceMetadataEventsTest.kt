package com.nexio.tv.core.trace

import com.nexio.tv.core.integration.RecordingTraceSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceMetadataEventsTest {
    @Test
    fun `emitFirstPaint emits metadata_first_paint envelope with required fields`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })

        events.emitFirstPaint(
            contentId = "tt12343534",
            itemType = "series",
            surface = SourceSurface.HOME,
            source = "ADDON_META_PREVIEW",
            routerExecuted = false,
            networkExecuted = false,
            fieldsUsed = listOf("title", "poster", "description"),
            profileHash = "ph_abc"
        )

        assertEquals(1, sink.events.size)
        val envelope = sink.events.first()
        assertEquals("metadata.first_paint", envelope.eventType)
        val payload = envelope.payload as Map<*, *>
        assertEquals("tt12343534", payload["contentId"])
        assertEquals("series", payload["itemType"])
        assertEquals("HOME", payload["surface"])
        assertEquals("ADDON_META_PREVIEW", payload["source"])
        assertEquals(false, payload["routerExecuted"])
        assertEquals(false, payload["networkExecuted"])
        assertEquals("ph_abc", payload["profileHash"])
        @Suppress("UNCHECKED_CAST")
        val fields = payload["fieldsUsed"] as List<String>
        assertTrue(fields.containsAll(listOf("title", "poster", "description")))
    }

    @Test
    fun `no emission when sessionId returns null`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { null })
        events.emitFirstPaint(
            contentId = "x",
            itemType = "movie",
            surface = SourceSurface.HOME,
            source = "ADDON_META_PREVIEW",
            routerExecuted = false,
            networkExecuted = false,
            fieldsUsed = emptyList(),
            profileHash = null
        )
        assertTrue("must not emit when no active session", sink.events.isEmpty())
    }
}
