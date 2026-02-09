package com.nuvio.tv.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.itemsIndexed
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import androidx.compose.ui.window.Dialog
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.concurrent.TimeUnit

private val CwCardShape = RoundedCornerShape(12.dp)
private val CwClipShape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
private val BadgeShape = RoundedCornerShape(4.dp)

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ContinueWatchingSection(
    items: List<ContinueWatchingItem>,
    onItemClick: (ContinueWatchingItem) -> Unit,
    onRemoveItem: (ContinueWatchingItem) -> Unit,
    modifier: Modifier = Modifier,
    focusedItemIndex: Int = -1,
    onItemFocused: (itemIndex: Int) -> Unit = {}
) {
    if (items.isEmpty()) return

    val itemFocusRequester = remember { FocusRequester() }
    val focusRequesters = remember(items.size) { List(items.size) { FocusRequester() } }
    var lastFocusedIndex by remember { mutableStateOf(-1) }
    var pendingFocusIndex by remember { mutableStateOf<Int?>(null) }
    var optionsItem by remember { mutableStateOf<ContinueWatchingItem?>(null) }

    // Restore focus to specific item if requested
    LaunchedEffect(focusedItemIndex) {
        if (focusedItemIndex >= 0 && focusedItemIndex < items.size) {
            kotlinx.coroutines.delay(100)
            try {
                itemFocusRequester.requestFocus()
            } catch (e: IllegalStateException) {
                // Item not yet composed, ignore
            }
        }
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 48.dp, end = 48.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Continue Watching",
                style = MaterialTheme.typography.titleLarge,
                color = NuvioColors.TextPrimary
            )
        }

        TvLazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .focusRestorer(),
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            itemsIndexed(
                items = items,
                key = { _, progress ->
                    when (progress) {
                        is ContinueWatchingItem.InProgress -> progress.progress.videoId
                        is ContinueWatchingItem.NextUp -> "nextup_${progress.info.videoId}"
                    }
                }
            ) { index, progress ->
                val focusModifier = when {
                    pendingFocusIndex == index && index < focusRequesters.size -> Modifier.focusRequester(focusRequesters[index])
                    index == focusedItemIndex -> Modifier.focusRequester(itemFocusRequester)
                    else -> Modifier
                }

                ContinueWatchingCard(
                    item = progress,
                    onClick = { onItemClick(progress) },
                    onLongPress = { optionsItem = progress },
                    modifier = Modifier
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                lastFocusedIndex = index
                                onItemFocused(index)
                            }
                        }
                        .then(focusModifier)
                )
            }
        }
    }

    val menuItem = optionsItem
    if (menuItem != null) {
        ContinueWatchingOptionsDialog(
            item = menuItem,
            onDismiss = { optionsItem = null },
            onRemove = {
                val targetIndex = if (items.size <= 1) null else minOf(lastFocusedIndex, items.size - 2)
                pendingFocusIndex = targetIndex
                onRemoveItem(menuItem)
                optionsItem = null
            },
            onDetails = {
                onItemClick(menuItem)
                optionsItem = null
            }
        )
    }

    LaunchedEffect(items.size, pendingFocusIndex) {
        val target = pendingFocusIndex
        if (target != null && target >= 0 && target < focusRequesters.size) {
            kotlinx.coroutines.delay(100)
            try {
                focusRequesters[target].requestFocus()
            } catch (_: IllegalStateException) {
            }
            pendingFocusIndex = null
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ContinueWatchingCard(
    item: ContinueWatchingItem,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 320.dp,
    imageHeight: Dp = 180.dp
) {
    var isFocused by remember { mutableStateOf(false) }
    var longPressTriggered by remember { mutableStateOf(false) }

    val progress = (item as? ContinueWatchingItem.InProgress)?.progress
    val nextUp = (item as? ContinueWatchingItem.NextUp)?.info
    val episodeStr = progress?.episodeDisplayString ?: nextUp?.let { "S${it.season}E${it.episode}" }
    val remainingText = progress?.let {
        remember(it.position, it.duration) { formatRemainingTime(it.remainingTime) }
    }
    val progressFraction = progress?.progressPercentage ?: 0f
    val imageModel = nextUp?.thumbnail ?: progress?.backdrop ?: progress?.poster ?: nextUp?.backdrop ?: nextUp?.poster
    val titleText = progress?.name ?: nextUp?.name.orEmpty()
    val episodeTitle = progress?.episodeTitle ?: nextUp?.episodeTitle

    val bgColor = NuvioColors.Background
    val overlayBrush = remember(bgColor) {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to Color.Transparent,
                0.5f to Color.Transparent,
                0.8f to bgColor.copy(alpha = 0.7f),
                1.0f to bgColor.copy(alpha = 0.95f)
            )
        )
    }
    val badgeBackground = remember(bgColor) { bgColor.copy(alpha = 0.8f) }

    Card(
        onClick = {
            if (longPressTriggered) {
                longPressTriggered = false
            } else {
                onClick()
            }
        },
        modifier = modifier
            .width(cardWidth)
            .onFocusChanged { isFocused = it.isFocused }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action == AndroidKeyEvent.ACTION_DOWN) {
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
                if (native.action == AndroidKeyEvent.ACTION_UP && longPressTriggered && isSelectKey(native.keyCode)) {
                    return@onPreviewKeyEvent true
                }
                false
            },
        shape = CardDefaults.shape(shape = CwCardShape),
        colors = CardDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.FocusBackground
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = CwCardShape
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1.02f)
    ) {
        Column {
            // Thumbnail with progress overlay
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(imageHeight)
                    .clip(CwClipShape)
            ) {
                // Background image with size hints for efficient decoding
                FadeInAsyncImage(
                    model = imageModel,
                    contentDescription = titleText,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    requestedWidthDp = cardWidth,
                    requestedHeightDp = imageHeight
                )

                // Gradient overlay for text readability
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(overlayBrush)
                )

                // Content info at bottom
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp)
                ) {
                    // Episode info (for series)
                    if (episodeStr != null) {
                        Text(
                            text = episodeStr,
                            style = MaterialTheme.typography.labelMedium,
                            color = NuvioColors.Primary
                        )
                    }

                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.titleSmall,
                        color = NuvioColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Episode title if available
                    episodeTitle?.let { title ->
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.extendedColors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Remaining time badge
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(BadgeShape)
                        .background(badgeBackground)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = remainingText ?: "Next Up",
                        style = MaterialTheme.typography.labelSmall,
                        color = NuvioColors.TextPrimary
                    )
                }
            }

            if (progress != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(NuvioColors.SurfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progressFraction)
                            .height(4.dp)
                            .background(NuvioColors.Primary)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ContinueWatchingOptionsDialog(
    item: ContinueWatchingItem,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
    onDetails: () -> Unit
) {
    val title = when (item) {
        is ContinueWatchingItem.InProgress -> item.progress.name
        is ContinueWatchingItem.NextUp -> item.info.name
    }

    val detailsFocusRequester = remember { FocusRequester() }
    var suppressNextKeyUp by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        detailsFocusRequester.requestFocus()
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(360.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(NuvioColors.BackgroundElevated)
                .padding(24.dp)
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (suppressNextKeyUp && native.action == AndroidKeyEvent.ACTION_UP) {
                        if (isSelectKey(native.keyCode) || native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                            suppressNextKeyUp = false
                            return@onPreviewKeyEvent true
                        }
                    }
                    false
                }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Button(
                    onClick = onDetails,
                    modifier = Modifier.focusRequester(detailsFocusRequester),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioColors.FocusBackground,
                        contentColor = NuvioColors.TextPrimary
                    )
                ) {
                    Text("Go to details")
                }

                Button(
                    onClick = onRemove,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioColors.BackgroundCard,
                        contentColor = NuvioColors.TextPrimary
                    )
                ) {
                    Text("Remove from Continue Watching")
                }
            }
        }
    }
}

private fun isSelectKey(keyCode: Int): Boolean {
    return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
}

internal fun formatRemainingTime(remainingMs: Long): String {
    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(remainingMs)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60

    return when {
        hours > 0 -> "${hours}h ${minutes}m left"
        minutes > 0 -> "${minutes}m left"
        else -> "Almost done"
    }
}
