package com.nexio.tv.core.trace

import com.nexio.tv.data.local.TraceSettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@Singleton
class SubtitleDiagnosticsGate @Inject constructor(
    store: TraceSettingsDataStore
) {
    private val state = MutableStateFlow(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch { store.subtitleDiagnosticsLogcatEnabled.collect { state.value = it } }
    }

    fun isEnabled(): Boolean = state.value

    companion object {
        const val TAG = "Nexio.SubsDiag"
    }
}
