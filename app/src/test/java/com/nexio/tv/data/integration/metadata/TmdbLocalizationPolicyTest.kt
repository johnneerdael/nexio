package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.tmdb.TmdbEnrichment
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
    fun `tmdb english request does not need duplicate english candidate`() {
        val policy = LocalizationPolicy.tmdb("en-US")

        assertEquals(listOf("en-US"), policy.languageChain().map { it.providerCode })
    }

    private fun enrichment(
        title: String?,
        overview: String?,
        poster: String?
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
            language = null,
            collectionId = null,
            collectionName = null
        )
}
