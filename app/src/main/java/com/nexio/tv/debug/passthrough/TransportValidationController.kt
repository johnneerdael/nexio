package com.nexio.tv.debug.passthrough

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Singleton
class TransportValidationController @Inject constructor(
    private val settingsStore: TransportValidationSettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(TransportValidationControllerState())
    val state: StateFlow<TransportValidationControllerState> = _state.asStateFlow()

    init {
        scope.launch {
            settingsStore.transportValidationSettings.collectLatest { settings ->
                _state.update { it.copy(settings = settings) }
            }
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        settingsStore.setTransportValidationEnabled(enabled)
    }

    suspend fun selectSample(sampleId: String?) {
        settingsStore.setTransportValidationSelectedSampleId(sampleId)
    }

    suspend fun setComparisonMode(mode: TransportValidationComparisonMode) {
        settingsStore.setTransportValidationComparisonMode(mode)
    }

    suspend fun setCaptureMode(mode: TransportValidationCaptureMode) {
        settingsStore.setTransportValidationCaptureMode(mode)
    }

    suspend fun setCaptureBurstCount(count: Int) {
        settingsStore.setTransportValidationCaptureBurstCount(count)
    }

    suspend fun setBinaryDumpsEnabled(enabled: Boolean) {
        settingsStore.setTransportValidationBinaryDumpsEnabled(enabled)
    }

    suspend fun requestExport() {
        settingsStore.incrementTransportValidationExportRequestCount()
    }
}

data class TransportValidationControllerState(
    val settings: TransportValidationSettings = TransportValidationSettings(),
)
