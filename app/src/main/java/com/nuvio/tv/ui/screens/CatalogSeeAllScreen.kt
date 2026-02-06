@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.foundation.lazy.grid.TvGridCells
import androidx.tv.foundation.lazy.grid.TvLazyVerticalGrid
import androidx.tv.foundation.lazy.grid.items
import androidx.tv.foundation.lazy.grid.rememberTvLazyGridState
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.screens.home.HomeEvent
import com.nuvio.tv.ui.screens.home.HomeViewModel
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun CatalogSeeAllScreen(
    catalogId: String,
    addonId: String,
    type: String,
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onBackPress: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    BackHandler { onBackPress() }

    // Find the matching catalog row
    val catalogKey = "${addonId}_${type}_${catalogId}"
    val catalogRow = uiState.catalogRows.find {
        "${it.addonId}_${it.type.toApiString()}_${it.catalogId}" == catalogKey
    }

    val gridState = rememberTvLazyGridState()

    // Load more when scrolling near the bottom
    LaunchedEffect(gridState, catalogRow?.items?.size) {
        snapshotFlow {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = gridState.layoutInfo.totalItemsCount
            lastVisible to total
        }
            .distinctUntilChanged()
            .collect { (lastVisible, total) ->
                if (total > 0 && lastVisible >= total - 10) {
                    val row = catalogRow
                    if (row != null && row.hasMore && !row.isLoading) {
                        viewModel.onEvent(
                            HomeEvent.OnLoadMoreCatalog(row.catalogId, row.addonId, row.type.toApiString())
                        )
                    }
                }
            }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
            .padding(horizontal = 48.dp, vertical = 24.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = catalogRow?.catalogName ?: "Catalog",
                style = MaterialTheme.typography.headlineLarge,
                color = NuvioColors.TextPrimary
            )
        }

        catalogRow?.addonName?.let { addonName ->
            Text(
                text = "from $addonName",
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioColors.TextSecondary
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (catalogRow != null && catalogRow.items.isNotEmpty()) {
            TvLazyVerticalGrid(
                state = gridState,
                columns = TvGridCells.Fixed(5),
                contentPadding = PaddingValues(bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(
                    items = catalogRow.items,
                    key = { "${catalogRow.catalogId}_${it.id}" }
                ) { item ->
                    GridContentCard(
                        item = item,
                        onClick = {
                            onNavigateToDetail(
                                item.id,
                                item.type.toApiString(),
                                catalogRow.addonBaseUrl
                            )
                        }
                    )
                }
            }
        } else {
            Text(
                text = "No items available",
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioColors.TextSecondary
            )
        }
    }
}
