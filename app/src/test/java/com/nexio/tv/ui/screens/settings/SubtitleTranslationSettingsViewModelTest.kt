package com.nexio.tv.ui.screens.settings

import com.nexio.tv.domain.model.SubtitleTranslationProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleTranslationSettingsViewModelTest {
    @Test
    fun sameProviderSelectionPreservesCustomModelAndEndpoint() {
        val state = SubtitleTranslationSettingsUiState(
            provider = SubtitleTranslationProvider.OPENAI,
            model = "openrouter/custom-model",
            baseUrl = "https://openrouter.ai/api/v1",
            assSsaSystemPromptEnabled = true,
            subRipSystemPromptEnabled = true
        )

        val next = state.withProviderDefaults(SubtitleTranslationProvider.OPENAI)

        assertEquals(SubtitleTranslationProvider.OPENAI, next.provider)
        assertEquals("openrouter/custom-model", next.model)
        assertEquals("https://openrouter.ai/api/v1", next.baseUrl)
        assertEquals(true, next.assSsaSystemPromptEnabled)
        assertEquals(true, next.subRipSystemPromptEnabled)
    }

    @Test
    fun providerChangeResetsBlankModelAndEndpointToProviderDefaults() {
        val state = SubtitleTranslationSettingsUiState(
            provider = SubtitleTranslationProvider.OPENAI,
            model = "custom-model",
            baseUrl = "https://openrouter.ai/api/v1"
        )

        val next = state.withProviderDefaults(SubtitleTranslationProvider.ANTHROPIC)

        assertEquals(SubtitleTranslationProvider.ANTHROPIC, next.provider)
        assertEquals("claude-haiku-4-5", next.model)
        assertEquals("https://api.anthropic.com/v1", next.baseUrl)
    }

    @Test
    fun enablingRequiresApiKeyAndModel() {
        val missingApiKeyState = SubtitleTranslationSettingsUiState(
            provider = SubtitleTranslationProvider.OPENAI,
            apiKey = "",
            model = "openrouter/free",
            baseUrl = "https://openrouter.ai/api/v1"
        )
        val missingModelState = SubtitleTranslationSettingsUiState(
            provider = SubtitleTranslationProvider.OPENAI,
            apiKey = "sk-test",
            model = "",
            baseUrl = "https://openrouter.ai/api/v1"
        )

        assertEquals(SubtitleTranslationValidationError.MissingApiKey, missingApiKeyState.enablementError())
        assertEquals(SubtitleTranslationValidationError.MissingModel, missingModelState.enablementError())
    }
}
