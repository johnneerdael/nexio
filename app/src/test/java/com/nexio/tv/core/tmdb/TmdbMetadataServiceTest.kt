package com.nexio.tv.core.tmdb

import com.nexio.tv.data.remote.api.TmdbImage
import org.junit.Assert.assertEquals
import org.junit.Test

class TmdbMetadataServiceTest {

    @Test
    fun `normalizeTmdbLanguageTag preserves script subtags`() {
        assertEquals("zh-Hant", normalizeTmdbLanguageTag("zh-Hant"))
        assertEquals("sr-Latn", normalizeTmdbLanguageTag("sr-latn"))
    }

    @Test
    fun `normalizeTmdbLanguageTag uppercases regions and remaps es 419`() {
        assertEquals("pt-BR", normalizeTmdbLanguageTag("pt-br"))
        assertEquals("es-MX", normalizeTmdbLanguageTag("es-419"))
    }

    @Test
    fun `selectBestLocalizedImagePath prefers exact region match over language fallback`() {
        val selected = selectBestLocalizedImagePath(
            images = listOf(
                TmdbImage(filePath = "/pt-generic.svg", iso6391 = "pt", iso31661 = null),
                TmdbImage(filePath = "/pt-pt.svg", iso6391 = "pt", iso31661 = "PT"),
                TmdbImage(filePath = "/pt-br.svg", iso6391 = "pt", iso31661 = "BR")
            ),
            normalizedLanguage = "pt-BR"
        )

        assertEquals("/pt-br.svg", selected)
    }
}
