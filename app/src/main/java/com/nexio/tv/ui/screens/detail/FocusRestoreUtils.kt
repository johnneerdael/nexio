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

suspend fun FocusRequester.requestFocusAfterFrames(
    frames: Int = 2,
    attempts: Int = 4,
    reason: String = "unspecified"
) {
    detailFocusDebug("requestFocusAfterFrames start reason=$reason frames=$frames attempts=$attempts")
    repeat(frames.coerceAtLeast(0)) {
        withFrameNanos { }
    }
    val retryAttempts = attempts.coerceAtLeast(1)
    repeat(retryAttempts) { attempt ->
        detailFocusDebug("requestFocusAfterFrames attempt=${attempt + 1}/$retryAttempts reason=$reason")
        runCatching {
            requestFocus()
        }
        if (attempt < retryAttempts - 1) {
            withFrameNanos { }
        }
    }
}

/**
 * Keeps requesting focus every few frames until [isFocused] returns true or [maxAttempts] is exhausted.
 * Hardens against post-composition focus thieves (lazy-list sections requesting focus after initial landing).
 * The caller is expected to track focus state via onFocusChanged and expose it through [isFocused].
 */
suspend fun FocusRequester.requestFocusUntilFocused(
    isFocused: () -> Boolean,
    initialFrames: Int = 2,
    framesBetweenAttempts: Int = 2,
    maxAttempts: Int = 20,
    reason: String = "unspecified"
) {
    detailFocusDebug(
        "requestFocusUntilFocused start reason=$reason maxAttempts=$maxAttempts initialFrames=$initialFrames"
    )
    repeat(initialFrames.coerceAtLeast(0)) {
        withFrameNanos { }
    }
    val attempts = maxAttempts.coerceAtLeast(1)
    repeat(attempts) { attempt ->
        if (isFocused()) {
            detailFocusDebug("requestFocusUntilFocused landed reason=$reason attempt=${attempt + 1}")
            return
        }
        detailFocusDebug("requestFocusUntilFocused attempt=${attempt + 1}/$attempts reason=$reason")
        runCatching { requestFocus() }
        repeat(framesBetweenAttempts.coerceAtLeast(1)) {
            withFrameNanos { }
        }
    }
    detailFocusDebug(
        "requestFocusUntilFocused exhausted reason=$reason finalFocused=${isFocused()}"
    )
}

internal suspend fun moveFocusToLazyItem(
    listState: LazyListState,
    targetItemIndex: Int,
    focusRequester: FocusRequester,
    reason: String = "unspecified"
) {
    val normalizedTargetIndex = targetItemIndex.coerceAtLeast(0)
    val targetVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == normalizedTargetIndex }
    if (!targetVisible) {
        detailFocusDebug(
            "moveFocusToLazyItem scroll reason=$reason targetIndex=$normalizedTargetIndex currentIndex=${listState.firstVisibleItemIndex} currentOffset=${listState.firstVisibleItemScrollOffset}"
        )
        listState.scrollToItem(normalizedTargetIndex)
        withFrameNanos { }
    }
    focusRequester.requestFocusAfterFrames(reason = "$reason:item_$normalizedTargetIndex")
}

suspend fun LazyListState.restoreHeroScrollAfterFocus(
    frames: Int = 2,
    reason: String = "unspecified"
) {
    detailFocusDebug(
        "restoreHeroScrollAfterFocus start reason=$reason index=$firstVisibleItemIndex offset=$firstVisibleItemScrollOffset"
    )
    repeat(frames.coerceAtLeast(0)) {
        withFrameNanos { }
    }
    repeat(3) { attempt ->
        if (firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0) return
        detailFocusDebug(
            "restoreHeroScrollAfterFocus correction attempt=${attempt + 1}/3 reason=$reason index=$firstVisibleItemIndex offset=$firstVisibleItemScrollOffset"
        )
        scrollToItem(0)
        if (attempt < 2) {
            withFrameNanos { }
        }
    }
}

suspend fun resetFocusToHero(
    focusManager: FocusManager,
    listState: LazyListState,
    heroPlayFocusRequester: FocusRequester,
    reason: String = "unspecified"
) {
    detailFocusDebug(
        "resetFocusToHero reason=$reason beforeClear index=${listState.firstVisibleItemIndex} offset=${listState.firstVisibleItemScrollOffset}"
    )
    focusManager.clearFocus(force = true)
    listState.scrollToItem(0)
    heroPlayFocusRequester.requestFocusAfterFrames(reason = "$reason:hero_play")
    detailFocusDebug(
        "resetFocusToHero postRequestFocus reason=$reason index=${listState.firstVisibleItemIndex} offset=${listState.firstVisibleItemScrollOffset}"
    )
    listState.restoreHeroScrollAfterFocus(reason = "$reason:hero_scroll")
    detailFocusDebug(
        "resetFocusToHero completed reason=$reason index=${listState.firstVisibleItemIndex} offset=${listState.firstVisibleItemScrollOffset}"
    )
}
