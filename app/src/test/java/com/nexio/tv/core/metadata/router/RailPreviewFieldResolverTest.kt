package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import org.junit.Assert.assertEquals
import org.junit.Test

class RailPreviewFieldResolverTest {
    private val resolver = FieldResolver()

    @Test
    fun `rail preview title is used before canonical hydration`() {
        val preview = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            sourceProvider = "TRAKT",
            sourceRole = SourceRole.RAIL_PREVIEW,
            fields = mapOf(
                ResolvedField.TITLE to FieldValue("Breaking Bad", FieldOwner.PRIMARY, SourceRole.RAIL_PREVIEW),
                ResolvedField.RELEASE_DATE to FieldValue("2008", FieldOwner.PRIMARY, SourceRole.RAIL_PREVIEW)
            )
        )

        val document = resolver.resolveWithPreview(
            preview = preview,
            primary = null,
            secondary = emptyList()
        )

        assertEquals("Breaking Bad", document.title)
        assertEquals(SourceRole.RAIL_PREVIEW, document.sourceRoles[ResolvedField.TITLE])
    }

    @Test
    fun `primary title replaces rail preview title after hydration`() {
        val sink = RecordingTraceSink()
        val resolver = FieldResolver(
            traceEvents = TraceMetadataEvents(sink, sessionId = { "rail-preview-test" })
        )
        val preview = MetadataCandidate(
            provider = MetadataPrimaryProvider.TVDB,
            sourceProvider = "TRAKT",
            sourceRole = SourceRole.RAIL_PREVIEW,
            fields = mapOf(
                ResolvedField.TITLE to FieldValue("Preview Title", FieldOwner.PRIMARY, SourceRole.RAIL_PREVIEW)
            )
        )
        val primary = MetadataCandidate(
            provider = MetadataPrimaryProvider.TVDB,
            sourceProvider = "TVDB",
            sourceRole = SourceRole.PRIMARY,
            fields = mapOf(
                ResolvedField.TITLE to FieldValue("Canonical Title", FieldOwner.PRIMARY, SourceRole.PRIMARY)
            )
        )

        val document = resolver.resolveWithPreview(
            preview = preview,
            primary = primary,
            secondary = emptyList()
        )

        assertEquals("Canonical Title", document.title)
        assertEquals(SourceRole.PRIMARY, document.sourceRoles[ResolvedField.TITLE])
        assertEquals("TVDB", document.sourceProviders[ResolvedField.TITLE])
        assertEquals(
            IgnoredFieldOverwrite(
                field = ResolvedField.TITLE,
                existingOwner = FieldOwner.PRIMARY,
                attemptedOwner = FieldOwner.PRIMARY,
                attemptedValue = "Preview Title"
            ),
            document.ignoredOverwrites.single()
        )
        assertEquals("Preview Title", document.ignoredOverwrites.single().attemptedValue)

        val titleEvent = sink.events
            .first { it.eventType == "metadata.field_selected" && (it.payload as Map<*, *>)["field"] == "TITLE" }
        val payload = titleEvent.payload as Map<*, *>
        assertEquals("TVDB", payload["selectedProvider"])
        assertEquals("PRIMARY", payload["sourceRole"])
        assertEquals("primary canonical field replaces rail preview", payload["ownershipRule"])
        @Suppress("UNCHECKED_CAST")
        val rejected = payload["rejectedCandidates"] as List<Map<String, Any?>>
        assertEquals(
            mapOf(
                "provider" to "TVDB",
                "sourceProvider" to "TRAKT",
                "sourceRole" to "RAIL_PREVIEW",
                "reason" to "primary canonical field available"
            ),
            rejected.single()
        )
    }
}
