package com.nuvio.tv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.decode.DataSource
import coil.request.ImageRequest
import coil.size.Precision

/**
 * AsyncImage wrapper with optional fade-in.
 *
 * By default, fades in only when the image is not already in Coil's memory cache.
 */
@Composable
fun FadeInAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    alignment: Alignment = Alignment.Center,
    fadeDurationMs: Int = 200,
    enableFadeIn: Boolean = false,
    requestedWidthDp: Dp? = null,
    requestedHeightDp: Dp? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val request = remember(model, requestedWidthDp, requestedHeightDp) {
        val builder = ImageRequest.Builder(context)
            .data(model)
            .crossfade(false)
            .allowHardware(true)
            .precision(Precision.INEXACT)

        if (requestedWidthDp != null && requestedHeightDp != null) {
            val widthPx = with(density) { requestedWidthDp.roundToPx() }
            val heightPx = with(density) { requestedHeightDp.roundToPx() }
            builder.size(widthPx, heightPx)
        }

        builder.build()
    }

    // Fast path: no animation state allocated when fade-in is disabled
    if (!enableFadeIn) {
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
            alignment = alignment
        )
        return
    }

    // Animation state only created for the few images that need fade-in (e.g. HeroCarousel)
    var shouldAnimate by remember(model) { mutableStateOf(true) }
    var loaded by remember(model) { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (loaded) 1f else 0f,
        animationSpec = tween(durationMillis = if (shouldAnimate) fadeDurationMs else 0),
        label = "imageFadeIn"
    )

    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier.graphicsLayer { this.alpha = alpha },
        contentScale = contentScale,
        alignment = alignment,
        onState = { state ->
            if (state is AsyncImagePainter.State.Success) {
                shouldAnimate = state.result.dataSource != DataSource.MEMORY_CACHE
                loaded = true
            }
        }
    )
}
