@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nexio.tv.ui.screens.home

import android.content.Intent
import android.net.Uri
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.tv.material3.Border
import androidx.tv.material3.Icon
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.ImageRequest
import com.nexio.tv.R
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.toCoilModelOrNull
import com.nexio.tv.core.image.ArtworkImageCacheKeys
import com.nexio.tv.core.image.toLegacyArtworkCoilModelOrNull
import com.nexio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.ui.components.ContinueWatchingCard
import com.nexio.tv.ui.components.ContinueWatchingResolvedDisplayItem
import com.nexio.tv.ui.components.toContinueWatchingItem
import com.nexio.tv.ui.components.FallbackArtworkImage
import com.nexio.tv.ui.components.MonochromePosterPlaceholder
import com.nexio.tv.ui.components.TrailerPlayer
import com.nexio.tv.ui.components.rememberShimmerBrush
import com.nexio.tv.ui.theme.NexioColors
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

private const val MODERN_HORIZONTAL_FOCUS_DEBOUNCE_MS = 140L
private const val POSTER_PREFETCH_DISTANCE = 8

internal fun resolveModernCarouselCardImageUrl(
    focusedPosterBackdropExpandEnabled: Boolean,
    isBackdropExpanded: Boolean,
    frozenBackdropUrl: String?,
    itemImageUrl: String?,
    heroPoster: String?,
    heroBackdrop: String?
): String? {
    return if (focusedPosterBackdropExpandEnabled && isBackdropExpanded) {
        heroBackdrop ?: itemImageUrl ?: heroPoster
    } else {
        itemImageUrl ?: heroPoster ?: frozenBackdropUrl ?: heroBackdrop
    }
}

internal fun resolveModernCarouselCardArtworkModel(
    item: ModernCarouselItem,
    useLandscapePosters: Boolean,
    focusedPosterBackdropExpandEnabled: Boolean,
    isBackdropExpanded: Boolean,
    fallbackModel: String? = item.imageUrl
): Any? {
    val useBackdrop = useLandscapePosters || (focusedPosterBackdropExpandEnabled && isBackdropExpanded)
    val artwork = item.metaPreview?.artwork
    val typedModel = if (useBackdrop) {
        artwork?.backdrop.toCoilModelOrNull() ?: artwork?.poster.toCoilModelOrNull()
    } else {
        artwork?.poster.toCoilModelOrNull()
    }
    val fallbackType = if (useBackdrop) ArtworkType.BACKDROP else ArtworkType.POSTER
    val legacyFallback = if (useBackdrop) {
        fallbackModel
    } else {
        firstNonBlank(
            item.metaPreview?.poster,
            item.heroPreview.poster
        )
    }
    return typedModel ?: legacyFallback.toLegacyArtworkCoilModelOrNull(
        ownerKey = "${item.key}:${fallbackType.name.lowercase()}",
        imageType = fallbackType
    )
}

internal fun resolveModernCarouselCardFallbackArtworkModel(
    item: ModernCarouselItem,
    useLandscapePosters: Boolean,
    focusedPosterBackdropExpandEnabled: Boolean,
    isBackdropExpanded: Boolean
): Any? {
    val useBackdrop = useLandscapePosters || (focusedPosterBackdropExpandEnabled && isBackdropExpanded)
    val fallbackType = if (useBackdrop) ArtworkType.BACKDROP else ArtworkType.POSTER
    val legacyFallback = if (useBackdrop) {
        firstNonBlank(
            item.metaPreview?.background,
            item.heroPreview.backdrop,
            item.metaPreview?.poster,
            item.heroPreview.poster
        )
    } else {
        firstNonBlank(
            item.metaPreview?.poster,
            item.heroPreview.poster
        )
    }
    return legacyFallback.toLegacyArtworkCoilModelOrNull(
        ownerKey = "${item.key}:fallback:${fallbackType.name.lowercase()}",
        imageType = fallbackType
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ModernContinueWatchingRowItem(
    payload: ModernPayload.ContinueWatching,
    requester: FocusRequester,
    cardWidth: Dp,
    imageHeight: Dp,
    onFocused: () -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onShowOptions: (ContinueWatchingResolvedDisplayItem) -> Unit,
    canPromoteHeroTrailerToFullscreen: Boolean,
    fullscreenTrailerActive: Boolean,
    onPromoteHeroTrailerToFullscreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val item = payload.item
    val onClick = remember(item) { { onContinueWatchingClick(item.toContinueWatchingItem()) } }
    val onLongPress = remember(item) { { onShowOptions(item) } }
    var focusEventId by remember { mutableStateOf(0) }
    var isCardFocused by remember { mutableStateOf(false) }
    val latestOnFocused by rememberUpdatedState(onFocused)

    LaunchedEffect(focusEventId, isCardFocused) {
        if (focusEventId == 0 || !isCardFocused) return@LaunchedEffect
        val targetEventId = focusEventId
        delay(MODERN_HORIZONTAL_FOCUS_DEBOUNCE_MS)
        if (!isCardFocused || focusEventId != targetEventId) return@LaunchedEffect
        latestOnFocused()
    }

    ContinueWatchingCard(
        item = item,
        onClick = onClick,
        onLongPress = onLongPress,
        canPromoteHeroTrailerToFullscreen = canPromoteHeroTrailerToFullscreen,
        fullscreenTrailerActive = fullscreenTrailerActive,
        onPromoteHeroTrailerToFullscreen = onPromoteHeroTrailerToFullscreen,
        cardWidth = cardWidth,
        imageHeight = imageHeight,
        modifier = modifier
            .focusRequester(requester)
            .onFocusChanged {
                isCardFocused = it.isFocused
                if (it.isFocused) {
                    focusEventId += 1
                }
            }
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ModernCatalogRowItem(
    rowKey: String,
    item: ModernCarouselItem,
    payload: ModernPayload.Catalog,
    requester: FocusRequester,
    useLandscapePosters: Boolean,
    showLabels: Boolean,
    posterCardCornerRadius: Dp,
    modernCatalogCardWidth: Dp,
    modernCatalogCardHeight: Dp,
    effectiveExpandEnabled: Boolean,
    isBackdropExpanded: Boolean,
    playTrailerInExpandedCard: Boolean,
    focusedPosterBackdropTrailerMuted: Boolean,
    trailerPreviewUrl: String?,
    trailerPreviewAudioUrl: String?,
    trailerPreviewUserAgent: String?,
    trailerPreviewExternalUrl: String?,
    isWatched: Boolean,
    onFocused: () -> Unit,
    onItemFocus: (MetaPreview) -> Unit,
    onPreloadAdjacentItem: () -> Unit,
    onCatalogSelectionFocused: (FocusedCatalogSelection) -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    onLongPress: () -> Unit,
    canPromoteHeroTrailerToFullscreen: Boolean,
    fullscreenTrailerActive: Boolean,
    onPromoteHeroTrailerToFullscreen: () -> Unit,
    onBackdropInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusKey = payload.focusKey
    var focusEventId by remember(focusKey) { mutableStateOf(0) }
    var isCardFocused by remember(focusKey) { mutableStateOf(false) }
    val latestOnFocused by rememberUpdatedState(onFocused)
    val latestOnItemFocus by rememberUpdatedState(onItemFocus)
    val latestOnPreloadAdjacentItem by rememberUpdatedState(onPreloadAdjacentItem)
    val latestOnCatalogSelectionFocused by rememberUpdatedState(onCatalogSelectionFocused)

    LaunchedEffect(focusEventId, isCardFocused, focusKey) {
        if (focusEventId == 0 || !isCardFocused) return@LaunchedEffect
        val targetEventId = focusEventId
        delay(MODERN_HORIZONTAL_FOCUS_DEBOUNCE_MS)
        if (!isCardFocused || focusEventId != targetEventId) return@LaunchedEffect

        latestOnFocused()
        item.metaPreview?.let { latestOnItemFocus(it) }
        latestOnPreloadAdjacentItem()
        latestOnCatalogSelectionFocused(
            FocusedCatalogSelection(
                rowKey = rowKey,
                focusKey = focusKey,
                payload = payload
            )
        )
    }

    ModernCarouselCard(
        item = item,
        useLandscapePosters = useLandscapePosters,
        showLabels = showLabels,
        cardCornerRadius = posterCardCornerRadius,
        cardWidth = modernCatalogCardWidth,
        cardHeight = modernCatalogCardHeight,
        modifier = modifier,
        focusedPosterBackdropExpandEnabled = effectiveExpandEnabled,
        isBackdropExpanded = isBackdropExpanded,
        playTrailerInExpandedCard = playTrailerInExpandedCard,
        focusedPosterBackdropTrailerMuted = focusedPosterBackdropTrailerMuted,
        trailerPreviewUrl = trailerPreviewUrl,
        trailerPreviewAudioUrl = trailerPreviewAudioUrl,
        trailerPreviewUserAgent = trailerPreviewUserAgent,
        trailerPreviewExternalUrl = trailerPreviewExternalUrl,
        isWatched = isWatched,
        focusRequester = requester,
        onFocused = { focusEventId += 1 },
        onFocusStateChanged = { focused -> isCardFocused = focused },
        onClick = {
            onNavigateToDetail(
                payload.itemId,
                payload.itemType,
                payload.addonBaseUrl
            )
        },
        onLongPress = onLongPress,
        canPromoteHeroTrailerToFullscreen = canPromoteHeroTrailerToFullscreen,
        fullscreenTrailerActive = fullscreenTrailerActive,
        onPromoteHeroTrailerToFullscreen = onPromoteHeroTrailerToFullscreen,
        onBackdropInteraction = onBackdropInteraction
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ModernRowSection(
    row: HeroCarouselRow,
    isActiveRow: Boolean,
    isVerticalRowsScrolling: Boolean,
    rowTitleBottom: Dp,
    defaultBringIntoViewSpec: BringIntoViewSpec,
    initialScrollIndex: Int,
    uiCaches: ModernHomeUiCaches,
    pendingRowFocusIndex: Int?,
    pendingRowFocusNonce: Int,
    onPendingRowFocusCleared: () -> Unit,
    onRowItemFocused: (String, Int, Boolean) -> Unit,
    useLandscapePosters: Boolean,
    showLabels: Boolean,
    posterCardCornerRadius: Dp,
    effectiveExpandEnabled: Boolean,
    expandedCatalogFocusKeyForRow: String?,
    focusedPosterBackdropTrailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget,
    trailerPlaybackUnlockedFocusKey: String?,
    focusedPosterBackdropTrailerMuted: Boolean,
    trailerPreviewUrls: Map<String, String>,
    trailerPreviewAudioUrls: Map<String, String>,
    trailerPreviewUserAgents: Map<String, String>,
    trailerPreviewExternalUrls: Map<String, String>,
    modernCatalogCardWidth: Dp,
    modernCatalogCardHeight: Dp,
    continueWatchingCardWidth: Dp,
    continueWatchingCardHeight: Dp,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingOptions: (ContinueWatchingResolvedDisplayItem) -> Unit,
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit,
    onItemFocus: (MetaPreview) -> Unit,
    onPreloadAdjacentItem: (MetaPreview) -> Unit,
    onCatalogSelectionFocused: (FocusedCatalogSelection) -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    onLoadMoreCatalog: (String, String, String) -> Unit,
    canPromoteHeroTrailerToFullscreen: Boolean,
    fullscreenTrailerActive: Boolean,
    onPromoteHeroTrailerToFullscreen: () -> Unit,
    onBackdropInteraction: () -> Unit
) {
    val focusedItemByRow = uiCaches.focusedItemByRow
    val itemFocusRequesters = uiCaches.itemFocusRequesters
    val rowListStates = uiCaches.rowListStates
    val loadMoreRequestedTotals = uiCaches.loadMoreRequestedTotals

    Column {
        val titleMediumStyle = MaterialTheme.typography.titleMedium
        val rowTitleStyle = remember(titleMediumStyle) {
            titleMediumStyle.copy(fontWeight = FontWeight.SemiBold)
        }
        Text(
            text = remember(row.title) { row.title },
            style = rowTitleStyle,
            color = NexioColors.TextPrimary,
            modifier = remember(rowTitleBottom) { Modifier.padding(start = 52.dp, bottom = rowTitleBottom) }
        )

        val rowListState = rowListStates.getOrPut(row.key) {
            LazyListState(
                firstVisibleItemIndex = initialScrollIndex,
                prefetchStrategy = LazyListPrefetchStrategy(nestedPrefetchItemCount = 2)
            )
        }
        val isRowScrollingState = remember(rowListState) {
            derivedStateOf { rowListState.isScrollInProgress }
        }
        val isRowScrolling by isRowScrollingState
        val currentRowState = rememberUpdatedState(row)
        val loadMoreCatalogId = row.catalogId
        val loadMoreAddonId = row.addonId
        val loadMoreApiType = row.apiType
        val canObserveLoadMore = row.supportsSkip &&
            row.hasMore &&
            !loadMoreCatalogId.isNullOrBlank() &&
            !loadMoreAddonId.isNullOrBlank() &&
            !loadMoreApiType.isNullOrBlank()

        LaunchedEffect(row.key, pendingRowFocusIndex, pendingRowFocusNonce) {
            val requestedIndex = pendingRowFocusIndex ?: return@LaunchedEffect
            val targetIndex = requestedIndex
                .coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
            val targetItemKey = row.items.getOrNull(targetIndex)?.key ?: return@LaunchedEffect
            val requester = uiCaches.requesterFor(row.key, targetItemKey)
            var didFocus = false
            var didScrollToTarget = false
            repeat(20) {
                didFocus = runCatching {
                    requester.requestFocus()
                    true
                }.getOrDefault(false)
                if (didFocus) {
                    return@repeat
                }
                if (!didScrollToTarget) {
                    runCatching { rowListState.scrollToItem(targetIndex) }
                    didScrollToTarget = true
                }
                withFrameNanos { }
            }
            if (!didFocus) {
                val fallbackIndex = rowListState.firstVisibleItemIndex
                    .coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
                val fallbackItemKey = row.items.getOrNull(fallbackIndex)?.key
                didFocus = runCatching {
                    if (fallbackItemKey != null) {
                        uiCaches.requesterFor(row.key, fallbackItemKey).requestFocus()
                    }
                    true
                }.getOrDefault(false)
            }
            if (didFocus) {
                onPendingRowFocusCleared()
            }
        }

        if (canObserveLoadMore) {
            LaunchedEffect(
                row.key,
                rowListState,
                canObserveLoadMore
            ) {
                snapshotFlow {
                    val layoutInfo = rowListState.layoutInfo
                    val total = layoutInfo.totalItemsCount
                    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    lastVisible to total
                }
                    .distinctUntilChanged()
                    .collect { (lastVisible, total) ->
                        if (total <= 0) return@collect
                        val rowState = currentRowState.value
                        val isNearEnd = lastVisible >= total - 4
                        if (!isNearEnd) {
                            loadMoreRequestedTotals.remove(rowState.key)
                            return@collect
                        }
                        val lastRequestedTotal = loadMoreRequestedTotals[rowState.key]
                        if (rowState.hasMore &&
                            !rowState.isLoading &&
                            lastRequestedTotal != total
                        ) {
                            loadMoreRequestedTotals[rowState.key] = total
                            onLoadMoreCatalog(
                                loadMoreCatalogId,
                                loadMoreAddonId,
                                loadMoreApiType
                            )
                        }
                    }
            }
        }

        val density = LocalDensity.current
        val rowStartPadding = 52.dp
        val context = LocalContext.current
        val imageLoader = context.imageLoader
        val playbackActivityTracker = remember(context) {
            com.nexio.tv.core.player.PlaybackActivityTracker.fromContext(context)
        }
        val isPlaybackActive by playbackActivityTracker.isActive.collectAsState()

        LaunchedEffect(
            row.key,
            isActiveRow,
            isVerticalRowsScrolling,
            isPlaybackActive,
            row.items.size,
            modernCatalogCardWidth,
            modernCatalogCardHeight,
            continueWatchingCardWidth,
            continueWatchingCardHeight
        ) {
            if (!shouldPrefetchModernRow(isActiveRow, isVerticalRowsScrolling)) return@LaunchedEffect
            if (isPlaybackActive) return@LaunchedEffect
            val catalogWidthPx = with(density) { modernCatalogCardWidth.roundToPx() }
            val catalogHeightPx = with(density) { modernCatalogCardHeight.roundToPx() }
            val cwWidthPx = with(density) { continueWatchingCardWidth.roundToPx() }
            val cwHeightPx = with(density) { continueWatchingCardHeight.roundToPx() }

            fun enqueueIfNeeded(item: ModernCarouselItem) {
                val model = resolveModernCarouselCardArtworkModel(
                    item = item,
                    useLandscapePosters = useLandscapePosters,
                    focusedPosterBackdropExpandEnabled = false,
                    isBackdropExpanded = false
                ) ?: return
                val (widthPx, heightPx) = when (item.payload) {
                    is ModernPayload.Catalog -> catalogWidthPx to catalogHeightPx
                    is ModernPayload.ContinueWatching -> cwWidthPx to cwHeightPx
                }
                val modelKey = model.toString()
                val cacheKey = "${modelKey}_${widthPx}x${heightPx}"
                if (imageLoader.memoryCache?.get(MemoryCache.Key(cacheKey)) != null) return
                imageLoader.enqueue(
                    ImageRequest.Builder(context)
                        .data(model)
                        .memoryCacheKey(cacheKey)
                        .size(width = widthPx, height = heightPx)
                        .build()
                )
            }

            currentRowState.value.items.take(POSTER_PREFETCH_DISTANCE).forEach(::enqueueIfNeeded)

            snapshotFlow {
                rowListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            }
                .distinctUntilChanged()
                .collect { lastVisibleIndex ->
                    val items = currentRowState.value.items
                    for (i in (lastVisibleIndex + 1)..(lastVisibleIndex + POSTER_PREFETCH_DISTANCE)) {
                        items.getOrNull(i)?.let(::enqueueIfNeeded)
                    }
                }
        }

        val horizontalBringIntoViewSpec = remember(density, defaultBringIntoViewSpec, rowStartPadding) {
            val parentStartOffsetPx = with(density) { rowStartPadding.roundToPx() }
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            object : BringIntoViewSpec {
                @Deprecated("Overrides deprecated BringIntoViewSpec.scrollAnimationSpec.")
                override val scrollAnimationSpec: AnimationSpec<Float> =
                    defaultBringIntoViewSpec.scrollAnimationSpec

                override fun calculateScrollDistance(
                    offset: Float,
                    size: Float,
                    containerSize: Float
                ): Float {
                    val childSize = abs(size)
                    val childSmallerThanParent = childSize <= containerSize
                    val initialTarget = parentStartOffsetPx.toFloat()
                    val spaceAvailable = containerSize - initialTarget

                    val targetForLeadingEdge =
                        if (childSmallerThanParent && spaceAvailable < childSize) {
                            containerSize - childSize
                        } else {
                            initialTarget
                        }

                    return offset - targetForLeadingEdge
                }
            }
        }

        var lastFocusedItemIndex by remember { mutableStateOf(0) }

        CompositionLocalProvider(LocalBringIntoViewSpec provides horizontalBringIntoViewSpec) {
            LazyRow(
                state = rowListState,
                modifier = Modifier
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                            val currentIndex = lastFocusedItemIndex
                            if (currentIndex > 0) {
                                val prevItem = row.items.getOrNull(currentIndex - 1)
                                if (prevItem != null) {
                                    runCatching {
                                        uiCaches.requesterFor(row.key, prevItem.key).requestFocus()
                                    }
                                    return@onPreviewKeyEvent true
                                }
                            }
                            false
                        } else {
                            false
                        }
                    }
                    .focusGroup(),
                contentPadding = PaddingValues(horizontal = rowStartPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (row.isLoading && row.items.isEmpty()) {
                    items(
                        count = modernLoadingPlaceholderCount(),
                        key = { index -> "${row.key}_loading_$index" },
                        contentType = { "modern_loading_placeholder" }
                    ) { index ->
                        val requester = uiCaches.requesterFor(row.key, "loading_$index")
                        val onFocused = remember(row.key, index) {
                            {
                                lastFocusedItemIndex = index
                                onRowItemFocused(row.key, index, false)
                            }
                        }
                        ModernCatalogLoadingPlaceholder(
                            cardWidth = modernCatalogCardWidth,
                            cardHeight = modernCatalogCardHeight,
                            cornerRadius = posterCardCornerRadius,
                            focusRequester = requester,
                            onFocused = onFocused
                        )
                    }
                } else {
                    itemsIndexed(
                        items = row.items,
                        key = { _, item -> item.key },
                        contentType = { _, item -> modernRowItemContentType(item) }
                    ) { index, item ->
                    val requester = uiCaches.requesterFor(row.key, item.key)
                    val isContinueWatchingRow = row.key == "continue_watching"
                    val onFocused = remember(row.key, index, isContinueWatchingRow) {
                        {
                            lastFocusedItemIndex = index
                            onRowItemFocused(row.key, index, isContinueWatchingRow)
                        }
                    }

                    when (val payload = item.payload) {
                        is ModernPayload.ContinueWatching -> {
                            ModernContinueWatchingRowItem(
                                payload = payload,
                                requester = requester,
                                cardWidth = continueWatchingCardWidth,
                                imageHeight = continueWatchingCardHeight,
                                onFocused = onFocused,
                                onContinueWatchingClick = onContinueWatchingClick,
                                onShowOptions = onContinueWatchingOptions
                                ,
                                canPromoteHeroTrailerToFullscreen = canPromoteHeroTrailerToFullscreen,
                                fullscreenTrailerActive = fullscreenTrailerActive,
                                onPromoteHeroTrailerToFullscreen = onPromoteHeroTrailerToFullscreen
                            )
                        }

                        is ModernPayload.Catalog -> {
                            val metaPreview = item.metaPreview ?: return@itemsIndexed
                            val nextCatalogItem = row.items.getOrNull(index + 1)?.metaPreview
                            val isWatched = remember(metaPreview.id) { isCatalogItemWatched(metaPreview) }
                            val playTrailerInExpandedCard =
                                focusedPosterBackdropTrailerPlaybackTarget ==
                                    FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD &&
                                    trailerPlaybackUnlockedFocusKey == payload.focusKey
                            val trailerPreviewUrl = if (playTrailerInExpandedCard) {
                                trailerPreviewUrls[payload.itemId]
                            } else {
                                null
                            }
                            val trailerPreviewAudioUrl = if (playTrailerInExpandedCard) {
                                trailerPreviewAudioUrls[payload.itemId]
                            } else {
                                null
                            }
                            val trailerPreviewUserAgent = if (playTrailerInExpandedCard) {
                                trailerPreviewUserAgents[payload.itemId]
                            } else {
                                null
                            }
                            val trailerPreviewExternalUrl = if (playTrailerInExpandedCard) {
                                trailerPreviewExternalUrls[payload.itemId]
                            } else {
                                null
                            }
                            ModernCatalogRowItem(
                                rowKey = row.key,
                                item = item,
                                payload = payload,
                                requester = requester,
                                useLandscapePosters = useLandscapePosters,
                                showLabels = showLabels,
                                posterCardCornerRadius = posterCardCornerRadius,
                                modernCatalogCardWidth = modernCatalogCardWidth,
                                modernCatalogCardHeight = modernCatalogCardHeight,
                                effectiveExpandEnabled = effectiveExpandEnabled,
                                isBackdropExpanded = effectiveExpandEnabled &&
                                    !isRowScrolling &&
                                    expandedCatalogFocusKeyForRow == payload.focusKey,
                                playTrailerInExpandedCard = playTrailerInExpandedCard,
                                focusedPosterBackdropTrailerMuted = focusedPosterBackdropTrailerMuted,
                                trailerPreviewUrl = trailerPreviewUrl,
                                trailerPreviewAudioUrl = trailerPreviewAudioUrl,
                                trailerPreviewUserAgent = trailerPreviewUserAgent,
                                trailerPreviewExternalUrl = trailerPreviewExternalUrl,
                                isWatched = isWatched,
                                onFocused = onFocused,
                                onItemFocus = onItemFocus,
                                onPreloadAdjacentItem = remember(nextCatalogItem, onPreloadAdjacentItem) {
                                    { nextCatalogItem?.let(onPreloadAdjacentItem) }
                                },
                                onCatalogSelectionFocused = onCatalogSelectionFocused,
                                onNavigateToDetail = onNavigateToDetail,
                                onLongPress = remember(metaPreview, payload.addonBaseUrl) {
                                    { onCatalogItemLongPress(metaPreview, payload.addonBaseUrl) }
                                },
                                canPromoteHeroTrailerToFullscreen = canPromoteHeroTrailerToFullscreen,
                                fullscreenTrailerActive = fullscreenTrailerActive,
                                onPromoteHeroTrailerToFullscreen = onPromoteHeroTrailerToFullscreen,
                                onBackdropInteraction = onBackdropInteraction
                            )
                        }
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernCatalogLoadingPlaceholder(
    cardWidth: Dp,
    cardHeight: Dp,
    cornerRadius: Dp,
    focusRequester: FocusRequester,
    onFocused: () -> Unit
) {
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val shimmerBrush = rememberShimmerBrush()
    Box(
        modifier = Modifier
            .width(cardWidth)
            .height(cardHeight)
            .clip(shape)
            .background(shimmerBrush)
            .border(
                border = BorderStroke(1.dp, NexioColors.SurfaceVariant.copy(alpha = 0.45f)),
                shape = shape
            )
            .clearAndSetSemantics { }
            .testTag(MODERN_LOADING_PLACEHOLDER_TEST_TAG)
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .focusable()
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ModernCarouselCard(
    item: ModernCarouselItem,
    useLandscapePosters: Boolean,
    showLabels: Boolean,
    cardCornerRadius: Dp,
    cardWidth: Dp,
    cardHeight: Dp,
    focusedPosterBackdropExpandEnabled: Boolean,
    isBackdropExpanded: Boolean,
    playTrailerInExpandedCard: Boolean,
    focusedPosterBackdropTrailerMuted: Boolean,
    trailerPreviewUrl: String?,
    trailerPreviewAudioUrl: String?,
    trailerPreviewUserAgent: String?,
    trailerPreviewExternalUrl: String?,
    isWatched: Boolean,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onFocusStateChanged: (Boolean) -> Unit = {},
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    canPromoteHeroTrailerToFullscreen: Boolean,
    fullscreenTrailerActive: Boolean,
    onPromoteHeroTrailerToFullscreen: () -> Unit,
    onBackdropInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardShape = remember(cardCornerRadius) { RoundedCornerShape(cardCornerRadius) }
    val context = LocalContext.current
    val density = LocalDensity.current
    val expandedCardWidth = cardHeight * (16f / 9f)
    val targetCardWidth = if (focusedPosterBackdropExpandEnabled && isBackdropExpanded) {
        expandedCardWidth
    } else {
        cardWidth
    }
    val animatedCardWidthState = if (focusedPosterBackdropExpandEnabled) {
        animateDpAsState(
            targetValue = targetCardWidth,
            label = "modernCardWidth"
        )
    } else {
        rememberUpdatedState(cardWidth)
    }
    val animatedCardWidth by animatedCardWidthState
    val frozenBackdropUrl = remember(item.key) {
        mutableStateOf(item.heroPreview.frozenBackdropUrl ?: item.heroPreview.backdrop)
    }
    if (frozenBackdropUrl.value.isNullOrBlank() && !item.heroPreview.backdrop.isNullOrBlank()) {
        frozenBackdropUrl.value = item.heroPreview.backdrop
    }
    val frozenLogoUrl = remember(item.key) {
        mutableStateOf(item.heroPreview.frozenLogoUrl ?: item.heroPreview.logo)
    }
    if (frozenLogoUrl.value.isNullOrBlank() && !item.heroPreview.logo.isNullOrBlank()) {
        frozenLogoUrl.value = item.heroPreview.logo
    }
    val imageUrl = resolveModernCarouselCardImageUrl(
        focusedPosterBackdropExpandEnabled = focusedPosterBackdropExpandEnabled,
        isBackdropExpanded = isBackdropExpanded,
        frozenBackdropUrl = frozenBackdropUrl.value,
        itemImageUrl = item.imageUrl,
        heroPoster = item.heroPreview.poster,
        heroBackdrop = item.heroPreview.backdrop
    )
    // Keep decode target stable across expand/collapse to avoid recreating image requests/painters
    // purely due to animated width changes.
    val maxRequestCardWidth = if (focusedPosterBackdropExpandEnabled) {
        maxOf(cardWidth, expandedCardWidth)
    } else {
        cardWidth
    }
    val requestWidthPx = remember(maxRequestCardWidth, density) {
        with(density) { maxRequestCardWidth.roundToPx() }
    }
    val requestHeightPx = remember(cardHeight, density) {
        with(density) { cardHeight.roundToPx() }
    }
    val coilModel = remember(item, imageUrl, focusedPosterBackdropExpandEnabled, isBackdropExpanded) {
        resolveModernCarouselCardArtworkModel(
            item = item,
            useLandscapePosters = useLandscapePosters,
            focusedPosterBackdropExpandEnabled = focusedPosterBackdropExpandEnabled,
            isBackdropExpanded = isBackdropExpanded,
            fallbackModel = imageUrl
        )
    }
    val fallbackArtworkModel = remember(item, useLandscapePosters, focusedPosterBackdropExpandEnabled, isBackdropExpanded) {
        resolveModernCarouselCardFallbackArtworkModel(
            item = item,
            useLandscapePosters = useLandscapePosters,
            focusedPosterBackdropExpandEnabled = focusedPosterBackdropExpandEnabled,
            isBackdropExpanded = isBackdropExpanded
        )
    }
    val imageModel = remember(context, coilModel, requestWidthPx, requestHeightPx, item.metaPreview?.id, item.metaPreview?.posterProviderTag) {
        coilModel?.let { model ->
            val modelKey = model.toString()
            val diskKey = item.metaPreview?.let { meta ->
                if (focusedPosterBackdropExpandEnabled && isBackdropExpanded) {
                    ArtworkImageCacheKeys.backdrop(meta.id, modelKey)
                } else {
                    ArtworkImageCacheKeys.poster(meta.id, meta.posterProviderTag, modelKey)
                }
            }
            ImageRequest.Builder(context)
                .data(model)
                .crossfade(false)
                .memoryCacheKey("${modelKey}_${requestWidthPx}x${requestHeightPx}")
                .apply { if (diskKey != null) diskCacheKey(diskKey) }
                .size(width = requestWidthPx, height = requestHeightPx)
                .build()
        }
    }
    val logoHeight = cardHeight * 0.34f
    val logoHeightPx = remember(logoHeight, density) {
        with(density) { logoHeight.roundToPx() }
    }
    val maxLogoWidthPx = remember(maxRequestCardWidth, density) {
        with(density) { (maxRequestCardWidth * 0.62f).roundToPx() }
    }
    val effectiveLogoUrl = frozenLogoUrl.value
    val logoModel = remember(context, effectiveLogoUrl, maxLogoWidthPx, logoHeightPx, item.metaPreview?.id) {
        (item.metaPreview?.artwork?.logo.toCoilModelOrNull()
            ?: effectiveLogoUrl.toLegacyArtworkCoilModelOrNull("${item.key}:logo", ArtworkType.LOGO))?.let {
            val modelKey = it.toString()
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(false)
                .memoryCacheKey("${modelKey}_${maxLogoWidthPx}x${logoHeightPx}")
                .apply { item.metaPreview?.let { meta -> diskCacheKey(ArtworkImageCacheKeys.logo(meta.id, modelKey)) } }
                .size(width = maxLogoWidthPx, height = logoHeightPx)
                .build()
        }
    }
    var landscapeLogoLoadFailed by remember(effectiveLogoUrl) { mutableStateOf(false) }
    var trailerFirstFrameRendered by remember(trailerPreviewUrl) { mutableStateOf(false) }
    var lastExternalTrailerLaunchKey by remember { mutableStateOf<String?>(null) }
    val hasImage = coilModel != null
    val hasLandscapeLogo =
        (useLandscapePosters || isBackdropExpanded) &&
            logoModel != null &&
            !landscapeLogoLoadFailed
    var isFocused by remember { mutableStateOf(false) }
    var longPressTriggered by remember { mutableStateOf(false) }
    val backgroundCardColor = NexioColors.BackgroundCard
    val focusRingColor = NexioColors.FocusRing
    val titleMedium = MaterialTheme.typography.titleMedium
    val focusedBorder = remember(cardShape, focusRingColor) {
        Border(
            border = BorderStroke(2.dp, focusRingColor),
            shape = cardShape
        )
    }
    val titleStyle = remember(titleMedium) {
        titleMedium.copy(fontWeight = FontWeight.Medium)
    }

    Column(
        modifier = modifier.width(animatedCardWidth),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LaunchedEffect(isBackdropExpanded, isFocused, trailerPreviewUrl, trailerPreviewExternalUrl) {
            if (!playTrailerInExpandedCard) return@LaunchedEffect
            if (!isBackdropExpanded || !isFocused) return@LaunchedEffect
            if (!trailerPreviewUrl.isNullOrBlank()) return@LaunchedEffect
            val externalUrl = trailerPreviewExternalUrl ?: return@LaunchedEffect
            val launchKey = "${item.key}|$externalUrl"
            if (lastExternalTrailerLaunchKey == launchKey) return@LaunchedEffect
            lastExternalTrailerLaunchKey = launchKey
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(externalUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        }
        Card(
            onClick = {
                if (longPressTriggered) {
                    longPressTriggered = false
                } else {
                    onClick()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(cardHeight)
                .focusRequester(focusRequester)
                .onFocusChanged {
                    isFocused = it.isFocused
                    onFocusStateChanged(it.isFocused)
                    if (it.isFocused) {
                        onFocused()
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (
                        shouldPromoteModernHomeHeroTrailerToFullscreen(
                            key = event.key,
                            eventType = event.type,
                            heroTrailerPlaying = canPromoteHeroTrailerToFullscreen,
                            fullscreenTrailerActive = fullscreenTrailerActive
                        )
                    ) {
                        onPromoteHeroTrailerToFullscreen()
                        return@onPreviewKeyEvent true
                    }
                    val native = event.nativeKeyEvent
                    if (native.action == AndroidKeyEvent.ACTION_DOWN) {
                        if (focusedPosterBackdropExpandEnabled && shouldResetBackdropTimer(event.key)) {
                            onBackdropInteraction()
                        }
                        if (native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                            longPressTriggered = true
                            onLongPress()
                            return@onPreviewKeyEvent true
                        }
                        val isLongPress = native.isLongPress || native.repeatCount > 0
                        if (isLongPress && isSelectKey(native.keyCode)) {
                            longPressTriggered = true
                            onLongPress()
                            return@onPreviewKeyEvent true
                        }
                    }
                    if (native.action == AndroidKeyEvent.ACTION_UP &&
                        longPressTriggered &&
                        isSelectKey(native.keyCode)
                    ) {
                        longPressTriggered = false
                        return@onPreviewKeyEvent true
                    }
                    false
                },
            shape = CardDefaults.shape(shape = cardShape),
            colors = CardDefaults.colors(
                containerColor = backgroundCardColor,
                focusedContainerColor = backgroundCardColor
            ),
            border = CardDefaults.border(
                focusedBorder = focusedBorder
            ),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                val mediaLayerModifier = remember(hasLandscapeLogo) {
                    if (hasLandscapeLogo) {
                        Modifier
                            .fillMaxSize()
                            .drawWithCache {
                                onDrawWithContent {
                                    drawContent()
                                    drawRect(brush = MODERN_LANDSCAPE_LOGO_GRADIENT, size = size)
                                }
                            }
                    } else {
                        Modifier.fillMaxSize()
                    }
                }
                val shouldPlayTrailerInCard =
                    playTrailerInExpandedCard &&
                        isBackdropExpanded &&
                        isFocused &&
                        !trailerPreviewUrl.isNullOrBlank()

                LaunchedEffect(shouldPlayTrailerInCard) {
                    if (!shouldPlayTrailerInCard) {
                        trailerFirstFrameRendered = false
                    }
                }

                val trailerCoverAlpha by animateFloatAsState(
                    targetValue = if (shouldPlayTrailerInCard && !trailerFirstFrameRendered) 1f else 0f,
                    animationSpec = tween(durationMillis = 250),
                    label = "modernExpandedCardTrailerCoverAlpha"
                )

                Box(modifier = mediaLayerModifier) {
                    if (hasImage) {
                        FallbackArtworkImage(
                            model = imageModel,
                            fallbackModel = fallbackArtworkModel,
                            contentDescription = item.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        MonochromePosterPlaceholder()
                    }
                    if (shouldPlayTrailerInCard) {
                        TrailerPlayer(
                            trailerUrl = trailerPreviewUrl,
                            trailerAudioUrl = trailerPreviewAudioUrl,
                            trailerUserAgent = trailerPreviewUserAgent,
                            isPlaying = true,
                            onEnded = {
                                trailerFirstFrameRendered = false
                            },
                            onFirstFrameRendered = {
                                trailerFirstFrameRendered = true
                            },
                            muted = focusedPosterBackdropTrailerMuted,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (hasImage) {
                            AsyncImage(
                                model = imageModel,
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { alpha = trailerCoverAlpha },
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }

                if (hasLandscapeLogo) {
                    AsyncImage(
                        model = logoModel,
                        contentDescription = item.title,
                        onError = { landscapeLogoLoadFailed = true },
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(0.62f)
                            .height(cardHeight * 0.34f)
                            .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart
                    )
                } else if (useLandscapePosters || isBackdropExpanded) {
                    Text(
                        text = item.title,
                        style = titleStyle,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(0.62f)
                            .padding(start = 10.dp, end = 10.dp, bottom = 12.dp)
                    )
                }

                if (isWatched) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.episodes_cd_watched),
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = 8.dp, top = 8.dp)
                            .zIndex(2f)
                            .size(21.dp)
                            .drawBehind {
                                drawCircle(
                                    color = Color.Black,
                                    radius = size.minDimension / 2f + 1.5f
                                )
                            }
                    )
                }
            }
        }

        if (showLabels && !isBackdropExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            ) {
                Text(
                    text = item.title,
                    style = titleStyle,
                    color = NexioColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                item.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = NexioColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}


private fun shouldResetBackdropTimer(key: Key): Boolean {
    return when (key) {
        Key.DirectionUp,
        Key.DirectionDown,
        Key.DirectionLeft,
        Key.DirectionRight,
        Key.DirectionCenter,
        Key.Enter,
        Key.NumPadEnter,
        Key.Back -> true
        else -> false
    }
}

private fun isSelectKey(keyCode: Int): Boolean {
    return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
}
