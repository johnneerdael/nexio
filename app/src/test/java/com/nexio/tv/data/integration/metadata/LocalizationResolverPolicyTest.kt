package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.integration.TmdbApiShapes
import com.nexio.tv.core.integration.TvdbApiShapes
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.ResolvedField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalizationResolverPolicyTest {
    @Test
    fun `missing TVDB requested language falls back to TVDB English not TMDB localized text`() {
        val policy = LocalizationPolicy.tvdb("nl")

        val selected = LocalizationResolver.selectField(
            field = ResolvedField.OVERVIEW,
            policy = policy,
            candidates = listOf(
                candidate(
                    value = "No description available.",
                    language = "nld",
                    provider = MetadataPrimaryProvider.TVDB,
                    sourceShape = TvdbApiShapes.SERIES_TRANSLATION,
                    fallbackRole = FallbackRole.LOCALIZED
                ),
                candidate(
                    value = "English TVDB overview",
                    language = "eng",
                    provider = MetadataPrimaryProvider.TVDB,
                    sourceShape = TvdbApiShapes.SERIES_TRANSLATION,
                    fallbackRole = FallbackRole.LANGUAGE_FALLBACK
                ),
                candidate(
                    value = "Nederlandse TMDB tekst",
                    language = "nl-NL",
                    provider = MetadataPrimaryProvider.TMDB,
                    sourceShape = TmdbApiShapes.TV_CORE,
                    fallbackRole = FallbackRole.LOCALIZED
                )
            )
        )

        assertEquals("English TVDB overview", selected?.value)
        assertEquals(MetadataPrimaryProvider.TVDB, selected?.provider)
        assertEquals("eng", selected?.language?.providerCode)
        assertEquals(FallbackRole.LANGUAGE_FALLBACK, selected?.fallbackRole)
    }

    @Test
    fun `cross-provider fallback is not selected when primary provider has no usable language candidate`() {
        val policy = LocalizationPolicy.tvdb("nl")

        val selected = LocalizationResolver.selectField(
            field = ResolvedField.TITLE,
            policy = policy,
            candidates = listOf(
                candidate(
                    field = ResolvedField.TITLE,
                    value = "N/A",
                    language = "nld",
                    provider = MetadataPrimaryProvider.TVDB,
                    sourceShape = TvdbApiShapes.SERIES_TRANSLATION,
                    fallbackRole = FallbackRole.LOCALIZED
                ),
                candidate(
                    field = ResolvedField.TITLE,
                    value = "TMDB Nederlandse titel",
                    language = "nl-NL",
                    provider = MetadataPrimaryProvider.TMDB,
                    sourceShape = TmdbApiShapes.TV_CORE,
                    fallbackRole = FallbackRole.LOCALIZED
                )
            )
        )

        assertNull(selected)
    }

    @Test
    fun `selected trace exposes selected language fallback role and rejected cross provider reason`() {
        val policy = LocalizationPolicy.tvdb("nl")

        val selected = LocalizationResolver.selectField(
            field = ResolvedField.TITLE,
            policy = policy,
            candidates = listOf(
                candidate(
                    field = ResolvedField.TITLE,
                    value = "Nederlandse TVDB titel",
                    language = "nld",
                    provider = MetadataPrimaryProvider.TVDB,
                    sourceShape = TvdbApiShapes.SERIES_TRANSLATION,
                    fallbackRole = FallbackRole.LOCALIZED
                ),
                candidate(
                    field = ResolvedField.TITLE,
                    value = "TMDB Nederlandse titel",
                    language = "nl-NL",
                    provider = MetadataPrimaryProvider.TMDB,
                    sourceShape = TmdbApiShapes.TV_CORE,
                    fallbackRole = FallbackRole.LOCALIZED
                )
            )
        )

        assertEquals("nld", selected?.language?.providerCode)
        assertEquals(FallbackRole.LOCALIZED, selected?.fallbackRole)
        assertTrue(
            selected?.rejectedCandidates.orEmpty().any { rejection ->
                rejection.provider == MetadataPrimaryProvider.TMDB &&
                    rejection.reason == "cross_provider_fallback_not_allowed_for_missing_localized_field"
            }
        )
    }

    private fun candidate(
        field: ResolvedField = ResolvedField.OVERVIEW,
        value: String?,
        language: String,
        provider: MetadataPrimaryProvider,
        sourceShape: String,
        fallbackRole: FallbackRole
    ): LocalizedFieldCandidate =
        LocalizedFieldCandidate(
            field = field,
            value = value,
            language = NormalizedLanguage(language, language),
            provider = provider,
            sourceShape = sourceShape,
            fallbackRole = fallbackRole
        )
}
