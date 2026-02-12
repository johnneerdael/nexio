package com.nuvio.tv.ui.screens.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.ui.components.ErrorState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioColors
import kotlin.math.roundToInt

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit = { item ->
        onNavigateToDetail(
            when (item) {
                is ContinueWatchingItem.InProgress -> item.progress.contentId
                is ContinueWatchingItem.NextUp -> item.info.contentId
            },
            when (item) {
                is ContinueWatchingItem.InProgress -> item.progress.contentType
                is ContinueWatchingItem.NextUp -> item.info.contentType
            },
            ""
        )
    },
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit = { _, _, _ -> }
) {
    val uiState by viewModel.uiState.collectAsState()
    val focusState by viewModel.focusState.collectAsState()
    val gridFocusState by viewModel.gridFocusState.collectAsState()
    val hasHeroContent = uiState.heroSectionEnabled && uiState.heroItems.isNotEmpty()
    val hasCatalogContent = uiState.catalogRows.any { it.items.isNotEmpty() }
    val hasContinueWatchingContent = uiState.continueWatchingItems.isNotEmpty()
    var hasEnteredCatalogContent: Boolean by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(hasCatalogContent) {
        if (hasCatalogContent) {
            hasEnteredCatalogContent = true
        }
    }

    val computedHeightDp = (uiState.posterCardWidthDp * 1.5f).roundToInt()
    val posterCardStyle = PosterCardStyle(
        width = uiState.posterCardWidthDp.dp,
        height = computedHeightDp.dp,
        cornerRadius = uiState.posterCardCornerRadiusDp.dp,
        focusedBorderWidth = PosterCardDefaults.Style.focusedBorderWidth,
        focusedScale = PosterCardDefaults.Style.focusedScale
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
    ) {
        when {
            uiState.isLoading && uiState.catalogRows.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            }
            uiState.error == "No addons installed" && uiState.catalogRows.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No addons installed. Add one to get started.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioColors.TextSecondary
                    )
                }
            }
            uiState.error == "No catalog addons installed" && uiState.catalogRows.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No catalog addons installed. Install a catalog addon to see content.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioColors.TextSecondary
                    )
                }
            }
            uiState.error != null && uiState.catalogRows.isEmpty() -> {
                ErrorState(
                    message = uiState.error ?: "An error occurred",
                    onRetry = { viewModel.onEvent(HomeEvent.OnRetry) }
                )
            }
            else -> {
                val shouldShowLoadingGate = hasEnteredCatalogContent == false && hasCatalogContent == false

                Crossfade(
                    targetState = shouldShowLoadingGate,
                    animationSpec = tween(durationMillis = 220),
                    label = "homeLoadingGate"
                ) { showLoading ->
                    if (showLoading) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            LoadingIndicator()
                        }
                    } else {
                        when (uiState.homeLayout) {
                            HomeLayout.CLASSIC -> ClassicHomeContent(
                                uiState = uiState,
                                posterCardStyle = posterCardStyle,
                                focusState = focusState,
                                onNavigateToDetail = onNavigateToDetail,
                                onContinueWatchingClick = onContinueWatchingClick,
                                onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAll,
                                onRemoveContinueWatching = { contentId ->
                                    viewModel.onEvent(HomeEvent.OnRemoveContinueWatching(contentId))
                                },
                                onRequestTrailerPreview = { item ->
                                    viewModel.requestTrailerPreview(item)
                                },
                                onSaveFocusState = { vi, vo, ri, ii, m ->
                                    viewModel.saveFocusState(vi, vo, ri, ii, m)
                                }
                            )
                            HomeLayout.GRID -> GridHomeContent(
                                uiState = uiState,
                                posterCardStyle = posterCardStyle,
                                gridFocusState = gridFocusState,
                                onNavigateToDetail = onNavigateToDetail,
                                onContinueWatchingClick = onContinueWatchingClick,
                                onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAll,
                                onRemoveContinueWatching = { contentId ->
                                    viewModel.onEvent(HomeEvent.OnRemoveContinueWatching(contentId))
                                },
                                onSaveGridFocusState = { vi, vo ->
                                    viewModel.saveGridFocusState(vi, vo)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
