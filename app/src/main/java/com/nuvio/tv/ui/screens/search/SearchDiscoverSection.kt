package com.nuvio.tv.ui.screens.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.theme.NuvioColors
import kotlin.math.max

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun DiscoverSection(
    uiState: SearchUiState,
    posterCardStyle: PosterCardStyle,
    focusResults: Boolean,
    firstItemFocusRequester: FocusRequester,
    focusedItemIndex: Int,
    shouldRestoreFocusedItem: Boolean,
    onRestoreFocusedItemHandled: () -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    onDiscoverItemFocused: (Int) -> Unit,
    onRequestRestoreFocus: (Int) -> Unit,
    onSelectType: (String) -> Unit,
    onSelectCatalog: (String) -> Unit,
    onSelectGenre: (String?) -> Unit,
    onShowMore: () -> Unit,
    onLoadMore: () -> Unit
) {
    val selectedCatalog = uiState.discoverCatalogs.firstOrNull { it.key == uiState.selectedDiscoverCatalogKey }
    val filteredCatalogs = uiState.discoverCatalogs.filter { it.type == uiState.selectedDiscoverType }
    val genres = selectedCatalog?.genres.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Discover",
            style = MaterialTheme.typography.headlineMedium,
            color = NuvioColors.TextPrimary
        )

        Text(
            text = "Type",
            style = MaterialTheme.typography.labelLarge,
            color = NuvioColors.TextSecondary
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
        ) {
            item {
                DiscoverFilterChip(
                    label = "Movies",
                    selected = uiState.selectedDiscoverType == "movie",
                    onClick = { onSelectType("movie") }
                )
            }
            item {
                DiscoverFilterChip(
                    label = "TV Shows",
                    selected = uiState.selectedDiscoverType == "series",
                    onClick = { onSelectType("series") }
                )
            }
        }

        if (filteredCatalogs.isNotEmpty()) {
            Text(
                text = "Catalog",
                style = MaterialTheme.typography.labelLarge,
                color = NuvioColors.TextSecondary
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
            ) {
                items(filteredCatalogs, key = { it.key }) { catalog ->
                    DiscoverFilterChip(
                        label = catalog.catalogName,
                        selected = catalog.key == uiState.selectedDiscoverCatalogKey,
                        onClick = { onSelectCatalog(catalog.key) }
                    )
                }
            }
        }

        if (genres.isNotEmpty()) {
            Text(
                text = "Genre",
                style = MaterialTheme.typography.labelLarge,
                color = NuvioColors.TextSecondary
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
            ) {
                item {
                    DiscoverFilterChip(
                        label = "All Genres",
                        selected = uiState.selectedDiscoverGenre == null,
                        onClick = { onSelectGenre(null) }
                    )
                }
                items(genres, key = { it }) { genre ->
                    DiscoverFilterChip(
                        label = genre,
                        selected = uiState.selectedDiscoverGenre == genre,
                        onClick = { onSelectGenre(genre) }
                    )
                }
            }
        }

        selectedCatalog?.let {
            Text(
                text = "${it.addonName} • ${if (it.type == "movie") "Movies" else "TV Shows"}${uiState.selectedDiscoverGenre?.let { g -> " • $g" } ?: ""}",
                style = MaterialTheme.typography.bodySmall,
                color = NuvioColors.TextSecondary
            )
        }

        when {
            uiState.discoverLoading && uiState.discoverResults.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 28.dp, bottom = 28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            }

            uiState.discoverResults.isNotEmpty() -> {
                DiscoverGrid(
                    items = uiState.discoverResults,
                    posterCardStyle = posterCardStyle,
                    focusResults = focusResults,
                    firstItemFocusRequester = firstItemFocusRequester,
                    focusedItemIndex = focusedItemIndex,
                    shouldRestoreFocusedItem = shouldRestoreFocusedItem,
                    onRestoreFocusedItemHandled = onRestoreFocusedItemHandled,
                    onItemFocused = onDiscoverItemFocused,
                    pendingCount = uiState.pendingDiscoverResults.size,
                    canLoadMore = uiState.discoverHasMore,
                    isLoadingMore = uiState.discoverLoadingMore,
                    onShowMore = onShowMore,
                    onLoadMore = onLoadMore,
                    onRequestRestoreFocus = onRequestRestoreFocus,
                    onItemClick = { _, item ->
                        onNavigateToDetail(
                            item.id,
                            item.type.toApiString(),
                            selectedCatalog?.addonBaseUrl ?: ""
                        )
                    }
                )

            }

            uiState.discoverInitialized && selectedCatalog == null -> {
                EmptyScreenState(
                    title = "Select a catalog",
                    subtitle = "Choose a discover catalog to browse",
                    icon = Icons.Default.Search
                )
            }

            uiState.discoverInitialized && !uiState.discoverLoading && selectedCatalog != null -> {
                EmptyScreenState(
                    title = "No content found",
                    subtitle = "Try a different genre or catalog",
                    icon = Icons.Default.Search
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DiscoverFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .onFocusChanged { state ->
                isFocused = state.isFocused
            },
        shape = CardDefaults.shape(shape = RoundedCornerShape(20.dp)),
        colors = CardDefaults.colors(
            containerColor = if (selected) {
                NuvioColors.Secondary.copy(alpha = 0.25f)
            } else {
                NuvioColors.BackgroundCard
            },
            focusedContainerColor = if (selected) {
                NuvioColors.Secondary.copy(alpha = 0.35f)
            } else {
                NuvioColors.BackgroundCard
            }
        ),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, NuvioColors.Border),
                shape = RoundedCornerShape(20.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(20.dp)
            )
        ),
        scale = CardDefaults.scale(
            focusedScale = 1.0f,
            pressedScale = 1.0f
        )
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            color = if (isFocused || selected) NuvioColors.TextPrimary else NuvioColors.TextSecondary
        )
    }
}

@Composable
private fun DiscoverGrid(
    items: List<MetaPreview>,
    posterCardStyle: PosterCardStyle,
    focusResults: Boolean,
    firstItemFocusRequester: FocusRequester,
    focusedItemIndex: Int,
    shouldRestoreFocusedItem: Boolean,
    onRestoreFocusedItemHandled: () -> Unit,
    onItemFocused: (Int) -> Unit,
    pendingCount: Int,
    canLoadMore: Boolean,
    isLoadingMore: Boolean,
    onShowMore: () -> Unit,
    onLoadMore: () -> Unit,
    onRequestRestoreFocus: (Int) -> Unit,
    onItemClick: (Int, MetaPreview) -> Unit
) {
    val restoreFocusRequester = remember { FocusRequester() }
    val actionType = when {
        pendingCount > 0 -> DiscoverGridAction.ShowMore(pendingCount)
        isLoadingMore -> DiscoverGridAction.Loading
        canLoadMore -> DiscoverGridAction.LoadMore
        else -> DiscoverGridAction.None
    }
    val totalCells = items.size + if (actionType != DiscoverGridAction.None) 1 else 0

    androidx.compose.runtime.LaunchedEffect(shouldRestoreFocusedItem, focusedItemIndex, totalCells) {
        if (!shouldRestoreFocusedItem) return@LaunchedEffect
        if (focusedItemIndex !in 0 until totalCells) {
            onRestoreFocusedItemHandled()
            return@LaunchedEffect
        }
        try {
            restoreFocusRequester.requestFocus()
        } catch (_: Exception) {
        }
        repeat(2) { withFrameNanos { } }
        try {
            restoreFocusRequester.requestFocus()
        } catch (_: Exception) {
        }
        onRestoreFocusedItemHandled()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val horizontalSpacing = 10.dp
        val preferredCardWidth = 120.dp
        val minCardWidth = 108.dp
        val maxCardWidth = 132.dp

        fun widthForColumns(columns: Int) =
            (maxWidth - (horizontalSpacing * (columns - 1))) / columns

        var columns = max(
            1,
            ((maxWidth + horizontalSpacing) / (preferredCardWidth + horizontalSpacing)).toInt()
        )

        while (columns > 1 && widthForColumns(columns) < minCardWidth) {
            columns--
        }
        while (widthForColumns(columns) > maxCardWidth) {
            columns++
        }

        val cardWidth = widthForColumns(columns).coerceIn(minCardWidth, maxCardWidth)
        val adaptiveStyle = posterCardStyle.copy(
            width = cardWidth,
            height = cardWidth * 1.5f
        )

        val cellIndices = (0 until totalCells).toList()
        val rows = cellIndices.chunked(columns)
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            rows.forEachIndexed { rowIndex, rowIndices ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(horizontalSpacing)
                ) {
                    rowIndices.forEachIndexed { column, absoluteIndex ->
                        val focusRequester = when {
                            shouldRestoreFocusedItem && absoluteIndex == focusedItemIndex -> restoreFocusRequester
                            focusResults && rowIndex == 0 && column == 0 -> firstItemFocusRequester
                            else -> null
                        }

                        if (absoluteIndex < items.size) {
                            val item = items[absoluteIndex]
                            GridContentCard(
                                item = item,
                                onClick = { onItemClick(absoluteIndex, item) },
                                posterCardStyle = adaptiveStyle,
                                modifier = Modifier.width(adaptiveStyle.width),
                                focusRequester = focusRequester,
                                onFocused = { onItemFocused(absoluteIndex) }
                            )
                        } else {
                            DiscoverActionCard(
                                actionType = actionType,
                                posterCardStyle = adaptiveStyle,
                                modifier = Modifier.width(adaptiveStyle.width),
                                focusRequester = focusRequester,
                                onFocused = { onItemFocused(absoluteIndex) },
                                onClick = {
                                    onRequestRestoreFocus((items.lastIndex).coerceAtLeast(0))
                                    when (actionType) {
                                        is DiscoverGridAction.ShowMore -> onShowMore()
                                        DiscoverGridAction.LoadMore -> onLoadMore()
                                        DiscoverGridAction.Loading -> Unit
                                        DiscoverGridAction.None -> Unit
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private sealed class DiscoverGridAction {
    object None : DiscoverGridAction()
    data class ShowMore(val count: Int) : DiscoverGridAction()
    object LoadMore : DiscoverGridAction()
    object Loading : DiscoverGridAction()
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DiscoverActionCard(
    actionType: DiscoverGridAction,
    posterCardStyle: PosterCardStyle,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    onClick: () -> Unit
) {
    val cardShape = RoundedCornerShape(posterCardStyle.cornerRadius)
    val title = when (actionType) {
        is DiscoverGridAction.ShowMore -> "Show more\n(${actionType.count})"
        DiscoverGridAction.LoadMore -> "Load more"
        DiscoverGridAction.Loading -> "Loading..."
        DiscoverGridAction.None -> ""
    }

    Card(
        onClick = onClick,
        modifier = modifier
            .width(posterCardStyle.width)
            .onFocusChanged { state -> if (state.isFocused) onFocused() }
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            ),
        shape = CardDefaults.shape(shape = cardShape),
        colors = CardDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.FocusBackground
        ),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, NuvioColors.Border),
                shape = cardShape
            ),
            focusedBorder = Border(
                border = BorderStroke(posterCardStyle.focusedBorderWidth, NuvioColors.FocusRing),
                shape = cardShape
            )
        ),
        scale = CardDefaults.scale(focusedScale = posterCardStyle.focusedScale)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .width(posterCardStyle.width)
                .aspectRatio(posterCardStyle.aspectRatio),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = NuvioColors.TextPrimary,
                textAlign = TextAlign.Center
            )
        }
    }
}
