package com.nexio.tv.data.local

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nexio.tv.domain.model.SubtitleTranslationDefaults
import com.nexio.tv.domain.model.SubtitleTranslationProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleTranslationSettingsDataStoreTest {
    @Test
    fun defaultsUseOpenRouterFreeModelAndEndpoint() {
        val settings = defaultSubtitleTranslationSettings()

        assertEquals(SubtitleTranslationProvider.OPENAI, settings.provider)
        assertEquals(SubtitleTranslationDefaults.OPENAI_MODEL, settings.model)
        assertEquals("openrouter/free", settings.model)
        assertEquals("https://openrouter.ai/api/v1", settings.baseUrl)
    }

    @Test
    fun defaultsForAnthropicUseHaikuAliasAndNativeEndpoint() {
        val settings = defaultSubtitleTranslationSettings(SubtitleTranslationProvider.ANTHROPIC)

        assertEquals("claude-haiku-4-5", settings.model)
        assertEquals("https://api.anthropic.com/v1", settings.baseUrl)
    }

    @Test
    fun defaultsForDashScopeUseQwenMtAndIntlEndpoint() {
        val settings = defaultSubtitleTranslationSettings(SubtitleTranslationProvider.DASHSCOPE)

        assertEquals("qwen-mt-flash", settings.model)
        assertEquals("https://dashscope-intl.aliyuncs.com/api/v1", settings.baseUrl)
    }

    @Test
    fun legacyGeminiKeyWithoutStoredProviderMigratesToGeminiProvider() {
        val settings = normalizeSubtitleTranslationSettings(
            enabled = true,
            providerName = "",
            apiKey = "legacy-gemini-key",
            model = "",
            baseUrl = ""
        )

        assertEquals(SubtitleTranslationProvider.GEMINI, settings.provider)
        assertEquals("gemini-2.5-flash", settings.model)
        assertEquals("https://generativelanguage.googleapis.com/v1beta", settings.baseUrl)
    }

    @Test
    fun openRouterEndpointKeepsCustomModelBaseUrlAndSubRipPromptFlag() {
        val settings = normalizeSubtitleTranslationSettings(
            enabled = true,
            providerName = "OPENAI",
            apiKey = "openrouter-key",
            model = "openai/gpt-5.2",
            baseUrl = "https://openrouter.ai/api/v1/",
            subRipSystemPromptEnabled = true
        )

        assertEquals(SubtitleTranslationProvider.OPENAI, settings.provider)
        assertEquals("openai/gpt-5.2", settings.model)
        assertEquals("https://openrouter.ai/api/v1", settings.baseUrl)
        assertEquals(true, settings.subRipSystemPromptEnabled)
    }

    @Test
    fun missingSubRipSystemPromptPreferenceDefaultsDisabled() {
        val settings = normalizeSubtitleTranslationSettings(
            enabled = true,
            providerName = "OPENAI",
            apiKey = "openrouter-key",
            model = "openai/gpt-5.2",
            baseUrl = "https://openrouter.ai/api/v1"
        )

        assertEquals(false, settings.subRipSystemPromptEnabled)
    }

    @Test
    fun invalidStoredProviderFallsBackToOpenAiNotGemini() {
        val settings = normalizeSubtitleTranslationSettings(
            enabled = true,
            providerName = "BROKEN_PROVIDER",
            apiKey = "existing-key",
            model = "",
            baseUrl = ""
        )

        assertEquals(SubtitleTranslationProvider.OPENAI, settings.provider)
        assertEquals("openrouter/free", settings.model)
        assertEquals("https://openrouter.ai/api/v1", settings.baseUrl)
    }

    @Test
    fun switchingProviderResetsStoredModelAndBaseUrlToProviderDefaults() {
        val providerKey = stringPreferencesKey("subtitle_translation_provider")
        val modelKey = stringPreferencesKey("subtitle_translation_model")
        val baseUrlKey = stringPreferencesKey("subtitle_translation_base_url")
        val subRipSystemPromptKey = booleanPreferencesKey("subtitle_translation_subrip_system_prompt_enabled")
        val prefs = mutablePreferencesOf(
            providerKey to "OPENAI",
            modelKey to "openai/gpt-5.2",
            baseUrlKey to "https://openrouter.ai/api/v1",
            subRipSystemPromptKey to true
        )

        applySubtitleTranslationProviderDefaults(prefs, SubtitleTranslationProvider.DASHSCOPE)

        assertEquals("DASHSCOPE", prefs[providerKey])
        assertEquals(SubtitleTranslationDefaults.DASHSCOPE_MODEL, prefs[modelKey])
        assertEquals(SubtitleTranslationDefaults.DASHSCOPE_BASE_URL, prefs[baseUrlKey])
        assertEquals(true, prefs[subRipSystemPromptKey])
    }
}
