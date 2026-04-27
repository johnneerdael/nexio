package com.nexio.tv.core.metadata.router

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
