package com.nexio.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.res.stringResource
import com.nexio.tv.R
import com.nexio.tv.ui.screens.home.ContinueWatchingItem
import com.nexio.tv.ui.theme.NexioColors

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun GridContinueWatchingSection(
    items: List<ContinueWatchingResolvedDisplayItem>,
    onItemClick: (ContinueWatchingItem) -> Unit,
    onDetailsClick: (ContinueWatchingItem) -> Unit = onItemClick,
    onRemoveItem: (ContinueWatchingItem) -> Unit,
    onMarkAsWatched: (ContinueWatchingItem) -> Unit = {},
    onCheckIn: ((ContinueWatchingItem) -> Unit)? = null,
    cwWatchlistMembership: Map<String, Boolean> = emptyMap(),
    onToggleLibrary: ((ContinueWatchingItem) -> Unit)? = null,
    onStartFromBeginning: (ContinueWatchingItem) -> Unit = {},
    showManualStreamSelection: (ContinueWatchingItem) -> Boolean = { false },
    onPlayWithManualStreamSelection: (ContinueWatchingItem) -> Unit = {},
    modifier: Modifier = Modifier,
    focusedItemIndex: Int = -1
) {
    if (items.isEmpty()) return
    var optionsItem by remember { mutableStateOf<ContinueWatchingResolvedDisplayItem?>(null) }
    val focusRequesters = remember(items.size) { List(items.size) { FocusRequester() } }
    var lastFocusedIndex by remember { mutableIntStateOf(-1) }
    var lastRequestedFocusIndex by remember { mutableIntStateOf(-1) }
    var pendingFocusIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(focusedItemIndex) {
        if (focusedItemIndex >= 0 && focusedItemIndex < items.size) {
            if (lastRequestedFocusIndex == focusedItemIndex) return@LaunchedEffect
            var focused = false
            for (attempt in 0 until 3) {
                withFrameNanos { }
                focused = runCatching { focusRequesters[focusedItemIndex].requestFocus() }.isSuccess
                if (focused) break
            }
            if (focused) {
                lastRequestedFocusIndex = focusedItemIndex
            }
        } else {
            lastRequestedFocusIndex = -1
        }
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 12.dp, start = 24.dp, end = 24.dp)
        ) {
            Column {
                Text(
                    text = stringResource(R.string.continue_watching),
                    style = MaterialTheme.typography.headlineMedium,
                    color = NexioColors.TextPrimary
                )
            }
        }

        val restoreRequester = remember(lastFocusedIndex, focusRequesters) {
            val idx = if (lastFocusedIndex >= 0 && lastFocusedIndex < focusRequesters.size) {
                lastFocusedIndex
            } else {
                0
            }
            focusRequesters.getOrNull(idx) ?: FocusRequester.Default
        }

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .focusRestorer(restoreRequester),
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(
                items = items,
                key = { _, resolved -> resolved.itemKey }
            ) { index, resolved ->
                val focusModifier = if (index < focusRequesters.size) {
                    Modifier.focusRequester(focusRequesters[index])
                } else {
                    Modifier
                }

                ContinueWatchingCard(
                    item = resolved,
                    onClick = { onItemClick(resolved.toContinueWatchingItem()) },
                    onLongPress = { optionsItem = resolved },
                    modifier = focusModifier
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused && lastFocusedIndex != index) {
                                lastFocusedIndex = index
                            }
                        },
                    cardWidth = 220.dp,
                    imageHeight = 124.dp
                )
            }
        }
    }

    val menuItem = optionsItem
    if (menuItem != null) {
        val menuLegacy = menuItem.toContinueWatchingItem()
        ContinueWatchingOptionsDialog(
            item = menuItem,
            onDismiss = { optionsItem = null },
            onRemove = {
                val targetIndex = if (items.size <= 1) null else minOf(lastFocusedIndex, items.size - 2)
                pendingFocusIndex = targetIndex
                onRemoveItem(menuLegacy)
                optionsItem = null
            },
            onMarkAsWatched = {
                onMarkAsWatched(menuLegacy)
                optionsItem = null
            },
            showManualStreamSelection = showManualStreamSelection(menuLegacy),
            onPlayWithManualStreamSelection = {
                onPlayWithManualStreamSelection(menuLegacy)
                optionsItem = null
            },
            onDetails = {
                onDetailsClick(menuLegacy)
                optionsItem = null
            },
            onCheckIn = onCheckIn?.let { callback ->
                {
                    callback(menuLegacy)
                    optionsItem = null
                }
            },
            isInWatchlist = cwWatchlistMembership[menuItem.contentId] == true,
            onToggleLibrary = onToggleLibrary?.let { callback ->
                {
                    callback(menuLegacy)
                    optionsItem = null
                }
            },
            onStartFromBeginning = {
                onStartFromBeginning(menuLegacy)
                optionsItem = null
            }
        )
    }

    LaunchedEffect(items.size, pendingFocusIndex) {
        val target = pendingFocusIndex
        if (target != null && target >= 0 && target < focusRequesters.size) {
            var focused = false
            for (attempt in 0 until 3) {
                withFrameNanos { }
                focused = runCatching { focusRequesters[target].requestFocus() }.isSuccess
                if (focused) break
            }
            if (focused) {
                lastRequestedFocusIndex = target
            }
            pendingFocusIndex = null
        }
    }
}
