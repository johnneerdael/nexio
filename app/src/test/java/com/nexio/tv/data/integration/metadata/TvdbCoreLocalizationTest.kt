package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.ResolvedField
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
