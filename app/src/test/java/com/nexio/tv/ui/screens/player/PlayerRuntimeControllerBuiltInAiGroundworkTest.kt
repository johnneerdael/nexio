package com.nexio.tv.ui.screens.player

import androidx.media3.common.text.Cue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerRuntimeControllerBuiltInAiGroundworkTest {

    @Test
    fun `built in cue translation returns empty overlay when any source text is missing`() {
        val hello = Cue.Builder().setText("Hello").build()
        val world = Cue.Builder().setText("World").build()

        val translated = translateBuiltInCuesWhenAllTextsReady(
            cues = listOf(hello, world),
            translatedTexts = mapOf("Hello" to "Hallo")
        )

        assertTrue(translated.isEmpty())
    }

    @Test
    fun `built in cue translation uses translated text for every cue when all are ready`() {
        val hello = Cue.Builder().setText(" Hello ").build()
        val world = Cue.Builder().setText("World").build()

        val translated = translateBuiltInCuesWhenAllTextsReady(
            cues = listOf(hello, world),
            translatedTexts = mapOf(
                "Hello" to "Hallo",
                "World" to "Wereld"
            )
        )

        assertEquals(listOf("Hallo", "Wereld"), translated.map { it.text.toString() })
    }
}
