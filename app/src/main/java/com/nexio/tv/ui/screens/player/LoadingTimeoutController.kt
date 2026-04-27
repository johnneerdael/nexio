package com.nexio.tv.ui.screens.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class LoadingPhase(val timeoutMs: Long) {
    Initial(120_000L),
    MidStream(60_000L)
}

sealed class LoadingTimeoutEvent {
    object Retry : LoadingTimeoutEvent()
    object Error : LoadingTimeoutEvent()
}

class LoadingTimeoutController(
    private val phase: LoadingPhase,
    private val onEvent: (LoadingTimeoutEvent) -> Unit,
    private val scope: CoroutineScope
) {
    private var job: Job? = null
    private var hasRetried = false

    fun start() {
        job?.cancel()
        job = scope.launch {
            delay(phase.timeoutMs)
            if (!hasRetried) {
                hasRetried = true
                onEvent(LoadingTimeoutEvent.Retry)
            } else {
                onEvent(LoadingTimeoutEvent.Error)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
