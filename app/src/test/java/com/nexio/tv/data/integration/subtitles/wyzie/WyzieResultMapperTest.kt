package com.nexio.tv.data.integration.subtitles.wyzie

import com.nexio.tv.data.remote.dto.WyzieSubtitleDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WyzieResultMapperTest {

    private fun dto(
        id: String = "abc",
        url: String = "https://sub.wyzie.io/c/x/id/abc",
        language: String = "en",
        source: String? = null,
        format: String = "srt",
    ) = WyzieSubtitleDto(
        id = id,
        url = url,
        format = format,
        encoding = "UTF-8",
        display = "English",
        language = language,
        media = "Test",
        isHearingImpaired = false,
        source = source,
    )

    @Test
    fun `known source maps to per-source label`() {
        val sub = WyzieResultMapper.map(dto(source = "subdl"))
        assertEquals("Wyzie · SubDL", sub.addonName)
        assertEquals("wyzie:abc", sub.id)
        assertEquals("https://sub.wyzie.io/c/x/id/abc", sub.url)
        assertEquals("en", sub.lang)
    }

    @Test
    fun `null source maps to plain Wyzie label`() {
        val sub = WyzieResultMapper.map(dto(source = null))
        assertEquals("Wyzie", sub.addonName)
    }

    @Test
    fun `unknown source maps to Wyzie · raw value`() {
        val sub = WyzieResultMapper.map(dto(source = "newsource"))
        assertEquals("Wyzie · newsource", sub.addonName)
    }

    @Test
    fun `each known source has a non-null logo uri`() {
        com.nexio.tv.domain.model.WyzieSource.values().forEach { src ->
            val sub = WyzieResultMapper.map(dto(source = src.apiName))
            assertTrue(
                "Logo missing for ${src.apiName}",
                sub.addonLogo?.startsWith("android.resource://") == true,
            )
        }
    }

    @Test
    fun `unknown source falls back to generic logo uri`() {
        val sub = WyzieResultMapper.map(dto(source = "newsource"))
        assertTrue(sub.addonLogo?.startsWith("android.resource://") == true)
    }

    @Test
    fun `null source uses generic logo uri`() {
        val sub = WyzieResultMapper.map(dto(source = null))
        assertTrue(sub.addonLogo?.startsWith("android.resource://") == true)
    }

    @Test
    fun `id is namespaced with wyzie prefix`() {
        val sub = WyzieResultMapper.map(dto(id = "12345"))
        assertEquals("wyzie:12345", sub.id)
    }

    @Test
    fun `lang passes through verbatim`() {
        val sub = WyzieResultMapper.map(dto(language = "pt-BR"))
        assertEquals("pt-BR", sub.lang)
    }
}
