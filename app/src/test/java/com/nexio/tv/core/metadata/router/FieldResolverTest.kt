package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import org.junit.Assert.assertEquals
import org.junit.Test

class FieldResolverTest {
    private val resolver = FieldResolver()

    @Test
    fun `secondary rating cannot overwrite primary title but can fill rating and records ignored title overwrite`() {
        val primary = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            fields = mapOf(
                ResolvedField.CANONICAL_ID to FieldValue("tmdb:123", FieldOwner.ARTWORK),
                ResolvedField.TITLE to FieldValue("Primary title", FieldOwner.RATING)
            )
        )
        val secondary = MetadataCandidate(
            provider = MetadataPrimaryProvider.TVDB,
            resolverType = ResolverType.RATING,
            fields = mapOf(
                ResolvedField.TITLE to FieldValue("Rating title", FieldOwner.RATING),
                ResolvedField.RATING to FieldValue(8.4, FieldOwner.RATING)
            )
        )

        val document = resolver.resolve(primary, listOf(secondary))

        assertEquals("Primary title", document.title)
        assertEquals(8.4, document.rating)
        assertEquals(FieldOwner.PRIMARY, document.fieldOwners[ResolvedField.TITLE])
        assertEquals(FieldOwner.RATING, document.fieldOwners[ResolvedField.RATING])
        assertEquals(
            listOf(
                IgnoredFieldOverwrite(
                    field = ResolvedField.TITLE,
                    existingOwner = FieldOwner.PRIMARY,
                    attemptedOwner = FieldOwner.RATING,
                    attemptedValue = "Rating title"
                )
            ),
            document.ignoredOverwrites
        )
    }

    @Test
    fun `artwork provider can fill poster but cannot change canonical id and records ignored overwrite`() {
        val primary = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            fields = mapOf(
                ResolvedField.CANONICAL_ID to FieldValue("tmdb:123", FieldOwner.ARTWORK),
                ResolvedField.TITLE to FieldValue("Primary title", FieldOwner.RATING)
            )
        )
        val secondary = MetadataCandidate(
            provider = MetadataPrimaryProvider.TVDB,
            resolverType = ResolverType.ARTWORK,
            fields = mapOf(
                ResolvedField.CANONICAL_ID to FieldValue("tvdb:456", FieldOwner.ARTWORK),
                ResolvedField.POSTER to FieldValue("https://image.example/poster.jpg", FieldOwner.ARTWORK)
            )
        )

        val document = resolver.resolve(primary, listOf(secondary))

        assertEquals("tmdb:123", document.canonicalId)
        assertEquals("https://image.example/poster.jpg", document.poster)
        assertEquals(FieldOwner.PRIMARY, document.fieldOwners[ResolvedField.CANONICAL_ID])
        assertEquals(FieldOwner.ARTWORK, document.fieldOwners[ResolvedField.POSTER])
        assertEquals(
            listOf(
                IgnoredFieldOverwrite(
                    field = ResolvedField.CANONICAL_ID,
                    existingOwner = FieldOwner.PRIMARY,
                    attemptedOwner = FieldOwner.ARTWORK,
                    attemptedValue = "tvdb:456"
                )
            ),
            document.ignoredOverwrites
        )
    }

    @Test
    fun `shared premium artwork provider replaces primary poster only`() {
        val premiumPoster = "nexio-artwork://decision/top-posters-poster"
        val primary = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            sourceProvider = "TMDB",
            fields = mapOf(
                ResolvedField.CANONICAL_ID to FieldValue("tmdb:123", FieldOwner.PRIMARY),
                ResolvedField.TITLE to FieldValue("Primary title", FieldOwner.PRIMARY),
                ResolvedField.POSTER to FieldValue("https://image.tmdb.org/t/p/w500/native.jpg", FieldOwner.PRIMARY)
            )
        )
        val premiumArtwork = MetadataCandidate(
            provider = MetadataPrimaryProvider.TOP_POSTERS,
            resolverType = ResolverType.ARTWORK,
            sourceProvider = "TOP_POSTERS",
            sourceRole = SourceRole.ARTWORK,
            fields = mapOf(
                ResolvedField.CANONICAL_ID to FieldValue("topposters:wrong", FieldOwner.ARTWORK),
                ResolvedField.TITLE to FieldValue("Premium title", FieldOwner.ARTWORK),
                ResolvedField.POSTER to FieldValue(premiumPoster, FieldOwner.ARTWORK)
            )
        )

        val document = resolver.resolve(primary, listOf(premiumArtwork))

        assertEquals("tmdb:123", document.canonicalId)
        assertEquals("Primary title", document.title)
        assertEquals(premiumPoster, document.poster)
        assertEquals(SourceRole.PRIMARY, document.sourceRoles[ResolvedField.TITLE])
        assertEquals(SourceRole.ARTWORK, document.sourceRoles[ResolvedField.POSTER])
        assertEquals("TOP_POSTERS", document.sourceProviders[ResolvedField.POSTER])
    }

    @Test
    fun `legacy premium integration poster does not replace primary poster`() {
        val primaryPoster = "https://image.tmdb.org/t/p/w500/native.jpg"
        val primary = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            sourceProvider = "TMDB",
            sourceRole = SourceRole.PRIMARY,
            fields = mapOf(
                ResolvedField.CANONICAL_ID to FieldValue("tmdb:123", FieldOwner.PRIMARY, SourceRole.PRIMARY),
                ResolvedField.TITLE to FieldValue("Primary title", FieldOwner.PRIMARY, SourceRole.PRIMARY),
                ResolvedField.POSTER to FieldValue(primaryPoster, FieldOwner.PRIMARY, SourceRole.PRIMARY)
            )
        )
        val premiumPoster = "integration-poster://fetch?provider=RPDB"
        val premiumArtwork = MetadataCandidate(
            provider = MetadataPrimaryProvider.RPDB,
            resolverType = ResolverType.ARTWORK,
            sourceProvider = "RPDB",
            sourceRole = SourceRole.ARTWORK,
            fields = mapOf(
                ResolvedField.POSTER to FieldValue(premiumPoster, FieldOwner.ARTWORK, SourceRole.ARTWORK)
            )
        )

        val document = resolver.resolve(primary, listOf(premiumArtwork))

        assertEquals("Primary title", document.title)
        assertEquals(primaryPoster, document.poster)
        assertEquals(SourceRole.PRIMARY, document.sourceRoles[ResolvedField.POSTER])
        assertEquals(
            "legacy_premium_artwork_model_not_displayable",
            document.rejectedCandidatesByField.getValue(ResolvedField.POSTER).single()["reason"]
        )
    }

    @Test
    fun `premium artwork override trace rule wins after preview and primary poster replacements`() {
        val sink = RecordingTraceSink()
        val resolver = FieldResolver(
            traceEvents = TraceMetadataEvents(sink, sessionId = { "premium-poster-test" })
        )
        val previewPoster = "https://preview.example/poster.jpg"
        val primaryPoster = "https://image.tmdb.org/t/p/w500/native.jpg"
        val preview = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            sourceProvider = "TRAKT",
            sourceRole = SourceRole.RAIL_PREVIEW,
            fields = mapOf(
                ResolvedField.POSTER to FieldValue(previewPoster, FieldOwner.PRIMARY, SourceRole.RAIL_PREVIEW)
            )
        )
        val primary = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            sourceProvider = "TMDB",
            sourceRole = SourceRole.PRIMARY,
            fields = mapOf(
                ResolvedField.CANONICAL_ID to FieldValue("tmdb:123", FieldOwner.PRIMARY, SourceRole.PRIMARY),
                ResolvedField.POSTER to FieldValue(primaryPoster, FieldOwner.PRIMARY, SourceRole.PRIMARY)
            )
        )
        val premiumPoster = "nexio-artwork://decision/rpdb-poster"
        val premiumArtwork = MetadataCandidate(
            provider = MetadataPrimaryProvider.RPDB,
            resolverType = ResolverType.ARTWORK,
            sourceProvider = "RPDB",
            sourceRole = SourceRole.ARTWORK,
            fields = mapOf(
                ResolvedField.POSTER to FieldValue(premiumPoster, FieldOwner.ARTWORK, SourceRole.ARTWORK)
            )
        )

        val document = resolver.resolveWithPreview(
            preview = preview,
            primary = primary,
            secondary = listOf(premiumArtwork)
        )

        assertEquals(premiumPoster, document.poster)
        assertEquals(SourceRole.ARTWORK, document.sourceRoles[ResolvedField.POSTER])
        assertEquals(
            listOf(
                mapOf(
                    "provider" to "TMDB",
                    "sourceProvider" to "TRAKT",
                    "sourceRole" to "RAIL_PREVIEW",
                    "reason" to "primary canonical field available"
                ),
                mapOf(
                    "provider" to "TMDB",
                    "sourceProvider" to "TMDB",
                    "sourceRole" to "PRIMARY",
                    "reason" to "premium_artwork_provider_precedence"
                )
            ),
            document.rejectedCandidatesByField.getValue(ResolvedField.POSTER)
        )

        val posterEvent = sink.events
            .first { it.eventType == "metadata.field_selected" && (it.payload as Map<*, *>)["field"] == "POSTER" }
        val payload = posterEvent.payload as Map<*, *>
        assertEquals("RPDB", payload["selectedProvider"])
        assertEquals("ARTWORK", payload["sourceRole"])
        assertEquals("premium artwork may override poster only", payload["ownershipRule"])
        @Suppress("UNCHECKED_CAST")
        val rejected = payload["rejectedCandidates"] as List<Map<String, Any?>>
        assertEquals(document.rejectedCandidatesByField.getValue(ResolvedField.POSTER), rejected)
    }

    @Test
    fun `secondary overwrite is traced when primary owns field`() {
        val primary = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            fields = mapOf(
                ResolvedField.TITLE to FieldValue("Primary Title", FieldOwner.PRIMARY)
            )
        )
        val secondary = MetadataCandidate(
            provider = MetadataPrimaryProvider.KITSU,
            fields = mapOf(
                ResolvedField.TITLE to FieldValue("Secondary Title", FieldOwner.PRIMARY)
            )
        )

        val result = resolver.resolve(primary = primary, secondary = listOf(secondary))

        assertEquals("Primary Title", result.title)
        assertEquals(
            listOf(
                IgnoredFieldOverwrite(
                    field = ResolvedField.TITLE,
                    existingOwner = FieldOwner.PRIMARY,
                    attemptedOwner = FieldOwner.PRIMARY,
                    attemptedValue = "Secondary Title"
                )
            ),
            result.ignoredOverwrites
        )
    }

    @Test
    fun `field resolver preserves selected localization source evidence`() {
        val selected = MetadataLocalizationFieldTrace(
            field = ResolvedField.OVERVIEW,
            selectedProvider = MetadataPrimaryProvider.TVDB,
            selectedLanguage = "eng",
            fallbackRole = MetadataLocalizationFallbackRole.LANGUAGE_FALLBACK,
            sourceApiShapeId = "tvdb.series.translation",
            rejectedCandidates = listOf(
                MetadataLocalizationRejectedCandidate(
                    provider = MetadataPrimaryProvider.TVDB,
                    language = "nld",
                    fallbackRole = MetadataLocalizationFallbackRole.LOCALIZED,
                    reason = "missing_or_placeholder"
                )
            )
        )

        val document = resolver.resolve(
            primary = MetadataCandidate(
                provider = MetadataPrimaryProvider.TVDB,
                fields = mapOf(
                    ResolvedField.OVERVIEW to FieldValue("English overview", FieldOwner.PRIMARY)
                ),
                localization = mapOf(ResolvedField.OVERVIEW to selected)
            ),
            secondary = emptyList()
        )

        assertEquals(selected, document.localization.getValue(ResolvedField.OVERVIEW))
    }
}
