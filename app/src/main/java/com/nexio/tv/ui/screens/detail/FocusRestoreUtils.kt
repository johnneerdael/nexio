@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nexio.tv.ui.screens.detail

import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.lazy.LazyListState

@Composable
fun rememberResettableLazyListState(
    resetKey: Any?,
    firstVisibleItemIndex: Int = 0,
    firstVisibleItemScrollOffset: Int = 0,
    prefetchStrategy: LazyListPrefetchStrategy = LazyListPrefetchStrategy()
): LazyListState {
    val listState = remember(resetKey, firstVisibleItemIndex, firstVisibleItemScrollOffset, prefetchStrategy) {
        LazyListState(
            firstVisibleItemIndex = firstVisibleItemIndex,
            firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
            prefetchStrategy = prefetchStrategy
        )
    }
    LaunchedEffect(listState, resetKey, firstVisibleItemIndex, firstVisibleItemScrollOffset) {
        if (
            listState.firstVisibleItemIndex != firstVisibleItemIndex ||
            listState.firstVisibleItemScrollOffset != firstVisibleItemScrollOffset
        ) {
            listState.scrollToItem(firstVisibleItemIndex, firstVisibleItemScrollOffset)
        }
    }
    return listState
}

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

suspend fun resetFocusToHero(
    focusManager: FocusManager,
    listState: LazyListState,
    heroPlayFocusRequester: FocusRequester
) {
    focusManager.clearFocus(force = true)
    listState.scrollToItem(0)
    heroPlayFocusRequester.requestFocusAfterFrames()
    listState.restoreHeroScrollAfterFocus()
}
