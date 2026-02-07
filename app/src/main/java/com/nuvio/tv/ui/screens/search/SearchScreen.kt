package com.nuvio.tv.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.CatalogRowSection
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.ErrorState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioColors

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            item {
                OutlinedTextField(
                    value = uiState.query,
                    onValueChange = { viewModel.onEvent(SearchEvent.QueryChanged(it)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 48.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    placeholder = {
                        Text(
                            text = "Search movies & series",
                            color = NuvioColors.TextTertiary
                        )
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = NuvioColors.BackgroundCard,
                        unfocusedContainerColor = NuvioColors.BackgroundCard,
                        focusedIndicatorColor = NuvioColors.FocusRing,
                        unfocusedIndicatorColor = NuvioColors.Border,
                        focusedTextColor = NuvioColors.TextPrimary,
                        unfocusedTextColor = NuvioColors.TextPrimary,
                        cursorColor = NuvioColors.FocusRing
                    )
                )
            }

            when {
                uiState.query.trim().length < 2 -> {
                    item {
                        EmptyScreenState(
                            title = "Start Searching",
                            subtitle = "Type at least 2 characters to search",
                            icon = Icons.Default.Search
                        )
                    }
                }

                uiState.isSearching && uiState.catalogRows.isEmpty() -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 80.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            LoadingIndicator()
                        }
                    }
                }

                uiState.error != null && uiState.catalogRows.isEmpty() -> {
                    item {
                        ErrorState(
                            message = uiState.error ?: "Search failed",
                            onRetry = { viewModel.onEvent(SearchEvent.Retry) }
                        )
                    }
                }

                uiState.catalogRows.isEmpty() -> {
                    item {
                        EmptyScreenState(
                            title = "No Results",
                            subtitle = "Try searching with different keywords",
                            icon = Icons.Default.Search
                        )
                    }
                }

                else -> {
                    itemsIndexed(
                        items = uiState.catalogRows,
                        key = { _, item -> "${item.addonId}_${item.type}_${item.catalogId}_${uiState.query.trim()}" }
                    ) { index, catalogRow ->
                        CatalogRowSection(
                            catalogRow = catalogRow,
                            onItemClick = { id, type, addonBaseUrl ->
                                onNavigateToDetail(id, type, addonBaseUrl)
                            },
                            onLoadMore = {
                                viewModel.onEvent(
                                    SearchEvent.LoadMoreCatalog(
                                        catalogId = catalogRow.catalogId,
                                        addonId = catalogRow.addonId,
                                        type = catalogRow.type.toApiString()
                                    )
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}
