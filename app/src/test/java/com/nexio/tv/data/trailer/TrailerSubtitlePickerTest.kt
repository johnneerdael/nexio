package com.nexio.tv.data.trailer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailerSubtitlePickerTest {

    private val englishManual = YouTubeCaptionTrack(
        baseUrl = "https://yt.example/timedtext?lang=en",
        languageCode = "en",
        isTranslatable = true
    )
    private val englishAsr = YouTubeCaptionTrack(
        baseUrl = "https://yt.example/timedtext?lang=en&asr",
        languageCode = "en",
        kind = "asr",
        isTranslatable = true
    )
    private val koreanManual = YouTubeCaptionTrack(
        baseUrl = "https://yt.example/timedtext?lang=ko",
        languageCode = "ko",
        isTranslatable = true
    )

    @Test
    fun `none and forced return null`() {
        assertNull(pickTrailerCaptionTrack(listOf(englishManual), "none"))
        assertNull(pickTrailerCaptionTrack(listOf(englishManual), "forced"))
        assertNull(pickTrailerCaptionTrack(listOf(englishManual), null))
        assertNull(pickTrailerCaptionTrack(emptyList(), "en"))
    }

    @Test
    fun `native track preferred over translation`() {
        val picked = pickTrailerCaptionTrack(listOf(englishManual, koreanManual), "ko")
        assertEquals(koreanManual.baseUrl, picked?.baseUrl)
        assertEquals("ko", picked?.languageCode)
        assertNull(picked?.translateTo)
    }

    @Test
    fun `manual track preferred over ASR for same language`() {
        val picked = pickTrailerCaptionTrack(listOf(englishAsr, englishManual), "en")
        assertEquals(englishManual.baseUrl, picked?.baseUrl)
    }

    @Test
    fun `translates from english when target language is missing`() {
        val picked = pickTrailerCaptionTrack(listOf(englishManual), "nl")
        assertEquals(englishManual.baseUrl, picked?.baseUrl)
        assertEquals("nl", picked?.translateTo)
        assertEquals("nl", picked?.languageCode)
    }

    @Test
    fun `original picks first manual track`() {
        val picked = pickTrailerCaptionTrack(listOf(englishAsr, koreanManual, englishManual), "original")
        assertEquals(koreanManual.baseUrl, picked?.baseUrl)
        assertNull(picked?.translateTo)
    }

    @Test
    fun `language base match falls back when exact tag is missing`() {
        val ptBr = YouTubeCaptionTrack(baseUrl = "u-ptbr", languageCode = "pt-BR", isTranslatable = true)
        val picked = pickTrailerCaptionTrack(listOf(englishManual, ptBr), "pt")
        assertEquals(ptBr.baseUrl, picked?.baseUrl)
    }

    @Test
    fun `vtt url appends fmt and tlang correctly`() {
        val withQuery = SelectedTrailerCaptionTrack(
            baseUrl = "https://yt.example/timedtext?v=1",
            languageCode = "nl",
            translateTo = "nl"
        )
        val url = buildTrailerSubtitleVttUrl(withQuery)
        assertTrue(url.endsWith("&fmt=vtt&tlang=nl"))

        val withoutQuery = SelectedTrailerCaptionTrack(
            baseUrl = "https://yt.example/timedtext",
            languageCode = "en"
        )
        assertEquals("https://yt.example/timedtext?fmt=vtt", buildTrailerSubtitleVttUrl(withoutQuery))
    }

    @Test
    fun `extract caption tracks strips existing fmt and tlang from baseUrl`() {
        val playerResponse = mapOf(
            "captions" to mapOf(
                "playerCaptionsTracklistRenderer" to mapOf(
                    "captionTracks" to listOf(
                        mapOf(
                            "baseUrl" to "https://yt.example/timedtext?v=1&fmt=srt&tlang=es&lang=en",
                            "languageCode" to "en",
                            "isTranslatable" to true
                        )
                    )
                )
            )
        )
        val tracks = extractYouTubeCaptionTracks(playerResponse)
        assertEquals(1, tracks.size)
        assertEquals("https://yt.example/timedtext?v=1&lang=en", tracks[0].baseUrl)
    }

    @Test
    fun `extract caption tracks detects ASR via vssId when kind is absent`() {
        val playerResponse = mapOf(
            "captions" to mapOf(
                "playerCaptionsTracklistRenderer" to mapOf(
                    "captionTracks" to listOf(
                        mapOf(
                            "baseUrl" to "https://yt.example/timedtext?lang=en",
                            "languageCode" to "en",
                            "vssId" to "a.en",
                            "isTranslatable" to true
                        ),
                        mapOf(
                            "baseUrl" to "https://yt.example/timedtext?lang=fr",
                            "languageCode" to "fr",
                            "vssId" to ".fr",
                            "isTranslatable" to true
                        )
                    )
                )
            )
        )
        val tracks = extractYouTubeCaptionTracks(playerResponse)
        assertEquals(2, tracks.size)
        assertEquals("asr", tracks[0].kind)
        assertEquals(null, tracks[1].kind)
    }

    @Test
    fun `extract caption tracks reads baseUrl and translatable flag`() {
        val playerResponse = mapOf(
            "captions" to mapOf(
                "playerCaptionsTracklistRenderer" to mapOf(
                    "captionTracks" to listOf(
                        mapOf(
                            "baseUrl" to "https://yt.example/timedtext?lang=en",
                            "languageCode" to "en",
                            "isTranslatable" to true,
                            "name" to mapOf("simpleText" to "English"),
                            "kind" to "asr"
                        ),
                        mapOf(
                            "baseUrl" to "https://yt.example/timedtext?lang=ko",
                            "languageCode" to "ko",
                            "isTranslatable" to false
                        )
                    )
                )
            )
        )
        val extracted = extractYouTubeCaptionTracks(playerResponse)
        assertEquals(2, extracted.size)
        assertEquals("en", extracted[0].languageCode)
        assertEquals("asr", extracted[0].kind)
        assertEquals("English", extracted[0].languageName)
        assertTrue(extracted[0].isTranslatable)
        assertEquals("ko", extracted[1].languageCode)
        assertNull(extracted[1].kind)
    }
}
