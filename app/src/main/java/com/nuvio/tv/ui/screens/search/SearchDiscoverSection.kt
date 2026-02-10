package com.nuvio.tv.ui.screens.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioColors

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun DiscoverSection(
    uiState: SearchUiState,
    focusResults: Boolean,
    firstItemFocusRequester: FocusRequester,
    onNavigateToDetail: (String, String, String) -> Unit,
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
                    focusResults = focusResults,
                    firstItemFocusRequester = firstItemFocusRequester,
                    onItemClick = { item ->
                        onNavigateToDetail(
                            item.id,
                            item.type.toApiString(),
                            selectedCatalog?.addonBaseUrl ?: ""
                        )
                    }
                )

                if (uiState.pendingDiscoverResults.isNotEmpty()) {
                    Button(
                        onClick = onShowMore,
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp)),
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioColors.BackgroundCard,
                            focusedContainerColor = NuvioColors.FocusBackground,
                            contentColor = NuvioColors.TextPrimary,
                            focusedContentColor = NuvioColors.TextPrimary
                        ),
                        border = ButtonDefaults.border(
                            focusedBorder = Border(
                                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                shape = RoundedCornerShape(12.dp)
                            )
                        )
                    ) {
                        Text("Show more (${uiState.pendingDiscoverResults.size})")
                    }
                } else if (uiState.discoverHasMore) {
                    if (uiState.discoverLoadingMore) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            LoadingIndicator()
                        }
                    } else {
                        Button(
                            onClick = onLoadMore,
                            shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp)),
                            colors = ButtonDefaults.colors(
                                containerColor = NuvioColors.BackgroundCard,
                                focusedContainerColor = NuvioColors.FocusBackground,
                                contentColor = NuvioColors.TextPrimary,
                                focusedContentColor = NuvioColors.TextPrimary
                            ),
                            border = ButtonDefaults.border(
                                focusedBorder = Border(
                                    border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                    shape = RoundedCornerShape(12.dp)
                                )
                            )
                        ) {
                            Text("Load more")
                        }
                    }
                }
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
                val nowFocused = state.isFocused
                isFocused = nowFocused
                if (nowFocused && !selected) {
                    onClick()
                }
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
    focusResults: Boolean,
    firstItemFocusRequester: FocusRequester,
    onItemClick: (MetaPreview) -> Unit
) {
    val columns = 5
    val rows = items.chunked(columns)

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        rows.forEachIndexed { rowIndex, rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                for (column in 0 until columns) {
                    if (column < rowItems.size) {
                        val item = rowItems[column]
                        GridContentCard(
                            item = item,
                            onClick = { onItemClick(item) },
                            modifier = Modifier.weight(1f),
                            focusRequester = if (focusResults && rowIndex == 0 && column == 0) {
                                firstItemFocusRequester
                            } else {
                                null
                            }
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
