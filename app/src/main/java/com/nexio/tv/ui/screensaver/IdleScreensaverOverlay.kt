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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.text.style.TextAlign
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
private const val SCREENSAVER_OPEN_GUARD_MS = 180L
private const val SCREENSAVER_DETAILS_PROMPT_VISIBLE_MS = 5_000L
private const val SCREENSAVER_DETAILS_PROMPT_FADE_MS = 1_500
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
    var activeSlotIndex by remember(sessionId) { mutableIntStateOf(0) }
    var nextMotionPresetIndex by remember(sessionId) { mutableIntStateOf(1) }
    var viewportSize by remember(sessionId) { mutableStateOf(IntSize.Zero) }
    val isLowPowerDevice = remember(context) { context.isLowPowerScreensaverDevice() }
    val decodeSize = remember(viewportSize, isLowPowerDevice) {
        screensaverDecodeSize(
            viewportSize = viewportSize,
            lowPower = isLowPowerDevice
        )
    }
    val backgroundLayers = remember(sessionId, sessionSlides) {
        listOf(
            ScreensaverBackgroundLayer(
                slide = sessionSlides.first(),
                motion = screensaverMotionPresetFor(0),
                initialAlpha = 1f
            ),
            ScreensaverBackgroundLayer(
                slide = sessionSlides.getOrElse(1.coerceAtMost(sessionSlides.lastIndex)) { sessionSlides.first() },
                motion = screensaverMotionPresetFor(1),
                initialAlpha = 0f
            )
        )
    }
    val activeLayer = backgroundLayers[activeSlotIndex]
    val hiddenLayer = backgroundLayers[1 - activeSlotIndex]
    val currentSlide = activeLayer.slide
    var pendingOpenSlide by remember(sessionId) { mutableStateOf<IdleScreensaverSlide?>(null) }
    val imdbModel = remember(context) {
        ImageRequest.Builder(context)
            .data(R.raw.imdb_logo_2016)
            .decoderFactory(SvgDecoder.Factory())
            .build()
    }
    val tomatoesModel = remember(context) {
        ImageRequest.Builder(context)
            .data(R.raw.mdblist_tomatoes)
            .decoderFactory(SvgDecoder.Factory())
            .build()
    }
    val detailsPromptAlpha = remember(sessionId) { Animatable(1f) }

    LaunchedEffect(sessionId) {
        runCatching { focusRequester.requestFocus() }
    }

    LaunchedEffect(pendingOpenSlide) {
        val slide = pendingOpenSlide ?: return@LaunchedEffect
        delay(SCREENSAVER_OPEN_GUARD_MS)
        onOpenSlide(slide)
    }

    LaunchedEffect(sessionId, currentSlide.itemId, activeSlotIndex) {
        detailsPromptAlpha.stop()
        detailsPromptAlpha.snapTo(1f)
        delay(SCREENSAVER_DETAILS_PROMPT_VISIBLE_MS)
        detailsPromptAlpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = SCREENSAVER_DETAILS_PROMPT_FADE_MS)
        )
    }

    LaunchedEffect(sessionId, sessionSlides, currentIndex, activeSlotIndex, decodeSize) {
        val visibleLayer = backgroundLayers[activeSlotIndex]
        visibleLayer.frozenProgress = null
        coroutineScope {
            launch {
                if (visibleLayer.alpha.value < 1f) {
                    visibleLayer.alpha.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = SCREENSAVER_CROSSFADE_MS)
                    )
                }
            }
            launch {
                if (!visibleLayer.progress.isRunning && visibleLayer.progress.value < 1f) {
                    visibleLayer.progress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(
                            durationMillis = SCREENSAVER_MOTION_DURATION_MS,
                            easing = ScreensaverMotionEasing
                        )
                    )
                }
            }
        }
        if (sessionSlides.size <= 1) return@LaunchedEffect
        val nextIndex = (currentIndex + 1) % sessionSlides.size
        prepareScreensaverLayer(
            layer = hiddenLayer,
            slide = sessionSlides[nextIndex],
            motion = screensaverMotionPresetFor(nextMotionPresetIndex)
        )
        preloadScreensaverSlide(
            context = context,
            slide = hiddenLayer.slide,
            decodeSize = decodeSize
        )

        delay(SCREENSAVER_SLIDE_ADVANCE_MS.toLong())

        val outgoingLayer = backgroundLayers[activeSlotIndex]
        val incomingLayer = backgroundLayers[1 - activeSlotIndex]
        outgoingLayer.progress.stop()
        outgoingLayer.frozenProgress = outgoingLayer.progress.value

        incomingLayer.alpha.snapTo(0f)
        incomingLayer.progress.snapTo(0f)
        incomingLayer.frozenProgress = null
        launch {
            incomingLayer.progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = SCREENSAVER_MOTION_DURATION_MS,
                    easing = ScreensaverMotionEasing
                )
            )
        }
        coroutineScope {
            launch {
                outgoingLayer.alpha.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = SCREENSAVER_CROSSFADE_MS)
                )
            }
            launch {
                incomingLayer.alpha.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = SCREENSAVER_CROSSFADE_MS)
                )
            }
        }
        currentIndex = nextIndex
        activeSlotIndex = 1 - activeSlotIndex
        nextMotionPresetIndex += 1
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { viewportSize = it }
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (pendingOpenSlide != null) return@onPreviewKeyEvent true
                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                when (keyEvent.key) {
                    Key.Enter,
                    Key.DirectionCenter,
                    Key.NumPadEnter -> {
                        pendingOpenSlide = currentSlide
                        true
                    }

                    else -> {
                        onDismiss()
                        true
                    }
                }
            }
    ) {
        backgroundLayers.forEach { layer ->
            ScreensaverBackgroundImage(
                layer = layer,
                context = context,
                decodeSize = decodeSize,
                modifier = Modifier.matchParentSize()
            )
        }
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
            horizontalAlignment = Alignment.CenterHorizontally,
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
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            ScreensaverPrimaryMetaLine(
                slide = currentSlide,
                imdbModel = imdbModel,
                tomatoesModel = tomatoesModel
            )
            Text(
                text = "Press OK for details",
                color = NexioColors.TextSecondary,
                fontSize = 17.sp,
                modifier = Modifier.graphicsLayer { alpha = detailsPromptAlpha.value }
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
    var size by remember(layer.slide.backgroundUrl) { mutableStateOf(IntSize.Zero) }
    val progress = layer.frozenProgress ?: layer.progress.value
    val translationX = size.width * layer.motion.translateXFraction * progress
    val translationY = size.height * layer.motion.translateYFraction * progress
    val scale = SCREENSAVER_START_SCALE + ((SCREENSAVER_END_SCALE - SCREENSAVER_START_SCALE) * progress)

    AsyncImage(
        model = remember(layer.slide.backgroundUrl, decodeSize) {
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
    imdbModel: ImageRequest,
    tomatoesModel: ImageRequest
) {
    val metaSegments = remember(slide) { buildScreensaverPrimaryMetaSegments(slide) }
    if (metaSegments.isEmpty()) return

    androidx.compose.foundation.layout.Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        metaSegments.forEach { segment ->
            when (segment) {
                is ScreensaverPrimaryMetaSegment.Divider -> ScreensaverMetaDivider()
                is ScreensaverPrimaryMetaSegment.Genres -> {
                    Text(
                        text = segment.text,
                        style = MaterialTheme.typography.labelLarge,
                        color = NexioTheme.extendedColors.textSecondary,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                is ScreensaverPrimaryMetaSegment.Rating -> {
                    ScreensaverRatingBadge(
                        model = if (segment.kind == ScreensaverPrimaryMetaSegment.Rating.Kind.IMDB) {
                            imdbModel
                        } else {
                            tomatoesModel
                        },
                        contentDescription = if (segment.kind == ScreensaverPrimaryMetaSegment.Rating.Kind.IMDB) {
                            "IMDb"
                        } else {
                            "Rotten Tomatoes"
                        },
                        text = segment.text
                    )
                }
            }
        }
    }
}

internal sealed interface ScreensaverPrimaryMetaSegment {
    data class Genres(val text: String) : ScreensaverPrimaryMetaSegment
    data class Rating(val kind: Kind, val text: String) : ScreensaverPrimaryMetaSegment {
        enum class Kind {
            IMDB,
            TOMATOES
        }
    }

    data object Divider : ScreensaverPrimaryMetaSegment
}

internal fun buildScreensaverPrimaryMetaSegments(slide: IdleScreensaverSlide): List<ScreensaverPrimaryMetaSegment> {
    val segments = mutableListOf<ScreensaverPrimaryMetaSegment>()
    if (slide.genres.isNotEmpty()) {
        segments += ScreensaverPrimaryMetaSegment.Genres(slide.genres.joinToString(" • "))
    }
    val ratingSegments = buildList {
        slide.imdbRating?.let { rating ->
            add(
                ScreensaverPrimaryMetaSegment.Rating(
                    kind = ScreensaverPrimaryMetaSegment.Rating.Kind.IMDB,
                    text = String.format("%.1f", rating)
                )
            )
        }
        slide.tomatoesRating?.let { rating ->
            add(
                ScreensaverPrimaryMetaSegment.Rating(
                    kind = ScreensaverPrimaryMetaSegment.Rating.Kind.TOMATOES,
                    text = formatScreensaverAggregateRating(rating)
                )
            )
        }
    }
    if (segments.isNotEmpty() && ratingSegments.isNotEmpty()) {
        segments += ScreensaverPrimaryMetaSegment.Divider
    }
    segments += ratingSegments
    return segments
}

@Composable
private fun ScreensaverRatingBadge(
    model: ImageRequest,
    contentDescription: String,
    text: String
) {
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            modifier = Modifier.size(30.dp),
            contentScale = ContentScale.Fit
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = NexioTheme.extendedColors.textSecondary,
            maxLines = 1,
            softWrap = false
        )
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

private fun formatScreensaverAggregateRating(rating: Double): String {
    return if (rating % 1.0 == 0.0) rating.toInt().toString() else String.format("%.1f", rating)
}

private class ScreensaverBackgroundLayer(
    slide: IdleScreensaverSlide,
    motion: ScreensaverMotionPreset,
    initialAlpha: Float
) {
    var slide by mutableStateOf(slide)
    var motion by mutableStateOf(motion)
    val progress = Animatable(0f)
    val alpha = Animatable(initialAlpha)
    var frozenProgress by mutableStateOf<Float?>(null)
}

private suspend fun prepareScreensaverLayer(
    layer: ScreensaverBackgroundLayer,
    slide: IdleScreensaverSlide,
    motion: ScreensaverMotionPreset
) {
    layer.progress.stop()
    layer.alpha.stop()
    layer.slide = slide
    layer.motion = motion
    layer.frozenProgress = null
    layer.progress.snapTo(0f)
    layer.alpha.snapTo(0f)
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
