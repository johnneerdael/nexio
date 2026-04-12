package com.nexio.tv.ui.screens.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import com.nexio.tv.data.repository.SubtitleTranslationService
import com.nexio.tv.domain.model.SubtitleTranslationDefaults
import com.nexio.tv.domain.model.SubtitleTranslationProvider
import com.nexio.tv.domain.model.SubtitleTranslationSettings
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.EmptyCoroutineContext

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

    @Test
    fun `built in translator configuration token changes when provider configuration changes`() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
            .build()
        var settings = SubtitleTranslationSettings(
            enabled = true,
            provider = SubtitleTranslationProvider.OPENAI,
            apiKey = "same-key",
            model = SubtitleTranslationDefaults.OPENAI_MODEL,
            baseUrl = SubtitleTranslationDefaults.OPENAI_BASE_URL
        )
        val translator = BuiltInSubtitleCueTranslator(
            scope = CoroutineScope(EmptyCoroutineContext),
            translationService = mockk<SubtitleTranslationService>(relaxed = true),
            isEnabledProvider = { true },
            settingsProvider = { settings },
            targetLanguageProvider = { "nl" },
            onTranslatingChanged = {},
            onTranslationError = {}
        )

        val openAiToken = translator.getConfigurationToken(format)

        settings = settings.copy(
            provider = SubtitleTranslationProvider.ANTHROPIC,
            model = SubtitleTranslationDefaults.ANTHROPIC_MODEL,
            baseUrl = SubtitleTranslationDefaults.ANTHROPIC_BASE_URL
        )

        assertNotEquals(openAiToken, translator.getConfigurationToken(format))

        settings = settings.copy(apiKey = "")

        assertNull(translator.getConfigurationToken(format))
    }

    @Test
    fun `built in translator configuration token is null when native translator is disabled`() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
            .build()
        val translator = BuiltInSubtitleCueTranslator(
            scope = CoroutineScope(EmptyCoroutineContext),
            translationService = mockk<SubtitleTranslationService>(relaxed = true),
            isEnabledProvider = { false },
            settingsProvider = {
                SubtitleTranslationSettings(
                    enabled = true,
                    apiKey = "configured-key"
                )
            },
            targetLanguageProvider = { "nl" },
            onTranslatingChanged = {},
            onTranslationError = {}
        )

        assertNull(translator.getConfigurationToken(format))
    }

    @Test
    fun `built in cue translation cache key changes by provider configuration without exposing api key`() {
        val openAiSettings = SubtitleTranslationSettings(
            enabled = true,
            provider = SubtitleTranslationProvider.OPENAI,
            apiKey = "secret-key",
            model = SubtitleTranslationDefaults.OPENAI_MODEL,
            baseUrl = SubtitleTranslationDefaults.OPENAI_BASE_URL
        )
        val openAiKey = builtInCueTranslationCacheKey(
            text = "Hello",
            targetLanguage = "nl",
            settings = openAiSettings
        )
        val anthropicKey = builtInCueTranslationCacheKey(
            text = "Hello",
            targetLanguage = "nl",
            settings = openAiSettings.copy(
                provider = SubtitleTranslationProvider.ANTHROPIC,
                model = SubtitleTranslationDefaults.ANTHROPIC_MODEL,
                baseUrl = SubtitleTranslationDefaults.ANTHROPIC_BASE_URL
            )
        )

        assertNotEquals(openAiKey, anthropicKey)
        assertFalse(openAiKey.contains("secret-key"))
    }
}
