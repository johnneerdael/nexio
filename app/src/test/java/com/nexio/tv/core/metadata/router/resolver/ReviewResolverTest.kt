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

class ReviewResolverTest {
    @Test
    fun `aggregates reviews across providers and emits field_selected`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        val resolver = ReviewResolver(events)

        val tmdbCandidate = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            fields = mapOf(
                ResolvedField.REVIEWS to FieldValue(listOf("tmdb-review-1", "tmdb-review-2"), FieldOwner.REVIEWS)
            )
        )
        val tvdbCandidate = MetadataCandidate(
            provider = MetadataPrimaryProvider.TVDB,
            fields = mapOf(
                ResolvedField.REVIEWS to FieldValue(listOf("tvdb-review-1"), FieldOwner.REVIEWS)
            )
        )

        val pick = resolver.resolve(
            contentId = "tmdb:603",
            primary = tmdbCandidate,
            secondary = listOf(tvdbCandidate)
        )

        assertNotNull(pick)
        @Suppress("UNCHECKED_CAST")
        val merged = pick!!.fields[ResolvedField.REVIEWS]?.value as List<String>
        assertEquals(listOf("tmdb-review-1", "tmdb-review-2", "tvdb-review-1"), merged)

        val fieldSelectedEvents = sink.events.filter { it.eventType == "metadata.field_selected" }
        assertEquals(1, fieldSelectedEvents.size)
        val payload = fieldSelectedEvents.first().payload as Map<*, *>
        assertEquals("REVIEWS", payload["field"])
        assertEquals("TMDB,TVDB", payload["selectedProvider"])
        assertEquals("aggregate", payload["sourceRole"])
        assertEquals("review-resolver: aggregate-all", payload["ownershipRule"])
        assertEquals("reviews-aggregated:3", payload["valuePreview"])
    }

    @Test
    fun `falls back to secondary when primary lacks REVIEWS`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        val resolver = ReviewResolver(events)

        val emptyTmdb = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            fields = emptyMap()
        )
        val tvdbCandidate = MetadataCandidate(
            provider = MetadataPrimaryProvider.TVDB,
            fields = mapOf(
                ResolvedField.REVIEWS to FieldValue(listOf("tvdb-review"), FieldOwner.REVIEWS)
            )
        )

        val pick = resolver.resolve(
            contentId = "tmdb:603",
            primary = emptyTmdb,
            secondary = listOf(tvdbCandidate)
        )

        assertNotNull(pick)
        assertEquals(MetadataPrimaryProvider.TVDB, pick!!.provider)
        @Suppress("UNCHECKED_CAST")
        val merged = pick.fields[ResolvedField.REVIEWS]?.value as List<String>
        assertEquals(listOf("tvdb-review"), merged)

        val payload = sink.events.first { it.eventType == "metadata.field_selected" }.payload as Map<*, *>
        @Suppress("UNCHECKED_CAST")
        val rejected = payload["rejectedCandidates"] as List<Map<String, Any?>>
        assertEquals(1, rejected.size)
        assertEquals("TMDB", rejected.first()["provider"])
        assertEquals("missing_field", rejected.first()["reason"])
    }

    @Test
    fun `treats empty REVIEWS collection as missing`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        val resolver = ReviewResolver(events)

        val emptyReviews = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            fields = mapOf(
                ResolvedField.REVIEWS to FieldValue(emptyList<String>(), FieldOwner.REVIEWS)
            )
        )
        val tvdbCandidate = MetadataCandidate(
            provider = MetadataPrimaryProvider.TVDB,
            fields = mapOf(
                ResolvedField.REVIEWS to FieldValue(listOf("tvdb-review"), FieldOwner.REVIEWS)
            )
        )

        val pick = resolver.resolve(
            contentId = "tmdb:603",
            primary = emptyReviews,
            secondary = listOf(tvdbCandidate)
        )

        assertNotNull(pick)
        assertEquals(MetadataPrimaryProvider.TVDB, pick!!.provider)
    }

    @Test
    fun `returns null when no candidate has REVIEWS`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        val resolver = ReviewResolver(events)

        val pick = resolver.resolve(
            contentId = "tmdb:603",
            primary = null,
            secondary = emptyList()
        )
        assertNull(pick)
        assertTrue(sink.events.isEmpty())
    }
}
