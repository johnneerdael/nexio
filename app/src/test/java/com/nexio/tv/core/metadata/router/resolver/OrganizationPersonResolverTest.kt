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

class OrganizationPersonResolverTest {

    @Test
    fun `picks per-field winners across CAST CREW and ORGANIZATION_LIST and emits 3 events`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        val resolver = OrganizationPersonResolver(events)

        val tmdbCandidate = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            fields = mapOf(
                ResolvedField.CAST to FieldValue("tmdb-cast", FieldOwner.ORGANIZATION_PERSON),
                ResolvedField.CREW to FieldValue("tmdb-crew", FieldOwner.ORGANIZATION_PERSON)
            )
        )
        val tvdbCandidate = MetadataCandidate(
            provider = MetadataPrimaryProvider.TVDB,
            fields = mapOf(
                ResolvedField.ORGANIZATION_LIST to FieldValue(
                    listOf("network"),
                    FieldOwner.ORGANIZATION_PERSON
                )
            )
        )

        val resolution = resolver.resolve(
            contentId = "tmdb:603",
            primary = tmdbCandidate,
            secondary = listOf(tvdbCandidate)
        )

        assertNotNull(resolution.cast)
        assertEquals(MetadataPrimaryProvider.TMDB, resolution.cast!!.provider)
        assertNotNull(resolution.crew)
        assertEquals(MetadataPrimaryProvider.TMDB, resolution.crew!!.provider)
        assertNotNull(resolution.organizations)
        assertEquals(MetadataPrimaryProvider.TVDB, resolution.organizations!!.provider)

        val fieldSelectedEvents = sink.events.filter { it.eventType == "metadata.field_selected" }
        assertEquals(3, fieldSelectedEvents.size)
        val fieldNames = fieldSelectedEvents.map { (it.payload as Map<*, *>)["field"] }.toSet()
        assertEquals(setOf("CAST", "CREW", "ORGANIZATION_LIST"), fieldNames)
    }

    @Test
    fun `falls back to secondary candidate when primary lacks the field`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        val resolver = OrganizationPersonResolver(events)

        val emptyTmdb = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            fields = emptyMap()
        )
        val tvdbCandidate = MetadataCandidate(
            provider = MetadataPrimaryProvider.TVDB,
            fields = mapOf(
                ResolvedField.CAST to FieldValue(listOf("tvdb-cast"), FieldOwner.ORGANIZATION_PERSON)
            )
        )

        val resolution = resolver.resolve(
            contentId = "tmdb:603",
            primary = emptyTmdb,
            secondary = listOf(tvdbCandidate)
        )

        assertNotNull(resolution.cast)
        assertEquals(MetadataPrimaryProvider.TVDB, resolution.cast!!.provider)
        assertNull(resolution.crew)
        assertNull(resolution.organizations)

        val fieldSelectedEvents = sink.events.filter { it.eventType == "metadata.field_selected" }
        assertEquals(1, fieldSelectedEvents.size)
        val payload = fieldSelectedEvents.first().payload as Map<*, *>
        assertEquals("CAST", payload["field"])
        assertEquals("TVDB", payload["selectedProvider"])
        assertEquals("ORGANIZATION_PERSON", payload["sourceRole"])
        assertEquals("cast", payload["valuePreview"])
        @Suppress("UNCHECKED_CAST")
        val rejected = payload["rejectedCandidates"] as List<Map<String, Any?>>
        assertEquals(1, rejected.size)
        assertEquals("TMDB", rejected.first()["provider"])
        assertEquals("missing_field", rejected.first()["reason"])
    }

    @Test
    fun `treats empty collection field as missing`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        val resolver = OrganizationPersonResolver(events)

        val emptyOrg = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            fields = mapOf(
                ResolvedField.ORGANIZATION_LIST to FieldValue(
                    emptyList<String>(),
                    FieldOwner.ORGANIZATION_PERSON
                )
            )
        )
        val tvdbCandidate = MetadataCandidate(
            provider = MetadataPrimaryProvider.TVDB,
            fields = mapOf(
                ResolvedField.ORGANIZATION_LIST to FieldValue(
                    listOf("network"),
                    FieldOwner.ORGANIZATION_PERSON
                )
            )
        )

        val resolution = resolver.resolve(
            contentId = "tmdb:603",
            primary = emptyOrg,
            secondary = listOf(tvdbCandidate)
        )

        assertNotNull(resolution.organizations)
        assertEquals(MetadataPrimaryProvider.TVDB, resolution.organizations!!.provider)

        val payload = sink.events.first { it.eventType == "metadata.field_selected" }.payload as Map<*, *>
        @Suppress("UNCHECKED_CAST")
        val rejected = payload["rejectedCandidates"] as List<Map<String, Any?>>
        assertEquals(1, rejected.size)
        assertEquals("TMDB", rejected.first()["provider"])
        assertEquals("lower_priority", rejected.first()["reason"])
    }

    @Test
    fun `returns nulls and emits no events when no candidates carry any field`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        val resolver = OrganizationPersonResolver(events)

        val resolution = resolver.resolve(
            contentId = "tmdb:603",
            primary = null,
            secondary = emptyList()
        )

        assertNull(resolution.cast)
        assertNull(resolution.crew)
        assertNull(resolution.organizations)
        assertTrue(sink.events.isEmpty())
    }
}
