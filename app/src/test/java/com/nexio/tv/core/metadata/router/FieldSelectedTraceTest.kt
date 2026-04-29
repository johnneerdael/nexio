package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldSelectedTraceTest {
    @Test
    fun `primary field emits field_selected with primary ownership rule`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        val resolver = FieldResolver(traceEvents = events)

        val primary = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            fields = mapOf(
                ResolvedField.TITLE to FieldValue(value = "Hello World", owner = FieldOwner.PRIMARY)
            )
        )
        resolver.resolve(primary = primary, secondary = emptyList())

        val selections = sink.events.filter { it.eventType == "metadata.field_selected" }
        assertTrue(
            "expected at least one selection event, got: ${sink.events.map { it.eventType }}",
            selections.isNotEmpty()
        )
        val titleEvent = selections.first { (it.payload as Map<*, *>)["field"] == "TITLE" }
        val payload = titleEvent.payload as Map<*, *>
        assertEquals("TMDB", payload["selectedProvider"])
        assertEquals("PRIMARY", payload["sourceRole"])
        assertEquals("Hello World", payload["valuePreview"])
        assertEquals("primary always wins", payload["ownershipRule"])
        @Suppress("UNCHECKED_CAST")
        val rejected = payload["rejectedCandidates"] as List<*>
        assertTrue("no rejections when primary wins solo", rejected.isEmpty())
    }

    @Test
    fun `secondary field rejected by primary ownership emits in rejectedCandidates`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        val resolver = FieldResolver(traceEvents = events)

        val primary = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            fields = mapOf(
                ResolvedField.POSTER to FieldValue(value = "tmdb-poster.jpg", owner = FieldOwner.PRIMARY)
            )
        )
        val secondary = listOf(
            MetadataCandidate(
                provider = MetadataPrimaryProvider.TVDB,
                fields = mapOf(
                    ResolvedField.POSTER to FieldValue(value = "tvdb-poster.jpg", owner = FieldOwner.PRIMARY)
                )
            )
        )
        resolver.resolve(primary = primary, secondary = secondary)

        val posterEvent = sink.events
            .first { it.eventType == "metadata.field_selected" && (it.payload as Map<*, *>)["field"] == "POSTER" }
        val payload = posterEvent.payload as Map<*, *>
        assertEquals("TMDB", payload["selectedProvider"])
        @Suppress("UNCHECKED_CAST")
        val rejected = payload["rejectedCandidates"] as List<Map<String, Any?>>
        assertEquals(1, rejected.size)
        assertEquals("TVDB", rejected.first()["provider"])
        assertTrue((rejected.first()["reason"] as String).contains("field already filled"))
    }
}
