package com.nexio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.core.auth.AuthManager
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.debug.passthrough.TransportValidationCatalog
import com.nexio.tv.debug.passthrough.TransportValidationCaptureMode
import com.nexio.tv.debug.passthrough.TransportValidationComparisonMode
import com.nexio.tv.debug.passthrough.TransportValidationController
import com.nexio.tv.debug.passthrough.TransportValidationPlaybackLauncher
import com.nexio.tv.debug.passthrough.TransportValidationSampleOption
import com.nexio.tv.debug.passthrough.TransportValidationSessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DebugSettingsViewModel @Inject constructor(
    private val dataStore: DebugSettingsDataStore,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val authManager: AuthManager,
    private val transportValidationController: TransportValidationController,
    private val transportValidationCatalog: TransportValidationCatalog,
    private val transportValidationPlaybackLauncher: TransportValidationPlaybackLauncher,
    private val transportValidationSessionStore: TransportValidationSessionStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DebugSettingsUiState())
    val uiState: StateFlow<DebugSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            dataStore.accountTabEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(accountTabEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            dataStore.syncCodeFeaturesEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(syncCodeFeaturesEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            playerSettingsDataStore.playerSettings.collectLatest { settings ->
                _uiState.update {
                    it.copy(
                        dv5ToneMapToSdrEnabled = settings.experimentalDv5ToneMapToSdrEnabled,
                        dv5HardwareToneMapToSdrEnabled =
                            settings.experimentalDv5HardwareToneMapToSdrEnabled,
                        dv5HardwareToneMapCpuFallbackEnabled =
                            settings.experimentalDv5HardwareToneMapCpuFallbackEnabled
                    )
                }
            }
        }
        viewModelScope.launch {
            transportValidationController.state.collectLatest { controllerState ->
                _uiState.update {
                    it.copy(
                        transportValidationEnabled = controllerState.settings.enabled,
                        transportValidationSelectedSampleId =
                            controllerState.settings.selectedSampleId,
                        transportValidationComparisonMode =
                            controllerState.settings.comparisonMode,
                        transportValidationCaptureMode = controllerState.settings.captureMode,
                        transportValidationCaptureBurstCount =
                            controllerState.settings.captureBurstCount,
                        transportValidationBinaryDumpsEnabled =
                            controllerState.settings.binaryDumpsEnabled,
                        transportValidationExportRequestCount =
                            controllerState.settings.exportRequestCount,
                    )
                }
            }
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    transportValidationAvailableSamples = transportValidationCatalog.availableSamples()
                )
            }
        }
    }

    fun onEvent(event: DebugSettingsEvent) {
        when (event) {
            is DebugSettingsEvent.ToggleAccountTab -> {
                viewModelScope.launch { dataStore.setAccountTabEnabled(event.enabled) }
            }
            is DebugSettingsEvent.ToggleSyncCodeFeatures -> {
                viewModelScope.launch { dataStore.setSyncCodeFeaturesEnabled(event.enabled) }
            }
            is DebugSettingsEvent.SignIn -> {
                viewModelScope.launch {
                    _uiState.update { it.copy(signInLoading = true, signInResult = null) }
                    val result = authManager.signInWithEmail(event.email, event.password)
                    _uiState.update {
                        it.copy(
                            signInLoading = false,
                            signInResult = if (result.isSuccess) "Signed in successfully" else "Failed: ${result.exceptionOrNull()?.message}"
                        )
                    }
                }
            }
            is DebugSettingsEvent.ToggleDv5ToneMapToSdr -> {
                viewModelScope.launch {
                    playerSettingsDataStore.setExperimentalDv5ToneMapToSdrEnabled(event.enabled)
                }
            }
            is DebugSettingsEvent.ToggleDv5HardwareToneMapToSdr -> {
                viewModelScope.launch {
                    playerSettingsDataStore
                        .setExperimentalDv5HardwareToneMapToSdrEnabled(event.enabled)
                    if (!event.enabled) {
                        playerSettingsDataStore
                            .setExperimentalDv5HardwareToneMapCpuFallbackEnabled(false)
                    }
                }
            }
            is DebugSettingsEvent.ToggleDv5HardwareToneMapCpuFallback -> {
                viewModelScope.launch {
                    playerSettingsDataStore
                        .setExperimentalDv5HardwareToneMapCpuFallbackEnabled(event.enabled)
                }
            }
            is DebugSettingsEvent.ToggleTransportValidationEnabled -> {
                viewModelScope.launch {
                    transportValidationController.setEnabled(event.enabled)
                }
            }
            is DebugSettingsEvent.SelectTransportValidationSample -> {
                viewModelScope.launch {
                    transportValidationController.selectSample(event.sampleId)
                }
            }
            is DebugSettingsEvent.SelectTransportValidationComparisonMode -> {
                viewModelScope.launch {
                    transportValidationController.setComparisonMode(event.mode)
                }
            }
            is DebugSettingsEvent.SelectTransportValidationCaptureMode -> {
                viewModelScope.launch {
                    transportValidationController.setCaptureMode(event.mode)
                }
            }
            is DebugSettingsEvent.SetTransportValidationBurstCount -> {
                viewModelScope.launch {
                    transportValidationController.setCaptureBurstCount(event.count)
                }
            }
            is DebugSettingsEvent.ToggleTransportValidationBinaryDumps -> {
                viewModelScope.launch {
                    transportValidationController.setBinaryDumpsEnabled(event.enabled)
                }
            }
            DebugSettingsEvent.RequestTransportValidationExport -> {
                viewModelScope.launch {
                    transportValidationController.requestExport()
                    transportValidationSessionStore.exportCurrentSession()
                }
            }
            DebugSettingsEvent.AdvanceTransportValidationSample -> {
                val samples = uiState.value.transportValidationAvailableSamples
                if (samples.isEmpty()) return
                val currentIndex =
                    samples.indexOfFirst { it.id == uiState.value.transportValidationSelectedSampleId }
                val next = samples[(currentIndex + 1).floorMod(samples.size)]
                viewModelScope.launch {
                    transportValidationController.selectSample(next.id)
                }
            }
            DebugSettingsEvent.AdvanceTransportValidationComparisonMode -> {
                val modes = TransportValidationComparisonMode.entries
                val currentIndex = modes.indexOf(uiState.value.transportValidationComparisonMode)
                val next = modes[(currentIndex + 1).floorMod(modes.size)]
                viewModelScope.launch {
                    transportValidationController.setComparisonMode(next)
                }
            }
            DebugSettingsEvent.AdvanceTransportValidationCaptureMode -> {
                val modes = TransportValidationCaptureMode.entries
                val currentIndex = modes.indexOf(uiState.value.transportValidationCaptureMode)
                val next = modes[(currentIndex + 1).floorMod(modes.size)]
                viewModelScope.launch {
                    transportValidationController.setCaptureMode(next)
                }
            }
            DebugSettingsEvent.AdvanceTransportValidationBurstCount -> {
                val allowedCounts = listOf(1, 4, 8, 16, 32)
                val currentIndex =
                    allowedCounts.indexOf(uiState.value.transportValidationCaptureBurstCount)
                val next = allowedCounts[(currentIndex + 1).floorMod(allowedCounts.size)]
                viewModelScope.launch {
                    transportValidationController.setCaptureBurstCount(next)
                }
            }
            DebugSettingsEvent.StartTransportValidationPlayback -> {
                val sampleId = uiState.value.transportValidationSelectedSampleId ?: return
                transportValidationPlaybackLauncher.launchSelectedSample(sampleId)
            }
            DebugSettingsEvent.StopTransportValidationPlayback -> {
                transportValidationPlaybackLauncher.stopPlayback()
            }
        }
    }
}

data class DebugSettingsUiState(
    val accountTabEnabled: Boolean = false,
    val syncCodeFeaturesEnabled: Boolean = false,
    val dv5ToneMapToSdrEnabled: Boolean = false,
    val dv5HardwareToneMapToSdrEnabled: Boolean = false,
    val dv5HardwareToneMapCpuFallbackEnabled: Boolean = false,
    val transportValidationEnabled: Boolean = false,
    val transportValidationSelectedSampleId: String? = null,
    val transportValidationComparisonMode: TransportValidationComparisonMode =
        TransportValidationComparisonMode.FULL_BURST_COMPARE,
    val transportValidationCaptureMode: TransportValidationCaptureMode =
        TransportValidationCaptureMode.FIRST_N_BURSTS,
    val transportValidationCaptureBurstCount: Int = 8,
    val transportValidationBinaryDumpsEnabled: Boolean = false,
    val transportValidationExportRequestCount: Int = 0,
    val transportValidationAvailableSamples: List<TransportValidationSampleOption> = emptyList(),
    val signInLoading: Boolean = false,
    val signInResult: String? = null
)

sealed class DebugSettingsEvent {
    data class ToggleAccountTab(val enabled: Boolean) : DebugSettingsEvent()
    data class ToggleSyncCodeFeatures(val enabled: Boolean) : DebugSettingsEvent()
    data class ToggleDv5ToneMapToSdr(val enabled: Boolean) : DebugSettingsEvent()
    data class ToggleDv5HardwareToneMapToSdr(val enabled: Boolean) : DebugSettingsEvent()
    data class ToggleDv5HardwareToneMapCpuFallback(val enabled: Boolean) : DebugSettingsEvent()
    data class ToggleTransportValidationEnabled(val enabled: Boolean) : DebugSettingsEvent()
    data class SelectTransportValidationSample(val sampleId: String?) : DebugSettingsEvent()
    data class SelectTransportValidationComparisonMode(
        val mode: TransportValidationComparisonMode
    ) : DebugSettingsEvent()
    data class SelectTransportValidationCaptureMode(
        val mode: TransportValidationCaptureMode
    ) : DebugSettingsEvent()
    data class SetTransportValidationBurstCount(val count: Int) : DebugSettingsEvent()
    data class ToggleTransportValidationBinaryDumps(val enabled: Boolean) : DebugSettingsEvent()
    data object RequestTransportValidationExport : DebugSettingsEvent()
    data object AdvanceTransportValidationSample : DebugSettingsEvent()
    data object AdvanceTransportValidationComparisonMode : DebugSettingsEvent()
    data object AdvanceTransportValidationCaptureMode : DebugSettingsEvent()
    data object AdvanceTransportValidationBurstCount : DebugSettingsEvent()
    data object StartTransportValidationPlayback : DebugSettingsEvent()
    data object StopTransportValidationPlayback : DebugSettingsEvent()
    data class SignIn(val email: String, val password: String) : DebugSettingsEvent()
}

private fun Int.floorMod(size: Int): Int {
    if (size <= 0) return 0
    val remainder = this % size
    return if (remainder < 0) remainder + size else remainder
}
