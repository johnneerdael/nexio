package com.nexio.tv.debug.passthrough

import kotlinx.coroutines.flow.Flow

data class TransportValidationSettings(
    val enabled: Boolean = false,
    val selectedSampleId: String? = null,
    val comparisonMode: TransportValidationComparisonMode =
        TransportValidationComparisonMode.FULL_BURST_COMPARE,
    val captureMode: TransportValidationCaptureMode =
        TransportValidationCaptureMode.FIRST_N_BURSTS,
    val captureBurstCount: Int = 8,
    val binaryDumpsEnabled: Boolean = false,
    val runtimeValidationEnabled: Boolean = true,
    val runtimeStartupTimeoutMs: Int =
        TransportValidationRuntimeDefaults.thresholds.startupTimeoutMs.toInt(),
    val runtimeObservationWindowMs: Int =
        TransportValidationRuntimeDefaults.thresholds.observationWindowMs.toInt(),
    val exportRequestCount: Int = 0,
)

enum class TransportValidationComparisonMode {
    PREAMBLE_ONLY,
    FULL_BURST_COMPARE,
}

interface TransportValidationSettingsStore {
    val transportValidationSettings: Flow<TransportValidationSettings>

    suspend fun setTransportValidationEnabled(enabled: Boolean)

    suspend fun setTransportValidationSelectedSampleId(sampleId: String?)

    suspend fun setTransportValidationComparisonMode(mode: TransportValidationComparisonMode)

    suspend fun setTransportValidationCaptureMode(mode: TransportValidationCaptureMode)

    suspend fun setTransportValidationCaptureBurstCount(count: Int)

    suspend fun setTransportValidationBinaryDumpsEnabled(enabled: Boolean)

    suspend fun setTransportValidationRuntimeValidationEnabled(enabled: Boolean)

    suspend fun setTransportValidationRuntimeStartupTimeoutMs(timeoutMs: Int)

    suspend fun setTransportValidationRuntimeObservationWindowMs(observationWindowMs: Int)

    suspend fun incrementTransportValidationExportRequestCount()
}
