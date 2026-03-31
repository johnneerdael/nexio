package com.nexio.tv.ui.screens.detail

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.lazy.LazyListState

suspend fun FocusRequester.requestFocusAfterFrames(frames: Int = 2) {
    repeat(frames.coerceAtLeast(0)) {
        withFrameNanos { }
    }
    repeat(4) { attempt ->
        val requested = runCatching {
            requestFocus()
            true
        }.getOrDefault(false)
        if (requested) return
        if (attempt < 3) {
            withFrameNanos { }
        }
    }
}

suspend fun LazyListState.restoreHeroScrollAfterFocus(frames: Int = 2) {
    repeat(frames.coerceAtLeast(0)) {
        withFrameNanos { }
    }
    repeat(3) { attempt ->
        if (firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0) return
        scrollToItem(0)
        if (attempt < 2) {
            withFrameNanos { }
        }
    }
}
