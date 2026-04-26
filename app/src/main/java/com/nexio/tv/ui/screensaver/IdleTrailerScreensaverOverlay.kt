package com.nexio.tv.ui.screensaver

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.nexio.tv.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nexio.tv.ui.components.TrailerPlayer
import com.nexio.tv.ui.theme.NexioColors
import kotlinx.coroutines.delay

private const val TRAILER_SCREENSAVER_BRANDING_VISIBLE_MS = 20_000L
private const val TRAILER_SCREENSAVER_BRANDING_FADE_MS = 1_500
private const val TRAILER_SCREENSAVER_OPEN_GUARD_MS = 180L
private const val TRAILER_SCREENSAVER_FIRST_FRAME_TIMEOUT_MS = 5_000L
private const val TRAILER_SCREENSAVER_STALL_TIMEOUT_MS = 8_000L
private const val TRAILER_SCREENSAVER_BRANDING_WIDTH_DP = 360

internal data class IdleTrailerBrandingPresentationSpec(
    val visibleMs: Long,
    val fadeDurationMs: Int,
    val contentAlignment: Alignment.Horizontal,
    val promptTextAlign: TextAlign
)

internal fun idleTrailerBrandingPresentationSpec(): IdleTrailerBrandingPresentationSpec {
    return IdleTrailerBrandingPresentationSpec(
        visibleMs = TRAILER_SCREENSAVER_BRANDING_VISIBLE_MS,
        fadeDurationMs = TRAILER_SCREENSAVER_BRANDING_FADE_MS,
        contentAlignment = Alignment.CenterHorizontally,
        promptTextAlign = TextAlign.Center
    )
}

internal fun shouldAdvanceIdleTrailerPlaybackAfterFirstFrameTimeout(
    hasRenderedFirstFrame: Boolean,
    playbackKey: String,
    failedPlaybackKeys: Set<String>
): Boolean {
    return !hasRenderedFirstFrame && playbackKey !in failedPlaybackKeys
}

@Composable
internal fun IdleTrailerScreensaverOverlay(
    sessionId: Long,
    sessionStart: IdleTrailerScreensaverSessionStart,
    onDismiss: () -> Unit,
    onOpenSlide: (IdleTrailerScreensaverCandidate) -> Unit,
    resolvePlaybackSource: suspend (IdleTrailerScreensaverCandidate, String) -> com.nexio.tv.data.trailer.TrailerPlaybackSource?
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnOpenDetails by rememberUpdatedState(onOpenSlide)
    val currentResolvePlayback by rememberUpdatedState(resolvePlaybackSource)
    var currentPlayback by remember(sessionId, sessionStart) { mutableStateOf(sessionStart.initialPlayback) }
    var preparedNextPlayback by remember(sessionId) { mutableStateOf<IdleTrailerScreensaverPlayback?>(null) }
    var sessionMuted by remember(sessionId) { mutableStateOf(true) }
    var pendingOpen by remember(sessionId) { mutableStateOf<IdleTrailerScreensaverCandidate?>(null) }
    var failedPlaybackKeys by remember(sessionId) { mutableStateOf<Set<String>>(emptySet()) }
    var hasRenderedFirstFrame by remember(sessionId, currentPlayback.index, currentPlayback.trailerId) { mutableStateOf(false) }
    var lastProgressMs by remember(sessionId, currentPlayback.index, currentPlayback.trailerId) { mutableStateOf(-1L) }
    var advanceSignal by remember(sessionId) { mutableIntStateOf(0) }
    val brandingSpec = remember { idleTrailerBrandingPresentationSpec() }
    val brandingAlpha = remember(sessionId) { Animatable(1f) }

    BackHandler(onBack = currentOnDismiss)

    LaunchedEffect(sessionId) {
        runCatching { focusRequester.requestFocus() }
    }

    LaunchedEffect(pendingOpen) {
        val candidate = pendingOpen ?: return@LaunchedEffect
        delay(TRAILER_SCREENSAVER_OPEN_GUARD_MS)
        currentOnOpenDetails(candidate)
    }

    LaunchedEffect(sessionId, currentPlayback.trailerId, currentPlayback.index) {
        hasRenderedFirstFrame = false
        brandingAlpha.stop()
        brandingAlpha.snapTo(1f)
        delay(brandingSpec.visibleMs)
        brandingAlpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = brandingSpec.fadeDurationMs)
        )
    }

    LaunchedEffect(sessionId, currentPlayback.index, currentPlayback.trailerId, sessionStart.candidates, failedPlaybackKeys) {
        preparedNextPlayback = resolveNextIdleTrailerPlayback(
            candidates = sessionStart.candidates,
            currentIndex = currentPlayback.index,
            skippedPlaybackKeys = failedPlaybackKeys,
            resolvePlayback = currentResolvePlayback
        )
    }

    LaunchedEffect(
        sessionId,
        currentPlayback.index,
        currentPlayback.trailerId,
        failedPlaybackKeys
    ) {
        val playbackKey = idleTrailerPlaybackKey(
            candidate = currentPlayback.candidate,
            trailerId = currentPlayback.trailerId
        )
        delay(TRAILER_SCREENSAVER_FIRST_FRAME_TIMEOUT_MS)
        if (
            shouldAdvanceIdleTrailerPlaybackAfterFirstFrameTimeout(
                hasRenderedFirstFrame = hasRenderedFirstFrame,
                playbackKey = playbackKey,
                failedPlaybackKeys = failedPlaybackKeys
            )
        ) {
            failedPlaybackKeys = failedPlaybackKeys + playbackKey
            preparedNextPlayback = null
            advanceSignal += 1
        }
    }

    LaunchedEffect(
        sessionId,
        currentPlayback.index,
        currentPlayback.trailerId,
        hasRenderedFirstFrame,
        failedPlaybackKeys
    ) {
        if (!hasRenderedFirstFrame) return@LaunchedEffect
        val playbackKey = idleTrailerPlaybackKey(
            candidate = currentPlayback.candidate,
            trailerId = currentPlayback.trailerId
        )
        if (playbackKey in failedPlaybackKeys) return@LaunchedEffect
        lastProgressMs = -1L
        var previousProgress = -1L
        while (true) {
            delay(TRAILER_SCREENSAVER_STALL_TIMEOUT_MS)
            val currentProgress = lastProgressMs
            if (currentProgress < 0L) {
                previousProgress = -1L
                continue
            }
            if (currentProgress == previousProgress) {
                failedPlaybackKeys = failedPlaybackKeys + playbackKey
                preparedNextPlayback = null
                advanceSignal += 1
                break
            }
            previousProgress = currentProgress
        }
    }

    LaunchedEffect(advanceSignal) {
        if (advanceSignal == 0) return@LaunchedEffect
        var nextPlayback = preparedNextPlayback ?: resolveNextIdleTrailerPlayback(
            candidates = sessionStart.candidates,
            currentIndex = currentPlayback.index,
            skippedPlaybackKeys = failedPlaybackKeys,
            resolvePlayback = currentResolvePlayback
        )
        if (nextPlayback == null && failedPlaybackKeys.isNotEmpty()) {
            failedPlaybackKeys = emptySet()
            nextPlayback = resolveNextIdleTrailerPlayback(
                candidates = sessionStart.candidates,
                currentIndex = currentPlayback.index,
                skippedPlaybackKeys = emptySet(),
                resolvePlayback = currentResolvePlayback
            )
        }
        if (nextPlayback == null) {
            currentOnDismiss()
            return@LaunchedEffect
        }
        preparedNextPlayback = null
        currentPlayback = nextPlayback
    }

    fun handleAction(action: IdleTrailerRemoteKeyAction): Boolean {
        when (action) {
            IdleTrailerRemoteKeyAction.OPEN_DETAILS -> {
                if (pendingOpen == null) {
                    pendingOpen = currentPlayback.candidate
                }
            }

            IdleTrailerRemoteKeyAction.DISMISS -> currentOnDismiss()
            IdleTrailerRemoteKeyAction.UNMUTE_SESSION -> sessionMuted = false
            IdleTrailerRemoteKeyAction.CONSUME -> Unit
        }
        return true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (pendingOpen != null) {
                    true
                } else if (keyEvent.type != KeyEventType.KeyDown) {
                    true
                } else {
                    handleAction(
                        when (keyEvent.key) {
                            Key.Enter,
                            Key.DirectionCenter,
                            Key.NumPadEnter -> IdleTrailerRemoteKeyAction.OPEN_DETAILS

                            Key.Back -> IdleTrailerRemoteKeyAction.DISMISS

                            else -> if (sessionMuted) {
                                IdleTrailerRemoteKeyAction.UNMUTE_SESSION
                            } else {
                                IdleTrailerRemoteKeyAction.CONSUME
                            }
                        }
                    )
                }
            }
    ) {
        AsyncImage(
            model = remember(currentPlayback.candidate.backgroundUrl) {
                ImageRequest.Builder(context)
                    .data(currentPlayback.candidate.backgroundUrl)
                    .crossfade(false)
                    .build()
            },
            contentDescription = currentPlayback.candidate.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val overlayBrush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0x22000000),
                            Color(0x11000000),
                            Color(0x88000000),
                            Color(0xDD000000)
                        )
                    )
                    onDrawBehind {
                        drawRect(overlayBrush)
                    }
                }
        )
        TrailerPlayer(
            trailerUrl = currentPlayback.source.videoUrl,
            trailerAudioUrl = currentPlayback.source.audioUrl,
            isPlaying = true,
            muted = sessionMuted,
            trailerUserAgent = currentPlayback.source.userAgent,
            onEnded = { advanceSignal += 1 },
            onFirstFrameRendered = { hasRenderedFirstFrame = true },
            onError = {
                val playbackKey = idleTrailerPlaybackKey(
                    candidate = currentPlayback.candidate,
                    trailerId = currentPlayback.trailerId
                )
                if (playbackKey !in failedPlaybackKeys) {
                    failedPlaybackKeys = failedPlaybackKeys + playbackKey
                    preparedNextPlayback = null
                    advanceSignal += 1
                }
            },
            onProgressChanged = { positionMs, _ ->
                if (hasRenderedFirstFrame) {
                    lastProgressMs = positionMs
                }
            },
            onRemoteKey = { keyCode, action, _ ->
                if (pendingOpen != null) {
                    true
                } else {
                    handleAction(
                        determineIdleTrailerRemoteKeyAction(
                            keyCode = keyCode,
                            action = action,
                            sessionMuted = sessionMuted
                        )
                    )
                }
            },
            cropToFill = true,
            modifier = Modifier.fillMaxSize()
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .width(TRAILER_SCREENSAVER_BRANDING_WIDTH_DP.dp)
                .padding(start = 44.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = brandingSpec.contentAlignment
        ) {
            currentPlayback.candidate.logoUrl?.let { logoUrl ->
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(logoUrl)
                        .crossfade(false)
                        .build(),
                    contentDescription = currentPlayback.candidate.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .width(TRAILER_SCREENSAVER_BRANDING_WIDTH_DP.dp)
                        .height(96.dp)
                        .graphicsLayer { alpha = brandingAlpha.value }
                )
            } ?: androidx.tv.material3.Text(
                text = currentPlayback.candidate.title,
                color = Color.White,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = brandingSpec.promptTextAlign,
                modifier = Modifier.graphicsLayer { alpha = brandingAlpha.value }
            )
            androidx.tv.material3.Text(
                text = stringResource(R.string.screensaver_press_ok_for_details),
                color = NexioColors.TextSecondary,
                fontSize = 17.sp,
                textAlign = brandingSpec.promptTextAlign,
                modifier = Modifier
                    .width(TRAILER_SCREENSAVER_BRANDING_WIDTH_DP.dp)
                    .graphicsLayer { alpha = brandingAlpha.value }
            )
        }
    }
}
