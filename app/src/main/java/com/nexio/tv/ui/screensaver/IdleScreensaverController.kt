package com.nexio.tv.ui.screensaver

import android.os.SystemClock
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

internal fun coerceScreensaverDelaySeconds(seconds: Int): Int = seconds.coerceIn(60, 600)

@Singleton
class IdleScreensaverController @Inject constructor(
    private val layoutPrefs: LayoutPreferenceDataStore
) {
    private val _lastInteractionAtMs = MutableStateFlow(SystemClock.elapsedRealtime())
    val lastInteractionAtMs = _lastInteractionAtMs.asStateFlow()

    private val _isVisible = MutableStateFlow(false)
    val isVisible = _isVisible.asStateFlow()

    private val _sessionId = MutableStateFlow(0L)
    val sessionId = _sessionId.asStateFlow()

    val currentTimeoutMs: Flow<Long> =
        layoutPrefs.screensaverDelaySeconds.map { it * 1000L }

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
