package com.nexio.tv.data.trailer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrailerSubtitlePickerTranslationTest {

    private val englishAsr = YouTubeCaptionTrack(
        baseUrl = "https://www.youtube.com/api/timedtext?v=abc&lang=en&caps=asr",
        languageCode = "en",
        languageName = "English (auto-generated)",
        kind = "asr",
        isTranslatable = true
    )

    private val englishManual = YouTubeCaptionTrack(
        baseUrl = "https://www.youtube.com/api/timedtext?v=abc&lang=en",
        languageCode = "en",
        languageName = "English",
        kind = null,
        isTranslatable = true
    )

    private val dutchManual = YouTubeCaptionTrack(
        baseUrl = "https://www.youtube.com/api/timedtext?v=abc&lang=nl",
        languageCode = "nl",
        languageName = "Dutch",
        kind = null,
        isTranslatable = true
    )

    @Test
    fun `native match returns source language without translateTo`() {
        val selected = pickTrailerCaptionTrack(
            tracks = listOf(englishManual, dutchManual),
            preferredLanguage = "nl"
        )
        assertEquals("nl", selected?.languageCode)
        assertNull(selected?.translateTo)
    }

    @Test
    fun `no native match returns english source with translateTo set to preferred lang`() {
        val selected = pickTrailerCaptionTrack(
            tracks = listOf(englishManual),
            preferredLanguage = "nl"
        )
        // Source track unchanged
        assertEquals("en", selected?.languageCode)
        assertEquals(englishManual.baseUrl, selected?.baseUrl)
        // Target language requested for AI translation
        assertEquals("nl", selected?.translateTo)
    }

    @Test
    fun `no native match prefers manual english over ASR english as source`() {
        val selected = pickTrailerCaptionTrack(
            tracks = listOf(englishAsr, englishManual),
            preferredLanguage = "nl"
        )
        assertEquals(englishManual.baseUrl, selected?.baseUrl)
        assertEquals("nl", selected?.translateTo)
    }

    @Test
    fun `preferredLanguage off disables track selection regardless of translation`() {
        val selected = pickTrailerCaptionTrack(
            tracks = listOf(englishManual),
            preferredLanguage = "off"
        )
        assertNull(selected)
    }
}
