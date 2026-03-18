@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package com.nexio.tv.ui.screens.home

import android.util.Log
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.nexio.tv.R
import com.nexio.tv.LocalContentFocusRequester
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.ui.components.ContinueWatchingCard
import com.nexio.tv.ui.components.ContinueWatchingOptionsDialog
import com.nexio.tv.ui.components.MonochromePosterPlaceholder
import com.nexio.tv.ui.theme.NexioColors
import kotlinx.coroutines.delay
import android.view.KeyEvent as AndroidKeyEvent
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val KEY_REPEAT_THROTTLE_MS = 80L
private const val MODERN_HERO_RAPID_NAV_THRESHOLD_MS = 130L
private const val MODERN_HERO_RAPID_NAV_SETTLE_MS = 170L
private const val MODERN_HOME_CONTENT_LOG_TAG = "ModernHomeContent"

@Composable
internal fun ModernHomeContent(
    contentState: ModernHomeContentState,
    focusState: HomeScreenFocusState,
    enrichingItemIdState: State<String?>,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit = {},
    onLoadMoreCatalog: (String, String, String) -> Unit,
    onRemoveContinueWatching: (String, Int?, Int?, Boolean) -> Unit,
    onMarkContinueWatchingWatched: (ContinueWatchingItem) -> Unit = {},
    onCheckInContinueWatching: ((ContinueWatchingItem) -> Unit)? = null,
    onManageListsContinueWatching: ((ContinueWatchingItem) -> Unit)? = null,
    isCatalogItemWatched: (MetaPreview) -> Boolean = { false },
    onCatalogItemLongPress: (MetaPreview, String) -> Unit = { _, _ -> },
    onItemFocus: (MetaPreview) -> Unit = {},
    onPreloadAdjacentItem: (MetaPreview) -> Unit = {},
    onSaveFocusState: (Int, Int, Int, Int, Map<String, Int>) -> Unit
) {
    val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
    val useLandscapePosters = contentState.modernLandscapePostersEnabled
    val showCatalogTypeSuffixInModern = contentState.catalogTypeSuffixEnabled
    val isLandscapeModern = useLandscapePosters
    val expandControlAvailable = !isLandscapeModern
    val landscapeExpandedCardMode = false
    val effectiveExpandEnabled =
        (contentState.focusedPosterBackdropExpandEnabled && expandControlAvailable) ||
            landscapeExpandedCardMode
    val shouldActivateFocusedPosterFlow = effectiveExpandEnabled
    val visibleCatalogRows = remember(contentState.catalogRows) {
        contentState.catalogRows.filter { it.items.isNotEmpty() }
    }
    val strContinueWatching = stringResource(R.string.continue_watching)
    val strAirsDate = stringResource(R.string.cw_airs_date)
    val strUpcoming = stringResource(R.string.cw_upcoming)
    val strTypeMovie = stringResource(R.string.type_movie)
    val strTypeSeries = stringResource(R.string.type_series)
    val rowBuildCache = remember { ModernCarouselRowBuildCache() }
    val carouselRows = remember(
        contentState.continueWatchingItems,
        visibleCatalogRows,
        useLandscapePosters,
        showCatalogTypeSuffixInModern,
        strTypeMovie,
        strTypeSeries
    ) {
        buildList {
            val activeCatalogKeys = LinkedHashSet<String>(visibleCatalogRows.size)
            if (contentState.continueWatchingItems.isNotEmpty()) {
                val reuseContinueWatchingRow =
                    rowBuildCache.continueWatchingRow != null &&
                        rowBuildCache.continueWatchingItems == contentState.continueWatchingItems &&
                        rowBuildCache.continueWatchingTitle == strContinueWatching &&
                        rowBuildCache.continueWatchingAirsDateTemplate == strAirsDate &&
                        rowBuildCache.continueWatchingUpcomingLabel == strUpcoming &&
                        rowBuildCache.continueWatchingUseLandscapePosters == useLandscapePosters
                val continueWatchingRow = if (reuseContinueWatchingRow) {
                    checkNotNull(rowBuildCache.continueWatchingRow)
                } else {
                    HeroCarouselRow(
                        key = "continue_watching",
                        title = strContinueWatching,
                        globalRowIndex = -1,
                        items = contentState.continueWatchingItems.map { item ->
                            buildContinueWatchingItem(
                                item = item,
                                useLandscapePosters = useLandscapePosters,
                                airsDateTemplate = strAirsDate,
                                upcomingLabel = strUpcoming
                            )
                        }
                    )
                }
                rowBuildCache.continueWatchingItems = contentState.continueWatchingItems
                rowBuildCache.continueWatchingTitle = strContinueWatching
                rowBuildCache.continueWatchingAirsDateTemplate = strAirsDate
                rowBuildCache.continueWatchingUpcomingLabel = strUpcoming
                rowBuildCache.continueWatchingUseLandscapePosters = useLandscapePosters
                rowBuildCache.continueWatchingRow = continueWatchingRow
                add(continueWatchingRow)
            } else {
                rowBuildCache.continueWatchingItems = emptyList()
                rowBuildCache.continueWatchingRow = null
            }

            visibleCatalogRows.forEachIndexed { index, row ->
                val rowKey = catalogRowKey(row)
                activeCatalogKeys += rowKey
                val cached = rowBuildCache.catalogRows[rowKey]
                val canReuseMappedRow =
                    cached != null &&
                        cached.source == row &&
                        cached.useLandscapePosters == useLandscapePosters &&
                        cached.showCatalogTypeSuffix == showCatalogTypeSuffixInModern

                val mappedRow = if (canReuseMappedRow) {
                    val cachedMappedRow = checkNotNull(cached).mappedRow
                    if (cachedMappedRow.globalRowIndex == index) {
                        cachedMappedRow
                    } else {
                        cachedMappedRow.copy(globalRowIndex = index)
                    }
                } else {
                    val rowItemOccurrenceCounts = mutableMapOf<String, Int>()
                    val rowItemCache = rowBuildCache.catalogItemCache.getOrPut(rowKey) { mutableMapOf() }
                    HeroCarouselRow(
                        key = rowKey,
                        title = catalogRowTitle(
                            row = row,
                            showCatalogTypeSuffix = showCatalogTypeSuffixInModern,
                            strTypeMovie = strTypeMovie,
                            strTypeSeries = strTypeSeries
                        ),
                        globalRowIndex = index,
                        catalogId = row.catalogId,
                        addonId = row.addonId,
                        apiType = row.apiType,
                        supportsSkip = row.supportsSkip,
                        hasMore = row.hasMore,
                        isLoading = row.isLoading,
                        items = row.items.map { item ->
                            val occurrence = rowItemOccurrenceCounts.getOrDefault(item.id, 0)
                            rowItemOccurrenceCounts[item.id] = occurrence + 1
                            val cacheKey = "${item.id}_$occurrence"
                            val cachedItem = rowItemCache[cacheKey]
                            if (cachedItem != null &&
                                cachedItem.source == item &&
                                cachedItem.useLandscapePosters == useLandscapePosters
                            ) {
                                cachedItem.carouselItem
                            } else {
                                val built = buildCatalogItem(
                                    item = item,
                                    row = row,
                                    useLandscapePosters = useLandscapePosters,
                                    occurrence = occurrence
                                )
                                rowItemCache[cacheKey] = CachedCarouselItem(
                                    source = item,
                                    useLandscapePosters = useLandscapePosters,
                                    carouselItem = built
                                )
                                built
                            }
                        }
                    )
                }

                rowBuildCache.catalogRows[rowKey] = ModernCatalogRowBuildCacheEntry(
                    source = row,
                    useLandscapePosters = useLandscapePosters,
                    showCatalogTypeSuffix = showCatalogTypeSuffixInModern,
                    mappedRow = mappedRow
                )
                add(mappedRow)
            }
            rowBuildCache.catalogRows.keys.retainAll(activeCatalogKeys)
            rowBuildCache.catalogItemCache.keys.retainAll(activeCatalogKeys)
        }
    }

    if (carouselRows.isEmpty()) return
    val carouselLookups = remember(carouselRows) {
        val rowIndexByKey = LinkedHashMap<String, Int>(carouselRows.size)
        val rowByKey = LinkedHashMap<String, HeroCarouselRow>(carouselRows.size)
        val activeRowKeys = LinkedHashSet<String>(carouselRows.size)
        val activeItemKeysByRow = LinkedHashMap<String, Set<String>>(carouselRows.size)
        val activeCatalogItemIds = LinkedHashSet<String>()

        carouselRows.forEachIndexed { index, row ->
            rowIndexByKey[row.key] = index
            rowByKey[row.key] = row
            activeRowKeys += row.key

            val itemKeys = LinkedHashSet<String>(row.items.size)
            row.items.forEach { item ->
                itemKeys += item.key
                val payload = item.payload
                if (payload is ModernPayload.Catalog) {
                    activeCatalogItemIds += payload.itemId
                }
            }
            activeItemKeysByRow[row.key] = itemKeys
        }

        CarouselRowLookups(
            rowIndexByKey = rowIndexByKey,
            rowByKey = rowByKey,
            activeRowKeys = activeRowKeys,
            activeItemKeysByRow = activeItemKeysByRow,
            activeCatalogItemIds = activeCatalogItemIds
        )
    }
    val rowIndexByKey = carouselLookups.rowIndexByKey
    val rowByKey = carouselLookups.rowByKey
    val activeRowKeys = carouselLookups.activeRowKeys
    val activeItemKeysByRow = carouselLookups.activeItemKeysByRow
    val activeCatalogItemIds = carouselLookups.activeCatalogItemIds
    val verticalRowListState = rememberLazyListState(
        initialFirstVisibleItemIndex = focusState.verticalScrollIndex,
        initialFirstVisibleItemScrollOffset = focusState.verticalScrollOffset
    )
    val isVerticalRowsScrolling by remember(verticalRowListState) {
        derivedStateOf { verticalRowListState.isScrollInProgress }
    }

    val uiCaches = remember { ModernHomeUiCaches() }
    val focusedItemByRow = uiCaches.focusedItemByRow
    val itemFocusRequesters = uiCaches.itemFocusRequesters
    val rowListStates = uiCaches.rowListStates
    val loadMoreRequestedTotals = uiCaches.loadMoreRequestedTotals
    val focusHolder = remember {
        object {
            var activeRowKey: String? = null
            var activeItemIndex: Int = 0
        }
    }
    var activeRowKey by remember { mutableStateOf<String?>(null) }
    var activeItemIndex by remember { mutableIntStateOf(0) }
    var pendingRowFocusKey by remember { mutableStateOf<String?>(null) }
    var pendingRowFocusIndex by remember { mutableStateOf<Int?>(null) }
    var pendingRowFocusNonce by remember { mutableIntStateOf(0) }
    var heroItem by remember { mutableStateOf<HeroPreview?>(null) }
    var displayedHeroItemKey by remember { mutableStateOf<String?>(null) }
    var restoredFromSavedState by remember { mutableStateOf(false) }
    var optionsItem by remember { mutableStateOf<ContinueWatchingItem?>(null) }
    val lastFocusedContinueWatchingIndexRef = remember { AtomicInteger(-1) }
    val lastKeyRepeatTimeRef = remember { AtomicLong(0L) }
    val lastHeroNavigationAtMsRef = remember { AtomicLong(0L) }
    val heroFocusSettleDelayMsRef = remember { AtomicLong(MODERN_HERO_FOCUS_DEBOUNCE_MS) }
    var focusedCatalogSelection by remember { mutableStateOf<FocusedCatalogSelection?>(null) }
    var expandedCatalogFocusKey by remember { mutableStateOf<String?>(null) }
    var expandedCatalogRowKey by remember { mutableStateOf<String?>(null) }
    var expansionInteractionNonce by remember { mutableIntStateOf(0) }

    LaunchedEffect(
        focusedCatalogSelection?.focusKey,
        expansionInteractionNonce,
        shouldActivateFocusedPosterFlow,
        contentState.focusedPosterBackdropExpandDelaySeconds,
        isVerticalRowsScrolling
    ) {
        expandedCatalogFocusKey = null
        expandedCatalogRowKey = null
        if (!shouldActivateFocusedPosterFlow) return@LaunchedEffect
        if (isVerticalRowsScrolling) return@LaunchedEffect
        val selection = focusedCatalogSelection ?: return@LaunchedEffect
        delay(contentState.focusedPosterBackdropExpandDelaySeconds.coerceAtLeast(0) * 1000L)
        if (shouldActivateFocusedPosterFlow &&
            !isVerticalRowsScrolling &&
            focusedCatalogSelection?.focusKey == selection.focusKey
        ) {
            expandedCatalogFocusKey = selection.focusKey
            expandedCatalogRowKey = selection.rowKey
        }
    }

    LaunchedEffect(carouselRows, focusState.hasSavedFocus, focusState.focusedRowIndex, focusState.focusedItemIndex) {
        focusedItemByRow.keys.retainAll(activeRowKeys)
        itemFocusRequesters.keys.retainAll(activeRowKeys)
        rowListStates.keys.retainAll(activeRowKeys)
        loadMoreRequestedTotals.keys.retainAll(activeRowKeys)
        carouselRows.forEach { row ->
            val rowRequesters = itemFocusRequesters[row.key] ?: return@forEach
            val allowedKeys = activeItemKeysByRow[row.key] ?: emptySet()
            rowRequesters.keys.retainAll(allowedKeys)
        }
        if (focusedCatalogSelection?.payload?.itemId !in activeCatalogItemIds) {
            focusedCatalogSelection = null
            expandedCatalogFocusKey = null
            expandedCatalogRowKey = null
        }

        carouselRows.forEach { row ->
            if (row.items.isNotEmpty() && row.key !in focusedItemByRow) {
                focusedItemByRow[row.key] = 0
            }
        }

        if (!restoredFromSavedState && focusState.hasSavedFocus) {
            val savedRowKey = when {
                focusState.focusedRowIndex == -1 && contentState.continueWatchingItems.isNotEmpty() -> "continue_watching"
                focusState.focusedRowIndex >= 0 -> visibleCatalogRows.getOrNull(focusState.focusedRowIndex)?.let { catalogRowKey(it) }
                else -> null
            }

            val resolvedRow = carouselRows.firstOrNull { it.key == savedRowKey } ?: carouselRows.first()
            val resolvedIndex = focusState.focusedItemIndex
                .coerceAtLeast(0)
                .coerceAtMost((resolvedRow.items.size - 1).coerceAtLeast(0))

            focusHolder.activeRowKey = resolvedRow.key
            focusHolder.activeItemIndex = resolvedIndex
            activeRowKey = resolvedRow.key
            activeItemIndex = resolvedIndex
            focusedItemByRow[resolvedRow.key] = resolvedIndex
            heroItem = resolvedRow.items.getOrNull(resolvedIndex)?.heroPreview
                ?: resolvedRow.items.firstOrNull()?.heroPreview
            displayedHeroItemKey = resolvedRow.items.getOrNull(resolvedIndex)?.key
                ?: resolvedRow.items.firstOrNull()?.key
            pendingRowFocusKey = resolvedRow.key
            pendingRowFocusIndex = resolvedIndex
            pendingRowFocusNonce++
            restoredFromSavedState = true
            return@LaunchedEffect
        }

        val hadActiveRow = focusHolder.activeRowKey != null
        val existingActive = focusHolder.activeRowKey?.let { key -> carouselRows.firstOrNull { it.key == key } }
        val resolvedActive = existingActive ?: carouselRows.first()
        val resolvedIndex = focusedItemByRow[resolvedActive.key]
            ?.coerceIn(0, (resolvedActive.items.size - 1).coerceAtLeast(0))
            ?: 0
        focusHolder.activeRowKey = resolvedActive.key
        focusHolder.activeItemIndex = resolvedIndex
        activeRowKey = resolvedActive.key
        activeItemIndex = resolvedIndex
        focusedItemByRow[resolvedActive.key] = resolvedIndex
        heroItem = resolvedActive.items.getOrNull(resolvedIndex)?.heroPreview
            ?: resolvedActive.items.firstOrNull()?.heroPreview
        displayedHeroItemKey = resolvedActive.items.getOrNull(resolvedIndex)?.key
            ?: resolvedActive.items.firstOrNull()?.key
        if (!focusState.hasSavedFocus && (!hadActiveRow || existingActive == null)) {
            pendingRowFocusKey = resolvedActive.key
            pendingRowFocusIndex = resolvedIndex
            pendingRowFocusNonce++
        }
    }

    LaunchedEffect(focusState.verticalScrollIndex, focusState.verticalScrollOffset) {
        val targetIndex = focusState.verticalScrollIndex
        val targetOffset = focusState.verticalScrollOffset
        if (verticalRowListState.firstVisibleItemIndex == targetIndex &&
            verticalRowListState.firstVisibleItemScrollOffset == targetOffset
        ) {
            return@LaunchedEffect
        }
        if (targetIndex > 0 || targetOffset > 0) {
            verticalRowListState.scrollToItem(targetIndex, targetOffset)
        }
    }

    val activeRow = remember(carouselRows, rowByKey, activeRowKey) {
        val activeKey = activeRowKey
        if (activeKey == null) {
            null
        } else {
            rowByKey[activeKey] ?: carouselRows.firstOrNull()
        }
    }
    val clampedActiveItemIndex = remember(activeRow, activeItemIndex) {
        activeRow?.let { row ->
            activeItemIndex.coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
        } ?: 0
    }

    LaunchedEffect(activeRow?.key, activeRow?.items?.size) {
        val row = activeRow ?: return@LaunchedEffect
        val clampedIndex = focusHolder.activeItemIndex.coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
        if (focusHolder.activeItemIndex != clampedIndex) {
            focusHolder.activeItemIndex = clampedIndex
            activeItemIndex = clampedIndex
        }
        focusedItemByRow[row.key] = clampedIndex
    }

    val activeHeroItemKey = remember(activeRow, clampedActiveItemIndex) {
        val row = activeRow ?: return@remember null
        row.items.getOrNull(clampedActiveItemIndex)?.key ?: row.items.firstOrNull()?.key
    }
    val activeHeroPreviewKey = remember(activeRow, clampedActiveItemIndex) {
        val row = activeRow ?: return@remember null
        heroPreviewContentKey(
            row.items.getOrNull(clampedActiveItemIndex) ?: row.items.firstOrNull()
        )
    }
    val latestHeroRow by rememberUpdatedState(activeRow)
    val latestHeroIndex by rememberUpdatedState(clampedActiveItemIndex)
    LaunchedEffect(activeHeroItemKey, isVerticalRowsScrolling) {
        if (isVerticalRowsScrolling) return@LaunchedEffect
        val targetHeroKey = activeHeroItemKey ?: return@LaunchedEffect
        val settleDelayMs = heroFocusSettleDelayMsRef.get()
        delay(settleDelayMs)
        if (isVerticalRowsScrolling) return@LaunchedEffect
        if (System.currentTimeMillis() - lastHeroNavigationAtMsRef.get() < settleDelayMs) return@LaunchedEffect
        val row = latestHeroRow ?: return@LaunchedEffect
        val latestKey = row.items.getOrNull(latestHeroIndex)?.key ?: row.items.firstOrNull()?.key
        if (latestKey != targetHeroKey) return@LaunchedEffect
        val latestHero = resolveActiveHeroPreview(row, latestHeroIndex)
        if (latestHero != null && heroItem != latestHero) {
            displayedHeroItemKey = latestKey
            heroItem = latestHero
        }
    }
    LaunchedEffect(activeHeroPreviewKey, isVerticalRowsScrolling) {
        if (isVerticalRowsScrolling) return@LaunchedEffect
        val latestHero = resolveActiveHeroPreview(latestHeroRow, latestHeroIndex) ?: return@LaunchedEffect
        if (heroItem != latestHero) {
            heroItem = latestHero
        }
    }
    val latestActiveRow by rememberUpdatedState(activeRow)
    val latestActiveItemIndex by rememberUpdatedState(clampedActiveItemIndex)
    val latestCarouselRows by rememberUpdatedState(carouselRows)
    val latestVerticalRowListState by rememberUpdatedState(verticalRowListState)
    DisposableEffect(Unit) {
        onDispose {
            val row = latestActiveRow
            val focusedRowIndex = row?.globalRowIndex ?: 0
            val catalogRowScrollStates = latestCarouselRows
                .filter { it.globalRowIndex >= 0 }
                .associate { rowState -> rowState.key to (focusedItemByRow[rowState.key] ?: 0) }

            onSaveFocusState(
                latestVerticalRowListState.firstVisibleItemIndex,
                latestVerticalRowListState.firstVisibleItemScrollOffset,
                focusedRowIndex,
                latestActiveItemIndex,
                catalogRowScrollStates
            )
        }
    }

    val portraitBaseWidth = contentState.posterCardWidthDp.dp
    val portraitBaseHeight = contentState.posterCardHeightDp.dp
    val modernPosterScale = if (useLandscapePosters) 1.34f else 1.08f
    val modernCatalogCardWidth = if (useLandscapePosters) {
        portraitBaseWidth * 1.24f * modernPosterScale
    } else {
        portraitBaseWidth * 0.84f * modernPosterScale
    }
    val modernCatalogCardHeight = if (useLandscapePosters) {
        modernCatalogCardWidth / 1.77f
    } else {
        portraitBaseHeight * 0.84f * modernPosterScale
    }
    val continueWatchingScale = 1.34f
    val continueWatchingCardWidth = portraitBaseWidth * 1.24f * continueWatchingScale
    val continueWatchingCardHeight = continueWatchingCardWidth / 1.77f

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val rowHorizontalPadding = 52.dp
        val posterCardCornerRadius = remember(contentState.posterCardCornerRadiusDp) { contentState.posterCardCornerRadiusDp.dp }
        val activeCarouselItem = remember(activeRow, clampedActiveItemIndex) {
            activeRow?.items?.getOrNull(clampedActiveItemIndex)
        }
        val activeItemId = activeCarouselItem?.metaPreview?.id
        val liveActiveHeroPreview = remember(activeRow, clampedActiveItemIndex) {
            resolveActiveHeroPreview(activeRow, clampedActiveItemIndex)
        }
        val resolvedHero = resolveDisplayedHeroPreview(
            displayedHeroItemKey = displayedHeroItemKey,
            activeHeroItemKey = activeHeroItemKey,
            displayedHeroPreview = heroItem,
            liveActiveHeroPreview = liveActiveHeroPreview
        )
        val activeRowFallbackBackdrop = remember(activeRow?.key, activeRow?.items) {
            activeRow?.items?.firstNotNullOfOrNull { item ->
                item.heroPreview.backdrop?.takeIf { it.isNotBlank() }
            }
        }
        val heroBackdrop = remember(heroItem, resolvedHero, activeRowFallbackBackdrop) {
            firstNonBlank(
                resolvedHero?.backdrop,
                resolvedHero?.imageUrl,
                resolvedHero?.poster,
                if (heroItem == null) activeRowFallbackBackdrop else null
            )
        }
        val catalogBottomPadding = 0.dp
        val heroToCatalogGap = 16.dp
        val rowTitleBottom = 14.dp
        val rowsViewportHeightFraction = if (useLandscapePosters) 0.49f else 0.52f
        val rowsViewportHeight = maxHeight * rowsViewportHeightFraction
        val localDensity = LocalDensity.current
        val verticalRowBringIntoViewSpec = remember(localDensity, defaultBringIntoViewSpec) {
            val topInsetPx = with(localDensity) { MODERN_ROW_HEADER_FOCUS_INSET.toPx() }
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            object : BringIntoViewSpec {
                @Deprecated("Overrides deprecated BringIntoViewSpec.scrollAnimationSpec.")
                override val scrollAnimationSpec: AnimationSpec<Float> =
                    defaultBringIntoViewSpec.scrollAnimationSpec

                override fun calculateScrollDistance(
                    offset: Float,
                    size: Float,
                    containerSize: Float
                ): Float = offset - topInsetPx
            }
        }
        val bgColor = NexioColors.Background
        val contentFocusRequester = LocalContentFocusRequester.current
        val heroMediaWidthPx = remember(maxWidth, localDensity) {
            with(localDensity) { (maxWidth * 0.75f).roundToPx() }
        }
        val heroMediaHeightPx = remember(maxHeight, localDensity) {
            with(localDensity) { (maxHeight * MODERN_HERO_BACKDROP_HEIGHT_FRACTION).roundToPx() }
        }

        val heroMediaModifier = remember {
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 56.dp)
                .fillMaxWidth(0.75f)
                .fillMaxHeight(MODERN_HERO_BACKDROP_HEIGHT_FRACTION)
        }

        ModernHeroSection(
            heroBackdrop = heroBackdrop,
            preview = resolvedHero,
            activeItemId = activeItemId,
            enrichingItemIdState = enrichingItemIdState,
            bgColor = bgColor,
            portraitMode = !useLandscapePosters,
            mediaModifier = heroMediaModifier,
            requestWidthPx = heroMediaWidthPx,
            requestHeightPx = heroMediaHeightPx,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = rowHorizontalPadding,
                    end = 48.dp,
                    bottom = catalogBottomPadding + rowsViewportHeight + heroToCatalogGap
                )
                .fillMaxWidth(MODERN_HERO_TEXT_WIDTH_FRACTION)
        )

        val onPendingRowFocusCleared = remember {
            {
                pendingRowFocusKey = null
                pendingRowFocusIndex = null
            }
        }
        val onRowItemFocused = remember {
            { rowKey: String, index: Int, isContinueWatchingRow: Boolean ->
                val rowBecameActive = focusHolder.activeRowKey != rowKey
                val itemChanged = focusHolder.activeItemIndex != index
                if (rowBecameActive || itemChanged) {
                    val now = System.currentTimeMillis()
                    val timeSinceLastHeroNav = now - lastHeroNavigationAtMsRef.get()
                    heroFocusSettleDelayMsRef.set(if (
                        lastHeroNavigationAtMsRef.get() != 0L &&
                        timeSinceLastHeroNav in 1 until MODERN_HERO_RAPID_NAV_THRESHOLD_MS
                    ) {
                        MODERN_HERO_RAPID_NAV_SETTLE_MS
                    } else {
                        MODERN_HERO_FOCUS_DEBOUNCE_MS
                    })
                    lastHeroNavigationAtMsRef.set(now)
                }
                if (focusedItemByRow[rowKey] != index) {
                    focusedItemByRow[rowKey] = index
                }
                if (rowBecameActive) {
                    focusHolder.activeRowKey = rowKey
                    activeRowKey = rowKey
                }
                if (rowBecameActive || itemChanged) {
                    focusHolder.activeItemIndex = index
                    activeItemIndex = index
                }
                if (isContinueWatchingRow) {
                    if (lastFocusedContinueWatchingIndexRef.get() != index) {
                        lastFocusedContinueWatchingIndexRef.set(index)
                    }
                    if (focusedCatalogSelection != null) {
                        focusedCatalogSelection = null
                    }
                }
            }
        }
        val onContinueWatchingOptions = remember {
            { item: ContinueWatchingItem ->
                optionsItem = item
            }
        }
        val onCatalogSelectionFocused = remember {
            { selection: FocusedCatalogSelection ->
                if (focusedCatalogSelection != selection) {
                    focusedCatalogSelection = selection
                }
            }
        }
        val onBackdropInteraction = remember {
            {
                expansionInteractionNonce++
                Unit
            }
        }

        CompositionLocalProvider(LocalBringIntoViewSpec provides verticalRowBringIntoViewSpec) {
            LazyColumn(
                state = verticalRowListState,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(rowsViewportHeight)
                    .padding(bottom = catalogBottomPadding)
                    .focusRequester(contentFocusRequester)
                    .onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount > 0) {
                            val now = System.currentTimeMillis()
                            if (now - lastKeyRepeatTimeRef.get() < KEY_REPEAT_THROTTLE_MS) {
                                return@onPreviewKeyEvent true
                            }
                            lastKeyRepeatTimeRef.set(now)
                        }
                        false
                    },
                contentPadding = PaddingValues(bottom = rowsViewportHeight),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                itemsIndexed(
                    items = carouselRows,
                    key = { _, row -> row.key },
                    contentType = { _, _ -> "modern_home_row" }
                ) { _, row ->
                    ModernRowSection(
                        row = row,
                        rowTitleBottom = rowTitleBottom,
                        defaultBringIntoViewSpec = defaultBringIntoViewSpec,
                        initialScrollIndex = focusState.catalogRowScrollStates[row.key] ?: 0,
                        uiCaches = uiCaches,
                        pendingRowFocusIndex = if (pendingRowFocusKey == row.key) pendingRowFocusIndex else null,
                        pendingRowFocusNonce = pendingRowFocusNonce,
                        onPendingRowFocusCleared = onPendingRowFocusCleared,
                        onRowItemFocused = onRowItemFocused,
                        useLandscapePosters = useLandscapePosters,
                        showLabels = contentState.posterLabelsEnabled,
                        posterCardCornerRadius = posterCardCornerRadius,
                        effectiveExpandEnabled = effectiveExpandEnabled,
                        expandedCatalogFocusKeyForRow = if (expandedCatalogRowKey == row.key) {
                            expandedCatalogFocusKey
                        } else {
                            null
                        },
                        modernCatalogCardWidth = modernCatalogCardWidth,
                        modernCatalogCardHeight = modernCatalogCardHeight,
                        continueWatchingCardWidth = continueWatchingCardWidth,
                        continueWatchingCardHeight = continueWatchingCardHeight,
                        onContinueWatchingClick = onContinueWatchingClick,
                        onContinueWatchingOptions = onContinueWatchingOptions,
                        isCatalogItemWatched = isCatalogItemWatched,
                        onCatalogItemLongPress = onCatalogItemLongPress,
                        onItemFocus = onItemFocus,
                        onPreloadAdjacentItem = onPreloadAdjacentItem,
                        onCatalogSelectionFocused = onCatalogSelectionFocused,
                        onNavigateToDetail = onNavigateToDetail,
                        onLoadMoreCatalog = onLoadMoreCatalog,
                        onBackdropInteraction = onBackdropInteraction
                    )
                }
            }
        }
    }

    val selectedOptionsItem = optionsItem
    if (selectedOptionsItem != null) {
        ContinueWatchingOptionsDialog(
            item = selectedOptionsItem,
            onDismiss = { optionsItem = null },
            onRemove = {
                val targetIndex = if (contentState.continueWatchingItems.size <= 1) {
                    null
                } else {
                    minOf(lastFocusedContinueWatchingIndexRef.get(), contentState.continueWatchingItems.size - 2)
                        .coerceAtLeast(0)
                }
                pendingRowFocusKey = if (targetIndex != null) "continue_watching" else null
                pendingRowFocusIndex = targetIndex
                pendingRowFocusNonce++
                onRemoveContinueWatching(
                    selectedOptionsItem.contentId(),
                    selectedOptionsItem.season(),
                    selectedOptionsItem.episode(),
                    selectedOptionsItem is ContinueWatchingItem.NextUp
                )
                optionsItem = null
            },
            onMarkAsWatched = {
                onMarkContinueWatchingWatched(selectedOptionsItem)
                optionsItem = null
            },
            onDetails = {
                onNavigateToDetail(
                    selectedOptionsItem.contentId(),
                    selectedOptionsItem.contentType(),
                    ""
                )
                optionsItem = null
            },
            onCheckIn = onCheckInContinueWatching?.let { callback ->
                {
                    callback(selectedOptionsItem)
                    optionsItem = null
                }
            },
            onManageLists = onManageListsContinueWatching?.let { callback ->
                {
                    callback(selectedOptionsItem)
                    optionsItem = null
                }
            },
            onStartFromBeginning = {
                onContinueWatchingStartFromBeginning(selectedOptionsItem)
                optionsItem = null
            }
        )
    }
}

@Composable
private fun ModernHeroSection(
    heroBackdrop: String?,
    preview: HeroPreview?,
    activeItemId: String?,
    enrichingItemIdState: State<String?>,
    bgColor: androidx.compose.ui.graphics.Color,
    portraitMode: Boolean,
    mediaModifier: Modifier,
    requestWidthPx: Int,
    requestHeightPx: Int,
    modifier: Modifier = Modifier
) {
    val enrichingItemId = enrichingItemIdState.value
    val enrichmentActive = activeItemId != null && activeItemId == enrichingItemId
    LaunchedEffect(preview, activeItemId, enrichingItemId, enrichmentActive) {
        Log.d(
            MODERN_HOME_CONTENT_LOG_TAG,
            "ModernHeroSection activeItemId=$activeItemId enrichingItemId=$enrichingItemId enrichmentActive=$enrichmentActive title=${preview?.title} imdb=${preview?.imdbText} tomatoes=${preview?.tomatoesText}"
        )
    }
    ModernHeroMediaLayer(
        heroBackdrop = heroBackdrop,
        enrichmentActive = enrichmentActive,
        modifier = mediaModifier,
        requestWidthPx = requestWidthPx,
        requestHeightPx = requestHeightPx
    )
    ModernHeroGradientLayer(
        bgColor = bgColor,
        modifier = mediaModifier
    )
    HeroTitleBlock(
        preview = preview,
        enrichmentActive = enrichmentActive,
        portraitMode = portraitMode,
        modifier = modifier
    )
}
