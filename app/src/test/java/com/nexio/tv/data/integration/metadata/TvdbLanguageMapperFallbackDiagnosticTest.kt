package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.tvdb.TvdbLanguageMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F2-E-01 pin: TvdbLanguageMapper.normalize MUST surface whether the requested locale was
 * collapsed to the English fallback. Italian, Portuguese, Polish, Russian, Korean, Arabic, and
 * Japanese users need the diagnostic to surface in trace bundles.
 */
class TvdbLanguageMapperFallbackDiagnosticTest {

    @Test
    fun `Italian collapses to English with diagnostic flag set`() {
        val result = TvdbLanguageMapper.normalize("it")
        assertEquals("eng", result.code)
        assertTrue("expected isCollapsedToFallback = true for Italian", result.isCollapsedToFallback)
    }

    @Test
    fun `Portuguese collapses to English with diagnostic flag set`() {
        val result = TvdbLanguageMapper.normalize("pt")
        assertEquals("eng", result.code)
        assertTrue("expected isCollapsedToFallback = true for Portuguese", result.isCollapsedToFallback)
    }

    @Test
    fun `Polish collapses to English with diagnostic flag set`() {
        val result = TvdbLanguageMapper.normalize("pl")
        assertEquals("eng", result.code)
        assertTrue("expected isCollapsedToFallback = true for Polish", result.isCollapsedToFallback)
    }

    @Test
    fun `Russian collapses to English with diagnostic flag set`() {
        val result = TvdbLanguageMapper.normalize("ru")
        assertEquals("eng", result.code)
        assertTrue("expected isCollapsedToFallback = true for Russian", result.isCollapsedToFallback)
    }

    @Test
    fun `Korean collapses to English with diagnostic flag set`() {
        val result = TvdbLanguageMapper.normalize("ko")
        assertEquals("eng", result.code)
        assertTrue("expected isCollapsedToFallback = true for Korean", result.isCollapsedToFallback)
    }

    @Test
    fun `Arabic collapses to English with diagnostic flag set`() {
        val result = TvdbLanguageMapper.normalize("ar")
        assertEquals("eng", result.code)
        assertTrue("expected isCollapsedToFallback = true for Arabic", result.isCollapsedToFallback)
    }

    @Test
    fun `Japanese collapses to English with diagnostic flag set`() {
        val result = TvdbLanguageMapper.normalize("ja")
        assertEquals("eng", result.code)
        assertTrue("expected isCollapsedToFallback = true for Japanese", result.isCollapsedToFallback)
    }

    @Test
    fun `English does not flag collapse`() {
        val result = TvdbLanguageMapper.normalize("en")
        assertEquals("eng", result.code)
        assertFalse(result.isCollapsedToFallback)
    }

    @Test
    fun `English us variant does not flag collapse`() {
        val result = TvdbLanguageMapper.normalize("en-US")
        assertEquals("eng", result.code)
        assertFalse(result.isCollapsedToFallback)
    }

    @Test
    fun `Spanish (whitelisted) does not flag collapse`() {
        val result = TvdbLanguageMapper.normalize("es")
        assertEquals("spa", result.code)
        assertFalse(result.isCollapsedToFallback)
    }

    @Test
    fun `French (whitelisted) does not flag collapse`() {
        val result = TvdbLanguageMapper.normalize("fr")
        assertEquals("fra", result.code)
        assertFalse(result.isCollapsedToFallback)
    }

    @Test
    fun `German (whitelisted) does not flag collapse`() {
        val result = TvdbLanguageMapper.normalize("de")
        assertEquals("deu", result.code)
        assertFalse(result.isCollapsedToFallback)
    }

    @Test
    fun `Dutch (whitelisted) does not flag collapse`() {
        val result = TvdbLanguageMapper.normalize("nl")
        assertEquals("nld", result.code)
        assertFalse(result.isCollapsedToFallback)
    }

    @Test
    fun `null locale yields English fallback without collapse flag`() {
        val result = TvdbLanguageMapper.normalize(null)
        assertEquals("eng", result.code)
        assertFalse("null locale should not flag collapse — there was no requested locale", result.isCollapsedToFallback)
    }

    @Test
    fun `blank locale yields English fallback without collapse flag`() {
        val result = TvdbLanguageMapper.normalize("   ")
        assertEquals("eng", result.code)
        assertFalse("blank locale should not flag collapse — there was no requested locale", result.isCollapsedToFallback)
    }
}
