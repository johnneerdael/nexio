package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private val Context.debugDataStore: DataStore<Preferences> by preferencesDataStore(name = "debug_settings")

@Singleton
class DebugSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.debugDataStore
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val accountTabEnabledKey = booleanPreferencesKey("account_tab_enabled")
    private val syncCodeFeaturesEnabledKey = booleanPreferencesKey("sync_code_features_enabled")
    private val streamDiagnosticsEnabledKey = booleanPreferencesKey("stream_diagnostics_enabled")
    private val startupPerfTelemetryEnabledKey = booleanPreferencesKey("startup_perf_telemetry_enabled")
    private val diskFirstHomeStartupEnabledKey = booleanPreferencesKey("disk_first_home_startup_enabled")
    private val diskFirstHomeStartupDefaultAppliedKey =
        booleanPreferencesKey("migration_disk_first_home_startup_default_applied")

    init {
        ioScope.launch {
            dataStore.edit { prefs ->
                val defaultApplied = prefs[diskFirstHomeStartupDefaultAppliedKey] ?: false
                if (!defaultApplied) {
                    prefs[diskFirstHomeStartupEnabledKey] = true
                    prefs[diskFirstHomeStartupDefaultAppliedKey] = true
                }
            }
        }
    }

    val accountTabEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[accountTabEnabledKey] ?: false
    }

    val syncCodeFeaturesEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[syncCodeFeaturesEnabledKey] ?: false
    }

    val streamDiagnosticsEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[streamDiagnosticsEnabledKey] ?: false
    }

    val startupPerfTelemetryEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[startupPerfTelemetryEnabledKey] ?: false
    }

    val diskFirstHomeStartupEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[diskFirstHomeStartupEnabledKey] ?: true
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

    suspend fun setStartupPerfTelemetryEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[startupPerfTelemetryEnabledKey] = enabled
        }
    }

    suspend fun setDiskFirstHomeStartupEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[diskFirstHomeStartupEnabledKey] = enabled
        }
    }
}
