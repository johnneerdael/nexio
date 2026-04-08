package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.theIntroDbSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "theintrodb_settings"
)

data class TheIntroDbSettings(
    val enabled: Boolean = true,
    val apiKey: String = "",
    val showIntroButton: Boolean = true,
    val showRecapButton: Boolean = true,
    val showCreditsButton: Boolean = true,
    val showPreviewButton: Boolean = true
)

@Singleton
class TheIntroDbSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.theIntroDbSettingsDataStore
    private fun store() = dataStore

    private val enabledKey = booleanPreferencesKey("theintrodb_enabled")
    private val apiKeyKey = stringPreferencesKey("theintrodb_api_key")
    private val showIntroButtonKey = booleanPreferencesKey("theintrodb_show_intro_button")
    private val showRecapButtonKey = booleanPreferencesKey("theintrodb_show_recap_button")
    private val showCreditsButtonKey = booleanPreferencesKey("theintrodb_show_credits_button")
    private val showPreviewButtonKey = booleanPreferencesKey("theintrodb_show_preview_button")

    val settings: Flow<TheIntroDbSettings> = dataStore.data.map { prefs ->
        TheIntroDbSettings(
            enabled = prefs[enabledKey] ?: true,
            apiKey = prefs[apiKeyKey] ?: "",
            showIntroButton = prefs[showIntroButtonKey] ?: true,
            showRecapButton = prefs[showRecapButtonKey] ?: true,
            showCreditsButton = prefs[showCreditsButtonKey] ?: true,
            showPreviewButton = prefs[showPreviewButtonKey] ?: true
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        store().edit { prefs -> prefs[enabledKey] = enabled }
    }

    suspend fun setApiKey(apiKey: String) {
        store().edit { prefs -> prefs[apiKeyKey] = apiKey.trim() }
    }

    suspend fun setShowIntroButton(enabled: Boolean) {
        store().edit { prefs -> prefs[showIntroButtonKey] = enabled }
    }

    suspend fun setShowRecapButton(enabled: Boolean) {
        store().edit { prefs -> prefs[showRecapButtonKey] = enabled }
    }

    suspend fun setShowCreditsButton(enabled: Boolean) {
        store().edit { prefs -> prefs[showCreditsButtonKey] = enabled }
    }

    suspend fun setShowPreviewButton(enabled: Boolean) {
        store().edit { prefs -> prefs[showPreviewButtonKey] = enabled }
    }
}
