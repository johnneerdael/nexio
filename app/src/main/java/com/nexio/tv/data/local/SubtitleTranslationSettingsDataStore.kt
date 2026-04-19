package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nexio.tv.domain.model.SubtitleTranslationDefaults
import com.nexio.tv.domain.model.SubtitleTranslationProvider
import com.nexio.tv.domain.model.SubtitleTranslationSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.subtitleTranslationSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "gemini_settings"
)

internal fun defaultSubtitleTranslationSettings(
    provider: SubtitleTranslationProvider = SubtitleTranslationProvider.OPENAI
): SubtitleTranslationSettings {
    return when (provider) {
        SubtitleTranslationProvider.OPENAI -> SubtitleTranslationSettings(
            provider = provider,
            model = SubtitleTranslationDefaults.OPENAI_MODEL,
            baseUrl = SubtitleTranslationDefaults.OPENAI_BASE_URL
        )
        SubtitleTranslationProvider.ANTHROPIC -> SubtitleTranslationSettings(
            provider = provider,
            model = SubtitleTranslationDefaults.ANTHROPIC_MODEL,
            baseUrl = SubtitleTranslationDefaults.ANTHROPIC_BASE_URL
        )
        SubtitleTranslationProvider.GEMINI -> SubtitleTranslationSettings(
            provider = provider,
            model = SubtitleTranslationDefaults.GEMINI_MODEL,
            baseUrl = SubtitleTranslationDefaults.GEMINI_BASE_URL
        )
        SubtitleTranslationProvider.DASHSCOPE -> SubtitleTranslationSettings(
            provider = provider,
            model = SubtitleTranslationDefaults.DASHSCOPE_MODEL,
            baseUrl = SubtitleTranslationDefaults.DASHSCOPE_BASE_URL
        )
    }
}

internal fun normalizeSubtitleTranslationSettings(
    enabled: Boolean,
    providerName: String?,
    apiKey: String?,
    model: String?,
    baseUrl: String?,
    assSsaSystemPromptEnabled: Boolean = false
): SubtitleTranslationSettings {
    val trimmedApiKey = apiKey?.trim().orEmpty()
    val trimmedProvider = providerName?.trim().orEmpty()
    val provider = if (trimmedProvider.isBlank()) {
        if (trimmedApiKey.isNotBlank()) SubtitleTranslationProvider.GEMINI else SubtitleTranslationProvider.OPENAI
    } else {
        runCatching { SubtitleTranslationProvider.valueOf(trimmedProvider.uppercase()) }
            .getOrDefault(SubtitleTranslationProvider.OPENAI)
    }
    val defaults = defaultSubtitleTranslationSettings(provider)
    return defaults.copy(
        enabled = enabled,
        apiKey = trimmedApiKey,
        model = model?.trim()?.takeIf(String::isNotBlank) ?: defaults.model,
        baseUrl = baseUrl?.trim()?.trimEnd('/')?.takeIf(String::isNotBlank) ?: defaults.baseUrl,
        assSsaSystemPromptEnabled = assSsaSystemPromptEnabled
    )
}

internal fun applySubtitleTranslationProviderDefaults(
    prefs: MutablePreferences,
    provider: SubtitleTranslationProvider
) {
    val providerDefaults = defaultSubtitleTranslationSettings(provider)
    prefs[stringPreferencesKey("subtitle_translation_provider")] = provider.name
    prefs[stringPreferencesKey("subtitle_translation_model")] = providerDefaults.model
    prefs[stringPreferencesKey("subtitle_translation_base_url")] = providerDefaults.baseUrl
}

@Singleton
class SubtitleTranslationSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.subtitleTranslationSettingsDataStore
    private fun store() = dataStore

    private val enabledKey = booleanPreferencesKey("gemini_enabled")
    private val apiKeyKey = stringPreferencesKey("gemini_api_key")
    private val providerKey = stringPreferencesKey("subtitle_translation_provider")
    private val modelKey = stringPreferencesKey("subtitle_translation_model")
    private val baseUrlKey = stringPreferencesKey("subtitle_translation_base_url")
    private val assSsaSystemPromptEnabledKey = booleanPreferencesKey("subtitle_translation_ass_ssa_system_prompt_enabled")

    val settings: Flow<SubtitleTranslationSettings> = dataStore.data.map { prefs ->
        normalizeSubtitleTranslationSettings(
            enabled = prefs[enabledKey] ?: false,
            providerName = prefs[providerKey],
            apiKey = prefs[apiKeyKey],
            model = prefs[modelKey],
            baseUrl = prefs[baseUrlKey],
            assSsaSystemPromptEnabled = prefs[assSsaSystemPromptEnabledKey] ?: false
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[enabledKey] = enabled
        }
    }

    suspend fun setApiKey(apiKey: String) {
        store().edit { prefs ->
            prefs[apiKeyKey] = apiKey.trim()
        }
    }

    suspend fun setProvider(provider: SubtitleTranslationProvider) {
        store().edit { prefs ->
            applySubtitleTranslationProviderDefaults(prefs, provider)
        }
    }

    suspend fun setModel(model: String) {
        store().edit { prefs ->
            prefs[modelKey] = model.trim()
        }
    }

    suspend fun setBaseUrl(baseUrl: String) {
        store().edit { prefs ->
            prefs[baseUrlKey] = baseUrl.trim()
        }
    }

    suspend fun setAssSsaSystemPromptEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[assSsaSystemPromptEnabledKey] = enabled
        }
    }

    suspend fun saveSyncedPublicSettings(
        enabled: Boolean,
        provider: SubtitleTranslationProvider,
        model: String,
        baseUrl: String
    ) {
        store().edit { prefs ->
            prefs[enabledKey] = enabled
            prefs[providerKey] = provider.name
            prefs[modelKey] = model.trim()
            prefs[baseUrlKey] = baseUrl.trim()
        }
    }

    suspend fun saveConfiguration(
        provider: SubtitleTranslationProvider,
        apiKey: String,
        model: String,
        baseUrl: String
    ) {
        store().edit { prefs ->
            prefs[providerKey] = provider.name
            prefs[apiKeyKey] = apiKey.trim()
            prefs[modelKey] = model.trim()
            prefs[baseUrlKey] = baseUrl.trim()
        }
    }
}

typealias GeminiSettingsDataStore = SubtitleTranslationSettingsDataStore
