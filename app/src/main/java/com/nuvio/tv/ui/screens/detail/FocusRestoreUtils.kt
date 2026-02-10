package com.nuvio.tv.ui.screens.detail

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.withFrameNanos

suspend fun FocusRequester.requestFocusAfterFrames(frames: Int = 2) {
    repeat(frames.coerceAtLeast(0)) {
        withFrameNanos { }
    }
    requestFocus()
}
