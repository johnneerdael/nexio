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

class RecommendationResolverTest {
    @Test
    fun `picks primary candidate when it has RECOMMENDATIONS field and emits field_selected`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        val resolver = RecommendationResolver(events)

        val tmdbCandidate = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            fields = mapOf(
                ResolvedField.RECOMMENDATIONS to FieldValue(listOf("tmdb-rec-1"), FieldOwner.RECOMMENDATIONS)
            )
        )
        val tvdbCandidate = MetadataCandidate(
            provider = MetadataPrimaryProvider.TVDB,
            fields = mapOf(
                ResolvedField.RECOMMENDATIONS to FieldValue(listOf("tvdb-rec-1"), FieldOwner.RECOMMENDATIONS)
            )
        )

        val pick = resolver.resolve(
            contentId = "tmdb:603",
            primary = tmdbCandidate,
            secondary = listOf(tvdbCandidate)
        )

        assertNotNull(pick)
        assertEquals(MetadataPrimaryProvider.TMDB, pick!!.provider)
        @Suppress("UNCHECKED_CAST")
        val recs = pick.fields[ResolvedField.RECOMMENDATIONS]?.value as List<String>
        assertEquals(listOf("tmdb-rec-1"), recs)

        val fieldSelectedEvents = sink.events.filter { it.eventType == "metadata.field_selected" }
        assertEquals(1, fieldSelectedEvents.size)
        val payload = fieldSelectedEvents.first().payload as Map<*, *>
        assertEquals("RECOMMENDATIONS", payload["field"])
        assertEquals("TMDB", payload["selectedProvider"])
        assertEquals("RECOMMENDATIONS", payload["sourceRole"])
        assertEquals("recommendation-resolver: first-match-by-priority", payload["ownershipRule"])
        assertEquals("recommendations", payload["valuePreview"])
        @Suppress("UNCHECKED_CAST")
        val rejected = payload["rejectedCandidates"] as List<Map<String, Any?>>
        assertEquals(1, rejected.size)
        assertEquals("TVDB", rejected.first()["provider"])
        assertEquals("lower_priority", rejected.first()["reason"])
    }

    @Test
    fun `falls back to secondary when primary lacks RECOMMENDATIONS`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        val resolver = RecommendationResolver(events)

        val emptyTmdb = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            fields = emptyMap()
        )
        val tvdbCandidate = MetadataCandidate(
            provider = MetadataPrimaryProvider.TVDB,
            fields = mapOf(
                ResolvedField.RECOMMENDATIONS to FieldValue(listOf("tvdb-rec"), FieldOwner.RECOMMENDATIONS)
            )
        )

        val pick = resolver.resolve(
            contentId = "tmdb:603",
            primary = emptyTmdb,
            secondary = listOf(tvdbCandidate)
        )

        assertNotNull(pick)
        assertEquals(MetadataPrimaryProvider.TVDB, pick!!.provider)

        val payload = sink.events.first { it.eventType == "metadata.field_selected" }.payload as Map<*, *>
        @Suppress("UNCHECKED_CAST")
        val rejected = payload["rejectedCandidates"] as List<Map<String, Any?>>
        assertEquals(1, rejected.size)
        assertEquals("TMDB", rejected.first()["provider"])
        assertEquals("missing_field", rejected.first()["reason"])
    }

    @Test
    fun `treats empty RECOMMENDATIONS collection as missing`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        val resolver = RecommendationResolver(events)

        val emptyRecs = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            fields = mapOf(
                ResolvedField.RECOMMENDATIONS to FieldValue(emptyList<String>(), FieldOwner.RECOMMENDATIONS)
            )
        )
        val tvdbCandidate = MetadataCandidate(
            provider = MetadataPrimaryProvider.TVDB,
            fields = mapOf(
                ResolvedField.RECOMMENDATIONS to FieldValue(listOf("tvdb-rec"), FieldOwner.RECOMMENDATIONS)
            )
        )

        val pick = resolver.resolve(
            contentId = "tmdb:603",
            primary = emptyRecs,
            secondary = listOf(tvdbCandidate)
        )

        assertNotNull(pick)
        assertEquals(MetadataPrimaryProvider.TVDB, pick!!.provider)
    }

    @Test
    fun `returns null when no candidate has RECOMMENDATIONS`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        val resolver = RecommendationResolver(events)

        val pick = resolver.resolve(
            contentId = "tmdb:603",
            primary = null,
            secondary = emptyList()
        )
        assertNull(pick)
        assertTrue(sink.events.isEmpty())
    }
}
