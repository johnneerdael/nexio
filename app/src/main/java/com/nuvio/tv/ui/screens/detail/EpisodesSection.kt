package com.nuvio.tv.ui.screens.detail

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.compose.ui.window.Dialog
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.NuvioTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SeasonTabs(
    seasons: List<Int>,
    selectedSeason: Int,
    onSeasonSelected: (Int) -> Unit,
    selectedTabFocusRequester: FocusRequester
) {
    // Move season 0 (specials) to the end
    val sortedSeasons = remember(seasons) {
        val regularSeasons = seasons.filter { it > 0 }.sorted()
        val specials = seasons.filter { it == 0 }
        regularSeasons + specials
    }

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(sortedSeasons, key = { it }) { season ->
            val isSelected = season == selectedSeason
            var isFocused by remember { mutableStateOf(false) }

            Card(
                onClick = { onSeasonSelected(season) },
                modifier = Modifier
                    .then(if (isSelected) Modifier.focusRequester(selectedTabFocusRequester) else Modifier)
                    .onFocusChanged {
                    val nowFocused = it.isFocused
                    isFocused = nowFocused
                    if (nowFocused && !isSelected) {
                        onSeasonSelected(season)
                    }
                },
                shape = CardDefaults.shape(
                    shape = RoundedCornerShape(20.dp)
                ),
                colors = CardDefaults.colors(
                    containerColor = if (isSelected) NuvioColors.SurfaceVariant else NuvioColors.BackgroundCard,
                    focusedContainerColor = NuvioColors.Secondary
                ),
                border = CardDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, NuvioColors.FocusRing),
                        shape = RoundedCornerShape(20.dp)
                    )
                ),
                scale = CardDefaults.scale(focusedScale = 1.0f)
            ) {
                Text(
                    text = if (season == 0) "Specials" else "Season $season",
                    style = MaterialTheme.typography.titleMedium,
                    color = when {
                        isFocused -> NuvioColors.OnPrimary
                        isSelected -> NuvioColors.TextPrimary
                        else -> NuvioTheme.extendedColors.textSecondary
                    },
                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 20.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EpisodesRow(
    episodes: List<Video>,
    episodeProgressMap: Map<Pair<Int, Int>, com.nuvio.tv.domain.model.WatchProgress> = emptyMap(),
    watchedEpisodes: Set<Pair<Int, Int>> = emptySet(),
    episodeWatchedPendingKeys: Set<String> = emptySet(),
    blurUnwatchedEpisodes: Boolean = false,
    onEpisodeClick: (Video) -> Unit,
    onToggleEpisodeWatched: (Video) -> Unit,
    upFocusRequester: FocusRequester,
    restoreEpisodeId: String? = null,
    restoreFocusToken: Int = 0,
    onRestoreFocusHandled: () -> Unit = {}
) {
    val restoreFocusRequester = remember { FocusRequester() }
    var focusedEpisodeId by remember { mutableStateOf<String?>(null) }
    var optionsEpisode by remember { mutableStateOf<Video?>(null) }

    LaunchedEffect(restoreFocusToken, restoreEpisodeId, episodes) {
        if (restoreFocusToken <= 0 || restoreEpisodeId.isNullOrBlank()) return@LaunchedEffect
        if (episodes.none { it.id == restoreEpisodeId }) return@LaunchedEffect
        restoreFocusRequester.requestFocusAfterFrames()
    }

    LazyRow(
        modifier = Modifier
            .fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(episodes, key = { it.id }) { episode ->
            val progress = episode.season?.let { s ->
                episode.episode?.let { e ->
                    episodeProgressMap[s to e]
                }
            }
            val isMarkedWatched = episode.season?.let { s ->
                episode.episode?.let { e ->
                    watchedEpisodes.contains(s to e)
                }
            } ?: false
            EpisodeCard(
                episode = episode,
                watchProgress = progress,
                isMarkedWatched = isMarkedWatched,
                blurUnwatched = blurUnwatchedEpisodes,
                onClick = { onEpisodeClick(episode) },
                onLongPress = { optionsEpisode = episode },
                upFocusRequester = upFocusRequester,
                dimmed = focusedEpisodeId != null && focusedEpisodeId != episode.id,
                onFocused = { focusedEpisodeId = episode.id },
                onFocusCleared = {
                    if (focusedEpisodeId == episode.id) focusedEpisodeId = null
                },
                focusRequester = if (episode.id == restoreEpisodeId) restoreFocusRequester else null,
                onFocusRestored = if (episode.id == restoreEpisodeId) onRestoreFocusHandled else null
            )
        }
    }

    optionsEpisode?.let { selectedEpisode ->
        val selectedWatched = selectedEpisode.season?.let { season ->
            selectedEpisode.episode?.let { episode ->
                episodeProgressMap[season to episode]?.isCompleted() == true
                    || watchedEpisodes.contains(season to episode)
            }
        } ?: false
        val isPending = episodeWatchedPendingKeys.contains(episodePendingKey(selectedEpisode))

        EpisodeOptionsDialog(
            episode = selectedEpisode,
            isWatched = selectedWatched,
            isPending = isPending,
            onDismiss = { optionsEpisode = null },
            onPlay = {
                onEpisodeClick(selectedEpisode)
                optionsEpisode = null
            },
            onToggleWatched = {
                onToggleEpisodeWatched(selectedEpisode)
                optionsEpisode = null
            }
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodeCard(
    episode: Video,
    watchProgress: com.nuvio.tv.domain.model.WatchProgress? = null,
    isMarkedWatched: Boolean = false,
    blurUnwatched: Boolean = false,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    upFocusRequester: FocusRequester,
    dimmed: Boolean = false,
    onFocused: () -> Unit = {},
    onFocusCleared: () -> Unit = {},
    focusRequester: FocusRequester? = null,
    onFocusRestored: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val formattedDate = remember(episode.released) {
        episode.released?.let { formatReleaseDate(it) } ?: ""
    }
    val isWatched = watchProgress?.isCompleted() == true || isMarkedWatched
    val shouldBlur = blurUnwatched && !isWatched
    var isFocused by remember { mutableStateOf(false) }
    var longPressTriggered by remember { mutableStateOf(false) }
    val thumbnailWidth by animateDpAsState(
        targetValue = if (isFocused) 268.dp else 280.dp,
        animationSpec = tween(durationMillis = 180),
        label = "episodeThumbnailWidth"
    )
    val cardWidth by animateDpAsState(
        targetValue = if (isFocused) 456.dp else 280.dp,
        animationSpec = tween(durationMillis = 180),
        label = "episodeCardWidth"
    )
    val cardAlpha by animateFloatAsState(
        targetValue = if (dimmed) 0.68f else 1f,
        animationSpec = tween(durationMillis = 160),
        label = "episodeCardAlpha"
    )
    val watchedIconEndPadding by animateDpAsState(
        targetValue = if (isFocused) 24.dp else 10.dp,
        animationSpec = tween(durationMillis = 180),
        label = "watchedIconEndPadding"
    )
    val episodeCode = remember(episode.season, episode.episode) {
        if (episode.season != null && episode.episode != null) {
            "S${episode.season.toString().padStart(2, '0')}E${episode.episode.toString().padStart(2, '0')}"
        } else {
            "Episode"
        }
    }
    val titleMedium = MaterialTheme.typography.titleMedium
    val backgroundCard = NuvioColors.BackgroundCard
    val episodeCodeTextStyle = remember(titleMedium) {
        titleMedium.copy(
            shadow = Shadow(
                color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.95f),
                offset = androidx.compose.ui.geometry.Offset(0f, 0f),
                blurRadius = 5f
            )
        )
    }
    val edgeFadeBrush = remember(backgroundCard) {
        Brush.horizontalGradient(
            0.0f to androidx.compose.ui.graphics.Color.Transparent,
            0.15f to backgroundCard.copy(alpha = 0.08f),
            0.35f to backgroundCard.copy(alpha = 0.25f),
            0.55f to backgroundCard.copy(alpha = 0.50f),
            0.72f to backgroundCard.copy(alpha = 0.72f),
            0.85f to backgroundCard.copy(alpha = 0.88f),
            1.0f to backgroundCard
        )
    }
    val detailsGradientBrush = remember(backgroundCard) {
        Brush.horizontalGradient(
            0.0f to backgroundCard.copy(alpha = 0f),
            0.12f to backgroundCard.copy(alpha = 0.15f),
            0.3f to backgroundCard.copy(alpha = 0.40f),
            0.5f to backgroundCard.copy(alpha = 0.65f),
            0.7f to backgroundCard.copy(alpha = 0.85f),
            0.85f to backgroundCard.copy(alpha = 0.95f),
            1.0f to backgroundCard
        )
    }
    val thumbnailWidthPx = remember(thumbnailWidth, density) {
        with(density) { thumbnailWidth.roundToPx() }
    }
    val thumbnailHeightPx = remember(density) {
        with(density) { 158.dp.roundToPx() }
    }
    val thumbnailRequest = remember(context, episode.thumbnail, thumbnailWidthPx, thumbnailHeightPx, shouldBlur) {
        ImageRequest.Builder(context)
            .data(episode.thumbnail)
            .crossfade(true)
            .size(width = thumbnailWidthPx, height = thumbnailHeightPx)
            .apply {
                if (shouldBlur) {
                    transformations(com.nuvio.tv.ui.util.BlurTransformation())
                }
            }
            .build()
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
            .width(cardWidth)
            .alpha(cardAlpha)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) {
                    onFocused()
                    onFocusRestored?.invoke()
                } else {
                    onFocusCleared()
                }
            }
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
                if (native.action == AndroidKeyEvent.ACTION_UP &&
                    longPressTriggered &&
                    isSelectKey(native.keyCode)
                ) {
                    return@onPreviewKeyEvent true
                }
                false
            }
            .focusProperties { up = upFocusRequester },
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
        scale = CardDefaults.scale(focusedScale = 1.0f)
    ) {
        Row {
            Box(
                modifier = Modifier
                    .width(thumbnailWidth)
                    .height(158.dp)
                    .clip(RoundedCornerShape(8.dp))
            ) {
                AsyncImage(
                    model = thumbnailRequest,
                    contentDescription = episode.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.62f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = episodeCode,
                        style = episodeCodeTextStyle,
                        color = NuvioColors.TextPrimary,
                        maxLines = 1
                    )
                }

                // Watched indicator
                if (watchProgress?.isCompleted() == true || isMarkedWatched) {
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
                            tint = androidx.compose.ui.graphics.Color.Black,
                            modifier = Modifier.size(24.dp)
                        )
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Watched",
                            tint = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }

                // Progress bar overlay at bottom of thumbnail
                watchProgress?.let { progress ->
                    if (progress.isInProgress()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .height(4.dp)
                                .background(NuvioColors.Background.copy(alpha = 0.5f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progress.progressPercentage)
                                    .height(4.dp)
                                    .background(NuvioColors.Primary)
                            )
                        }
                    }
                }

                val edgeFadeAlpha by animateFloatAsState(
                    targetValue = if (isFocused) 1f else 0f,
                    animationSpec = tween(durationMillis = 180),
                    label = "episodeEdgeFadeAlpha"
                )
                // Always present and alpha-animated, so blend starts immediately with expansion.
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(64.dp)
                        .fillMaxSize()
                        .alpha(edgeFadeAlpha)
                        .background(edgeFadeBrush)
                )
            }

            val detailsAlpha by animateFloatAsState(
                targetValue = if (isFocused) 1f else 0f,
                animationSpec = tween(durationMillis = 170),
                label = "episodeDetailsAlpha"
            )
            val detailsWidth by animateDpAsState(
                targetValue = if (isFocused) 200.dp else 0.dp,
                animationSpec = tween(durationMillis = 180),
                label = "episodeDetailsWidth"
            )
            Column(
                modifier = Modifier
                    .width(detailsWidth)
                    .height(158.dp)
                    .offset(x = (-12).dp)
                    .alpha(detailsAlpha)
                    .background(detailsGradientBrush)
                    .padding(start = 0.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                horizontalAlignment = Alignment.Start
            ) {
                if (detailsAlpha > 0.01f) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        if (formattedDate.isNotBlank()) {
                            Text(
                                text = formattedDate,
                                style = MaterialTheme.typography.labelMedium,
                                color = NuvioTheme.extendedColors.textTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        episode.runtime?.let { runtime ->
                            Text(
                                text = "${runtime}m",
                                style = MaterialTheme.typography.labelMedium,
                                color = NuvioTheme.extendedColors.textTertiary,
                                maxLines = 1
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = episode.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )

                    episode.overview?.let { overview ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = overview,
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.extendedColors.textSecondary,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodeOptionsDialog(
    episode: Video,
    isWatched: Boolean,
    isPending: Boolean,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onToggleWatched: () -> Unit
) {
    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        primaryFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = episode.title,
        subtitle = "Episode actions"
    ) {
        Button(
            onClick = onToggleWatched,
            enabled = !isPending,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(primaryFocusRequester),
            colors = ButtonDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                contentColor = NuvioColors.TextPrimary
            )
        ) {
            Text(if (isWatched) "Mark as unwatched" else "Mark as watched")
        }

        Button(
            onClick = onPlay,
            colors = ButtonDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                contentColor = NuvioColors.TextPrimary
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Play")
        }
    }
}

private fun isSelectKey(keyCode: Int): Boolean {
    return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
}

private fun episodePendingKey(video: Video): String {
    return "${video.id}:${video.season ?: -1}:${video.episode ?: -1}"
}
