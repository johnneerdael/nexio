@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nexio.tv.ui.screens.home

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.drawWithCache
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
import coil.request.ImageRequest
import com.nexio.tv.R
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.ui.components.ContinueWatchingCard
import com.nexio.tv.ui.components.MonochromePosterPlaceholder
import com.nexio.tv.ui.theme.NexioColors
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ModernContinueWatchingRowItem(
    payload: ModernPayload.ContinueWatching,
    requester: FocusRequester,
    cardWidth: Dp,
    imageHeight: Dp,
    onFocused: () -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onShowOptions: (ContinueWatchingItem) -> Unit
) {
    ContinueWatchingCard(
        item = payload.item,
        onClick = { onContinueWatchingClick(payload.item) },
        onLongPress = { onShowOptions(payload.item) },
        cardWidth = cardWidth,
        imageHeight = imageHeight,
        modifier = Modifier
            .focusRequester(requester)
            .onFocusChanged {
                if (it.isFocused) {
                    onFocused()
                }
            }
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ModernCatalogRowItem(
    item: ModernCarouselItem,
    payload: ModernPayload.Catalog,
    requester: FocusRequester,
    useLandscapePosters: Boolean,
    showLabels: Boolean,
    posterCardCornerRadius: Dp,
    modernCatalogCardWidth: Dp,
    modernCatalogCardHeight: Dp,
    effectiveExpandEnabled: Boolean,
    expandedCatalogFocusKey: String?,
    isWatched: Boolean,
    onFocused: () -> Unit,
    onItemFocus: (MetaPreview) -> Unit,
    onCatalogSelectionFocused: (FocusedCatalogSelection) -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    onLongPress: () -> Unit,
    onBackdropInteraction: () -> Unit
) {
    val focusKey = payload.focusKey
    val isBackdropExpanded =
        effectiveExpandEnabled &&
            expandedCatalogFocusKey == focusKey

    ModernCarouselCard(
        item = item,
        useLandscapePosters = useLandscapePosters,
        showLabels = showLabels,
        cardCornerRadius = posterCardCornerRadius,
        cardWidth = modernCatalogCardWidth,
        cardHeight = modernCatalogCardHeight,
        focusedPosterBackdropExpandEnabled = effectiveExpandEnabled,
        isBackdropExpanded = isBackdropExpanded,
        isWatched = isWatched,
        focusRequester = requester,
        onFocused = {
            onFocused()
            item.metaPreview?.let { onItemFocus(it) }
            onCatalogSelectionFocused(
                FocusedCatalogSelection(
                    focusKey = focusKey,
                    payload = payload
                )
            )
        },
        onClick = {
            onNavigateToDetail(
                payload.itemId,
                payload.itemType,
                payload.addonBaseUrl
            )
        },
        onLongPress = onLongPress,
        onBackdropInteraction = onBackdropInteraction
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ModernRowSection(
    row: HeroCarouselRow,
    rowTitleBottom: Dp,
    defaultBringIntoViewSpec: BringIntoViewSpec,
    focusStateCatalogRowScrollStates: Map<String, Int>,
    uiCaches: ModernHomeUiCaches,
    pendingRowFocusKey: String?,
    pendingRowFocusIndex: Int?,
    pendingRowFocusNonce: Int,
    onPendingRowFocusCleared: () -> Unit,
    onRowItemFocused: (String, Int, Boolean) -> Unit,
    useLandscapePosters: Boolean,
    showLabels: Boolean,
    posterCardCornerRadius: Dp,
    effectiveExpandEnabled: Boolean,
    expandedCatalogFocusKey: String?,
    modernCatalogCardWidth: Dp,
    modernCatalogCardHeight: Dp,
    continueWatchingCardWidth: Dp,
    continueWatchingCardHeight: Dp,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingOptions: (ContinueWatchingItem) -> Unit,
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit,
    onItemFocus: (MetaPreview) -> Unit,
    onCatalogSelectionFocused: (FocusedCatalogSelection) -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    onLoadMoreCatalog: (String, String, String) -> Unit,
    onBackdropInteraction: () -> Unit
) {
    val focusedItemByRow = uiCaches.focusedItemByRow
    val itemFocusRequesters = uiCaches.itemFocusRequesters
    val rowListStates = uiCaches.rowListStates
    val loadMoreRequestedTotals = uiCaches.loadMoreRequestedTotals

    Column {
        Text(
            text = row.title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = NexioColors.TextPrimary,
            modifier = Modifier.padding(start = 52.dp, bottom = rowTitleBottom)
        )

        val rowListState = rowListStates.getOrPut(row.key) {
            LazyListState(
                firstVisibleItemIndex = focusStateCatalogRowScrollStates[row.key] ?: 0
            )
        }
        val isRowScrolling by remember(rowListState) {
            derivedStateOf { rowListState.isScrollInProgress }
        }
        val currentRowState = rememberUpdatedState(row)
        val loadMoreCatalogId = row.catalogId
        val loadMoreAddonId = row.addonId
        val loadMoreApiType = row.apiType
        val canObserveLoadMore = row.supportsSkip &&
            row.hasMore &&
            !loadMoreCatalogId.isNullOrBlank() &&
            !loadMoreAddonId.isNullOrBlank() &&
            !loadMoreApiType.isNullOrBlank()

        LaunchedEffect(row.key, pendingRowFocusNonce) {
            if (pendingRowFocusKey != row.key) return@LaunchedEffect
            val targetIndex = (pendingRowFocusIndex ?: 0)
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
        val horizontalBringIntoViewSpec = remember(density, defaultBringIntoViewSpec) {
            val parentStartOffsetPx = with(density) { rowStartPadding.roundToPx() }
            object : BringIntoViewSpec {
                @Suppress("DEPRECATION")
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

        val restoreRequester = remember(
            focusedItemByRow[row.key],
            row.items,
            rowListState.firstVisibleItemIndex,
            rowListState.layoutInfo.visibleItemsInfo,
            itemFocusRequesters,
            row.key
        ) {
            val rememberedIndex = (focusedItemByRow[row.key] ?: 0)
                .coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
            val fallbackIndex = rowListState.firstVisibleItemIndex
                .coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
            val restoreIndex = if (rememberedIndex in row.items.indices) {
                rememberedIndex
            } else {
                fallbackIndex
            }
            val visibleIndices = rowListState.layoutInfo.visibleItemsInfo.map { it.index }.toSet()
            val safeIndex = if (restoreIndex in visibleIndices) {
                restoreIndex
            } else {
                visibleIndices.minByOrNull { kotlin.math.abs(it - restoreIndex) } ?: fallbackIndex
            }
            val itemKey = row.items.getOrNull(safeIndex)?.key ?: row.items.first().key
            itemFocusRequesters[row.key]?.get(itemKey) ?: FocusRequester.Default
        }

        CompositionLocalProvider(LocalBringIntoViewSpec provides horizontalBringIntoViewSpec) {
            LazyRow(
                state = rowListState,
                modifier = Modifier
                    .focusRestorer(restoreRequester),
                contentPadding = PaddingValues(horizontal = rowStartPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(
                    items = row.items,
                    key = { _, item -> item.key },
                    contentType = { _, item ->
                        when (item.payload) {
                            is ModernPayload.ContinueWatching -> "modern_cw_card"
                            is ModernPayload.Catalog -> "modern_catalog_card"
                        }
                    }
                ) { index, item ->
                    val requester = uiCaches.requesterFor(row.key, item.key)
                    val isContinueWatchingRow = row.key == "continue_watching"
                    val onFocused = {
                        onRowItemFocused(row.key, index, isContinueWatchingRow)
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
                            )
                        }

                        is ModernPayload.Catalog -> {
                            ModernCatalogRowItem(
                                item = item,
                                payload = payload,
                                requester = requester,
                                useLandscapePosters = useLandscapePosters,
                                showLabels = showLabels,
                                posterCardCornerRadius = posterCardCornerRadius,
                                modernCatalogCardWidth = modernCatalogCardWidth,
                                modernCatalogCardHeight = modernCatalogCardHeight,
                                effectiveExpandEnabled = effectiveExpandEnabled && !isRowScrolling,
                                expandedCatalogFocusKey = expandedCatalogFocusKey,
                                isWatched = item.metaPreview?.let(isCatalogItemWatched) == true,
                                onFocused = onFocused,
                                onItemFocus = onItemFocus,
                                onCatalogSelectionFocused = onCatalogSelectionFocused,
                                onNavigateToDetail = onNavigateToDetail,
                                onLongPress = {
                                    item.metaPreview?.let { preview ->
                                        onCatalogItemLongPress(preview, payload.addonBaseUrl)
                                    }
                                },
                                onBackdropInteraction = onBackdropInteraction
                            )
                        }
                    }
                }
            }
        }
    }
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
    isWatched: Boolean,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onBackdropInteraction: () -> Unit
) {
    val cardShape = RoundedCornerShape(cardCornerRadius)
    val context = LocalContext.current
    val density = LocalDensity.current
    val expandedCardWidth = cardHeight * (16f / 9f)
    val targetCardWidth = if (focusedPosterBackdropExpandEnabled && isBackdropExpanded) {
        expandedCardWidth
    } else {
        cardWidth
    }
    val animatedCardWidth by if (focusedPosterBackdropExpandEnabled) {
        animateDpAsState(
            targetValue = targetCardWidth,
            label = "modernCardWidth"
        )
    } else {
        rememberUpdatedState(cardWidth)
    }
    val imageUrl = if (focusedPosterBackdropExpandEnabled && isBackdropExpanded) {
        item.heroPreview.backdrop ?: item.imageUrl ?: item.heroPreview.poster
    } else {
        item.imageUrl ?: item.heroPreview.poster ?: item.heroPreview.backdrop
    }
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
    val imageModel = remember(context, imageUrl, requestWidthPx, requestHeightPx) {
        imageUrl?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(false)
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
    val logoModel = remember(context, item.heroPreview.logo, maxLogoWidthPx, logoHeightPx) {
        item.heroPreview.logo?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(false)
                .size(width = maxLogoWidthPx, height = logoHeightPx)
                .build()
        }
    }
    var landscapeLogoLoadFailed by remember(item.heroPreview.logo) { mutableStateOf(false) }
    val hasImage = !imageUrl.isNullOrBlank()
    val hasLandscapeLogo =
        useLandscapePosters &&
            !item.heroPreview.logo.isNullOrBlank() &&
            !landscapeLogoLoadFailed
    var isFocused by remember { mutableStateOf(false) }
    var longPressTriggered by remember { mutableStateOf(false) }
    val watchedIconEndPadding by animateDpAsState(
        targetValue = if (isFocused) 16.dp else 8.dp,
        animationSpec = tween(durationMillis = 180),
        label = "modernCardWatchedIconEndPadding"
    )
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
        modifier = Modifier.width(animatedCardWidth),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
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
                    if (it.isFocused) {
                        onFocused()
                    }
                }
                .onPreviewKeyEvent { event ->
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
                val mediaLayerModifier = if (hasLandscapeLogo) {
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

                Box(modifier = mediaLayerModifier) {
                    if (hasImage) {
                        AsyncImage(
                            model = imageModel,
                            contentDescription = item.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        MonochromePosterPlaceholder()
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
                } else if (useLandscapePosters) {
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
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = watchedIconEndPadding, top = 8.dp)
                            .zIndex(2f),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(24.dp)
                        )
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = stringResource(R.string.episodes_cd_watched),
                            tint = Color.White,
                            modifier = Modifier.size(21.dp)
                        )
                    }
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
