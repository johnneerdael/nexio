package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.tv.domain.model.MDBListSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.mdbListSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "mdblist_settings")

@Singleton
class MDBListSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.mdbListSettingsDataStore

    private val enabledKey = booleanPreferencesKey("mdblist_enabled")
    private val apiKeyKey = stringPreferencesKey("mdblist_api_key")
    private val showTraktKey = booleanPreferencesKey("mdblist_show_trakt")
    private val showImdbKey = booleanPreferencesKey("mdblist_show_imdb")
    private val showTmdbKey = booleanPreferencesKey("mdblist_show_tmdb")
    private val showLetterboxdKey = booleanPreferencesKey("mdblist_show_letterboxd")
    private val showTomatoesKey = booleanPreferencesKey("mdblist_show_tomatoes")
    private val showAudienceKey = booleanPreferencesKey("mdblist_show_audience")
    private val showMetacriticKey = booleanPreferencesKey("mdblist_show_metacritic")

    val settings: Flow<MDBListSettings> = dataStore.data.map { prefs ->
        MDBListSettings(
            enabled = prefs[enabledKey] ?: false,
            apiKey = prefs[apiKeyKey] ?: "",
            showTrakt = prefs[showTraktKey] ?: true,
            showImdb = prefs[showImdbKey] ?: true,
            showTmdb = prefs[showTmdbKey] ?: true,
            showLetterboxd = prefs[showLetterboxdKey] ?: true,
            showTomatoes = prefs[showTomatoesKey] ?: true,
            showAudience = prefs[showAudienceKey] ?: true,
            showMetacritic = prefs[showMetacriticKey] ?: true
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[enabledKey] = enabled }
    }

    suspend fun setApiKey(apiKey: String) {
        dataStore.edit { it[apiKeyKey] = apiKey.trim() }
    }

    suspend fun setShowTrakt(enabled: Boolean) {
        dataStore.edit { it[showTraktKey] = enabled }
    }

    suspend fun setShowImdb(enabled: Boolean) {
        dataStore.edit { it[showImdbKey] = enabled }
    }

    suspend fun setShowTmdb(enabled: Boolean) {
        dataStore.edit { it[showTmdbKey] = enabled }
    }

    suspend fun setShowLetterboxd(enabled: Boolean) {
        dataStore.edit { it[showLetterboxdKey] = enabled }
    }

    suspend fun setShowTomatoes(enabled: Boolean) {
        dataStore.edit { it[showTomatoesKey] = enabled }
    }

    suspend fun setShowAudience(enabled: Boolean) {
        dataStore.edit { it[showAudienceKey] = enabled }
    }

    suspend fun setShowMetacritic(enabled: Boolean) {
        dataStore.edit { it[showMetacriticKey] = enabled }
    }
}
