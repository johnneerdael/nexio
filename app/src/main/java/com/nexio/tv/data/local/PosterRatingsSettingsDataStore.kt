package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nexio.tv.domain.model.PosterRatingsSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.posterRatingsSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "poster_ratings_settings"
)

@Singleton
class PosterRatingsSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.posterRatingsSettingsDataStore

    private val rpdbEnabledKey = booleanPreferencesKey("poster_ratings_rpdb_enabled")
    private val rpdbApiKeyKey = stringPreferencesKey("poster_ratings_rpdb_api_key")
    private val topPostersEnabledKey = booleanPreferencesKey("poster_ratings_top_enabled")
    private val topPostersApiKeyKey = stringPreferencesKey("poster_ratings_top_api_key")

    val settings: Flow<PosterRatingsSettings> = dataStore.data.map { prefs ->
        PosterRatingsSettings(
            rpdbEnabled = prefs[rpdbEnabledKey] ?: false,
            rpdbApiKey = prefs[rpdbApiKeyKey] ?: "",
            topPostersEnabled = prefs[topPostersEnabledKey] ?: false,
            topPostersApiKey = prefs[topPostersApiKeyKey] ?: ""
        )
    }

    suspend fun setRpdbEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[rpdbEnabledKey] = enabled
            if (enabled) {
                prefs[topPostersEnabledKey] = false
            }
        }
    }

    suspend fun setRpdbApiKey(apiKey: String) {
        dataStore.edit { it[rpdbApiKeyKey] = apiKey.trim() }
    }

    suspend fun setTopPostersEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[topPostersEnabledKey] = enabled
            if (enabled) {
                prefs[rpdbEnabledKey] = false
            }
        }
    }

    suspend fun setTopPostersApiKey(apiKey: String) {
        dataStore.edit { it[topPostersApiKeyKey] = apiKey.trim() }
    }
}

