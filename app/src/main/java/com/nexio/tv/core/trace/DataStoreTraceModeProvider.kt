package com.nexio.tv.core.trace

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DataStoreTraceModeProvider(
    source: Flow<TraceMode>,
    scope: CoroutineScope
) : TraceModeProvider {
    private val state = MutableStateFlow(TraceMode.OFF)

    init {
        scope.launch {
            source.collect { state.value = it }
        }
    }

    override val current: TraceMode get() = state.value
    override fun observe(): StateFlow<TraceMode> = state.asStateFlow()
}
