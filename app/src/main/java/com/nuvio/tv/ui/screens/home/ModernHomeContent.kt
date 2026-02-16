package com.nuvio.tv.ui.screens.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.theme.NuvioColors

private val YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")
private const val MODERN_HERO_TEXT_WIDTH_FRACTION = 0.75f

private data class HeroPreview(
    val title: String,
    val logo: String?,
    val description: String?,
    val contentTypeText: String?,
    val yearText: String?,
    val imdbText: String?,
    val genres: List<String>,
    val poster: String?,
    val backdrop: String?,
    val imageUrl: String?
)

private sealed class ModernPayload {
    data class ContinueWatching(val item: ContinueWatchingItem) : ModernPayload()
    data class Catalog(
        val itemId: String,
        val itemType: String,
        val addonBaseUrl: String
    ) : ModernPayload()
}

private data class ModernCarouselItem(
    val key: String,
    val title: String,
    val subtitle: String?,
    val imageUrl: String?,
    val heroPreview: HeroPreview,
    val payload: ModernPayload
)

private data class HeroCarouselRow(
    val key: String,
    val title: String,
    val globalRowIndex: Int,
    val items: List<ModernCarouselItem>
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ModernHomeContent(
    uiState: HomeUiState,
    focusState: HomeScreenFocusState,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onSaveFocusState: (Int, Int, Int, Int, Map<String, Int>) -> Unit
) {
    val useLandscapePosters = uiState.modernLandscapePostersEnabled
    val showNextRowPreview = uiState.modernNextRowPreviewEnabled
    val visibleCatalogRows = remember(uiState.catalogRows) {
        uiState.catalogRows.filter { it.items.isNotEmpty() }
    }
    val carouselRows = remember(
        uiState.continueWatchingItems,
        visibleCatalogRows,
        useLandscapePosters
    ) {
        buildList {
            if (uiState.continueWatchingItems.isNotEmpty()) {
                add(
                    HeroCarouselRow(
                        key = "continue_watching",
                        title = "Continue Watching",
                        globalRowIndex = -1,
                        items = uiState.continueWatchingItems.mapIndexed { index, item ->
                            buildContinueWatchingItem(
                                index = index,
                                item = item,
                                useLandscapePosters = useLandscapePosters
                            )
                        }
                    )
                )
            }

            visibleCatalogRows.forEachIndexed { index, row ->
                add(
                    HeroCarouselRow(
                        key = catalogRowKey(row),
                        title = catalogRowTitle(row),
                        globalRowIndex = index,
                        items = row.items.mapIndexed { itemIndex, item ->
                            buildCatalogItem(
                                index = itemIndex,
                                item = item,
                                row = row,
                                useLandscapePosters = useLandscapePosters
                            )
                        }
                    )
                )
            }
        }
    }

    if (carouselRows.isEmpty()) return

    val focusedItemByRow = remember { mutableStateMapOf<String, Int>() }
    val itemFocusRequesters = remember { mutableMapOf<String, MutableMap<Int, FocusRequester>>() }
    val rowListStates = remember { mutableMapOf<String, LazyListState>() }
    val transitionFocusRequester = remember { FocusRequester() }

    var activeRowKey by remember { mutableStateOf<String?>(null) }
    var pendingRowFocusKey by remember { mutableStateOf<String?>(null) }
    var pendingRowFocusIndex by remember { mutableStateOf<Int?>(null) }
    var heroItem by remember { mutableStateOf<HeroPreview?>(null) }
    var rowTransitionInProgress by remember { mutableStateOf(false) }
    var restoredFromSavedState by remember { mutableStateOf(false) }

    fun requesterFor(rowKey: String, index: Int): FocusRequester {
        val byIndex = itemFocusRequesters.getOrPut(rowKey) { mutableMapOf() }
        return byIndex.getOrPut(index) { FocusRequester() }
    }

    fun moveToRow(direction: Int): Boolean {
        if (rowTransitionInProgress) return true
        val currentRowKey = activeRowKey ?: return false
        val currentIndex = carouselRows.indexOfFirst { it.key == currentRowKey }
        if (currentIndex < 0) return false
        val targetIndex = currentIndex + direction
        if (targetIndex !in carouselRows.indices) return false
        val targetRow = carouselRows[targetIndex]
        if (targetRow.items.isEmpty()) return false

        rowTransitionInProgress = true
        activeRowKey = targetRow.key
        focusedItemByRow[targetRow.key] = 0
        heroItem = targetRow.items.firstOrNull()?.heroPreview
        pendingRowFocusKey = targetRow.key
        pendingRowFocusIndex = 0
        return true
    }

    LaunchedEffect(carouselRows, focusState.hasSavedFocus, focusState.focusedRowIndex, focusState.focusedItemIndex) {
        val activeKeys = carouselRows.map { it.key }.toSet()
        focusedItemByRow.keys.retainAll(activeKeys)
        itemFocusRequesters.keys.retainAll(activeKeys)
        rowListStates.keys.retainAll(activeKeys)

        carouselRows.forEach { row ->
            if (row.items.isNotEmpty() && row.key !in focusedItemByRow) {
                focusedItemByRow[row.key] = 0
            }
        }

        if (!restoredFromSavedState && focusState.hasSavedFocus) {
            val savedRowKey = when {
                focusState.focusedRowIndex == -1 && uiState.continueWatchingItems.isNotEmpty() -> "continue_watching"
                focusState.focusedRowIndex >= 0 -> visibleCatalogRows.getOrNull(focusState.focusedRowIndex)?.let { catalogRowKey(it) }
                else -> null
            }

            val resolvedRow = carouselRows.firstOrNull { it.key == savedRowKey } ?: carouselRows.first()
            val resolvedIndex = focusState.focusedItemIndex
                .coerceAtLeast(0)
                .coerceAtMost((resolvedRow.items.size - 1).coerceAtLeast(0))

            activeRowKey = resolvedRow.key
            focusedItemByRow[resolvedRow.key] = resolvedIndex
            heroItem = resolvedRow.items.getOrNull(resolvedIndex)?.heroPreview
                ?: resolvedRow.items.firstOrNull()?.heroPreview
            pendingRowFocusKey = resolvedRow.key
            pendingRowFocusIndex = resolvedIndex
            restoredFromSavedState = true
            return@LaunchedEffect
        }

        val hadActiveRow = activeRowKey != null
        val existingActive = activeRowKey?.let { key -> carouselRows.firstOrNull { it.key == key } }
        val resolvedActive = existingActive ?: carouselRows.first()
        activeRowKey = resolvedActive.key
        val resolvedIndex = focusedItemByRow[resolvedActive.key]
            ?.coerceIn(0, (resolvedActive.items.size - 1).coerceAtLeast(0))
            ?: 0
        focusedItemByRow[resolvedActive.key] = resolvedIndex
        heroItem = resolvedActive.items.getOrNull(resolvedIndex)?.heroPreview
            ?: resolvedActive.items.firstOrNull()?.heroPreview
        if (!focusState.hasSavedFocus && !hadActiveRow) {
            pendingRowFocusKey = resolvedActive.key
            pendingRowFocusIndex = resolvedIndex
        }
    }

    val activeRow = remember(carouselRows, activeRowKey) {
        val activeKey = activeRowKey
        carouselRows.firstOrNull { it.key == activeKey } ?: carouselRows.firstOrNull()
    }
    val activeItemIndex = activeRow?.let { row ->
        focusedItemByRow[row.key]?.coerceIn(0, (row.items.size - 1).coerceAtLeast(0)) ?: 0
    } ?: 0
    val nextRow = remember(carouselRows, activeRow?.key) {
        val index = carouselRows.indexOfFirst { it.key == activeRow?.key }
        if (index in carouselRows.indices && index + 1 < carouselRows.size) {
            carouselRows[index + 1]
        } else {
            null
        }
    }

    DisposableEffect(activeRow?.key, activeItemIndex, carouselRows) {
        onDispose {
            val row = activeRow
            val focusedRowIndex = row?.globalRowIndex ?: 0
            val catalogRowScrollStates = carouselRows
                .filter { it.globalRowIndex >= 0 }
                .associate { rowState -> rowState.key to (focusedItemByRow[rowState.key] ?: 0) }

            onSaveFocusState(
                0,
                0,
                focusedRowIndex,
                activeItemIndex,
                catalogRowScrollStates
            )
        }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val previewRowEnabled = showNextRowPreview
        val posterScale = if (previewRowEnabled) 1f else 1.34f
        val rowHorizontalPadding = 52.dp
        val rowItemSpacing = 12.dp
        val portraitBaseWidth = uiState.posterCardWidthDp.dp
        val portraitBaseHeight = uiState.posterCardHeightDp.dp
        val activeCardWidth = if (useLandscapePosters) {
            portraitBaseWidth * 1.24f * posterScale
        } else {
            portraitBaseWidth * 0.84f * posterScale
        }
        val activeCardHeight = if (useLandscapePosters) {
            activeCardWidth / 1.77f
        } else {
            portraitBaseHeight * 0.84f * posterScale
        }
        val previewCardWidth = activeCardWidth
        val previewCardHeight = activeCardHeight
        val previewVisibleHeight = if (useLandscapePosters) {
            previewCardHeight * 0.30f
        } else {
            previewCardHeight * 0.22f
        }

        val resolvedHero = heroItem ?: activeRow?.items?.firstOrNull()?.heroPreview
        val heroBackdrop = resolvedHero?.backdrop?.takeIf { it.isNotBlank() }
            ?: activeRow?.items?.firstNotNullOfOrNull { item ->
                item.heroPreview.backdrop?.takeIf { it.isNotBlank() }
            }
        val shouldRenderPreviewRow = showNextRowPreview && nextRow != null
        val titleY = when {
            useLandscapePosters -> maxHeight * 0.23f
            shouldRenderPreviewRow -> maxHeight * 0.16f
            else -> maxHeight * 0.18f
        }

        Crossfade(
            targetState = heroBackdrop,
            animationSpec = tween(durationMillis = 350),
            label = "modernHeroBackground"
        ) { imageUrl ->
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageUrl)
                    .crossfade(false)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        val bgColor = NuvioColors.Background
        val bottomGradient = remember(bgColor) {
            Brush.verticalGradient(
                colorStops = arrayOf(
                    0.0f to Color.Transparent,
                    0.5f to Color.Transparent,
                    0.76f to bgColor.copy(alpha = 0.68f),
                    1.0f to bgColor
                )
            )
        }
        val leftGradient = remember(bgColor) {
            Brush.horizontalGradient(
                colorStops = arrayOf(
                    0.0f to bgColor.copy(alpha = 0.94f),
                    0.34f to bgColor.copy(alpha = 0.58f),
                    0.64f to Color.Transparent,
                    1.0f to Color.Transparent
                )
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bottomGradient)
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(leftGradient)
        )

        HeroTitleBlock(
            preview = resolvedHero,
            portraitMode = !useLandscapePosters,
            shouldRenderPreviewRow = shouldRenderPreviewRow,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(y = titleY)
                .padding(start = rowHorizontalPadding, end = 48.dp)
                .fillMaxWidth(MODERN_HERO_TEXT_WIDTH_FRACTION)
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(bottom = if (shouldRenderPreviewRow) 42.dp else 52.dp)
        ) {
            Text(
                text = activeRow?.title.orEmpty(),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = NuvioColors.TextPrimary,
                modifier = Modifier.padding(start = rowHorizontalPadding, bottom = 6.dp)
            )

            activeRow?.let { row ->
                val rowListState = rowListStates.getOrPut(row.key) { LazyListState() }

                LaunchedEffect(row.key, pendingRowFocusKey, pendingRowFocusIndex) {
                    if (pendingRowFocusKey != row.key) return@LaunchedEffect
                    val targetIndex = (pendingRowFocusIndex ?: 0)
                        .coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
                    runCatching { rowListState.scrollToItem(targetIndex) }
                }

                LazyRow(
                    state = rowListState,
                    contentPadding = PaddingValues(horizontal = rowHorizontalPadding),
                    horizontalArrangement = Arrangement.spacedBy(rowItemSpacing)
                ) {
                    itemsIndexed(
                        items = row.items,
                        key = { index, item -> "${row.key}_${item.key}_$index" }
                    ) { index, item ->
                        val requester = requesterFor(row.key, index)
                        if (pendingRowFocusKey == row.key && pendingRowFocusIndex == index) {
                            LaunchedEffect(row.key, pendingRowFocusKey, pendingRowFocusIndex, index) {
                                repeat(24) {
                                    val didFocus = runCatching {
                                        requester.requestFocus()
                                        true
                                    }.getOrDefault(false)
                                    if (didFocus) {
                                        pendingRowFocusKey = null
                                        pendingRowFocusIndex = null
                                        rowTransitionInProgress = false
                                        return@LaunchedEffect
                                    }
                                    withFrameNanos { }
                                }
                                pendingRowFocusKey = null
                                pendingRowFocusIndex = null
                                rowTransitionInProgress = false
                            }
                        }
                        ModernCarouselCard(
                            item = item,
                            useLandscapePosters = useLandscapePosters,
                            cardWidth = activeCardWidth,
                            cardHeight = activeCardHeight,
                            focusRequester = requester,
                            onFocused = {
                                focusedItemByRow[row.key] = index
                                activeRowKey = row.key
                                heroItem = item.heroPreview
                            },
                            onClick = {
                                when (val payload = item.payload) {
                                    is ModernPayload.Catalog -> onNavigateToDetail(
                                        payload.itemId,
                                        payload.itemType,
                                        payload.addonBaseUrl
                                    )
                                    is ModernPayload.ContinueWatching -> onContinueWatchingClick(payload.item)
                                }
                            },
                            onMoveUp = { moveToRow(-1) },
                            onMoveDown = { moveToRow(1) },
                            rowTransitionInProgress = rowTransitionInProgress
                        )
                    }
                }
            }

            if (shouldRenderPreviewRow) {
                val previewRow = nextRow
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .height(previewVisibleHeight)
                        .clipToBounds()
                ) {
                    LazyRow(
                        userScrollEnabled = false,
                        contentPadding = PaddingValues(horizontal = rowHorizontalPadding),
                        horizontalArrangement = Arrangement.spacedBy(rowItemSpacing)
                    ) {
                        itemsIndexed(previewRow.items.take(12), key = { index, item -> "${previewRow.key}_${item.key}_$index" }) { _, item ->
                            PreviewCarouselCard(
                                imageUrl = item.imageUrl ?: item.heroPreview.poster ?: item.heroPreview.backdrop,
                                cardWidth = previewCardWidth,
                                cardHeight = previewCardHeight
                            )
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .size(1.dp)
                .focusRequester(transitionFocusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (!rowTransitionInProgress || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionUp,
                        Key.DirectionDown,
                        Key.DirectionLeft,
                        Key.DirectionRight,
                        Key.DirectionCenter,
                        Key.Enter -> true
                        else -> false
                    }
                }
        )
    }
}

private fun buildContinueWatchingItem(
    index: Int,
    item: ContinueWatchingItem,
    useLandscapePosters: Boolean
): ModernCarouselItem {
    val heroPreview = when (item) {
        is ContinueWatchingItem.InProgress -> HeroPreview(
            title = item.progress.name,
            logo = item.progress.logo,
            description = item.episodeDescription ?: item.progress.episodeTitle,
            contentTypeText = item.progress.contentType.replaceFirstChar { ch -> ch.uppercase() },
            yearText = null,
            imdbText = null,
            genres = emptyList(),
            poster = item.progress.poster,
            backdrop = item.progress.backdrop,
            imageUrl = if (useLandscapePosters) {
                item.progress.backdrop ?: item.progress.poster
            } else {
                item.progress.poster ?: item.progress.backdrop
            }
        )
        is ContinueWatchingItem.NextUp -> HeroPreview(
            title = item.info.name,
            logo = item.info.logo,
            description = item.info.episodeDescription ?: item.info.episodeTitle,
            contentTypeText = item.info.contentType.replaceFirstChar { ch -> ch.uppercase() },
            yearText = null,
            imdbText = null,
            genres = emptyList(),
            poster = item.info.poster,
            backdrop = item.info.backdrop,
            imageUrl = if (useLandscapePosters) {
                item.info.backdrop ?: item.info.poster
            } else {
                item.info.poster ?: item.info.backdrop
            }
        )
    }

    val imageUrl = when (item) {
        is ContinueWatchingItem.InProgress -> if (useLandscapePosters) {
            if (isSeriesType(item.progress.contentType)) {
                item.episodeThumbnail ?: item.progress.poster ?: item.progress.backdrop
            } else {
                item.progress.backdrop ?: item.progress.poster
            }
        } else {
            if (isSeriesType(item.progress.contentType)) {
                heroPreview.poster ?: item.progress.poster
            } else {
                item.progress.poster ?: item.progress.backdrop
            }
        }
        is ContinueWatchingItem.NextUp -> if (useLandscapePosters) {
            item.info.thumbnail ?: item.info.poster ?: item.info.backdrop
        } else {
            item.info.poster ?: item.info.backdrop
        }
    }

    return ModernCarouselItem(
        key = "cw_${index}_$imageUrl",
        title = when (item) {
            is ContinueWatchingItem.InProgress -> item.progress.name
            is ContinueWatchingItem.NextUp -> item.info.name
        },
        subtitle = when (item) {
            is ContinueWatchingItem.InProgress -> item.progress.episodeDisplayString ?: item.progress.episodeTitle
            is ContinueWatchingItem.NextUp -> "S${item.info.season}E${item.info.episode}"
        },
        imageUrl = imageUrl,
        heroPreview = heroPreview.copy(imageUrl = imageUrl ?: heroPreview.imageUrl),
        payload = ModernPayload.ContinueWatching(item)
    )
}

private fun buildCatalogItem(
    index: Int,
    item: MetaPreview,
    row: CatalogRow,
    useLandscapePosters: Boolean
): ModernCarouselItem {
    val heroPreview = HeroPreview(
        title = item.name,
        logo = item.logo,
        description = item.description,
        contentTypeText = item.apiType.replaceFirstChar { ch -> ch.uppercase() },
        yearText = extractYear(item.releaseInfo),
        imdbText = item.imdbRating?.let { String.format("%.1f", it) },
        genres = item.genres.take(3),
        poster = item.poster,
        backdrop = item.background,
        imageUrl = if (useLandscapePosters) {
            item.background ?: item.poster
        } else {
            item.poster ?: item.background
        }
    )

    return ModernCarouselItem(
        key = "catalog_${row.key()}_${item.id}_$index",
        title = item.name,
        subtitle = item.releaseInfo,
        imageUrl = if (useLandscapePosters) {
            item.background ?: item.poster
        } else {
            item.poster ?: item.background
        },
        heroPreview = heroPreview,
        payload = ModernPayload.Catalog(
            itemId = item.id,
            itemType = item.type.toApiString(),
            addonBaseUrl = row.addonBaseUrl
        )
    )
}

private fun catalogRowKey(row: CatalogRow): String {
    return "${row.addonId}_${row.apiType}_${row.catalogId}"
}

private fun catalogRowTitle(row: CatalogRow): String {
    return "${row.catalogName.replaceFirstChar { it.uppercase() }} - ${row.apiType.replaceFirstChar { it.uppercase() }}"
}

private fun CatalogRow.key(): String {
    return "${addonId}_${apiType}_${catalogId}"
}

private fun isSeriesType(type: String?): Boolean {
    return type.equals("series", ignoreCase = true) || type.equals("tv", ignoreCase = true)
}

private fun extractYear(releaseInfo: String?): String? {
    if (releaseInfo.isNullOrBlank()) return null
    return YEAR_REGEX.find(releaseInfo)?.value
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ModernCarouselCard(
    item: ModernCarouselItem,
    useLandscapePosters: Boolean,
    cardWidth: Dp,
    cardHeight: Dp,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onMoveUp: () -> Boolean,
    onMoveDown: () -> Boolean,
    rowTransitionInProgress: Boolean
) {
    val cardShape = RoundedCornerShape(if (useLandscapePosters) 12.dp else 14.dp)
    val titleStyle = if (useLandscapePosters) {
        MaterialTheme.typography.titleSmall
    } else {
        MaterialTheme.typography.bodyLarge
    }
    val landscapeLogoGradient = remember {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to Color.Transparent,
                0.58f to Color.Transparent,
                1.0f to Color.Black.copy(alpha = 0.75f)
            )
        )
    }

    Column(
        modifier = Modifier.width(cardWidth),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Card(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(cardHeight)
                .focusRequester(focusRequester)
                .onFocusChanged {
                    if (it.isFocused) {
                        onFocused()
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (rowTransitionInProgress) return@onPreviewKeyEvent true
                    when (event.key) {
                        Key.DirectionUp -> {
                            onMoveUp()
                            true
                        }
                        Key.DirectionDown -> {
                            onMoveDown()
                            true
                        }
                        else -> false
                    }
                },
            shape = CardDefaults.shape(shape = cardShape),
            colors = CardDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                focusedContainerColor = NuvioColors.BackgroundCard
            ),
            border = CardDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(2.dp, NuvioColors.FocusRing),
                    shape = cardShape
                )
            ),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(item.imageUrl)
                        .crossfade(false)
                        .build(),
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )

                if (useLandscapePosters && !item.heroPreview.logo.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(landscapeLogoGradient)
                    )
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(item.heroPreview.logo)
                            .crossfade(false)
                            .build(),
                        contentDescription = item.title,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(0.62f)
                            .height(cardHeight * 0.34f)
                            .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart
                    )
                }
            }
        }

        Text(
            text = item.title,
            style = titleStyle.copy(fontWeight = FontWeight.Medium),
            color = NuvioColors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun PreviewCarouselCard(
    imageUrl: String?,
    cardWidth: Dp,
    cardHeight: Dp
) {
    Box(
        modifier = Modifier
            .width(cardWidth)
            .height(cardHeight)
            .clip(RoundedCornerShape(10.dp))
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(imageUrl)
                .crossfade(false)
                .build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.36f))
        )
    }
}

@Composable
private fun HeroTitleBlock(
    preview: HeroPreview?,
    portraitMode: Boolean,
    shouldRenderPreviewRow: Boolean,
    modifier: Modifier = Modifier
) {
    if (preview == null) return

    val descriptionMaxLines = if (portraitMode) 2 else 3
    val descriptionScale = if (portraitMode) 0.90f else 1f
    val titleScale = if (portraitMode) 0.92f else 1f
    val metaScale = if (portraitMode && shouldRenderPreviewRow) 0.90f else 1f
    val imdbBadgeHorizontalPadding = if (portraitMode) 7.dp else 9.dp
    val imdbBadgeVerticalPadding = if (portraitMode) 2.dp else 3.dp

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy((8.dp * titleScale))
    ) {
        if (!preview.logo.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(preview.logo)
                    .crossfade(false)
                    .build(),
                contentDescription = preview.title,
                modifier = Modifier
                    .height(if (portraitMode) 62.dp else 82.dp)
                    .fillMaxWidth(),
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart
            )
        } else {
            Text(
                text = preview.title,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = MaterialTheme.typography.headlineLarge.fontSize * titleScale,
                    lineHeight = MaterialTheme.typography.headlineLarge.lineHeight * titleScale
                ),
                color = NuvioColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp * metaScale)
        ) {
            var hasLeadingMeta = false

            preview.contentTypeText?.takeIf { it.isNotBlank() }?.let { contentType ->
                Text(
                    text = contentType,
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                hasLeadingMeta = true
            }

            preview.genres.firstOrNull()?.takeIf { it.isNotBlank() }?.let { genre ->
                if (hasLeadingMeta) {
                    HeroMetaDivider(metaScale)
                }
                Text(
                    text = genre,
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                hasLeadingMeta = true
            }

            val yearText = preview.yearText
            val imdbText = preview.imdbText?.let { "IMDb $it" }
            val hasYearOrImdb = !yearText.isNullOrBlank() || !imdbText.isNullOrBlank()
            if (hasYearOrImdb) {
                if (hasLeadingMeta) {
                    HeroMetaDivider(metaScale)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp * metaScale)
                ) {
                    if (!yearText.isNullOrBlank()) {
                        Text(
                            text = yearText,
                            style = MaterialTheme.typography.labelMedium,
                            color = NuvioColors.TextSecondary,
                            maxLines = 1
                        )
                    }
                    if (!imdbText.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(Color(0xFFDBA506))
                                .padding(
                                    horizontal = imdbBadgeHorizontalPadding,
                                    vertical = imdbBadgeVerticalPadding
                                )
                        ) {
                            Text(
                                text = imdbText,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Black,
                                maxLines = 1
                            )
                        }
                    }
                }
                hasLeadingMeta = true
            }
        }

        preview.description?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize * descriptionScale,
                    lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * descriptionScale
                ),
                color = NuvioColors.TextSecondary,
                maxLines = descriptionMaxLines,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HeroMetaDivider(scale: Float) {
    Box(
        modifier = Modifier
            .size((4.dp * scale).coerceAtLeast(2.dp))
            .clip(RoundedCornerShape(percent = 50))
            .background(NuvioColors.TextTertiary.copy(alpha = 0.78f))
    )
}
