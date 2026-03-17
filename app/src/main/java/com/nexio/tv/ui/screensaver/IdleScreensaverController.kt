package com.nexio.tv.ui.screensaver

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class IdleScreensaverController @Inject constructor() {
    private val _lastInteractionAtMs = MutableStateFlow(SystemClock.elapsedRealtime())
    val lastInteractionAtMs = _lastInteractionAtMs.asStateFlow()

    private val _isVisible = MutableStateFlow(false)
    val isVisible = _isVisible.asStateFlow()

    private val _sessionId = MutableStateFlow(0L)
    val sessionId = _sessionId.asStateFlow()

    fun registerInteraction(nowMs: Long = SystemClock.elapsedRealtime()) {
        _lastInteractionAtMs.value = nowMs
    }

    fun show() {
        if (_isVisible.value) return
        _isVisible.value = true
        _sessionId.value = _sessionId.value + 1L
    }

    fun dismiss(nowMs: Long = SystemClock.elapsedRealtime()) {
        _isVisible.value = false
        _lastInteractionAtMs.value = nowMs
    }
}
