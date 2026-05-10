package com.nexio.tv.ui.navigation

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerNavOriginalLanguageTest {
    @Test
    fun `nav arg sources from originalLanguage when present`() {
        val item = stubMetaPreview(
            language = "nld",          // UI-locale leakage value
            originalLanguage = "eng"    // production language
        )
        val navArg = chooseNavOriginalLanguage(item)
        assertEquals("eng", navArg)
    }

    @Test
    fun `nav arg falls back to legacy language when originalLanguage absent`() {
        // Until every producer is migrated (e.g. addon-only paths), fall back
        // to the legacy field for compatibility, but ONLY when originalLanguage
        // is null.
        val item = stubMetaPreview(language = "eng", originalLanguage = null)
        val navArg = chooseNavOriginalLanguage(item)
        assertEquals("eng", navArg)
    }

    @Test
    fun `nav arg null when both fields absent`() {
        val item = stubMetaPreview(language = null, originalLanguage = null)
        val navArg = chooseNavOriginalLanguage(item)
        assertEquals(null, navArg)
    }

    private fun stubMetaPreview(language: String?, originalLanguage: String?): MetaPreview =
        MetaPreview(
            id = "tmdb:1",
            type = ContentType.SERIES,
            name = "Stub",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            language = language,
            originalLanguage = originalLanguage
        )
}
