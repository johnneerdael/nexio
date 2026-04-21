package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
    private val diskSpoolDiagnosticsEnabledKey = booleanPreferencesKey("disk_spool_diagnostics_enabled")
    private val dolbyVisionDiagnosticsEnabledKey =
        booleanPreferencesKey("dolby_vision_diagnostics_enabled")
    private val autoTranslateDiagnosticsEnabledKey =
        booleanPreferencesKey("auto_translate_diagnostics_enabled")
    private val autoTranslateUnsafeBodyLoggingEnabledKey =
        booleanPreferencesKey("auto_translate_unsafe_body_logging_enabled")
    private val diskFirstHomeStartupEnabledKey = booleanPreferencesKey("disk_first_home_startup_enabled")
    private val diskFirstHomeStartupDefaultAppliedKey =
        booleanPreferencesKey("migration_disk_first_home_startup_default_applied")
    private val searchPosterPreviewEnabledKey =
        booleanPreferencesKey("search_poster_preview_enabled")

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

    val diskSpoolDiagnosticsEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[diskSpoolDiagnosticsEnabledKey] ?: false
    }

    val dolbyVisionDiagnosticsEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[dolbyVisionDiagnosticsEnabledKey] ?: false
    }

    val autoTranslateDiagnosticsEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[autoTranslateDiagnosticsEnabledKey] ?: false
    }

    val autoTranslateUnsafeBodyLoggingEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[autoTranslateUnsafeBodyLoggingEnabledKey] ?: false
    }

    val diskFirstHomeStartupEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[diskFirstHomeStartupEnabledKey] ?: true
    }

    val searchPosterPreviewEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[searchPosterPreviewEnabledKey] ?: false
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

    suspend fun setDiskSpoolDiagnosticsEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[diskSpoolDiagnosticsEnabledKey] = enabled
        }
    }

    suspend fun setDolbyVisionDiagnosticsEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[dolbyVisionDiagnosticsEnabledKey] = enabled
        }
    }

    suspend fun setAutoTranslateDiagnosticsEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[autoTranslateDiagnosticsEnabledKey] = enabled
            if (!enabled) {
                prefs[autoTranslateUnsafeBodyLoggingEnabledKey] = false
            }
        }
    }

    suspend fun setAutoTranslateUnsafeBodyLoggingEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[autoTranslateUnsafeBodyLoggingEnabledKey] = enabled
            if (enabled) {
                prefs[autoTranslateDiagnosticsEnabledKey] = true
            }
        }
    }

    suspend fun setDiskFirstHomeStartupEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[diskFirstHomeStartupEnabledKey] = enabled
        }
    }

    suspend fun setSearchPosterPreviewEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[searchPosterPreviewEnabledKey] = enabled
        }
    }
}
