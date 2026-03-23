package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.imdbSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "imdb_settings"
)

data class ImdbSettings(
    val enabled: Boolean = false,
    val apiKey: String = ""
)

@Singleton
class ImdbSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.imdbSettingsDataStore
    private fun store() = dataStore

    private val enabledKey = booleanPreferencesKey("imdb_enabled")
    private val apiKeyKey = stringPreferencesKey("imdb_api_key")

    val settings: Flow<ImdbSettings> = dataStore.data.map { prefs ->
        ImdbSettings(
            enabled = prefs[enabledKey] ?: false,
            apiKey = prefs[apiKeyKey] ?: ""
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        store().edit { it[enabledKey] = enabled }
    }

    suspend fun setApiKey(apiKey: String) {
        store().edit { it[apiKeyKey] = apiKey.trim() }
    }
}
