package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.debugDataStore: DataStore<Preferences> by preferencesDataStore(name = "debug_settings")

@Singleton
class DebugSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.debugDataStore

    private val accountTabEnabledKey = booleanPreferencesKey("account_tab_enabled")
    private val syncCodeFeaturesEnabledKey = booleanPreferencesKey("sync_code_features_enabled")
    private val streamDiagnosticsEnabledKey = booleanPreferencesKey("stream_diagnostics_enabled")

    val accountTabEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[accountTabEnabledKey] ?: false
    }

    val syncCodeFeaturesEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[syncCodeFeaturesEnabledKey] ?: false
    }

    val streamDiagnosticsEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[streamDiagnosticsEnabledKey] ?: false
    }

    suspend fun setAccountTabEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[accountTabEnabledKey] = enabled
        }
    }

    suspend fun setSyncCodeFeaturesEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[syncCodeFeaturesEnabledKey] = enabled
        }
    }

    suspend fun setStreamDiagnosticsEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[streamDiagnosticsEnabledKey] = enabled
        }
    }
}
