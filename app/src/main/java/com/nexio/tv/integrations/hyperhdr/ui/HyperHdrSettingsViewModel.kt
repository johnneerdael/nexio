package com.nexio.tv.integrations.hyperhdr.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.integrations.hyperhdr.capture.DisplayColorCapability
import com.nexio.tv.integrations.hyperhdr.data.HdrMode
import com.nexio.tv.integrations.hyperhdr.data.HyperHdrConfig
import com.nexio.tv.integrations.hyperhdr.data.HyperHdrConfigDataStore
import com.nexio.tv.integrations.hyperhdr.network.HyperHdrFlatBufferClient
import com.nexio.tv.integrations.hyperhdr.network.HyperHdrJsonApiClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HyperHdrSettingsViewModel @Inject constructor(
    private val store: HyperHdrConfigDataStore,
    private val displayCapability: DisplayColorCapability,
) : ViewModel() {

    val composesWideColor: Boolean = displayCapability.composesWideColor

    val config: StateFlow<HyperHdrConfig> = store.config
        .stateIn(viewModelScope, SharingStarted.Eagerly, HyperHdrConfig())

    private val _testResult = MutableStateFlow<TestResult>(TestResult.Idle)
    val testResult: StateFlow<TestResult> = _testResult.asStateFlow()

    sealed interface TestResult {
        data object Idle : TestResult
        data object Testing : TestResult
        data class Success(val hostname: String, val instanceName: String?) : TestResult
        data class Failed(val message: String) : TestResult
    }

    fun setEnabled(value: Boolean) =
        viewModelScope.launch { store.update { it.copy(enabled = value) } }

    fun setHost(value: String) =
        viewModelScope.launch { store.update { it.copy(host = value.trim()) } }

    fun setPort(value: Int) =
        viewModelScope.launch { store.update { it.copy(port = value.coerceIn(1, 65535)) } }

    fun setJsonPort(value: Int) =
        viewModelScope.launch { store.update { it.copy(jsonPort = value.coerceIn(1, 65535)) } }

    fun setPriority(value: Int) =
        viewModelScope.launch { store.update { it.copy(priority = value.coerceIn(0, 255)) } }

    fun setHdrMode(value: HdrMode) =
        viewModelScope.launch { store.update { it.copy(hdrMode = value) } }

    fun testConnection() = viewModelScope.launch {
        val cfg = config.value
        if (cfg.host.isBlank()) {
            _testResult.value = TestResult.Failed("Host cannot be empty"); return@launch
        }
        _testResult.value = TestResult.Testing

        // Test 1: FlatBuffer port — open + register + close.
        val fbClient = HyperHdrFlatBufferClient(
            host = cfg.host, port = cfg.port, priority = cfg.priority,
        )
        val fbOutcome = runCatching { fbClient.connect() }
        fbClient.close()
        if (fbOutcome.isFailure) {
            _testResult.value = TestResult.Failed(
                "FlatBuffer port ${cfg.port} unreachable: ${fbOutcome.exceptionOrNull()?.message}"
            )
            return@launch
        }

        // Test 2: JSON port — fetch serverInfo.
        val jsonClient = HyperHdrJsonApiClient(host = cfg.host, port = cfg.jsonPort)
        val jsonOutcome = runCatching { jsonClient.serverInfo() }
        _testResult.value = jsonOutcome.fold(
            onSuccess = { TestResult.Success(it.hostname, it.instanceName) },
            onFailure = {
                TestResult.Failed("JSON port ${cfg.jsonPort} unreachable: ${it.message}")
            },
        )
    }
}
