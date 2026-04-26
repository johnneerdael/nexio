package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.integration.TvdbApiShapes
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.ResolvedField
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalizationResolverTest {
    @Test
    fun `requested language candidate wins over english fallback`() {
        val policy = LocalizationPolicy.tvdb("nl")
        val selected = LocalizationResolver.selectField(
            field = ResolvedField.OVERVIEW,
            policy = policy,
            candidates = listOf(
                candidate(ResolvedField.OVERVIEW, "English overview", "eng", FallbackRole.LANGUAGE_FALLBACK),
                candidate(ResolvedField.OVERVIEW, "Nederlandse tekst", "nld", FallbackRole.LOCALIZED)
            )
        )

        assertEquals("Nederlandse tekst", selected?.value)
        assertEquals("nld", selected?.language?.providerCode)
        assertEquals(FallbackRole.LOCALIZED, selected?.fallbackRole)
    }

    @Test
    fun `english fallback wins when requested language is missing or placeholder`() {
        val policy = LocalizationPolicy.tvdb("nl")
        val selected = LocalizationResolver.selectField(
            field = ResolvedField.OVERVIEW,
            policy = policy,
            candidates = listOf(
                candidate(ResolvedField.OVERVIEW, "No description available.", "nld", FallbackRole.LOCALIZED),
                candidate(ResolvedField.OVERVIEW, "English overview", "eng", FallbackRole.LANGUAGE_FALLBACK)
            )
        )

        assertEquals("English overview", selected?.value)
        assertEquals("eng", selected?.language?.providerCode)
        assertEquals(FallbackRole.LANGUAGE_FALLBACK, selected?.fallbackRole)
    }

    @Test
    fun `resolver never selects cross provider candidate for missing localized field`() {
        val policy = LocalizationPolicy.tvdb("nl")
        val selected = LocalizationResolver.selectField(
            field = ResolvedField.TITLE,
            policy = policy,
            candidates = listOf(
                candidate(
                    field = ResolvedField.TITLE,
                    value = "TMDB fallback title",
                    language = "nl-NL",
                    role = FallbackRole.PROVIDER_FALLBACK,
                    provider = MetadataPrimaryProvider.TMDB
                ),
                candidate(
                    field = ResolvedField.TITLE,
                    value = "English TVDB title",
                    language = "eng",
                    role = FallbackRole.LANGUAGE_FALLBACK,
                    provider = MetadataPrimaryProvider.TVDB
                )
            )
        )

        assertEquals("English TVDB title", selected?.value)
        assertEquals(MetadataPrimaryProvider.TVDB, selected?.provider)
    }

    private fun candidate(
        field: ResolvedField,
        value: String,
        language: String,
        role: FallbackRole,
        provider: MetadataPrimaryProvider = MetadataPrimaryProvider.TVDB
    ): LocalizedFieldCandidate =
        LocalizedFieldCandidate(
            field = field,
            value = value,
            language = NormalizedLanguage(language, language),
            provider = provider,
            sourceShape = TvdbApiShapes.SERIES_TRANSLATION,
            fallbackRole = role
        )
}
