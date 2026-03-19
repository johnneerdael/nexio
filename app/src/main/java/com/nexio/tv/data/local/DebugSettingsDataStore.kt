package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nexio.tv.debug.passthrough.TransportValidationCaptureMode
import com.nexio.tv.debug.passthrough.TransportValidationComparisonMode
import com.nexio.tv.debug.passthrough.TransportValidationSettings
import com.nexio.tv.debug.passthrough.TransportValidationSettingsStore
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
) : TransportValidationSettingsStore {
    private val dataStore = context.debugDataStore
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val accountTabEnabledKey = booleanPreferencesKey("account_tab_enabled")
    private val syncCodeFeaturesEnabledKey = booleanPreferencesKey("sync_code_features_enabled")
    private val streamDiagnosticsEnabledKey = booleanPreferencesKey("stream_diagnostics_enabled")
    private val startupPerfTelemetryEnabledKey = booleanPreferencesKey("startup_perf_telemetry_enabled")
    private val diskFirstHomeStartupEnabledKey = booleanPreferencesKey("disk_first_home_startup_enabled")
    private val diskFirstHomeStartupDefaultAppliedKey =
        booleanPreferencesKey("migration_disk_first_home_startup_default_applied")
    private val transportValidationEnabledKey =
        booleanPreferencesKey("transport_validation_enabled")
    private val transportValidationSelectedSampleIdKey =
        stringPreferencesKey("transport_validation_selected_sample_id")
    private val transportValidationComparisonModeKey =
        stringPreferencesKey("transport_validation_comparison_mode")
    private val transportValidationCaptureModeKey =
        stringPreferencesKey("transport_validation_capture_mode")
    private val transportValidationCaptureBurstCountKey =
        intPreferencesKey("transport_validation_capture_burst_count")
    private val transportValidationBinaryDumpsEnabledKey =
        booleanPreferencesKey("transport_validation_binary_dumps_enabled")
    private val transportValidationExportRequestCountKey =
        intPreferencesKey("transport_validation_export_request_count")

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

    override val transportValidationSettings: Flow<TransportValidationSettings> =
        dataStore.data.map { prefs ->
            TransportValidationSettings(
                enabled = prefs[transportValidationEnabledKey] ?: false,
                selectedSampleId = prefs[transportValidationSelectedSampleIdKey],
                comparisonMode = prefs[transportValidationComparisonModeKey]
                    ?.let(TransportValidationComparisonMode::valueOf)
                    ?: TransportValidationComparisonMode.FULL_BURST_COMPARE,
                captureMode = prefs[transportValidationCaptureModeKey]
                    ?.let(TransportValidationCaptureMode::valueOf)
                    ?: TransportValidationCaptureMode.FIRST_N_BURSTS,
                captureBurstCount = prefs[transportValidationCaptureBurstCountKey] ?: 8,
                binaryDumpsEnabled = prefs[transportValidationBinaryDumpsEnabledKey] ?: false,
                exportRequestCount = prefs[transportValidationExportRequestCountKey] ?: 0,
            )
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

    override suspend fun setTransportValidationEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[transportValidationEnabledKey] = enabled
        }
    }

    override suspend fun setTransportValidationSelectedSampleId(sampleId: String?) {
        dataStore.edit { prefs ->
            if (sampleId == null) {
                prefs.remove(transportValidationSelectedSampleIdKey)
            } else {
                prefs[transportValidationSelectedSampleIdKey] = sampleId
            }
        }
    }

    override suspend fun setTransportValidationComparisonMode(
        mode: TransportValidationComparisonMode
    ) {
        dataStore.edit { prefs ->
            prefs[transportValidationComparisonModeKey] = mode.name
        }
    }

    override suspend fun setTransportValidationCaptureMode(
        mode: TransportValidationCaptureMode
    ) {
        dataStore.edit { prefs ->
            prefs[transportValidationCaptureModeKey] = mode.name
        }
    }

    override suspend fun setTransportValidationCaptureBurstCount(count: Int) {
        dataStore.edit { prefs ->
            prefs[transportValidationCaptureBurstCountKey] = count
        }
    }

    override suspend fun setTransportValidationBinaryDumpsEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[transportValidationBinaryDumpsEnabledKey] = enabled
        }
    }

    override suspend fun incrementTransportValidationExportRequestCount() {
        dataStore.edit { prefs ->
            val current = prefs[transportValidationExportRequestCountKey] ?: 0
            prefs[transportValidationExportRequestCountKey] = current + 1
        }
    }
}
