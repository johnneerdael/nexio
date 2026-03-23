package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nexio.tv.domain.model.ImdbSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.imdbSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "imdb_settings"
)

@Singleton
class ImdbSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.imdbSettingsDataStore
    private fun store() = dataStore

    private val enabledKey = booleanPreferencesKey("imdb_enabled")
    private val baseUrlKey = stringPreferencesKey("imdb_base_url")
    private val apiKeyKey = stringPreferencesKey("imdb_api_key")

    val settings: Flow<ImdbSettings> = dataStore.data.map { prefs ->
        ImdbSettings(
            enabled = prefs[enabledKey] ?: false,
            baseUrl = prefs[baseUrlKey] ?: "",
            apiKey = prefs[apiKeyKey] ?: ""
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        store().edit { it[enabledKey] = enabled }
    }

    suspend fun setBaseUrl(baseUrl: String) {
        store().edit { it[baseUrlKey] = normalizeCustomImdbBaseUrl(baseUrl) }
    }
    suspend fun setApiKey(apiKey: String) {
        store().edit { it[apiKeyKey] = apiKey.trim() }
    }
}

private fun normalizeCustomImdbBaseUrl(baseUrl: String): String {
    return baseUrl.trim().trimEnd('/')
}
