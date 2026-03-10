@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nexio.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nexio.tv.R
import com.nexio.tv.ui.components.EmptyScreenState
import com.nexio.tv.ui.components.ErrorState
import com.nexio.tv.ui.components.GridContentCard
import com.nexio.tv.ui.components.LoadingIndicator
import com.nexio.tv.ui.components.PosterCardDefaults
import com.nexio.tv.ui.theme.NexioColors
import kotlin.math.min
import androidx.compose.ui.res.stringResource

@Composable
fun AndroidTvFeedBrowserScreen(
    viewModel: AndroidTvFeedBrowserViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onBackPress: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val gridState = rememberLazyGridState()
    val restoreFocusRequester = remember { FocusRequester() }
    var focusedItemIndex by rememberSaveable(uiState.title) { mutableStateOf(0) }

    BackHandler { onBackPress() }

    if (!uiState.isLoading && uiState.items.isNotEmpty()) {
        val clampedFocusIndex = min(focusedItemIndex, uiState.items.lastIndex)
        androidx.compose.runtime.LaunchedEffect(uiState.items.size, clampedFocusIndex) {
            if (uiState.items.isEmpty()) return@LaunchedEffect
            val isTargetVisible = gridState.layoutInfo.visibleItemsInfo.any { it.index == clampedFocusIndex }
            if (!isTargetVisible) {
                gridState.animateScrollToItem(clampedFocusIndex)
            }
            repeat(2) { withFrameNanos { } }
            try {
                restoreFocusRequester.requestFocus()
            } catch (_: IllegalStateException) {
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NexioColors.Background)
            .padding(horizontal = 48.dp, vertical = 24.dp)
    ) {
        Text(
            text = uiState.title.ifBlank { stringResource(R.string.android_tv_feed_browser_title_fallback) },
            style = MaterialTheme.typography.headlineLarge,
            color = NexioColors.TextPrimary
        )

        if (uiState.subtitle.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = uiState.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = NexioColors.TextSecondary
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            }

            uiState.error != null -> {
                ErrorState(
                    message = uiState.error ?: stringResource(R.string.error_generic),
                    onRetry = viewModel::refresh,
                    modifier = Modifier.fillMaxSize()
                )
            }

            uiState.items.isEmpty() -> {
                EmptyScreenState(
                    title = stringResource(R.string.android_tv_feed_browser_empty_title),
                    subtitle = stringResource(R.string.android_tv_feed_browser_empty_subtitle),
                    icon = Icons.Default.GridView,
                    modifier = Modifier.fillMaxSize()
                )
            }

            else -> {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = PosterCardDefaults.Style.width),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(
                        items = uiState.items,
                        key = { index, item -> "${item.id}_${item.apiType}_$index" }
                    ) { index, item ->
                        GridContentCard(
                            item = item,
                            posterCardStyle = PosterCardDefaults.Style,
                            focusRequester = if (index == focusedItemIndex) restoreFocusRequester else null,
                            onFocused = { focusedItemIndex = index },
                            onClick = {
                                onNavigateToDetail(
                                    item.id,
                                    item.apiType,
                                    uiState.addonBaseUrl.orEmpty()
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}
