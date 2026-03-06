package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nexio.tv.domain.model.GeminiSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.geminiSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "gemini_settings"
)

@Singleton
class GeminiSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.geminiSettingsDataStore
    private fun store() = dataStore

    private val enabledKey = booleanPreferencesKey("gemini_enabled")
    private val apiKeyKey = stringPreferencesKey("gemini_api_key")

    val settings: Flow<GeminiSettings> = dataStore.data.map { prefs ->
        GeminiSettings(
            enabled = prefs[enabledKey] ?: false,
            apiKey = prefs[apiKeyKey] ?: ""
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
}
