package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataLocalizationFallbackRole
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.tmdb.TmdbEnrichment
import com.nexio.tv.domain.model.MetaCastMember
import com.nexio.tv.domain.model.MetaCompany
import com.nexio.tv.domain.model.MetaCompanyKind
import org.junit.Assert.assertEquals
import org.junit.Test

class TmdbLocalizationPolicyTest {
    @Test
    fun `tmdb movie localized missing overview falls back to tmdb english only`() {
        val policy = LocalizationPolicy.tmdb("nl-NL")
        val candidate = buildTmdbLocalizedCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            policy = policy,
            requested = enrichment(
                title = "Nederlandse titel",
                overview = "No description available.",
                poster = "poster-nl"
            ),
            english = enrichment(
                title = "English title",
                overview = "English overview",
                poster = "poster-en"
            )
        )

        assertEquals("Nederlandse titel", candidate.fields.getValue(ResolvedField.TITLE).value)
        assertEquals("English overview", candidate.fields.getValue(ResolvedField.OVERVIEW).value)
        assertEquals("poster-nl", candidate.fields.getValue(ResolvedField.POSTER).value)
    }

    @Test
    fun `tmdb tv localized missing overview falls back to tmdb english only`() {
        val policy = LocalizationPolicy.tmdb("nl-NL")
        val candidate = buildTmdbLocalizedCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            policy = policy,
            requested = enrichment(
                title = "Nederlandse serie",
                overview = "No description available.",
                poster = "poster-nl"
            ),
            english = enrichment(
                title = "English series",
                overview = "English overview",
                poster = "poster-en"
            ),
            sourceApiShapeId = "tmdb.tv.core"
        )

        assertEquals("Nederlandse serie", candidate.fields.getValue(ResolvedField.TITLE).value)
        assertEquals("English overview", candidate.fields.getValue(ResolvedField.OVERVIEW).value)
        assertEquals("en-US", candidate.localization.getValue(ResolvedField.OVERVIEW).selectedLanguage)
        assertEquals(
            MetadataLocalizationFallbackRole.LANGUAGE_FALLBACK,
            candidate.localization.getValue(ResolvedField.OVERVIEW).fallbackRole
        )
        assertEquals("tmdb.tv.core", candidate.localization.getValue(ResolvedField.OVERVIEW).sourceApiShapeId)
    }

    @Test
    fun `tmdb english request does not need duplicate english candidate`() {
        val policy = LocalizationPolicy.tmdb("en-US")

        assertEquals(listOf("en-US"), policy.languageChain().map { it.providerCode })
    }

    @Test
    fun `tmdb localized candidate carries rich cast and organization fields`() {
        val policy = LocalizationPolicy.tmdb("en-US")
        val cast = listOf(MetaCastMember(name = "Actor", tmdbId = 42, provider = "tmdb", providerId = "42"))
        val companies = listOf(MetaCompany(tmdbId = 7, name = "Studio", kind = MetaCompanyKind.COMPANY))
        val networks = listOf(MetaCompany(tmdbId = 8, name = "Network", kind = MetaCompanyKind.NETWORK))

        val candidate = buildTmdbLocalizedCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            policy = policy,
            requested = enrichment(
                title = "Title",
                overview = "Overview",
                poster = "poster",
                castMembers = cast,
                productionCompanies = companies,
                networks = networks
            ),
            english = null
        )

        assertEquals(cast, candidate.fields.getValue(ResolvedField.CAST).value)
        assertEquals(companies + networks, candidate.fields.getValue(ResolvedField.ORGANIZATION_LIST).value)
    }

    private fun enrichment(
        title: String?,
        overview: String?,
        poster: String?,
        castMembers: List<MetaCastMember> = emptyList(),
        productionCompanies: List<MetaCompany> = emptyList(),
        networks: List<MetaCompany> = emptyList()
    ): TmdbEnrichment =
        TmdbEnrichment(
            localizedTitle = title,
            description = overview,
            genres = emptyList(),
            backdrop = null,
            logo = null,
            poster = poster,
            directorMembers = emptyList(),
            writerMembers = emptyList(),
            castMembers = castMembers,
            releaseInfo = null,
            rating = null,
            runtimeMinutes = null,
            director = emptyList(),
            writer = emptyList(),
            productionCompanies = productionCompanies,
            networks = networks,
            ageRating = null,
            countries = null,
            language = null,
            collectionId = null,
            collectionName = null
        )
}
