package com.nexio.tv.ui.components

import android.view.KeyEvent

internal fun shouldKeepScreenOnForTrailer(isPlaying: Boolean, isBuffering: Boolean): Boolean =
    isPlaying || isBuffering

internal fun shouldConsumeTrailerKey(keyCode: Int): Boolean = when (keyCode) {
    KeyEvent.KEYCODE_MEDIA_PAUSE,
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> true
    else -> false
}
