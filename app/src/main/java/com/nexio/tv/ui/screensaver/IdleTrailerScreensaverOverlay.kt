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
import com.nexio.tv.core.artwork.toCoilModelOrNull
import com.nexio.tv.core.metadata.router.resolver.TrailerPlaybackRef
import com.nexio.tv.ui.components.TrailerPlayer
import com.nexio.tv.ui.theme.NexioColors
import kotlinx.coroutines.delay

private const val TRAILER_SCREENSAVER_BRANDING_VISIBLE_MS = 20_000L
private const val TRAILER_SCREENSAVER_BRANDING_FADE_MS = 1_500
private const val TRAILER_SCREENSAVER_OPEN_GUARD_MS = 180L
private const val TRAILER_SCREENSAVER_FIRST_FRAME_TIMEOUT_MS = 15_000L
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
    // Plan: Bug B — Task B3. Fired when no next playback can be resolved
    // AFTER at least one trailer played successfully. MainActivity sets
    // idleTrailerSessionStart = null in response, which flips the
    // presentation mode to IMAGE on next composition — IdleScreensaverOverlay
    // takes over and rotates still images instead of camping on a frozen
    // TextureView. Default no-op preserves test-site signatures.
    onAllCandidatesExhausted: () -> Unit = {},
    resolvePlaybackSource: suspend (IdleTrailerScreensaverCandidate, TrailerPlaybackRef) -> com.nexio.tv.data.trailer.TrailerPlaybackSource?
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnOpenDetails by rememberUpdatedState(onOpenSlide)
    val currentOnExhausted by rememberUpdatedState(onAllCandidatesExhausted)
    val currentResolvePlayback by rememberUpdatedState(resolvePlaybackSource)
    var currentPlayback by remember(sessionId, sessionStart) { mutableStateOf(sessionStart.initialPlayback) }
    var preparedNextPlayback by remember(sessionId) { mutableStateOf<IdleTrailerScreensaverPlayback?>(null) }
    var sessionMuted by remember(sessionId) { mutableStateOf(true) }
    var pendingOpen by remember(sessionId) { mutableStateOf<IdleTrailerScreensaverCandidate?>(null) }
    var failedPlaybackKeys by remember(sessionId) { mutableStateOf<Set<String>>(emptySet()) }
    var hasRenderedFirstFrame by remember(sessionId, currentPlayback.index, currentPlayback.playbackRef) { mutableStateOf(false) }
    // Plan: Bug B — Task B3. Tracks whether any trailer in this session has
    // ever rendered first frame. Drives decideIdleTrailerExhaustionAction —
    // we only fall back to IMAGE mode (vs dismiss) if there was at least
    // one playback so the user perceives the screensaver as alive.
    var hadAtLeastOneSuccessfulPlayback by remember(sessionId) { mutableStateOf(false) }
    var lastProgressMs by remember(sessionId, currentPlayback.index, currentPlayback.playbackRef) { mutableStateOf(-1L) }
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

    LaunchedEffect(sessionId, currentPlayback.playbackRef, currentPlayback.index) {
        hasRenderedFirstFrame = false
        brandingAlpha.stop()
        brandingAlpha.snapTo(1f)
        delay(brandingSpec.visibleMs)
        brandingAlpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = brandingSpec.fadeDurationMs)
        )
    }

    LaunchedEffect(sessionId, currentPlayback.index, currentPlayback.playbackRef, sessionStart.candidates, failedPlaybackKeys) {
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
        currentPlayback.playbackRef,
        failedPlaybackKeys
    ) {
        val playbackKey = idleTrailerPlaybackKey(
            candidate = currentPlayback.candidate,
            playbackRef = currentPlayback.playbackRef
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
        currentPlayback.playbackRef,
        hasRenderedFirstFrame,
        failedPlaybackKeys
    ) {
        if (!hasRenderedFirstFrame) return@LaunchedEffect
        val playbackKey = idleTrailerPlaybackKey(
            candidate = currentPlayback.candidate,
            playbackRef = currentPlayback.playbackRef
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
        var secondAttemptResolvedAny = false
        if (nextPlayback == null && failedPlaybackKeys.isNotEmpty()) {
            failedPlaybackKeys = emptySet()
            nextPlayback = resolveNextIdleTrailerPlayback(
                candidates = sessionStart.candidates,
                currentIndex = currentPlayback.index,
                skippedPlaybackKeys = emptySet(),
                resolvePlayback = currentResolvePlayback
            )
            if (nextPlayback != null) secondAttemptResolvedAny = true
        }
        if (nextPlayback == null) {
            // Plan: Bug B — Task B3. Pool exhausted. Route through the
            // exhaustion decision so the IMAGE-mode fallback (still-image
            // rotation) takes over instead of dismissing the overlay back
            // to a potentially-stuck loop of show → exhaust → dismiss →
            // show → ... that would camp on a frozen TextureView.
            when (
                decideIdleTrailerExhaustionAction(
                    hadAtLeastOneSuccessfulPlayback = hadAtLeastOneSuccessfulPlayback,
                    secondAttemptResolvedAny = secondAttemptResolvedAny
                )
            ) {
                IdleTrailerExhaustionAction.FALLBACK_TO_IMAGE -> currentOnExhausted()
                IdleTrailerExhaustionAction.DISMISS -> currentOnDismiss()
            }
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
            model = remember(currentPlayback.candidate.backgroundArtwork) {
                ImageRequest.Builder(context)
                    .data(currentPlayback.candidate.backgroundArtwork.toCoilModelOrNull())
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
            trailerUserAgent = currentPlayback.source.userAgent,
            trailerSigningClientKey = currentPlayback.source.signingClientKey,
            trailerCaptions = currentPlayback.source.captions,
            isPlaying = true,
            muted = sessionMuted,
            onEnded = { advanceSignal += 1 },
            onFirstFrameRendered = {
                hasRenderedFirstFrame = true
                // Plan: Bug B — Task B3. Mark the session as "ever played
                // something" so a future exhaustion can route to IMAGE
                // fallback instead of dismiss.
                hadAtLeastOneSuccessfulPlayback = true
            },
            // Plan: Bug B — Task B3. Wire the TrailerBufferingWatchdog
            // (added in Task B1) into the candidate-advance path. If the
            // player parks in STATE_BUFFERING without progress for >10s
            // (which Player.STATE_ENDED would not catch and onPlayerError
            // also may not fire for codec init hangs), mark the playback
            // key as failed and advance. Without this the TextureView
            // would hold the last frame indefinitely — exactly the
            // OLED-burn scenario this whole plan exists to prevent.
            onBufferingStall = {
                val playbackKey = idleTrailerPlaybackKey(
                    candidate = currentPlayback.candidate,
                    playbackRef = currentPlayback.playbackRef
                )
                if (playbackKey !in failedPlaybackKeys) {
                    failedPlaybackKeys = failedPlaybackKeys + playbackKey
                    preparedNextPlayback = null
                    advanceSignal += 1
                }
            },
            onError = {
                val playbackKey = idleTrailerPlaybackKey(
                    candidate = currentPlayback.candidate,
                    playbackRef = currentPlayback.playbackRef
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
            currentPlayback.candidate.logoArtwork.toCoilModelOrNull()?.let { logoModel ->
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(logoModel)
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
