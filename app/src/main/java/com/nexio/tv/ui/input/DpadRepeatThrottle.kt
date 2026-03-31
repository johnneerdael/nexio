package com.nexio.tv.ui.input

import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key

internal const val DPAD_REPEAT_THROTTLE_MS = 112L

internal enum class DpadRepeatThrottleAxis {
    HORIZONTAL,
    VERTICAL
}

internal data class DpadRepeatThrottleState(
    val horizontalLastDirectionalInputUptimeMs: Long = 0L,
    val verticalLastDirectionalInputUptimeMs: Long = 0L
)

internal data class DpadRepeatThrottleResult(
    val consumed: Boolean,
    val updatedState: DpadRepeatThrottleState
)

internal fun KeyEvent.consumeThrottledDirectionalRepeat(
    state: DpadRepeatThrottleState,
    throttleMs: Long = DPAD_REPEAT_THROTTLE_MS
): DpadRepeatThrottleResult {
    if (nativeKeyEvent.action != AndroidKeyEvent.ACTION_DOWN) {
        return DpadRepeatThrottleResult(
            consumed = false,
            updatedState = state
        )
    }
    val axis = directionalAxisForKey(key) ?: return DpadRepeatThrottleResult(
        consumed = false,
        updatedState = state
    )
    val now = SystemClock.uptimeMillis()
    if (shouldConsumeDirectionalRepeat(axis, nativeKeyEvent.repeatCount, now, state, throttleMs)) {
        return DpadRepeatThrottleResult(
            consumed = true,
            updatedState = state
        )
    }
    return DpadRepeatThrottleResult(
        consumed = false,
        updatedState = state.updatedAfterDirectionalAxis(axis, now)
    )
}

internal fun directionalAxisForKey(key: Key): DpadRepeatThrottleAxis? {
    return when (key) {
        Key.DirectionUp,
        Key.DirectionDown -> DpadRepeatThrottleAxis.VERTICAL
        Key.DirectionLeft,
        Key.DirectionRight -> DpadRepeatThrottleAxis.HORIZONTAL
        else -> null
    }
}

internal fun shouldConsumeDirectionalRepeat(
    axis: DpadRepeatThrottleAxis,
    repeatCount: Int,
    nowUptimeMs: Long,
    state: DpadRepeatThrottleState,
    throttleMs: Long = DPAD_REPEAT_THROTTLE_MS
): Boolean {
    val previousDirectionalInputUptimeMs = when (axis) {
        DpadRepeatThrottleAxis.VERTICAL -> state.verticalLastDirectionalInputUptimeMs
        DpadRepeatThrottleAxis.HORIZONTAL -> state.horizontalLastDirectionalInputUptimeMs
    }
    return repeatCount > 0 &&
        previousDirectionalInputUptimeMs != 0L &&
        nowUptimeMs - previousDirectionalInputUptimeMs < throttleMs
}

internal fun DpadRepeatThrottleState.updatedAfterDirectionalAxis(
    axis: DpadRepeatThrottleAxis,
    eventUptimeMs: Long
): DpadRepeatThrottleState {
    return when (axis) {
        DpadRepeatThrottleAxis.VERTICAL -> copy(verticalLastDirectionalInputUptimeMs = eventUptimeMs)
        DpadRepeatThrottleAxis.HORIZONTAL -> copy(horizontalLastDirectionalInputUptimeMs = eventUptimeMs)
    }
}

internal fun DpadRepeatThrottleState.updatedAfterDirectionalEvent(
    event: KeyEvent,
    eventUptimeMs: Long
): DpadRepeatThrottleState {
    val axis = directionalAxisForKey(event.key) ?: return this
    return updatedAfterDirectionalAxis(axis, eventUptimeMs)
}

internal fun DpadRepeatThrottleState.wasRepeatThrottled(
    event: KeyEvent,
    nowUptimeMs: Long,
    throttleMs: Long = DPAD_REPEAT_THROTTLE_MS
): Boolean {
    val axis = directionalAxisForKey(event.key) ?: return false
    return shouldConsumeDirectionalRepeat(
        axis = axis,
        repeatCount = event.nativeKeyEvent.repeatCount,
        nowUptimeMs = nowUptimeMs,
        state = this,
        throttleMs = throttleMs
    )
}
