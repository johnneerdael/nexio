package com.nuvio.tv.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.grid.TvGridCells
import androidx.tv.foundation.lazy.grid.TvGridItemSpan
import androidx.tv.foundation.lazy.grid.TvLazyVerticalGrid
import androidx.tv.foundation.lazy.grid.rememberTvLazyGridState
import androidx.tv.material3.Border
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.GridContinueWatchingSection
import com.nuvio.tv.ui.components.HeroCarousel
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.flow.distinctUntilChanged

private const val GRID_COLUMNS = 5

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GridHomeContent(
    uiState: HomeUiState,
    gridFocusState: HomeScreenFocusState,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit,
    onLoadMore: (catalogId: String, addonId: String, type: String) -> Unit,
    onSaveGridFocusState: (Int, Int) -> Unit
) {
    val gridState = rememberTvLazyGridState(
        initialFirstVisibleItemIndex = gridFocusState.verticalScrollIndex,
        initialFirstVisibleItemScrollOffset = gridFocusState.verticalScrollOffset
    )

    // Save scroll state when leaving
    DisposableEffect(Unit) {
        onDispose {
            onSaveGridFocusState(
                gridState.firstVisibleItemIndex,
                gridState.firstVisibleItemScrollOffset
            )
        }
    }

    // Offset for section indices when continue watching is present
    val continueWatchingOffset = if (uiState.continueWatchingItems.isNotEmpty()) 1 else 0

    // Build index-to-section mapping for sticky header
    val sectionMapping = remember(uiState.gridItems, continueWatchingOffset) {
        buildSectionMapping(uiState.gridItems, continueWatchingOffset)
    }

    // Track current section for sticky header
    var currentSectionName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(gridState, sectionMapping) {
        snapshotFlow { gridState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { firstVisibleIndex ->
                val section = sectionMapping.findSectionForIndex(firstVisibleIndex)
                currentSectionName = section?.catalogName
            }
    }

    // Pre-compute whether hero exists to avoid repeated list scan in derivedStateOf
    val hasHero = remember(uiState.gridItems) {
        uiState.gridItems.firstOrNull() is GridItem.Hero
    }

    // Determine if hero is scrolled past
    val isScrolledPastHero by remember(hasHero) {
        derivedStateOf {
            if (hasHero) {
                gridState.firstVisibleItemIndex > 0
            } else {
                true
            }
        }
    }

    // Pre-compute section bounds once when gridItems changes (not on every scroll)
    val sectionBounds = remember(uiState.gridItems, continueWatchingOffset) {
        buildSectionBounds(uiState.gridItems, continueWatchingOffset)
    }

    // Per-section load-more detection
    LaunchedEffect(gridState, sectionBounds, uiState.catalogRows) {
        snapshotFlow {
            gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        }
            .distinctUntilChanged()
            .collect { lastVisibleIndex ->
                for (bound in sectionBounds) {
                    // If the last visible item is within 5 items of this section's end
                    if (lastVisibleIndex >= bound.lastContentIndex - 5 && lastVisibleIndex <= bound.lastContentIndex) {
                        val row = uiState.catalogRows.find {
                            it.catalogId == bound.catalogId && it.addonId == bound.addonId
                        }
                        if (row != null && row.hasMore && !row.isLoading) {
                            onLoadMore(row.catalogId, row.addonId, row.type.toApiString())
                        }
                    }
                }
            }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        TvLazyVerticalGrid(
            state = gridState,
            columns = TvGridCells.Fixed(GRID_COLUMNS),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 24.dp,
                end = 24.dp,
                top = 0.dp,
                bottom = 32.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            var continueWatchingInserted = false

            uiState.gridItems.forEachIndexed { index, gridItem ->
                when (gridItem) {
                    is GridItem.Hero -> {
                        item(
                            key = "hero",
                            span = { TvGridItemSpan(maxLineSpan) },
                            contentType = "hero"
                        ) {
                            HeroCarousel(
                                items = gridItem.items,
                                onItemClick = { item ->
                                    onNavigateToDetail(
                                        item.id,
                                        item.type.toApiString(),
                                        ""
                                    )
                                }
                            )
                        }
                    }

                    is GridItem.SectionDivider -> {
                        // Insert continue watching before the first section divider
                        if (!continueWatchingInserted && uiState.continueWatchingItems.isNotEmpty()) {
                            continueWatchingInserted = true
                            item(
                                key = "continue_watching",
                                span = { TvGridItemSpan(maxLineSpan) },
                                contentType = "continue_watching"
                            ) {
                                GridContinueWatchingSection(
                                    items = uiState.continueWatchingItems,
                                    onItemClick = { progress ->
                                        onNavigateToDetail(
                                            progress.contentId,
                                            progress.contentType,
                                            ""
                                        )
                                    }
                                )
                            }
                        }

                        item(
                            key = "divider_${index}_${gridItem.catalogId}_${gridItem.addonId}_${gridItem.type}",
                            span = { TvGridItemSpan(maxLineSpan) },
                            contentType = "divider"
                        ) {
                            SectionDivider(
                                catalogName = gridItem.catalogName
                            )
                        }
                    }

                    is GridItem.Content -> {
                        item(
                            key = "content_${index}_${gridItem.catalogId}_${gridItem.item.id}",
                            span = { TvGridItemSpan(1) },
                            contentType = "content"
                        ) {
                            GridContentCard(
                                item = gridItem.item,
                                onClick = {
                                    onNavigateToDetail(
                                        gridItem.item.id,
                                        gridItem.item.type.toApiString(),
                                        gridItem.addonBaseUrl
                                    )
                                }
                            )
                        }
                    }

                    is GridItem.SeeAll -> {
                        item(
                            key = "see_all_${gridItem.catalogId}_${gridItem.addonId}_${gridItem.type}",
                            span = { TvGridItemSpan(1) },
                            contentType = "see_all"
                        ) {
                            SeeAllGridCard(
                                onClick = {
                                    onNavigateToCatalogSeeAll(
                                        gridItem.catalogId,
                                        gridItem.addonId,
                                        gridItem.type
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }

        // Sticky header overlay
        AnimatedVisibility(
            visible = isScrolledPastHero && currentSectionName != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            StickyCategoryHeader(
                sectionName = currentSectionName ?: ""
            )
        }
    }
}

@Composable
private fun SectionDivider(
    catalogName: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 12.dp, start = 24.dp, end = 24.dp)
    ) {
        Text(
            text = catalogName,
            style = MaterialTheme.typography.headlineMedium,
            color = NuvioColors.TextPrimary
        )
    }
}

@Composable
private fun StickyCategoryHeader(
    sectionName: String,
    modifier: Modifier = Modifier
) {
    val bgColor = NuvioColors.Background
    val headerGradient = remember(bgColor) {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to bgColor,
                0.7f to bgColor.copy(alpha = 0.95f),
                1.0f to Color.Transparent
            )
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(headerGradient)
            .padding(horizontal = 48.dp, vertical = 12.dp)
    ) {
        Crossfade(
            targetState = sectionName,
            animationSpec = tween(150),
            label = "sectionNameCrossfade"
        ) { name ->
            Text(
                text = name,
                style = MaterialTheme.typography.titleLarge,
                color = NuvioColors.TextPrimary
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SeeAllGridCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(210.dp),
        shape = CardDefaults.shape(
            shape = RoundedCornerShape(8.dp)
        ),
        colors = CardDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.BackgroundCard
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(8.dp)
            )
        ),
        scale = CardDefaults.scale(
            focusedScale = 1.02f
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "See All",
                    modifier = Modifier.size(32.dp),
                    tint = NuvioColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "See All",
                    style = MaterialTheme.typography.titleSmall,
                    color = NuvioColors.TextSecondary
                )
            }
        }
    }
}

// Section mapping utilities

private data class SectionInfo(
    val catalogName: String,
    val catalogId: String,
    val addonId: String,
    val type: String
)

private class SectionMapping(
    private val indexToSection: Map<Int, SectionInfo>
) {
    fun findSectionForIndex(index: Int): SectionInfo? {
        // Find the section for the given index by searching backwards
        for (i in index downTo 0) {
            indexToSection[i]?.let { return it }
        }
        return null
    }
}

private fun buildSectionMapping(gridItems: List<GridItem>, indexOffset: Int = 0): SectionMapping {
    val mapping = mutableMapOf<Int, SectionInfo>()
    gridItems.forEachIndexed { index, item ->
        if (item is GridItem.SectionDivider) {
            mapping[index + indexOffset] = SectionInfo(
                catalogName = item.catalogName,
                catalogId = item.catalogId,
                addonId = item.addonId,
                type = item.type
            )
        }
    }
    return SectionMapping(mapping)
}

private data class SectionBound(
    val catalogId: String,
    val addonId: String,
    val lastContentIndex: Int
)

private fun buildSectionBounds(gridItems: List<GridItem>, indexOffset: Int = 0): List<SectionBound> {
    val bounds = mutableListOf<SectionBound>()
    var currentCatalogId: String? = null
    var currentAddonId: String? = null
    var lastContentIndex = 0

    fun flushSection() {
        val catId = currentCatalogId ?: return
        val addId = currentAddonId ?: return
        bounds.add(SectionBound(catId, addId, lastContentIndex))
    }

    gridItems.forEachIndexed { index, item ->
        when (item) {
            is GridItem.SectionDivider -> {
                flushSection()
                currentCatalogId = item.catalogId
                currentAddonId = item.addonId
            }
            is GridItem.Content -> {
                lastContentIndex = index + indexOffset
            }
            is GridItem.Hero -> { /* skip */ }
            is GridItem.SeeAll -> { /* skip */ }
        }
    }
    flushSection()
    return bounds
}
