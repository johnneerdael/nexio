package com.nexio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.core.trace.TraceMode
import com.nexio.tv.core.trace.TraceSessionManager
import com.nexio.tv.data.local.TraceSettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RuntimeTraceSettingsUiState(
    val mode: TraceMode = TraceMode.OFF,
    val isSessionActive: Boolean = false,
    val sessionId: String? = null,
    val eventsWritten: Long = 0L,
    val eventsDropped: Long = 0L
)

@HiltViewModel
class RuntimeTraceSettingsViewModel @Inject constructor(
    private val settingsStore: TraceSettingsDataStore,
    private val sessionManager: TraceSessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(RuntimeTraceSettingsUiState())
    val uiState: StateFlow<RuntimeTraceSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsStore.mode.collect { mode -> updateState { it.copy(mode = mode) } }
        }
    }

    fun selectMode(mode: TraceMode) {
        viewModelScope.launch { settingsStore.setMode(mode) }
    }

    fun startSession() {
        sessionManager.start(mode = _uiState.value.mode, activeProfileHash = null)
        val session = sessionManager.activeSession()
        updateState {
            it.copy(
                isSessionActive = session != null,
                sessionId = session?.traceSessionId,
                eventsWritten = sessionManager.activeSink().eventsWritten(),
                eventsDropped = sessionManager.activeSink().eventsDropped()
            )
        }
    }

    fun stopSession() {
        sessionManager.stop()
        updateState { it.copy(isSessionActive = false, sessionId = null) }
    }

    fun refreshCounters() {
        updateState {
            it.copy(
                eventsWritten = sessionManager.activeSink().eventsWritten(),
                eventsDropped = sessionManager.activeSink().eventsDropped()
            )
        }
    }

    private inline fun updateState(block: (RuntimeTraceSettingsUiState) -> RuntimeTraceSettingsUiState) {
        _uiState.value = block(_uiState.value)
    }
}
