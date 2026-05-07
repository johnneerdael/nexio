package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.metadata.router.FieldOwner
import com.nexio.tv.core.metadata.router.FieldValue
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataLocalizationFallbackRole
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.metadata.router.SourceRole
import com.nexio.tv.data.remote.api.TvdbSeriesExtendedRecord
import com.nexio.tv.data.remote.api.TvdbTranslationRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class TvdbCoreLocalizationTest {
    @Test
    fun `localized missing overview falls back to english tvdb translation not extended original overview`() {
        val policy = LocalizationPolicy.tvdb("nl")
        val selected = buildTvdbCoreLocalizedCandidate(
            provider = MetadataPrimaryProvider.TVDB,
            policy = policy,
            extended = TvdbSeriesExtendedRecord(
                id = 81189,
                name = "Original language title",
                overview = "Original language overview"
            ),
            englishTranslation = TvdbTranslationRecord(
                name = "English title",
                overview = "English overview"
            ),
            requestedTranslation = TvdbTranslationRecord(
                name = "Nederlandse titel",
                overview = ""
            )
        )

        assertEquals("Nederlandse titel", selected.fields.getValue(ResolvedField.TITLE).value)
        assertEquals("English overview", selected.fields.getValue(ResolvedField.OVERVIEW).value)
    }

    @Test
    fun `english request uses english translation fields only`() {
        val policy = LocalizationPolicy.tvdb("en-US")
        val selected = buildTvdbCoreLocalizedCandidate(
            provider = MetadataPrimaryProvider.TVDB,
            policy = policy,
            extended = TvdbSeriesExtendedRecord(
                id = 81189,
                name = "Original title",
                overview = "Original overview"
            ),
            englishTranslation = TvdbTranslationRecord(
                name = "English title",
                overview = "English overview"
            ),
            requestedTranslation = null
        )

        assertEquals("English title", selected.fields.getValue(ResolvedField.TITLE).value)
        assertEquals("English overview", selected.fields.getValue(ResolvedField.OVERVIEW).value)
    }

    @Test
    fun `tvdb core localized candidate preserves language fallback trace`() {
        val policy = LocalizationPolicy.tvdb("nl")
        val selected = buildTvdbCoreLocalizedCandidate(
            provider = MetadataPrimaryProvider.TVDB,
            policy = policy,
            extended = TvdbSeriesExtendedRecord(
                id = 81189,
                name = "Original title",
                overview = "Original overview"
            ),
            englishTranslation = TvdbTranslationRecord(
                name = "English title",
                overview = "English overview"
            ),
            requestedTranslation = TvdbTranslationRecord(
                name = "Nederlandse titel",
                overview = "N/A"
            )
        )

        val overviewTrace = selected.localization.getValue(ResolvedField.OVERVIEW)
        assertEquals("eng", overviewTrace.selectedLanguage)
        assertEquals(MetadataLocalizationFallbackRole.LANGUAGE_FALLBACK, overviewTrace.fallbackRole)
        assertEquals("tvdb.series.translation", overviewTrace.sourceApiShapeId)
        assertEquals("nld", overviewTrace.rejectedCandidates.single().language)
    }

    @Test
    fun `tvdb core localized candidate merges resolved artwork fields`() {
        val policy = LocalizationPolicy.tvdb("en-US")
        val posterField = FieldValue("runtime-poster-ref", FieldOwner.ARTWORK, SourceRole.ARTWORK)
        val logoField = FieldValue("runtime-logo-ref", FieldOwner.ARTWORK, SourceRole.ARTWORK)

        val selected = buildTvdbCoreLocalizedCandidate(
            provider = MetadataPrimaryProvider.TVDB,
            policy = policy,
            extended = TvdbSeriesExtendedRecord(
                id = 81189,
                image = "https://art.tvdb.com/raw-extended-image.jpg"
            ),
            englishTranslation = TvdbTranslationRecord(name = "English title"),
            requestedTranslation = null,
            artworkFields = mapOf(
                ResolvedField.POSTER to posterField,
                ResolvedField.LOGO to logoField
            )
        )

        assertEquals(posterField, selected.fields.getValue(ResolvedField.POSTER))
        assertEquals(logoField, selected.fields.getValue(ResolvedField.LOGO))
    }

    @Test
    fun `tvdb core localized candidate does not emit raw extended image poster fallback`() {
        val policy = LocalizationPolicy.tvdb("en-US")

        val selected = buildTvdbCoreLocalizedCandidate(
            provider = MetadataPrimaryProvider.TVDB,
            policy = policy,
            extended = TvdbSeriesExtendedRecord(
                id = 81189,
                image = "https://art.tvdb.com/raw-extended-image.jpg"
            ),
            englishTranslation = TvdbTranslationRecord(name = "English title"),
            requestedTranslation = null
        )

        assertEquals(false, selected.fields.containsKey(ResolvedField.POSTER))
    }

    @Test
    fun `tvdb core translation cache key includes language and policy version`() {
        val policy = LocalizationPolicy.tvdb("nl")

        assertEquals(
            "tvdb:series:81189:translation:nld:policy:${policy.policyVersion}",
            tvdbSeriesTranslationCacheKey(81189, policy.requestedLanguage.providerCode, policy.policyVersion)
        )
        assertEquals(
            "tvdb:series:81189:translation:eng:policy:${policy.policyVersion}",
            tvdbSeriesTranslationCacheKey(81189, policy.fallbackLanguage.providerCode, policy.policyVersion)
        )
    }
}
