package com.nexio.tv.data.repository

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the system-prompt builders include explicit source-language
 * instructions. Source-language plumbing already exists end-to-end; the
 * change here is making the prompt body itself describe the translation
 * direction so the LLM behaves consistently for `lang=und` embedded subs.
 *
 * The prompt builders are private to [SubtitleTranslationService]. These
 * tests exercise them via package-internal companion accessors that mirror
 * the production prompt body (see *ForTest helpers).
 */
class SubtitleTranslationServicePromptTest {

    @Test
    fun `buildTranslationSystemPrompt instructs auto-detection when source is auto`() {
        val prompt = SubtitleTranslationService.buildTranslationSystemPromptForTest(
            targetLanguageCode = "nl",
            targetLanguageName = "Dutch",
            sourceLanguageName = "auto"
        )

        val lower = prompt.lowercase()
        assertTrue("expected detect/automatically in: $prompt",
            lower.contains("detect") && lower.contains("automatically")
        )
        assertTrue("expected target name in: $prompt", prompt.contains("Dutch"))
    }

    @Test
    fun `buildTranslationSystemPrompt names source explicitly when known`() {
        val prompt = SubtitleTranslationService.buildTranslationSystemPromptForTest(
            targetLanguageCode = "nl",
            targetLanguageName = "Dutch",
            sourceLanguageName = "Polish"
        )

        assertTrue("expected source name in: $prompt", prompt.contains("Polish"))
        assertTrue("expected target name in: $prompt", prompt.contains("Dutch"))
    }

    @Test
    fun `buildRawSubRipSystemPrompt instructs auto-detection when source is auto`() {
        val prompt = SubtitleTranslationService.buildRawSubRipSystemPromptForTest(
            targetLanguageName = "Dutch",
            sourceLanguageName = "auto"
        )

        val lower = prompt.lowercase()
        assertTrue("expected detect/automatically in: $prompt",
            lower.contains("detect") && lower.contains("automatically")
        )
        assertTrue("expected target name in: $prompt", prompt.contains("Dutch"))
    }

    @Test
    fun `buildRawSubRipSystemPrompt names source explicitly when known`() {
        val prompt = SubtitleTranslationService.buildRawSubRipSystemPromptForTest(
            targetLanguageName = "Dutch",
            sourceLanguageName = "Polish"
        )

        assertTrue("expected source name in: $prompt", prompt.contains("Polish"))
        assertTrue("expected target name in: $prompt", prompt.contains("Dutch"))
    }

    @Test
    fun `buildRawAssSsaSystemPrompt instructs auto-detection when source is auto`() {
        val prompt = SubtitleTranslationService.buildRawAssSsaSystemPromptForTest(
            targetLanguageName = "Dutch",
            sourceLanguageName = "auto"
        )

        val lower = prompt.lowercase()
        assertTrue("expected detect/automatically in: $prompt",
            lower.contains("detect") && lower.contains("automatically")
        )
        assertTrue("expected target name in: $prompt", prompt.contains("Dutch"))
    }

    @Test
    fun `buildRawAssSsaSystemPrompt names source explicitly when known`() {
        val prompt = SubtitleTranslationService.buildRawAssSsaSystemPromptForTest(
            targetLanguageName = "Dutch",
            sourceLanguageName = "Polish"
        )

        assertTrue("expected source name in: $prompt", prompt.contains("Polish"))
        assertTrue("expected target name in: $prompt", prompt.contains("Dutch"))
    }
}
