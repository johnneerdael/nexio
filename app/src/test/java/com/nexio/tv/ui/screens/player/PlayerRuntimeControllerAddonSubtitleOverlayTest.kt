package com.nexio.tv.ui.screens.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerRuntimeControllerAddonSubtitleOverlayTest {

    @Test
    fun `overlay mime routing accepts srt and vtt only`() {
        assertTrue(addonSubtitleSupportsOverlay(MimeTypes.APPLICATION_SUBRIP))
        assertTrue(addonSubtitleSupportsOverlay(MimeTypes.TEXT_VTT))
        assertFalse(addonSubtitleSupportsOverlay(MimeTypes.TEXT_SSA))
        assertFalse(addonSubtitleSupportsOverlay(MimeTypes.APPLICATION_TTML))
    }

    @Test
    fun `subrip system prompt mode bypasses addon overlay translation`() {
        assertFalse(
            shouldUseAddonOverlayTranslation(
                mimeType = MimeTypes.APPLICATION_SUBRIP,
                subRipSystemPromptEnabled = true
            )
        )
        assertTrue(
            shouldUseAddonOverlayTranslation(
                mimeType = MimeTypes.APPLICATION_SUBRIP,
                subRipSystemPromptEnabled = false
            )
        )
        assertTrue(
            shouldUseAddonOverlayTranslation(
                mimeType = MimeTypes.TEXT_VTT,
                subRipSystemPromptEnabled = true
            )
        )
    }

    @Test
    fun `ass and ssa urls route to text ssa and bypass overlay`() {
        assertEquals(
            MimeTypes.TEXT_SSA,
            PlayerSubtitleUtils.mimeTypeFromUrl("https://example.test/subs/movie.ass")
        )
        assertEquals(
            MimeTypes.TEXT_SSA,
            PlayerSubtitleUtils.mimeTypeFromUrl("https://example.test/subs/movie.ssa")
        )
        assertFalse(addonSubtitleSupportsOverlay(PlayerSubtitleUtils.mimeTypeFromUrl("https://example.test/subs/movie.ass")))
        assertFalse(addonSubtitleSupportsOverlay(PlayerSubtitleUtils.mimeTypeFromUrl("https://example.test/subs/movie.ssa")))
    }

    @Test
    fun `ai translation accepts ass and ssa`() {
        assertTrue(subtitleSupportsAiTranslationForTest("https://example.test/subtitle.ass"))
        assertTrue(subtitleSupportsAiTranslationForTest("https://example.test/subtitle.ssa"))
    }

    @Test
    fun `effective overlay position matches subtitle delay sign`() {
        assertEquals(
            900L,
            delayedAddonSubtitleOverlayPositionMs(currentPositionMs = 1_500L, subtitleDelayUs = 600_000L)
        )
        assertEquals(
            2_100L,
            delayedAddonSubtitleOverlayPositionMs(currentPositionMs = 1_500L, subtitleDelayUs = -600_000L)
        )
        assertEquals(
            0L,
            delayedAddonSubtitleOverlayPositionMs(currentPositionMs = 200L, subtitleDelayUs = 600_000L)
        )
    }

    @Test
    fun `active cue lookup returns overlapping cue groups and respects end boundary`() {
        val early = cue("early")
        val overlap = cue("overlap")
        val expired = cue("expired")
        val groups = listOf(
            TimedAddonCueGroup(startMs = 0L, endMs = 1_000L, cues = listOf(early)),
            TimedAddonCueGroup(startMs = 500L, endMs = 1_500L, cues = listOf(overlap)),
            TimedAddonCueGroup(startMs = 700L, endMs = 900L, cues = listOf(expired))
        )

        assertEquals(listOf(early, overlap, expired), activeAddonOverlayCuesAt(groups, 800L))
        assertEquals(listOf(overlap), activeAddonOverlayCuesAt(groups, 1_000L))
        assertEquals(emptyList<Cue>(), activeAddonOverlayCuesAt(groups, 1_500L))
    }

    @Test
    fun `next overlay delay wakes at next cue start boundary`() {
        val groups = listOf(
            TimedAddonCueGroup(startMs = 1_000L, endMs = 2_000L, cues = listOf(cue("a"))),
            TimedAddonCueGroup(startMs = 3_500L, endMs = 4_000L, cues = listOf(cue("b")))
        )

        assertEquals(250L, nextAddonOverlayUpdateDelayMs(groups, positionMs = 750L))
    }

    @Test
    fun `next overlay delay wakes at next cue end boundary`() {
        val groups = listOf(
            TimedAddonCueGroup(startMs = 1_000L, endMs = 2_000L, cues = listOf(cue("a")))
        )

        assertEquals(200L, nextAddonOverlayUpdateDelayMs(groups, positionMs = 1_800L))
    }

    @Test
    fun `next overlay delay falls back to bounded idle delay when no further cues exist`() {
        val groups = listOf(
            TimedAddonCueGroup(startMs = 1_000L, endMs = 2_000L, cues = listOf(cue("a")))
        )

        assertEquals(500L, nextAddonOverlayUpdateDelayMs(groups, positionMs = 5_000L))
    }

    @Test
    fun `srt parser fallback produces timed cue groups`() {
        val raw = """
            1
            00:00:01,000 --> 00:00:02,000
            Hello overlay
        """.trimIndent().toByteArray()

        val groups = parseAddonSubtitleOverlayCueGroups(
            url = "https://example.test/subtitle.srt",
            mimeType = MimeTypes.APPLICATION_SUBRIP,
            bytes = raw
        )

        assertEquals(1, groups.size)
        assertEquals(1_000L, groups.single().startMs)
        assertEquals(2_000L, groups.single().endMs)
        assertEquals("Hello overlay", groups.single().cues.single().text.toString())
    }

    @Test
    fun `source texts for translation are trimmed and deduplicated`() {
        val groups = listOf(
            TimedAddonCueGroup(startMs = 0L, endMs = 1_000L, cues = listOf(cue(" Hello "), cue("World"))),
            TimedAddonCueGroup(startMs = 1_000L, endMs = 2_000L, cues = listOf(cue("Hello")))
        )

        assertEquals(listOf("Hello", "World"), sourceTextsForTranslation(groups))
    }

    @Test
    fun `translated cue groups preserve timing and cue metadata`() {
        val sourceCue = Cue.Builder()
            .setText("Hello")
            .setPosition(0.25f)
            .build()
        val groups = listOf(
            TimedAddonCueGroup(startMs = 500L, endMs = 1_500L, cues = listOf(sourceCue))
        )

        val translated = translateTimedAddonCueGroups(groups, mapOf("Hello" to "Hallo"))

        assertEquals(500L, translated.single().startMs)
        assertEquals(1_500L, translated.single().endMs)
        assertEquals("Hallo", translated.single().cues.single().text.toString())
        assertEquals(sourceCue.position, translated.single().cues.single().position)
    }

    private fun cue(text: String): Cue {
        return Cue.Builder().setText(text).build()
    }
}
