package com.nexio.tv.ui.screensaver

import android.app.ActivityManager
import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.imageLoader
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import coil.size.Dimension
import coil.size.Size
import com.nexio.tv.R
import com.nexio.tv.ui.theme.NexioColors
import com.nexio.tv.ui.theme.NexioTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SCREENSAVER_MOTION_DURATION_MS = 20_000
private const val SCREENSAVER_CROSSFADE_MS = 1_000
private const val SCREENSAVER_SLIDE_ADVANCE_MS = 15_000
private const val SCREENSAVER_PRELOAD_LEAD_MS = 5_000L
private const val SCREENSAVER_START_SCALE = 1.05f
private const val SCREENSAVER_END_SCALE = 1.145f
private const val SCREENSAVER_TRANSLATE_X_FRACTION = 0.045f
private const val SCREENSAVER_TRANSLATE_Y_FRACTION = 0.026f
private const val SCREENSAVER_LOW_POWER_MAX_WIDTH = 1280
private const val SCREENSAVER_LOW_POWER_MAX_HEIGHT = 720
private val ScreensaverMotionEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

@Composable
fun IdleScreensaverOverlay(
    slides: List<IdleScreensaverSlide>,
    sessionId: Long,
    onDismiss: () -> Unit,
    onOpenSlide: (IdleScreensaverSlide) -> Unit
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val sessionSlides = remember(sessionId, slides) {
        slides.shuffled()
    }
    if (sessionSlides.isEmpty()) return

    var currentIndex by remember(sessionId) { mutableIntStateOf(0) }
    var motionPresetIndex by remember(sessionId) { mutableIntStateOf(0) }
    var viewportSize by remember(sessionId) { mutableStateOf(IntSize.Zero) }
    val isLowPowerDevice = remember(context) { context.isLowPowerScreensaverDevice() }
    val decodeSize = remember(viewportSize, isLowPowerDevice) {
        screensaverDecodeSize(
            viewportSize = viewportSize,
            lowPower = isLowPowerDevice
        )
    }
    var activeLayer by remember(sessionId, sessionSlides) {
        mutableStateOf(
            ScreensaverBackgroundLayer(
                slide = sessionSlides.first(),
                instanceId = 0,
                motion = screensaverMotionPresetFor(0),
                initialAlpha = 1f
            )
        )
    }
    var preloadedLayer by remember(sessionId, sessionSlides) {
        mutableStateOf(
            sessionSlides.getOrNull(1 % sessionSlides.size)?.let { nextSlide ->
                ScreensaverBackgroundLayer(
                    slide = nextSlide,
                    instanceId = 1,
                    motion = screensaverMotionPresetFor(1),
                    initialAlpha = 0f
                )
            }
        )
    }
    var fadingLayer by remember(sessionId) { mutableStateOf<ScreensaverBackgroundLayer?>(null) }
    val currentSlide = activeLayer.slide
    val imdbModel = remember(context) {
        ImageRequest.Builder(context)
            .data(R.raw.imdb_logo_2016)
            .decoderFactory(SvgDecoder.Factory())
            .build()
    }

    LaunchedEffect(sessionId) {
        runCatching { focusRequester.requestFocus() }
    }

    LaunchedEffect(sessionId, activeLayer.instanceId) {
        coroutineScope {
            launch {
                if (activeLayer.alpha.value < 1f) {
                    activeLayer.alpha.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = SCREENSAVER_CROSSFADE_MS)
                    )
                }
            }
            launch {
                if (!activeLayer.progress.isRunning && activeLayer.progress.value < 1f) {
                    activeLayer.progress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(
                            durationMillis = SCREENSAVER_MOTION_DURATION_MS,
                            easing = ScreensaverMotionEasing
                        )
                    )
                }
            }
        }
    }

    LaunchedEffect(sessionId, preloadedLayer?.instanceId) {
        val layer = preloadedLayer ?: return@LaunchedEffect
        coroutineScope {
            launch {
                preloadScreensaverSlide(
                    context = context,
                    slide = layer.slide,
                    decodeSize = decodeSize
                )
            }
        }
    }

    LaunchedEffect(sessionId, fadingLayer?.instanceId) {
        val layer = fadingLayer ?: return@LaunchedEffect
        layer.alpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = SCREENSAVER_CROSSFADE_MS)
        )
        if (fadingLayer?.instanceId == layer.instanceId) {
            fadingLayer = null
        }
    }

    LaunchedEffect(sessionId, sessionSlides, currentIndex, activeLayer.instanceId, preloadedLayer?.instanceId) {
        if (sessionSlides.size <= 1) return@LaunchedEffect
        val waitBeforeFadeMs = (SCREENSAVER_SLIDE_ADVANCE_MS.toLong() - SCREENSAVER_PRELOAD_LEAD_MS).coerceAtLeast(0L)
        if (waitBeforeFadeMs > 0L) {
            delay(waitBeforeFadeMs)
        }
        if (preloadedLayer == null) {
            val nextIndex = (currentIndex + 1) % sessionSlides.size
            motionPresetIndex += 1
            preloadedLayer = ScreensaverBackgroundLayer(
                slide = sessionSlides[nextIndex],
                instanceId = activeLayer.instanceId + 1,
                motion = screensaverMotionPresetFor(motionPresetIndex),
                initialAlpha = 0f
            )
        }
        delay(SCREENSAVER_PRELOAD_LEAD_MS)
        val nextIndex = (currentIndex + 1) % sessionSlides.size
        val outgoingLayer = activeLayer
        val incomingLayer = preloadedLayer ?: return@LaunchedEffect
        outgoingLayer.progress.stop()
        outgoingLayer.frozenProgress = outgoingLayer.progress.value
        fadingLayer = outgoingLayer
        currentIndex = nextIndex
        activeLayer = incomingLayer
        motionPresetIndex += 1
        val bufferedIndex = (nextIndex + 1) % sessionSlides.size
        preloadedLayer = ScreensaverBackgroundLayer(
            slide = sessionSlides[bufferedIndex],
            instanceId = incomingLayer.instanceId + 1,
            motion = screensaverMotionPresetFor(motionPresetIndex),
            initialAlpha = 0f
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { viewportSize = it }
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                when (keyEvent.key) {
                    Key.Enter,
                    Key.DirectionCenter,
                    Key.NumPadEnter -> {
                        onOpenSlide(currentSlide)
                        true
                    }

                    else -> {
                        onDismiss()
                        true
                    }
                }
            }
    ) {
        fadingLayer?.let { layer ->
            ScreensaverBackgroundImage(
                layer = layer,
                context = context,
                decodeSize = decodeSize,
                modifier = Modifier.matchParentSize()
            )
        }
        preloadedLayer?.let { layer ->
            ScreensaverBackgroundImage(
                layer = layer,
                context = context,
                decodeSize = decodeSize,
                modifier = Modifier.matchParentSize()
            )
        }
        ScreensaverBackgroundImage(
            layer = activeLayer,
            context = context,
            decodeSize = decodeSize,
            modifier = Modifier.matchParentSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val overlayBrush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0x66000000),
                            Color(0x33000000),
                            Color(0xAA000000),
                            Color(0xEE000000)
                        )
                    )
                    onDrawBehind {
                        drawRect(overlayBrush)
                    }
                }
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.52f)
                .padding(start = 56.dp, end = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            currentSlide.logoUrl?.let { logoUrl ->
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(logoUrl)
                        .crossfade(false)
                        .build(),
                    contentDescription = currentSlide.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(124.dp)
                )
            } ?: Text(
                text = currentSlide.title,
                color = Color.White,
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 46.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            currentSlide.description?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NexioColors.TextPrimary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            ScreensaverPrimaryMetaLine(
                slide = currentSlide,
                imdbModel = imdbModel
            )
            Text(
                text = "Press OK for details",
                color = NexioColors.TextSecondary,
                fontSize = 17.sp
            )
        }
    }
}

@Composable
private fun ScreensaverBackgroundImage(
    layer: ScreensaverBackgroundLayer,
    context: Context,
    decodeSize: Size,
    modifier: Modifier = Modifier
) {
    var size by remember(layer.instanceId) { mutableStateOf(IntSize.Zero) }
    val progress = layer.frozenProgress ?: layer.progress.value
    val translationX = size.width * layer.motion.translateXFraction * progress
    val translationY = size.height * layer.motion.translateYFraction * progress
    val scale = SCREENSAVER_START_SCALE + ((SCREENSAVER_END_SCALE - SCREENSAVER_START_SCALE) * progress)

    AsyncImage(
        model = remember(layer.instanceId, layer.slide.backgroundUrl, decodeSize) {
            buildScreensaverImageRequest(
                context = context,
                url = layer.slide.backgroundUrl,
                decodeSize = decodeSize
            )
        },
        contentDescription = layer.slide.title,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .graphicsLayer {
                alpha = layer.alpha.value
                compositingStrategy = CompositingStrategy.Offscreen
                transformOrigin = layer.motion.transformOrigin
                scaleX = scale
                scaleY = scale
                this.translationX = translationX
                this.translationY = translationY
            }
            .onSizeChanged { size = it }
    )
}

@Composable
private fun ScreensaverPrimaryMetaLine(
    slide: IdleScreensaverSlide,
    imdbModel: ImageRequest
) {
    val genresText = remember(slide.genres) {
        slide.genres.joinToString(" • ")
    }
    val runtimeText = remember(slide.runtime) {
        slide.runtime?.let(::formatScreensaverRuntime)
    }
    val yearText = remember(slide.releaseInfo) {
        Regex("""\b(19|20)\d{2}\b""").find(slide.releaseInfo.orEmpty())?.value
            ?: slide.releaseInfo?.substringBefore('-')?.trim()?.ifBlank { null }
            ?: slide.releaseInfo?.trim()
    }
    val shouldShowAnything = slide.genres.isNotEmpty() || !runtimeText.isNullOrBlank() || !yearText.isNullOrBlank() || slide.imdbRating != null
    if (!shouldShowAnything) return

    androidx.compose.foundation.layout.Row(
        modifier = Modifier.wrapContentWidth(unbounded = true),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (slide.genres.isNotEmpty()) {
            Text(
                text = genresText,
                style = MaterialTheme.typography.labelLarge,
                color = NexioTheme.extendedColors.textSecondary,
                maxLines = 1,
                softWrap = false
            )
            ScreensaverMetaDivider()
        }

        runtimeText?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = NexioTheme.extendedColors.textSecondary,
                maxLines = 1,
                softWrap = false
            )
            ScreensaverMetaDivider()
        }

        if (!yearText.isNullOrBlank()) {
            Text(
                text = yearText,
                style = MaterialTheme.typography.labelLarge,
                color = NexioTheme.extendedColors.textSecondary,
                maxLines = 1,
                softWrap = false
            )
            if (slide.imdbRating != null) {
                ScreensaverMetaDivider()
            }
        }

        slide.imdbRating?.let { rating ->
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = imdbModel,
                    contentDescription = "IMDb",
                    modifier = Modifier.size(30.dp),
                    contentScale = ContentScale.Fit
                )
                val ratingText = remember(rating) { String.format("%.1f", rating) }
                Text(
                    text = ratingText,
                    style = MaterialTheme.typography.labelLarge,
                    color = NexioTheme.extendedColors.textSecondary,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
private fun ScreensaverMetaDivider() {
    Text(
        text = "•",
        style = MaterialTheme.typography.labelLarge,
        color = NexioTheme.extendedColors.textTertiary
    )
}

private fun formatScreensaverRuntime(runtime: String): String {
    val minutes = runtime.filter { it.isDigit() }.toIntOrNull() ?: return runtime
    return if (minutes >= 60) {
        val hours = minutes / 60
        val mins = minutes % 60
        if (mins > 0) "${hours}h ${mins}m" else "${hours}h"
    } else {
        "${minutes}m"
    }
}

private class ScreensaverBackgroundLayer(
    val slide: IdleScreensaverSlide,
    val instanceId: Int,
    val motion: ScreensaverMotionPreset,
    initialAlpha: Float
) {
    val progress = Animatable(0f)
    val alpha = Animatable(initialAlpha)
    var frozenProgress by mutableStateOf<Float?>(null)
}

private data class ScreensaverMotionPreset(
    val transformOrigin: TransformOrigin,
    val translateXFraction: Float,
    val translateYFraction: Float
)

private fun screensaverMotionPresetFor(index: Int): ScreensaverMotionPreset {
    val presets = listOf(
        ScreensaverMotionPreset(
            transformOrigin = TransformOrigin(0f, 0f),
            translateXFraction = SCREENSAVER_TRANSLATE_X_FRACTION,
            translateYFraction = SCREENSAVER_TRANSLATE_Y_FRACTION
        ),
        ScreensaverMotionPreset(
            transformOrigin = TransformOrigin(1f, 1f),
            translateXFraction = -(SCREENSAVER_TRANSLATE_X_FRACTION * 0.95f),
            translateYFraction = -(SCREENSAVER_TRANSLATE_Y_FRACTION * 1.1f)
        ),
        ScreensaverMotionPreset(
            transformOrigin = TransformOrigin(1f, 0f),
            translateXFraction = -(SCREENSAVER_TRANSLATE_X_FRACTION * 1.08f),
            translateYFraction = SCREENSAVER_TRANSLATE_Y_FRACTION
        ),
        ScreensaverMotionPreset(
            transformOrigin = TransformOrigin(0f, 1f),
            translateXFraction = SCREENSAVER_TRANSLATE_X_FRACTION,
            translateYFraction = -(SCREENSAVER_TRANSLATE_Y_FRACTION * 1.15f)
        )
    )
    return presets[index.mod(presets.size)]
}

private fun buildScreensaverImageRequest(
    context: Context,
    url: String,
    decodeSize: Size
): ImageRequest {
    val width = (decodeSize.width as? Dimension.Pixels)?.px ?: SCREENSAVER_LOW_POWER_MAX_WIDTH
    val height = (decodeSize.height as? Dimension.Pixels)?.px ?: SCREENSAVER_LOW_POWER_MAX_HEIGHT
    return ImageRequest.Builder(context)
        .data(url)
        .crossfade(false)
        .allowHardware(true)
        .memoryCacheKey("${url}_${width}x${height}")
        .size(decodeSize)
        .build()
}

private fun screensaverDecodeSize(
    viewportSize: IntSize,
    lowPower: Boolean
): Size {
    val width = viewportSize.width.takeIf { it > 0 } ?: 1920
    val height = viewportSize.height.takeIf { it > 0 } ?: 1080
    val targetWidth = if (lowPower) {
        minOf(width, SCREENSAVER_LOW_POWER_MAX_WIDTH)
    } else {
        width
    }
    val targetHeight = if (lowPower) {
        minOf(height, SCREENSAVER_LOW_POWER_MAX_HEIGHT)
    } else {
        height
    }
    return Size(
        width = Dimension(targetWidth),
        height = Dimension(targetHeight)
    )
}

private suspend fun preloadScreensaverSlide(
    context: Context,
    slide: IdleScreensaverSlide,
    decodeSize: Size
) {
    context.imageLoader.enqueue(
        buildScreensaverImageRequest(
            context = context,
            url = slide.backgroundUrl,
            decodeSize = decodeSize
        )
    )
    slide.logoUrl?.let { logoUrl ->
        context.imageLoader.enqueue(
            ImageRequest.Builder(context)
                .data(logoUrl)
                .crossfade(false)
                .allowHardware(true)
                .build()
        )
    }
}

private fun Context.isLowPowerScreensaverDevice(): Boolean {
    val activityManager = getSystemService(ActivityManager::class.java)
    return activityManager?.isLowRamDevice == true
}
