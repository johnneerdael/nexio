package com.nexio.tv.core.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailerLanguageMatcherTest {

    @Test
    fun `normalize maps ISO 639-1 codes through unchanged`() {
        assertEquals("en", TrailerLanguageMatcher.normalize("en"))
        assertEquals("de", TrailerLanguageMatcher.normalize("DE"))
        assertEquals("ja", TrailerLanguageMatcher.normalize("ja"))
    }

    @Test
    fun `normalize strips region suffixes`() {
        assertEquals("en", TrailerLanguageMatcher.normalize("en-US"))
        assertEquals("zh", TrailerLanguageMatcher.normalize("zh-CN"))
        assertEquals("pt", TrailerLanguageMatcher.normalize("pt_BR"))
    }

    @Test
    fun `normalize maps ISO 639-2 terminological codes to 639-1`() {
        assertEquals("en", TrailerLanguageMatcher.normalize("eng"))
        assertEquals("de", TrailerLanguageMatcher.normalize("deu"))
        assertEquals("fr", TrailerLanguageMatcher.normalize("fra"))
        assertEquals("ja", TrailerLanguageMatcher.normalize("jpn"))
    }

    @Test
    fun `normalize maps ISO 639-2 bibliographic codes to 639-1`() {
        assertEquals("de", TrailerLanguageMatcher.normalize("ger"))
        assertEquals("fr", TrailerLanguageMatcher.normalize("fre"))
        assertEquals("nl", TrailerLanguageMatcher.normalize("dut"))
        assertEquals("zh", TrailerLanguageMatcher.normalize("chi"))
    }

    @Test
    fun `normalize returns null for blank or unknown codes`() {
        assertNull(TrailerLanguageMatcher.normalize(null))
        assertNull(TrailerLanguageMatcher.normalize(""))
        assertNull(TrailerLanguageMatcher.normalize("   "))
        assertNull(TrailerLanguageMatcher.normalize("xyz"))
        assertNull(TrailerLanguageMatcher.normalize("toolong"))
    }

    @Test
    fun `matches returns true when codes resolve to same base across encodings`() {
        assertTrue(TrailerLanguageMatcher.matches("eng", "en"))
        assertTrue(TrailerLanguageMatcher.matches("en", "eng"))
        assertTrue(TrailerLanguageMatcher.matches("deu", "de-DE"))
        assertTrue(TrailerLanguageMatcher.matches("ger", "de"))
        assertTrue(TrailerLanguageMatcher.matches("jpn", "ja"))
    }

    @Test
    fun `matches returns false on mismatch or unknown codes`() {
        assertFalse(TrailerLanguageMatcher.matches("deu", "en"))
        assertFalse(TrailerLanguageMatcher.matches("eng", "ja"))
        assertFalse(TrailerLanguageMatcher.matches(null, "en"))
        assertFalse(TrailerLanguageMatcher.matches("en", null))
        assertFalse(TrailerLanguageMatcher.matches(null, null))
        assertFalse(TrailerLanguageMatcher.matches("xyz", "en"))
    }
}
