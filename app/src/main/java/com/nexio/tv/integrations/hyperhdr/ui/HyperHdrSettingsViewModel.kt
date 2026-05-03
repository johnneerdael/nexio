package com.nexio.tv.integrations.hyperhdr.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.integrations.hyperhdr.data.HyperHdrConfig
import com.nexio.tv.integrations.hyperhdr.data.HyperHdrConfigDataStore
import com.nexio.tv.integrations.hyperhdr.network.HyperHdrFlatBufferClient
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
) : ViewModel() {

    val config: StateFlow<HyperHdrConfig> = store.config
        .stateIn(viewModelScope, SharingStarted.Eagerly, HyperHdrConfig())

    private val _testResult = MutableStateFlow<TestResult>(TestResult.Idle)
    val testResult: StateFlow<TestResult> = _testResult.asStateFlow()

    sealed interface TestResult {
        data object Idle : TestResult
        data object Testing : TestResult
        data object Success : TestResult
        data class Failed(val message: String) : TestResult
    }

    fun setEnabled(value: Boolean) = viewModelScope.launch { store.update { it.copy(enabled = value) } }
    fun setHost(value: String) = viewModelScope.launch { store.update { it.copy(host = value.trim()) } }
    fun setPort(value: Int) = viewModelScope.launch { store.update { it.copy(port = value.coerceIn(1, 65535)) } }
    fun setPriority(value: Int) = viewModelScope.launch { store.update { it.copy(priority = value.coerceIn(0, 255)) } }

    fun testConnection() = viewModelScope.launch {
        val cfg = config.value
        if (cfg.host.isBlank()) {
            _testResult.value = TestResult.Failed("Host cannot be empty"); return@launch
        }
        _testResult.value = TestResult.Testing
        val client = HyperHdrFlatBufferClient(host = cfg.host, port = cfg.port, priority = cfg.priority)
        val outcome = runCatching { client.connect() }
        client.close()
        _testResult.value = outcome.fold(
            onSuccess = { TestResult.Success },
            onFailure = { TestResult.Failed(it.message ?: "Connection failed") },
        )
    }
}
