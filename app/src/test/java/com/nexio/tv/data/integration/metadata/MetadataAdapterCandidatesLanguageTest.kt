package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.tmdb.TmdbEnrichment
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

    // C5: ORIGINAL_LANGUAGE emission tests

    @Test
    fun `TmdbEnrichment toMetadataCandidate emits ORIGINAL_LANGUAGE`() {
        val enrichment = TmdbEnrichment(
            localizedTitle = "Fight Club",
            description = null,
            genres = emptyList(),
            backdrop = null,
            logo = null,
            poster = null,
            directorMembers = emptyList(),
            writerMembers = emptyList(),
            castMembers = emptyList(),
            releaseInfo = null,
            rating = null,
            runtimeMinutes = null,
            director = emptyList(),
            writer = emptyList(),
            productionCompanies = emptyList(),
            networks = emptyList(),
            ageRating = null,
            countries = null,
            language = "en",
            collectionId = null,
            collectionName = null
        )
        val candidate = enrichment.toMetadataCandidate(MetadataPrimaryProvider.TMDB)
        assertEquals("en", candidate.fields[ResolvedField.ORIGINAL_LANGUAGE]?.value)
    }

    @Test
    fun `TvMetadataEnrichment toMetadataCandidate emits ORIGINAL_LANGUAGE alongside LANGUAGE`() {
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
            remoteIds = emptyMap()
        )
        val candidate = enrichment.toMetadataCandidate(MetadataPrimaryProvider.TVDB)
        // Both fields populated during the deprecation window.
        assertEquals("eng", candidate.fields[ResolvedField.LANGUAGE]?.value)
        assertEquals("eng", candidate.fields[ResolvedField.ORIGINAL_LANGUAGE]?.value)
    }

    @Test
    fun `TvdbSeriesExtendedRecord toMetadataCandidate emits ORIGINAL_LANGUAGE`() {
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
        assertEquals("eng", candidate.fields[ResolvedField.ORIGINAL_LANGUAGE]?.value)
    }

    @Test
    fun `buildTmdbLocalizedCandidate emits ORIGINAL_LANGUAGE from source enrichment`() {
        val lang = stubNormalizedLanguage("en")
        val policy = LocalizationPolicy(
            requestedLanguage = lang,
            fallbackLanguage = lang,
            provider = MetadataPrimaryProvider.TMDB,
            policyVersion = 1,
            allowProviderFallbackForMissingLocalizedFields = false,
            maxPerEpisodeTranslationFallbacksPerRequest = 0
        )
        val enrichment = TmdbEnrichment(
            localizedTitle = "Fight Club",
            description = null,
            genres = emptyList(),
            backdrop = null,
            logo = null,
            poster = null,
            directorMembers = emptyList(),
            writerMembers = emptyList(),
            castMembers = emptyList(),
            releaseInfo = null,
            rating = null,
            runtimeMinutes = null,
            director = emptyList(),
            writer = emptyList(),
            productionCompanies = emptyList(),
            networks = emptyList(),
            ageRating = null,
            countries = null,
            language = "en",
            collectionId = null,
            collectionName = null
        )
        val candidate = buildTmdbLocalizedCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            policy = policy,
            requested = enrichment,
            english = enrichment
        )
        assertEquals("en", candidate.fields[ResolvedField.ORIGINAL_LANGUAGE]?.value)
    }

    private fun stubNormalizedLanguage(code: String): NormalizedLanguage =
        NormalizedLanguage(requestedTag = code, providerCode = code)
}
