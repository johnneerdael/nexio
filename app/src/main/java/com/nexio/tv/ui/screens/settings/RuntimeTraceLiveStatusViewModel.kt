package com.nexio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.core.trace.FileRuntimeTraceSink
import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.TraceEventEnvelope
import com.nexio.tv.core.trace.TraceSessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RuntimeTraceLiveStatusUiState(
    val isActive: Boolean = false,
    val sessionId: String? = null,
    val eventsWritten: Long = 0L,
    val eventsDropped: Long = 0L,
    val recentRuntime: List<TraceEventEnvelope<*>> = emptyList(),
    val recentRoutes: List<TraceEventEnvelope<*>> = emptyList(),
    val recentPolicyViolations: List<TraceEventEnvelope<*>> = emptyList()
)

@HiltViewModel
class RuntimeTraceLiveStatusViewModel @Inject constructor(
    private val sessionManager: TraceSessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(RuntimeTraceLiveStatusUiState())
    val uiState: StateFlow<RuntimeTraceLiveStatusUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                snapshot()
                delay(1_000L)
            }
        }
    }

    private fun snapshot() {
        val sink = sessionManager.activeSink()
        val session = sessionManager.activeSession()
        val recent = (sink as? FileRuntimeTraceSink)?.recent() ?: emptyList()
        _uiState.value = RuntimeTraceLiveStatusUiState(
            isActive = sink !== NoopRuntimeTraceSink,
            sessionId = session?.traceSessionId,
            eventsWritten = sink.eventsWritten(),
            eventsDropped = sink.eventsDropped(),
            recentRuntime = recent.filter { it.eventType.startsWith("runtime.") }.takeLast(20),
            recentRoutes = recent.filter { it.eventType == "metadata.route_decision" }.takeLast(20),
            recentPolicyViolations = recent.filter { it.eventType.startsWith("policy.") }.takeLast(20)
        )
    }
}
