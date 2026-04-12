@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package com.nexio.tv.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import com.nexio.tv.domain.model.Stream
import com.nexio.tv.core.stream.StreamCardModel
import com.nexio.tv.ui.components.LoadingIndicator
import com.nexio.tv.ui.theme.NexioColors
import com.nexio.tv.ui.theme.NexioTheme
import androidx.compose.ui.res.stringResource
import com.nexio.tv.R

@Composable
internal fun StreamSourcesSidePanel(
    uiState: PlayerUiState,
    streamsFocusRequester: FocusRequester,
    onClose: () -> Unit,
    onReload: () -> Unit,
    onAddonFilterSelected: (String?) -> Unit,
    onStreamSelected: (Stream) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentStreamIndex = findCurrentStreamIndex(
        streams = uiState.sourcePresentedStreams,
        currentStreamUrl = uiState.currentStreamUrl,
        currentStreamName = uiState.currentStreamName
    )
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentStreamIndex.coerceAtLeast(0))
    val reloadFocusRequester = remember { FocusRequester() }
    val closeFocusRequester = remember { FocusRequester() }
    var hasUserNavigated by remember { mutableStateOf(false) }
    var focusedStreamIndex by remember { mutableStateOf(-1) }

    LaunchedEffect(uiState.sourcePresentedStreams.size, currentStreamIndex) {
        if (uiState.sourcePresentedStreams.isEmpty()) return@LaunchedEffect
        if (hasUserNavigated) return@LaunchedEffect
        val targetIndex = if (currentStreamIndex >= 0) currentStreamIndex else 0
        if (targetIndex < uiState.sourcePresentedStreams.size) {
            listState.scrollToItem(targetIndex)
        }
        withFrameNanos { }
        withFrameNanos { }
        runCatching { streamsFocusRequester.requestFocus() }
    }

    val orderedAddonNames = remember(uiState.sourceAvailableAddons, uiState.sourceChips) {
        buildList {
            addAll(uiState.sourceAvailableAddons)
            uiState.sourceChips.forEach { if (it.name !in this) add(it.name) }
        }
    }
    val chipFocusRequesters = remember(orderedAddonNames.size) {
        List(orderedAddonNames.size + 1) { FocusRequester() }
    }
    val chipsVisible = uiState.showSourceAddonFilters && (
        uiState.sourceChips.isNotEmpty() ||
            (!uiState.isLoadingSourceStreams && uiState.sourceAvailableAddons.isNotEmpty())
        )
    val isAwaitingMoreSourceResults =
        uiState.isLoadingSourceStreams || uiState.sourceChips.any { it.status == com.nexio.tv.ui.components.SourceChipStatus.LOADING }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(520.dp)
            .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
            .background(NexioColors.BackgroundElevated)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.sources_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = NexioColors.TextPrimary
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val headerDownHandler: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = handler@{ event ->
                        if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@handler false
                        if (event.key != Key.DirectionDown) return@handler false
                        if (chipsVisible && chipFocusRequesters.isNotEmpty()) {
                            val selected = uiState.sourceSelectedAddonFilter
                            val idx = if (selected == null) 0 else orderedAddonNames.indexOf(selected) + 1
                            if (idx in chipFocusRequesters.indices) {
                                runCatching { chipFocusRequesters[idx].requestFocus() }
                                return@handler true
                            }
                        }
                        runCatching { streamsFocusRequester.requestFocus() }
                        true
                    }
                    DialogButton(
                        text = stringResource(R.string.sources_reload),
                        onClick = onReload,
                        isPrimary = false,
                        prominentFocus = true,
                        modifier = Modifier
                            .focusRequester(reloadFocusRequester)
                            .onKeyEvent(headerDownHandler)
                    )
                    DialogButton(
                        text = stringResource(R.string.sources_close),
                        onClick = onClose,
                        isPrimary = false,
                        prominentFocus = true,
                        modifier = Modifier
                            .focusRequester(closeFocusRequester)
                            .onKeyEvent(headerDownHandler)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Current content info
            Text(
                text = buildString {
                    if (uiState.currentSeason != null && uiState.currentEpisode != null) {
                        append("S${uiState.currentSeason} E${uiState.currentEpisode}")
                        if (!uiState.currentEpisodeTitle.isNullOrBlank()) {
                            append(" • ${uiState.currentEpisodeTitle}")
                        }
                    } else {
                        append(uiState.title)
                    }
                },
                style = MaterialTheme.typography.bodyLarge,
                color = NexioTheme.extendedColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(16.dp))

            AnimatedVisibility(
                visible = chipsVisible,
                enter = fadeIn(animationSpec = tween(200)),
                exit = fadeOut(animationSpec = tween(120))
            ) {
                AddonFilterChips(
                    addons = uiState.sourceAvailableAddons,
                    sourceChips = uiState.sourceChips,
                    selectedAddon = uiState.sourceSelectedAddonFilter,
                    onAddonSelected = onAddonFilterSelected,
                    externalFocusRequesters = chipFocusRequesters,
                    externalOrderedNames = orderedAddonNames,
                    onUpKey = { runCatching { reloadFocusRequester.requestFocus() } }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            when {
                uiState.sourcePresentedStreams.isEmpty() && uiState.isLoadingSourceStreams -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator()
                    }
                }

                uiState.sourceStreamsError != null && uiState.sourcePresentedStreams.isEmpty() -> {
                    Text(
                        text = uiState.sourceStreamsError,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }

                uiState.sourcePresentedStreams.isEmpty() && isAwaitingMoreSourceResults -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator()
                    }
                }

                uiState.sourcePresentedStreams.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.sources_no_streams),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(
                            start = 8.dp,
                            top = 14.dp,
                            end = 8.dp,
                            bottom = 8.dp
                        ),
                        modifier = Modifier
                            .fillMaxHeight()
                            .onKeyEvent { event ->
                                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                                val addons = uiState.sourceAvailableAddons
                                if (addons.isEmpty()) return@onKeyEvent false
                                val allOptions = listOf<String?>(null) + addons
                                val currentIdx = allOptions.indexOf(uiState.sourceSelectedAddonFilter)
                                when (event.key) {
                                    Key.DirectionLeft -> {
                                        if (currentIdx > 0) { onAddonFilterSelected(allOptions[currentIdx - 1]); true } else false
                                    }
                                    Key.DirectionRight -> {
                                        if (currentIdx < allOptions.lastIndex) { onAddonFilterSelected(allOptions[currentIdx + 1]); true } else false
                                    }
                                    else -> false
                                }
                            }
                    ) {
                        itemsIndexed(uiState.sourcePresentedStreams) { index, item ->
                            StreamItem(
                                item = item,
                                focusRequester = streamsFocusRequester,
                                requestInitialFocus = index == currentStreamIndex ||
                                    (currentStreamIndex < 0 && index == 0),
                                isCurrentStream = index == currentStreamIndex,
                                onClick = { onStreamSelected(item.stream) },
                                onUpKey = if (index == 0) {{
                                    if (chipsVisible && chipFocusRequesters.isNotEmpty()) {
                                        val selected = uiState.sourceSelectedAddonFilter
                                        val idx = if (selected == null) 0 else orderedAddonNames.indexOf(selected) + 1
                                        if (idx >= 0 && idx < chipFocusRequesters.size) {
                                            try { chipFocusRequesters[idx].requestFocus() } catch (_: Exception) {}
                                        }
                                    } else {
                                        runCatching { reloadFocusRequester.requestFocus() }
                                    }
                                }} else null,
                                onFocused = {
                                    if (focusedStreamIndex >= 0 && focusedStreamIndex != index) {
                                        hasUserNavigated = true
                                    }
                                    focusedStreamIndex = index
                                }
                            )
                        }

                        if (isAwaitingMoreSourceResults) {
                            item(key = "loading_footer") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    LoadingIndicator()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun findCurrentStreamIndex(
    streams: List<StreamCardModel>,
    currentStreamUrl: String?,
    currentStreamName: String?
): Int {
    if (streams.isEmpty()) return -1

    val hasUrl = !currentStreamUrl.isNullOrBlank()
    val hasName = !currentStreamName.isNullOrBlank()

    if (hasUrl && hasName) {
        val bothMatch = streams.indexOfFirst { stream ->
            stream.stream.getStreamUrl() == currentStreamUrl &&
                stream.title.equals(currentStreamName, ignoreCase = true)
        }
        if (bothMatch >= 0) return bothMatch
    }

    if (hasUrl) {
        val urlMatch = streams.indexOfFirst { stream ->
            stream.stream.getStreamUrl() == currentStreamUrl
        }
        if (urlMatch >= 0) return urlMatch
    }

    if (hasName) {
        val nameMatch = streams.indexOfFirst { stream ->
            stream.title.equals(currentStreamName, ignoreCase = true)
        }
        if (nameMatch >= 0) return nameMatch
    }

    return -1
}
