package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.trailerSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "trailer_settings")

@Singleton
class TrailerSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.trailerSettingsDataStore

    private val enabledKey = booleanPreferencesKey("trailer_enabled")
    private val delaySecondsKey = intPreferencesKey("trailer_delay_seconds")

    val settings: Flow<TrailerSettings> = dataStore.data.map { prefs ->
        TrailerSettings(
            enabled = prefs[enabledKey] ?: false,
            delaySeconds = prefs[delaySecondsKey] ?: 7
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[enabledKey] = enabled }
    }

    suspend fun setDelaySeconds(seconds: Int) {
        dataStore.edit { it[delaySecondsKey] = seconds }
    }
}

data class TrailerSettings(
    val enabled: Boolean = false,
    val delaySeconds: Int = 7
)
