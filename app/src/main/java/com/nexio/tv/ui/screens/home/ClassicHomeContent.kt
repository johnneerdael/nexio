package com.nexio.tv.ui.screens.home

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.ui.components.CatalogRowSection
import com.nexio.tv.ui.components.ContinueWatchingResolvedDisplayItem
import com.nexio.tv.ui.components.ContinueWatchingSection
import com.nexio.tv.ui.components.HeroCarousel
import com.nexio.tv.ui.components.PosterCardStyle

/** Minimum interval between processed key repeat events to prevent HWUI overload. */
private const val KEY_REPEAT_THROTTLE_MS = 80L

private class FocusSnapshot(
    var rowIndex: Int,
    var itemIndex: Int
)

internal fun catalogRowContentType(row: com.nexio.tv.domain.model.CatalogRow): String = row.apiType

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ClassicHomeContent(
    uiState: HomeUiState,
    presentation: ClassicHomePresentationState,
    continueWatchingItems: List<ContinueWatchingResolvedDisplayItem>,
    posterCardStyle: PosterCardStyle,
    focusState: HomeScreenFocusState,
    trailerPreviewUrls: Map<String, String>,
    trailerPreviewAudioUrls: Map<String, String>,
    trailerPreviewUserAgents: Map<String, String>,
    trailerPreviewSigningClientKeys: Map<String, String>,
    trailerPreviewCaptions: Map<String, List<com.nexio.tv.data.trailer.YouTubeCaptionTrack>>,
    trailerPreviewExternalUrls: Map<String, String>,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit = {},
    onContinueWatchingManualStreamSelection: (ContinueWatchingItem) -> Unit = {},
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit,
    onRemoveContinueWatching: (String, Int?, Int?, Boolean) -> Unit,
    onMarkContinueWatchingWatched: (ContinueWatchingItem) -> Unit = {},
    onCheckInContinueWatching: ((ContinueWatchingItem) -> Unit)? = null,
    cwWatchlistMembership: Map<String, Boolean> = emptyMap(),
    onToggleContinueWatchingLibrary: ((ContinueWatchingItem) -> Unit)? = null,
    isCatalogItemWatched: (MetaPreview) -> Boolean = { false },
    onCatalogItemLongPress: (MetaPreview, String) -> Unit = { _, _ -> },
    onRequestTrailerPreview: (MetaPreview) -> Unit,
    onItemFocus: (MetaPreview) -> Unit = {},
    onSaveFocusState: (Int, Int, Int, Int, Map<String, Int>) -> Unit,
    resolvedHeroItems: List<HeroDisplayItem> = emptyList()
) {

    // Nested prefetch: when LazyColumn prefetches a row ahead of scrolling,
    // pre-compose up to 2 ContentCards in its nested LazyRow across multiple frames.
    // This spreads the composition work and prevents frame spikes when a new row scrolls in.
    val nestedPrefetchStrategy = remember { LazyListPrefetchStrategy(nestedPrefetchItemCount = 2) }

    val columnListState = rememberLazyListState(
        initialFirstVisibleItemIndex = focusState.verticalScrollIndex,
        initialFirstVisibleItemScrollOffset = focusState.verticalScrollOffset,
        prefetchStrategy = nestedPrefetchStrategy
    )

    LaunchedEffect(focusState.verticalScrollIndex, focusState.verticalScrollOffset) {
        val targetIndex = focusState.verticalScrollIndex
        val targetOffset = focusState.verticalScrollOffset
        if (columnListState.firstVisibleItemIndex == targetIndex &&
            columnListState.firstVisibleItemScrollOffset == targetOffset
        ) {
            return@LaunchedEffect
        }
        if (targetIndex > 0 || targetOffset > 0) {
            columnListState.scrollToItem(
                targetIndex,
                targetOffset
            )
        }
    }

    val currentFocusSnapshot = remember {
        FocusSnapshot(
            rowIndex = focusState.focusedRowIndex,
            itemIndex = focusState.focusedItemIndex
        )
    }

    // Store scroll state for each row to persist position during recycling
    val rowStates = remember { mutableMapOf<String, LazyListState>() }
    val rowFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }

    var restoringFocus by remember { mutableStateOf(focusState.hasSavedFocus) }
    val heroFocusRequester = remember { FocusRequester() }
    val shouldRequestInitialFocus = remember(focusState) {
        !focusState.hasSavedFocus &&
            focusState.verticalScrollIndex == 0 &&
            focusState.verticalScrollOffset == 0
    }
    // Plan B Task 5a — Classic Home now consumes the typed-surface
    // [ClassicHomePresentationState]. The ViewModel-side
    // [observeClassicHomePresentationPipeline] builds the deduped
    // (resolvedRail, catalogRow) list, the resolved-item lookup map, and the
    // active rail-key set with reference-stable memoization (CLAUDE.md rule #5).
    // Compose-side recomputation of those structures is gone — `presentation`
    // is already canonical.
    val visibleRows: List<ClassicHomeRailEntry> = presentation.visibleRows
    val resolvedItemsByItemKey: Map<String, ModernHomeRowItem> = presentation.resolvedItemsByItemKey
    val visibleCatalogKeys: Set<String> = presentation.visibleCatalogKeys

    LaunchedEffect(visibleCatalogKeys) {
        rowStates.keys.retainAll(visibleCatalogKeys)
        rowFocusRequesters.keys.retainAll(visibleCatalogKeys)
    }

    DisposableEffect(Unit) {
        onDispose {
            onSaveFocusState(
                columnListState.firstVisibleItemIndex,
                columnListState.firstVisibleItemScrollOffset,
                currentFocusSnapshot.rowIndex,
                currentFocusSnapshot.itemIndex,
                focusState.catalogRowScrollStates + rowStates.mapValues { it.value.firstVisibleItemIndex }
            )
        }
    }

    val heroVisible = uiState.heroSectionEnabled && uiState.heroItems.isNotEmpty()

    LaunchedEffect(shouldRequestInitialFocus, heroVisible, uiState.heroItems.size) {
        if (!shouldRequestInitialFocus || !heroVisible) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        try {
            heroFocusRequester.requestFocus()
        } catch (_: IllegalStateException) {
        }
    }

    // Throttle D-pad key repeats to prevent HWUI overload when a key is held down.
    var lastKeyRepeatTime by remember { mutableStateOf(0L) }

    LazyColumn(
        state = columnListState,
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount > 0) {
                    val now = System.currentTimeMillis()
                    if (now - lastKeyRepeatTime < KEY_REPEAT_THROTTLE_MS) {
                        return@onPreviewKeyEvent true // consume — too fast
                    }
                    lastKeyRepeatTime = now
                }
                false
            },
        contentPadding = PaddingValues(top = if (heroVisible) 0.dp else 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        if (heroVisible) {
            item(key = "hero_carousel", contentType = "hero") {
                HeroCarousel(
                    items = uiState.heroItems,
                    focusRequester = if (shouldRequestInitialFocus) heroFocusRequester else null,
                    onItemFocus = onItemFocus,
                    onItemClick = { item ->
                        onNavigateToDetail(
                            item.id,
                            item.apiType,
                            ""
                        )
                    },
                    resolvedHeroItems = resolvedHeroItems
                )
            }
        }

        if (continueWatchingItems.isNotEmpty()) {
            item(key = "continue_watching", contentType = "continue_watching") {
                ContinueWatchingSection(
                    items = continueWatchingItems,
                    onItemClick = { item ->
                        onContinueWatchingClick(item)
                    },
                    onStartFromBeginning = onContinueWatchingStartFromBeginning,
                    showManualStreamSelection = { item ->
                        shouldShowContinueWatchingManualStreamSelection(
                            deterministicAutoplayEnabled = uiState.deterministicAutoplayEnabled,
                            item = item
                        )
                    },
                    onPlayWithManualStreamSelection = onContinueWatchingManualStreamSelection,
                    onMarkAsWatched = onMarkContinueWatchingWatched,
                    onCheckIn = onCheckInContinueWatching,
                    cwWatchlistMembership = cwWatchlistMembership,
                    onToggleLibrary = onToggleContinueWatchingLibrary,
                    onDetailsClick = { item ->
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
                    onRemoveItem = { item ->
                        val contentId = when (item) {
                            is ContinueWatchingItem.InProgress -> item.progress.contentId
                            is ContinueWatchingItem.NextUp -> item.info.contentId
                        }
                        val season = when (item) {
                            is ContinueWatchingItem.InProgress -> item.progress.season
                            is ContinueWatchingItem.NextUp -> item.info.season
                        }
                        val episode = when (item) {
                            is ContinueWatchingItem.InProgress -> item.progress.episode
                            is ContinueWatchingItem.NextUp -> item.info.episode
                        }
                        val isNextUp = item is ContinueWatchingItem.NextUp
                        onRemoveContinueWatching(contentId, season, episode, isNextUp)
                    },
                    focusedItemIndex = when {
                        focusState.hasSavedFocus && focusState.focusedRowIndex == -1 -> focusState.focusedItemIndex
                        shouldRequestInitialFocus && !heroVisible -> 0
                        else -> -1
                    },
                    onItemFocused = { itemIndex ->
                        currentFocusSnapshot.rowIndex = -1
                        currentFocusSnapshot.itemIndex = itemIndex
                    }
                )
            }
        }

        itemsIndexed(
            items = visibleRows,
            key = { _, row -> row.railKey },
            contentType = { _, row -> catalogRowContentType(row.catalogRow) }
        ) { index, entry ->
            val catalogRow = entry.catalogRow
            val catalogKey = entry.railKey
            val shouldRestoreFocus = restoringFocus && index == focusState.focusedRowIndex
            val shouldInitialFocusFirstCatalogRow =
                shouldRequestInitialFocus &&
                    !heroVisible &&
                    continueWatchingItems.isEmpty() &&
                    index == 0
            val focusedItemIndex = when {
                shouldRestoreFocus -> focusState.focusedItemIndex
                shouldInitialFocusFirstCatalogRow -> 0
                else -> -1
            }

            val listState = rowStates.getOrPut(catalogKey) {
                LazyListState(
                    firstVisibleItemIndex = focusState.catalogRowScrollStates[catalogKey] ?: 0
                )
            }
            val rowFocusRequester = rowFocusRequesters.getOrPut(catalogKey) { FocusRequester() }

            CatalogRowSection(
                catalogRow = catalogRow,
                resolvedItemsByItemKey = resolvedItemsByItemKey,
                posterCardStyle = posterCardStyle,
                showPosterLabels = uiState.posterLabelsEnabled,
                showAddonName = uiState.catalogAddonNameEnabled,
                showCatalogTypeSuffix = uiState.catalogTypeSuffixEnabled,
                focusedPosterBackdropExpandEnabled = uiState.focusedPosterBackdropExpandEnabled,
                focusedPosterBackdropExpandDelaySeconds = uiState.focusedPosterBackdropExpandDelaySeconds,
                focusedPosterBackdropTrailerEnabled = uiState.focusedPosterBackdropTrailerEnabled,
                focusedPosterBackdropTrailerMuted = uiState.focusedPosterBackdropTrailerMuted,
                trailerPreviewUrls = trailerPreviewUrls,
                trailerPreviewAudioUrls = trailerPreviewAudioUrls,
                trailerPreviewUserAgents = trailerPreviewUserAgents,
                trailerPreviewSigningClientKeys = trailerPreviewSigningClientKeys,
                trailerPreviewCaptions = trailerPreviewCaptions,
                trailerPreviewExternalUrls = trailerPreviewExternalUrls,
                onRequestTrailerPreview = onRequestTrailerPreview,
                onItemFocus = onItemFocus,
                isItemWatched = isCatalogItemWatched,
                onItemLongPress = onCatalogItemLongPress,
                onItemClick = { id, type, addonBaseUrl ->
                    onNavigateToDetail(id, type, addonBaseUrl)
                },
                onSeeAll = {
                    onNavigateToCatalogSeeAll(
                        catalogRow.catalogId,
                        catalogRow.addonId,
                        catalogRow.apiType
                    )
                },
                rowFocusRequester = rowFocusRequester,
                listState = listState,
                enableRowFocusRestorer = true,
                
                focusedItemIndex = focusedItemIndex,
                onItemFocused = { itemIndex ->
                    if (restoringFocus) restoringFocus = false
                    currentFocusSnapshot.rowIndex = index
                    currentFocusSnapshot.itemIndex = itemIndex
                }
            )
        }
    }
}
