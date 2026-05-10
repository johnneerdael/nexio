package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetadataAdapterCandidatesLanguageTest {

    @Test
    fun `TvMetadataEnrichment toMetadataCandidate forwards language`() {
        val enrichment = TvMetadataEnrichment(
            seriesTvdbId = 393268,
            localizedTitle = "Citadel",
            description = null,
            backdrop = null,
            logo = null,
            poster = null,
            releaseInfo = null,
            runtimeMinutes = null,
            ageRating = null,
            language = "eng",
            remoteIds = mapOf("imdb" to setOf("tt12111188"))
        )

        val candidate = enrichment.toMetadataCandidate(MetadataPrimaryProvider.TVDB)

        assertEquals(
            "TVDB-routed series must carry production language as a primary-owned candidate; " +
                "see Bug B in 2026-05-10 dossier",
            "eng",
            candidate.fields[ResolvedField.LANGUAGE]?.value
        )
    }

    @Test
    fun `TvMetadataEnrichment toMetadataCandidate omits LANGUAGE when null`() {
        val enrichment = TvMetadataEnrichment(
            seriesTvdbId = null,
            localizedTitle = null,
            description = null,
            backdrop = null,
            logo = null,
            poster = null,
            releaseInfo = null,
            runtimeMinutes = null,
            ageRating = null,
            language = null,
            remoteIds = emptyMap()
        )

        val candidate = enrichment.toMetadataCandidate(MetadataPrimaryProvider.TVDB)

        assertNull(candidate.fields[ResolvedField.LANGUAGE])
    }

    @Test
    fun `null receiver yields candidate with no LANGUAGE field`() {
        val candidate = (null as TvMetadataEnrichment?).toMetadataCandidate(MetadataPrimaryProvider.TVDB)
        assertNull(candidate.fields[ResolvedField.LANGUAGE])
    }

    @Test
    fun `TvdbSeriesExtendedRecord toMetadataCandidate forwards originalLanguage`() {
        val record = com.nexio.tv.data.remote.api.TvdbSeriesExtendedRecord(
            id = 393268,
            name = "Citadel",
            overview = null,
            image = null,
            score = null,
            averageRuntime = null,
            originalLanguage = "eng",
            originalCountry = "usa",
            remoteIds = emptyList()
        )

        val candidate = record.toMetadataCandidate(MetadataPrimaryProvider.TVDB)

        assertEquals("eng", candidate.fields[ResolvedField.LANGUAGE]?.value)
        assertEquals("usa", candidate.fields[ResolvedField.ORIGINAL_COUNTRY]?.value)
    }
}
