package com.nexio.tv.ui.screens.player

import android.view.KeyEvent

internal object PlayerSeekKeyPolicy {
    fun previewSeekDeltaMs(keyCode: Int, repeatCount: Int): Long? {
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND -> -1L
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> 1L
            else -> return null
        }
        return direction * seekStepMs(repeatCount)
    }

    fun shouldCommitPreviewSeekOnKeyUp(keyCode: Int, controlsVisible: Boolean): Boolean =
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_REWIND -> true
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> !controlsVisible
            else -> false
        }

    fun isDpadSeekKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT

    fun isMediaSeekKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND

    private fun seekStepMs(repeatCount: Int): Long = when {
        repeatCount >= 30 -> 120_000L
        repeatCount >= 15 -> 60_000L
        repeatCount >= 8 -> 30_000L
        repeatCount >= 3 -> 15_000L
        else -> 10_000L
    }
}
