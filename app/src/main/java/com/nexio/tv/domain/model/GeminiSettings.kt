package com.nexio.tv.domain.model

enum class SubtitleTranslationProvider {
    OPENAI,
    ANTHROPIC,
    GEMINI
}

object SubtitleTranslationDefaults {
    const val OPENAI_MODEL = "gpt-5-nano"
    const val OPENAI_BASE_URL = "https://api.openai.com/v1"
    const val ANTHROPIC_MODEL = "claude-haiku-4-5"
    const val ANTHROPIC_BASE_URL = "https://api.anthropic.com/v1"
    const val GEMINI_MODEL = "gemini-2.5-flash"
    const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
}

data class SubtitleTranslationSettings(
    val enabled: Boolean = false,
    val provider: SubtitleTranslationProvider = SubtitleTranslationProvider.OPENAI,
    val apiKey: String = "",
    val model: String = SubtitleTranslationDefaults.OPENAI_MODEL,
    val baseUrl: String = SubtitleTranslationDefaults.OPENAI_BASE_URL
)

typealias GeminiSettings = SubtitleTranslationSettings
