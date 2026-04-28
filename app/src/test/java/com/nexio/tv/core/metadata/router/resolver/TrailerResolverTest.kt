package com.nexio.tv.core.metadata.router.resolver

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.metadata.router.FieldOwner
import com.nexio.tv.core.metadata.router.FieldValue
import com.nexio.tv.core.metadata.router.MetadataCandidate
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.trace.TraceMetadataEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailerResolverTest {
    @Test
    fun `picks primary candidate when it has TRAILERS field and emits field_selected`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        val resolver = TrailerResolver(events)

        val tvdbCandidate = MetadataCandidate(
            provider = MetadataPrimaryProvider.TVDB,
            fields = mapOf(
                ResolvedField.TRAILERS to FieldValue(listOf("tvdb-trailer"), FieldOwner.TRAILERS)
            )
        )
        val tmdbCandidate = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            fields = mapOf(
                ResolvedField.TRAILERS to FieldValue(listOf("tmdb-trailer"), FieldOwner.TRAILERS)
            )
        )

        val pick = resolver.resolve(
            contentId = "tt1",
            primary = tvdbCandidate,
            secondary = listOf(tmdbCandidate)
        )

        assertNotNull(pick)
        assertEquals(MetadataPrimaryProvider.TVDB, pick!!.provider)

        val fieldSelectedEvents = sink.events.filter { it.eventType == "metadata.field_selected" }
        assertEquals(1, fieldSelectedEvents.size)
        val payload = fieldSelectedEvents.first().payload as Map<*, *>
        assertEquals("TRAILERS", payload["field"])
        assertEquals("TVDB", payload["selectedProvider"])
        @Suppress("UNCHECKED_CAST")
        val rejected = payload["rejectedCandidates"] as List<Map<String, Any?>>
        assertEquals(1, rejected.size)
        assertEquals("TMDB", rejected.first()["provider"])
    }

    @Test
    fun `falls back to secondary when primary lacks TRAILERS`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        val resolver = TrailerResolver(events)

        val emptyTvdb = MetadataCandidate(
            provider = MetadataPrimaryProvider.TVDB,
            fields = emptyMap()
        )
        val tmdbCandidate = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            fields = mapOf(
                ResolvedField.TRAILERS to FieldValue(listOf("tmdb-trailer"), FieldOwner.TRAILERS)
            )
        )

        val pick = resolver.resolve(
            contentId = "tt1",
            primary = emptyTvdb,
            secondary = listOf(tmdbCandidate)
        )

        assertNotNull(pick)
        assertEquals(MetadataPrimaryProvider.TMDB, pick!!.provider)
    }

    @Test
    fun `treats empty TRAILERS collection as missing`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        val resolver = TrailerResolver(events)

        val emptyTrailers = MetadataCandidate(
            provider = MetadataPrimaryProvider.TVDB,
            fields = mapOf(
                ResolvedField.TRAILERS to FieldValue(emptyList<String>(), FieldOwner.TRAILERS)
            )
        )
        val tmdbCandidate = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            fields = mapOf(
                ResolvedField.TRAILERS to FieldValue(listOf("tmdb"), FieldOwner.TRAILERS)
            )
        )

        val pick = resolver.resolve(
            contentId = "tt1",
            primary = emptyTrailers,
            secondary = listOf(tmdbCandidate)
        )
        assertNotNull(pick)
        assertEquals(MetadataPrimaryProvider.TMDB, pick!!.provider)
    }

    @Test
    fun `returns null when no candidate has TRAILERS field`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        val resolver = TrailerResolver(events)

        val pick = resolver.resolve(contentId = "tt1", primary = null, secondary = emptyList())
        assertNull(pick)
        assertTrue(sink.events.isEmpty())
    }
}
