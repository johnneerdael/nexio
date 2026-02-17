package com.nuvio.tv.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.NuvioTheme
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import kotlinx.coroutines.delay

private const val BACKDROP_ASPECT_RATIO = 16f / 9f
private val YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ContentCard(
    item: MetaPreview,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    posterCardStyle: PosterCardStyle = PosterCardDefaults.Style,
    showLabels: Boolean = true,
    focusedPosterBackdropExpandEnabled: Boolean = false,
    focusedPosterBackdropExpandDelaySeconds: Int = 3,
    focusedPosterBackdropTrailerEnabled: Boolean = false,
    focusedPosterBackdropTrailerMuted: Boolean = true,
    trailerPreviewUrl: String? = null,
    onRequestTrailerPreview: (MetaPreview) -> Unit = {},
    onClick: () -> Unit = {}
) {
    val cardShape = RoundedCornerShape(posterCardStyle.cornerRadius)
    val baseCardWidth = when (item.posterShape) {
        PosterShape.POSTER -> posterCardStyle.width
        PosterShape.LANDSCAPE -> 260.dp
        PosterShape.SQUARE -> 170.dp
    }
    val baseCardHeight = when (item.posterShape) {
        PosterShape.POSTER -> posterCardStyle.height
        PosterShape.LANDSCAPE -> 148.dp
        PosterShape.SQUARE -> 170.dp
    }
    val expandedCardWidth = baseCardHeight * BACKDROP_ASPECT_RATIO

    var isFocused by remember(item.id) { mutableStateOf(false) }
    var interactionNonce by remember(item.id) { mutableIntStateOf(0) }
    var isBackdropExpanded by remember(item.id) { mutableStateOf(false) }
    var trailerFirstFrameRendered by remember(item.id, trailerPreviewUrl) { mutableStateOf(false) }

    LaunchedEffect(
        focusedPosterBackdropExpandEnabled,
        focusedPosterBackdropExpandDelaySeconds,
        isFocused,
        interactionNonce,
        item.id
    ) {
        if (!focusedPosterBackdropExpandEnabled || !isFocused) {
            isBackdropExpanded = false
            return@LaunchedEffect
        }

        val delaySeconds = focusedPosterBackdropExpandDelaySeconds.coerceAtLeast(0)
        if (delaySeconds == 0) {
            isBackdropExpanded = true
            return@LaunchedEffect
        }

        isBackdropExpanded = false
        val backdropDelayMs = delaySeconds * 1000L
        delay(backdropDelayMs)
        if (isFocused && focusedPosterBackdropExpandEnabled) {
            isBackdropExpanded = true
        }
    }

    LaunchedEffect(
        item.id,
        isFocused,
        focusedPosterBackdropTrailerEnabled,
        trailerPreviewUrl
    ) {
        if (!focusedPosterBackdropTrailerEnabled) return@LaunchedEffect
        if (!isFocused) return@LaunchedEffect
        if (trailerPreviewUrl != null) return@LaunchedEffect
        onRequestTrailerPreview(item)
    }

    val targetCardWidth = if (isBackdropExpanded) expandedCardWidth else baseCardWidth
    val animatedCardWidth by animateDpAsState(targetValue = targetCardWidth, label = "contentCardWidth")
    val metaTokens = remember(item.type, item.genres, item.releaseInfo, item.imdbRating) {
        buildList {
            add(
                item.apiType
                    .replaceFirstChar { ch -> ch.uppercase() }
            )
            item.genres.firstOrNull()?.let { add(it) }
            item.releaseInfo
                ?.let { YEAR_REGEX.find(it)?.value }
                ?.let { add(it) }
            item.imdbRating?.let { add(String.format("%.1f", it)) }
        }
    }

    Column(
        modifier = modifier.width(animatedCardWidth)
    ) {
        val context = LocalContext.current
        val density = LocalDensity.current
        val requestWidthPx = remember(targetCardWidth, density) {
            with(density) { targetCardWidth.roundToPx() }
        }
        val requestHeightPx = remember(baseCardHeight, density) {
            with(density) { baseCardHeight.roundToPx() }
        }
        val imageUrl = if (isBackdropExpanded) item.background ?: item.poster else item.poster
        val imageModel = remember(context, imageUrl, requestWidthPx, requestHeightPx) {
            ImageRequest.Builder(context)
                .data(imageUrl)
                .crossfade(false)
                .size(width = requestWidthPx, height = requestHeightPx)
                .build()
        }
        val imagePainter = rememberAsyncImagePainter(model = imageModel)

        Card(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { state ->
                    val focusedNow = state.isFocused
                    if (focusedNow != isFocused) {
                        isFocused = focusedNow
                        if (focusedNow) {
                            interactionNonce++
                        } else {
                            isBackdropExpanded = false
                        }
                    }
                }
                .then(
                    if (focusedPosterBackdropExpandEnabled) {
                        Modifier.onPreviewKeyEvent { keyEvent ->
                            if (isFocused && shouldResetBackdropTimer(keyEvent.nativeKeyEvent)) {
                                interactionNonce++
                            }
                            false
                        }
                    } else {
                        Modifier
                    }
                )
                .then(
                    if (focusRequester != null) Modifier.focusRequester(focusRequester)
                    else Modifier
                ),
            shape = CardDefaults.shape(shape = cardShape),
            colors = CardDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                focusedContainerColor = NuvioColors.BackgroundCard
            ),
            border = CardDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(posterCardStyle.focusedBorderWidth, NuvioColors.FocusRing),
                    shape = cardShape
                )
            ),
            scale = CardDefaults.scale(focusedScale = posterCardStyle.focusedScale)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(baseCardHeight)
                    .clip(cardShape)
            ) {
                Image(
                    painter = imagePainter,
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )

                val shouldPlayTrailerPreview = isBackdropExpanded &&
                    focusedPosterBackdropTrailerEnabled &&
                    isFocused &&
                    trailerPreviewUrl != null

                LaunchedEffect(shouldPlayTrailerPreview) {
                    if (!shouldPlayTrailerPreview) {
                        trailerFirstFrameRendered = false
                    }
                }

                val trailerCoverAlpha by animateFloatAsState(
                    targetValue = if (shouldPlayTrailerPreview && !trailerFirstFrameRendered) 1f else 0f,
                    animationSpec = tween(durationMillis = 250),
                    label = "trailerCoverAlpha"
                )

                if (shouldPlayTrailerPreview) {
                    TrailerPlayer(
                        trailerUrl = trailerPreviewUrl,
                        isPlaying = true,
                        onEnded = {
                            trailerFirstFrameRendered = false
                            isBackdropExpanded = false
                        },
                        onFirstFrameRendered = {
                            trailerFirstFrameRendered = true
                        },
                        modifier = Modifier.fillMaxSize(),
                        muted = focusedPosterBackdropTrailerMuted
                    )
                }

                if (shouldPlayTrailerPreview) {
                    Image(
                        painter = imagePainter,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = trailerCoverAlpha },
                        contentScale = ContentScale.Crop
                    )
                }

                if (isBackdropExpanded) {
                    val logoAreaGradient = remember {
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.76f)
                            )
                        )
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(96.dp)
                            .background(logoAreaGradient)
                    )

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                            .fillMaxWidth(0.75f)
                    ) {
                        if (item.logo != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(item.logo)
                                    .crossfade(false)
                                    .size(
                                        width = requestWidthPx,
                                        height = with(density) { 48.dp.roundToPx() }
                                    )
                                    .build(),
                                contentDescription = item.name,
                                modifier = Modifier
                                    .height(48.dp)
                                    .fillMaxWidth(),
                                contentScale = ContentScale.Fit,
                                alignment = Alignment.CenterStart
                            )
                        } else {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        if (isBackdropExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                if (metaTokens.isNotEmpty()) {
                    Text(
                        text = metaTokens.joinToString("  •  "),
                        style = MaterialTheme.typography.labelMedium,
                        color = NuvioTheme.extendedColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                item.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioColors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (showLabels && !isBackdropExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                item.releaseInfo?.let { release ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = release,
                        style = MaterialTheme.typography.labelMedium,
                        color = NuvioTheme.extendedColors.textSecondary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

private fun shouldResetBackdropTimer(nativeEvent: AndroidKeyEvent): Boolean {
    if (nativeEvent.action != AndroidKeyEvent.ACTION_DOWN) return false
    if (nativeEvent.repeatCount > 0 || nativeEvent.isLongPress) return false

    return when (nativeEvent.keyCode) {
        AndroidKeyEvent.KEYCODE_DPAD_UP,
        AndroidKeyEvent.KEYCODE_DPAD_DOWN,
        AndroidKeyEvent.KEYCODE_DPAD_LEFT,
        AndroidKeyEvent.KEYCODE_DPAD_RIGHT,
        AndroidKeyEvent.KEYCODE_DPAD_CENTER,
        AndroidKeyEvent.KEYCODE_ENTER,
        AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
        AndroidKeyEvent.KEYCODE_BACK -> true
        else -> false
    }
}
