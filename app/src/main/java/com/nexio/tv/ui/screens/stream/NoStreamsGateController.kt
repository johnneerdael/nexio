package com.nexio.tv.ui.screens.stream

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class NoStreamsGateController(
    private val scope: CoroutineScope,
    private val delayMs: Long,
    private val onGateElapsed: () -> Unit
) {
    private var gateJob: Job? = null

    fun restart() {
        gateJob?.cancel()
        gateJob = scope.launch {
            delay(delayMs)
            onGateElapsed()
        }
    }

    fun cancel() {
        gateJob?.cancel()
        gateJob = null
    }
}
