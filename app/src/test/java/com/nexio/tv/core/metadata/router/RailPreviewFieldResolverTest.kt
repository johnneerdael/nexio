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

    @Test
    fun rail_preview_poster_replaced_by_primary_or_artwork_router() {
        val preview = previewCandidate(
            ResolvedField.POSTER to FieldValue("https://preview.example/poster.jpg", FieldOwner.PRIMARY, SourceRole.RAIL_PREVIEW)
        )
        val artwork = MetadataCandidate(
            provider = MetadataPrimaryProvider.TVDB,
            resolverType = ResolverType.ARTWORK,
            sourceProvider = "TVDB_ARTWORK",
            sourceRole = SourceRole.ARTWORK,
            fields = mapOf(
                ResolvedField.POSTER to FieldValue("https://artwork.example/poster.jpg", FieldOwner.ARTWORK)
            )
        )

        val document = resolver.resolveWithPreview(
            preview = preview,
            primary = null,
            secondary = listOf(artwork)
        )

        assertEquals("https://artwork.example/poster.jpg", document.poster)
        assertEquals(SourceRole.ARTWORK, document.sourceRoles[ResolvedField.POSTER])
        assertEquals("TVDB_ARTWORK", document.sourceProviders[ResolvedField.POSTER])
    }

    @Test
    fun rail_preview_overview_replaced_by_primary_overview() {
        val preview = previewCandidate(
            ResolvedField.OVERVIEW to FieldValue("Preview overview", FieldOwner.PRIMARY, SourceRole.RAIL_PREVIEW)
        )
        val primary = MetadataCandidate(
            provider = MetadataPrimaryProvider.TVDB,
            sourceProvider = "TVDB",
            sourceRole = SourceRole.PRIMARY,
            fields = mapOf(
                ResolvedField.OVERVIEW to FieldValue("Primary overview", FieldOwner.PRIMARY)
            )
        )

        val document = resolver.resolveWithPreview(
            preview = preview,
            primary = primary,
            secondary = emptyList()
        )

        assertEquals("Primary overview", document.overview)
        assertEquals(SourceRole.PRIMARY, document.sourceRoles[ResolvedField.OVERVIEW])
        assertEquals("TVDB", document.sourceProviders[ResolvedField.OVERVIEW])
    }

    @Test
    fun rail_preview_rating_loses_to_rating_resolver_when_available() {
        val preview = previewCandidate(
            ResolvedField.RATING to FieldValue(7.1, FieldOwner.PRIMARY, SourceRole.RAIL_PREVIEW)
        )
        val rating = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            resolverType = ResolverType.RATING,
            sourceProvider = "TMDB_RATING",
            sourceRole = SourceRole.RATING,
            fields = mapOf(
                ResolvedField.RATING to FieldValue(8.8, FieldOwner.RATING)
            )
        )

        val document = resolver.resolveWithPreview(
            preview = preview,
            primary = null,
            secondary = listOf(rating)
        )

        assertEquals(8.8, document.rating)
        assertEquals(SourceRole.RATING, document.sourceRoles[ResolvedField.RATING])
        assertEquals("TMDB_RATING", document.sourceProviders[ResolvedField.RATING])
    }

    @Test
    fun rail_preview_remains_when_hydration_fails() {
        val previewCandidate = previewCandidate(
            ResolvedField.TITLE to FieldValue("Preview Title", FieldOwner.PRIMARY, SourceRole.RAIL_PREVIEW)
        )

        val document = resolver.resolveWithPreview(
            preview = previewCandidate,
            primary = null,
            secondary = emptyList()
        )

        assertEquals("Preview Title", document.title)
        assertEquals(SourceRole.RAIL_PREVIEW, document.sourceRoles[ResolvedField.TITLE])
        assertEquals("TRAKT", document.sourceProviders[ResolvedField.TITLE])
    }

    private fun previewCandidate(
        vararg fields: Pair<ResolvedField, FieldValue>
    ): MetadataCandidate {
        return MetadataCandidate(
            provider = MetadataPrimaryProvider.TVDB,
            sourceProvider = "TRAKT",
            sourceRole = SourceRole.RAIL_PREVIEW,
            fields = mapOf(*fields)
        )
    }
}
