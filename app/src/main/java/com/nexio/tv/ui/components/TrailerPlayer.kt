package com.nexio.tv.ui.components

import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import com.nexio.tv.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import com.nexio.tv.core.player.BurnInProtectionState
import com.nexio.tv.core.player.FrameRateUtils
import com.nexio.tv.data.local.SubtitleStyleSettings
import com.nexio.tv.core.ui.findLifecycleOwner
import com.nexio.tv.data.trailer.YOUTUBE_STABLE_WEB_USER_AGENT
import com.nexio.tv.data.trailer.YouTubeCaptionTrack
import com.nexio.tv.data.trailer.YouTubeWireProfile
import com.nexio.tv.data.trailer.YoutubeChunkedDataSourceFactory
import com.nexio.tv.data.trailer.buildYouTubeWireProperties
import com.nexio.tv.data.trailer.pickTrailerCaptionTrack
import com.nexio.tv.data.trailer.shouldUseYouTubeChunkedTransfer
import com.nexio.tv.domain.model.SubtitleTranslationSettings
import com.nexio.tv.ui.screens.player.TimedAddonCueGroup
import com.nexio.tv.ui.screens.player.ensureExternalSubtitleOverlay
import androidx.compose.runtime.rememberCoroutineScope
import androidx.media3.common.text.Cue
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

private const val TAG = "TrailerPlayer"

internal fun shouldUseChunkedTrailerDataSource(
    trailerUrl: String?,
    trailerAudioUrl: String?
): Boolean {
    val videoUsesChunking = trailerUrl
        ?.takeIf { it.isNotBlank() }
        ?.let(::shouldUseYouTubeChunkedTransfer)
        ?: false
    val audioUsesChunking = trailerAudioUrl
        ?.takeIf { it.isNotBlank() }
        ?.let(::shouldUseYouTubeChunkedTransfer)
        ?: false
    return videoUsesChunking || audioUsesChunking
}

internal fun bindTrailerPlayerView(
    view: PlayerView,
    player: Player?,
    subtitleStyle: SubtitleStyleSettings? = null,
    overlayCues: List<Cue> = emptyList(),
) {
    if (view.player !== player) {
        view.player = player
    }
    if (subtitleStyle != null) {
        view.subtitleView?.let {
            applySubtitleViewStyle(
                subtitleView = it,
                subtitleStyle = subtitleStyle,
                burnInProtection = BurnInProtectionState.DISABLED,
            )
        }
        view.ensureExternalSubtitleOverlay()?.let {
            applySubtitleViewStyle(
                subtitleView = it,
                subtitleStyle = subtitleStyle,
                burnInProtection = BurnInProtectionState.DISABLED,
            )
        }
    }
    // Render trailer cues into the external overlay (same pattern as the
    // stream player's addon subtitle overlay). The native subtitleView is
    // hidden whenever we own the overlay so we never get dual-rendering.
    view.ensureExternalSubtitleOverlay()?.let { overlay ->
        val hasCues = overlayCues.isNotEmpty()
        overlay.visibility = if (hasCues) android.view.View.VISIBLE else android.view.View.GONE
        overlay.setCues(overlayCues)
        view.subtitleView?.visibility = if (hasCues) {
            android.view.View.INVISIBLE
        } else {
            android.view.View.VISIBLE
        }
    }
}

internal fun shouldPrepareTrailerPlayback(
    lifecycleState: Lifecycle.State,
    isPlaying: Boolean,
    trailerUrl: String?
): Boolean {
    return lifecycleState == Lifecycle.State.RESUMED && isPlaying && !trailerUrl.isNullOrBlank()
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun TrailerPlayer(
    trailerUrl: String?,
    trailerAudioUrl: String? = null,
    isPlaying: Boolean,
    onEnded: () -> Unit,
    onFirstFrameRendered: () -> Unit = {},
    onError: () -> Unit = {},
    muted: Boolean = false,
    seekRequestToken: Int = 0,
    seekDeltaMs: Long = 0L,
    onProgressChanged: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> },
    onRemoteKey: (keyCode: Int, action: Int, repeatCount: Int) -> Boolean = { _, _, _ -> false },
    cropToFill: Boolean = false,
    overscanZoom: Float = 1f,
    trailerUserAgent: String? = null,
    trailerSigningClientKey: String? = null,
    trailerCaptions: List<YouTubeCaptionTrack> = emptyList(),
    modifier: Modifier = Modifier,
    enter: EnterTransition = fadeIn(animationSpec = tween(800)),
    exit: ExitTransition = fadeOut(animationSpec = tween(500))
) {
    val context = LocalContext.current
    val navLifecycleOwner = LocalLifecycleOwner.current
    val playerSettingsDataStore = remember(context) { TrailerSubtitlePrefAccess.from(context) }
    val playerSettingsSnapshot by playerSettingsDataStore.playerSettings
        .collectAsStateWithLifecycle(initialValue = null)
    val preferredSubtitleLanguage = playerSettingsSnapshot?.subtitleStyle?.preferredLanguage
    val subtitleStyleForView = playerSettingsSnapshot?.subtitleStyle
    val subtitleCache = remember(context) { TrailerSubtitleCacheAccess.from(context) }

    // App-controlled subtitle overlay — mirrors the stream player's
    // AddonSubtitleOverlay design. Parse the SRT into TimedAddonCueGroups
    // in-memory; a polling coroutine selects active cues at the player's
    // current position and pushes them into the PlayerView's external
    // subtitle overlay (see ensureExternalSubtitleOverlay in PlayerScreen).
    // Media3's text track is left empty (no SubtitleConfiguration on the
    // MediaItem) so there's no MergingMediaSource sideload to block
    // playback start, no TextRenderer cue queue to dual-render, and the
    // translated cue list cleanly replaces source on swap.
    val overlayAccess = remember(context) { TrailerSubtitleOverlayAccess.from(context) }
    val translationSettings by overlayAccess
        .subtitleTranslationSettingsDataStore()
        .settings
        .collectAsStateWithLifecycle(initialValue = SubtitleTranslationSettings())
    val translationService = remember(overlayAccess) { overlayAccess.subtitleTranslationService() }
    val targetLanguage = preferredSubtitleLanguage
        ?.takeIf { it.isNotBlank() && !it.equals("off", true) && !it.equals("none", true) }

    var cueGroups by remember(trailerCaptions, preferredSubtitleLanguage) {
        mutableStateOf<List<TimedAddonCueGroup>>(emptyList())
    }
    var overlayCues by remember(trailerCaptions, preferredSubtitleLanguage) {
        mutableStateOf<List<Cue>>(emptyList())
    }
    val currentOverlayCues by rememberUpdatedState(overlayCues)

    LaunchedEffect(trailerCaptions, preferredSubtitleLanguage) {
        overlayCues = emptyList()
        cueGroups = emptyList()
        val selected = pickTrailerCaptionTrack(trailerCaptions, preferredSubtitleLanguage)
            ?.copy(translateTo = null)
            ?: return@LaunchedEffect
        val cachedUri = subtitleCache.ensure(selected) ?: return@LaunchedEffect
        Log.d(TAG, "subtitle ready lang=${selected.languageCode} uri=$cachedUri")
        val sourceCueGroups = parseTrailerSubtitleCueGroups(cachedUri)
        if (sourceCueGroups.isEmpty()) {
            Log.d(TAG, "subtitle parse produced zero cue groups")
            return@LaunchedEffect
        }
        // Show source-language cues immediately.
        cueGroups = sourceCueGroups

        // If AI translation is configured and the target language differs
        // from the source, translate in the background and swap cueGroups
        // when the translation arrives. The poll loop on (cueGroups,
        // trailerPlayer) picks up the swap automatically — no player
        // re-prepare, no playback restart.
        val resolvedTarget = targetLanguage
        if (resolvedTarget != null &&
            !resolvedTarget.equals(selected.languageCode, ignoreCase = true) &&
            translationSettings.enabled &&
            translationSettings.apiKey.isNotBlank()
        ) {
            val translated = translateTrailerCueGroups(
                service = translationService,
                settings = translationSettings,
                sourceLanguageCode = selected.languageCode,
                targetLanguageCode = resolvedTarget,
                cueGroups = sourceCueGroups
            )
            if (translated !== sourceCueGroups) {
                Log.d(TAG, "translated cueGroups ready (size=${translated.size})")
                cueGroups = translated
            }
        }
    }
    val lifecycleOwner = remember(context, navLifecycleOwner) {
        context.findLifecycleOwner() ?: navLifecycleOwner
    }
    var lifecycleState by remember(lifecycleOwner) { mutableStateOf(lifecycleOwner.lifecycle.currentState) }
    val currentIsPlaying by rememberUpdatedState(isPlaying)
    val currentTrailerUrl by rememberUpdatedState(trailerUrl)
    val currentTrailerAudioUrl by rememberUpdatedState(trailerAudioUrl)
    val currentOnEnded by rememberUpdatedState(onEnded)
    val currentOnFirstFrameRendered by rememberUpdatedState(onFirstFrameRendered)
    val currentOnError by rememberUpdatedState(onError)
    val currentOnProgressChanged by rememberUpdatedState(onProgressChanged)
    val currentOnRemoteKey by rememberUpdatedState(onRemoteKey)
    val zoomScale = if (cropToFill) overscanZoom.coerceAtLeast(1f) else 1f
    var hasRenderedFirstFrame by remember(trailerUrl) { mutableStateOf(false) }
    val playerAlpha by animateFloatAsState(
        targetValue = if (isPlaying && hasRenderedFirstFrame) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "trailerFirstFrameAlpha"
    )

    val trailerPlayer = remember(trailerUrl, trailerAudioUrl) {
        if (trailerUrl != null) {
            // Trailers are short (~30s–2min) and rarely seek; the previous
            // 30s/120s/5s/10s defaults caused DefaultAllocator to preallocate
            // ~10 MiB of upstream byte[] buffers per ExoPlayer instance. With
            // 2 concurrent trailer composables on home (hero + focused card),
            // that's ~20 MiB on the home screen (heap-confirmed 2026-05-11).
            // Tighter durations + an explicit 2 MiB target buffer cap drop
            // per-player preallocation to <2 MiB without affecting trailer UX.
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 5_000,
                    /* maxBufferMs = */ 15_000,
                    /* bufferForPlaybackMs = */ 1_500,
                    /* bufferForPlaybackAfterRebufferMs = */ 3_000
                )
                .setTargetBufferBytes(2 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build()
            ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                .build()
                .apply {
                    repeatMode = Player.REPEAT_MODE_OFF
                    volume = if (muted) 0f else 1f
                    videoScalingMode = if (cropToFill) {
                        C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                    } else {
                        C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                    }
                    // Trailers are short (~30s). The default ABR estimator
                    // (DefaultBandwidthMeter) starts conservatively (~700
                    // kbps) and ramps up over time — for a short trailer
                    // it never reaches a high variant before playback ends.
                    // Force the highest available video variant up front;
                    // the 2 MiB target buffer cap on the LoadControl above
                    // bounds memory. Also disable text tracks because we
                    // render subtitles via the app-controlled overlay
                    // pipeline (TrailerSubtitleOverlay), not Media3's
                    // TextRenderer.
                    trackSelectionParameters = trackSelectionParameters.buildUpon()
                        .setForceHighestSupportedBitrate(true)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                }
        } else {
            null
        }
    }

    // Poll player position and update overlayCues from the current cueGroups.
    // The loop re-runs whenever cueGroups changes (e.g. source → translated
    // swap) so the overlay picks up the new language without re-prepare.
    LaunchedEffect(trailerPlayer, cueGroups) {
        val player = trailerPlayer ?: return@LaunchedEffect
        if (cueGroups.isEmpty()) {
            overlayCues = emptyList()
            return@LaunchedEffect
        }
        pollTrailerActiveCues(player, cueGroups) { active ->
            overlayCues = active
        }
    }
    var isBuffering by remember(trailerPlayer) { mutableStateOf(false) }
    DisposableEffect(trailerPlayer) {
        val player = trailerPlayer ?: return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
            }
        }
        isBuffering = player.playbackState == Player.STATE_BUFFERING
        player.addListener(listener)
        onDispose {
            runCatching { player.removeListener(listener) }
        }
    }
    val shouldKeepScreenOn = shouldKeepScreenOnForTrailer(
        isPlaying = isPlaying,
        isBuffering = isBuffering
    )
    val releaseCalled = remember(trailerPlayer) { AtomicBoolean(false) }

    fun buildTrailerMediaSourceFactory(
        videoUrl: String,
        audioUrl: String?
    ): DefaultMediaSourceFactory {
        // The trailer source's userAgent matches the client that signed the
        // video URL (iOS app UA when the HLS manifest came from the iOS
        // player response). We need this UA on googlevideo.com segment
        // fetches — but NOT on youtube.com/api/timedtext fetches. The
        // timedtext endpoint sees web origin/referer paired with the iOS
        // app UA as a contradictory fingerprint and returns 429 (the iOS
        // app never fetches timedtext, so a web-shaped request with an
        // iOS UA is anti-abuse-suspicious).
        val signedClientUserAgent = trailerUserAgent
            ?.takeIf { it.isNotBlank() }
            ?: YOUTUBE_STABLE_WEB_USER_AGENT
        val signedClientProfile = when (trailerSigningClientKey) {
            "ios" -> YouTubeWireProfile.IOS
            "android" -> YouTubeWireProfile.ANDROID
            else -> YouTubeWireProfile.WEB
        }
        val signedClientProperties = buildYouTubeWireProperties(
            profile = signedClientProfile,
            userAgent = signedClientUserAgent
        )
        // For non-googlevideo.com hosts (timedtext, other youtube.com
        // endpoints), use a clean web fingerprint: web profile (origin/
        // referer/accept-language) paired with the stable web Chrome UA.
        // No carryover of the iOS app UA.
        val webProperties = buildYouTubeWireProperties(
            profile = YouTubeWireProfile.WEB,
            userAgent = YOUTUBE_STABLE_WEB_USER_AGENT
        )
        val resolver = ResolvingDataSource.Resolver { dataSpec ->
            val host = dataSpec.uri.host.orEmpty()
            val properties = if (host.contains("googlevideo.com")) {
                signedClientProperties
            } else {
                webProperties
            }
            dataSpec.withRequestHeaders(properties)
        }
        // Pass-through for the upstream HTTP factory: use the signed-client
        // UA as the default (matches googlevideo.com expectations); the
        // resolver overrides per-host for non-googlevideo URIs.
        val effectiveUserAgent = signedClientUserAgent
        // Sideloaded subtitles arrive as file:// URIs (TrailerSubtitleCache
        // writes SRT files under cacheDir). DefaultHttpDataSource crashes
        // with ClassCastException when handed a file:// URL because it casts
        // unconditionally to HttpURLConnection. Wrap the upstream HTTP
        // factory in DefaultDataSource.Factory so file://, asset://, and
        // content:// dispatch to their own sources while https:// continues
        // through the HTTP factory (+ our per-host resolver).
        return if (shouldUseChunkedTrailerDataSource(videoUrl, audioUrl)) {
            DefaultMediaSourceFactory(
                DefaultDataSource.Factory(
                    context,
                    ResolvingDataSource.Factory(
                        YoutubeChunkedDataSourceFactory(
                            userAgent = effectiveUserAgent,
                            requestProperties = signedClientProperties
                        ),
                        resolver
                    )
                )
            )
        } else {
            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(effectiveUserAgent)
                .setAllowCrossProtocolRedirects(true)
            DefaultMediaSourceFactory(
                DefaultDataSource.Factory(
                    context,
                    ResolvingDataSource.Factory(httpFactory, resolver)
                )
            )
        }
    }

    fun prepareTrailerMediaSource(
        player: ExoPlayer,
        videoUrl: String,
        audioUrl: String?
    ) {
        val mediaSourceFactory = buildTrailerMediaSourceFactory(videoUrl, audioUrl)
        val videoMediaItem = MediaItem.fromUri(videoUrl)
        if (!audioUrl.isNullOrBlank()) {
            val videoSource = mediaSourceFactory.createMediaSource(videoMediaItem)
            val audioSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(audioUrl))
            player.setMediaSource(MergingMediaSource(videoSource, audioSource))
        } else {
            player.setMediaSource(mediaSourceFactory.createMediaSource(videoMediaItem))
        }
    }

    LaunchedEffect(isPlaying, trailerUrl, trailerAudioUrl, muted, lifecycleState) {
        val player = trailerPlayer ?: return@LaunchedEffect
        player.volume = if (muted) 0f else 1f
        if (shouldPrepareTrailerPlayback(lifecycleState, isPlaying, trailerUrl)) {
            FrameRateUtils.blockDisplayModeChangesForNonPlayerPlayback()
            hasRenderedFirstFrame = false
            prepareTrailerMediaSource(player, trailerUrl!!, trailerAudioUrl)
            player.prepare()
            player.playWhenReady = true
        } else {
            hasRenderedFirstFrame = false
            player.stop()
            player.clearMediaItems()
        }
    }

    LaunchedEffect(trailerPlayer, cropToFill) {
        val player = trailerPlayer ?: return@LaunchedEffect
        player.videoScalingMode = if (cropToFill) {
            C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        } else {
            C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        }
    }

    LaunchedEffect(seekRequestToken, seekDeltaMs, trailerPlayer) {
        val player = trailerPlayer ?: return@LaunchedEffect
        if (seekRequestToken <= 0) return@LaunchedEffect
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        val current = player.currentPosition
        val target = (current + seekDeltaMs).coerceIn(0L, duration.coerceAtLeast(0L))
        player.seekTo(target)
    }

    LaunchedEffect(trailerPlayer, isPlaying) {
        val player = trailerPlayer ?: return@LaunchedEffect
        while (isPlaying) {
            val position = player.currentPosition.coerceAtLeast(0L)
            val duration = player.duration.takeIf { it > 0 } ?: 0L
            currentOnProgressChanged(position, duration)
            delay(250)
        }
        currentOnProgressChanged(0L, 0L)
    }

    DisposableEffect(lifecycleOwner, trailerPlayer) {
        val player = trailerPlayer ?: return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    currentOnEnded()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(
                    TAG,
                    "Trailer playback failed code=${error.errorCodeName} " +
                        "video=${currentTrailerUrl.orEmpty()} audio=${currentTrailerAudioUrl.orEmpty()}",
                    error
                )
                currentOnError()
            }

            override fun onRenderedFirstFrame() {
                hasRenderedFirstFrame = true
                currentOnFirstFrameRendered()
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            lifecycleState = lifecycleOwner.lifecycle.currentState
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (
                        shouldPrepareTrailerPlayback(
                            lifecycleState = lifecycleOwner.lifecycle.currentState,
                            isPlaying = currentIsPlaying,
                            trailerUrl = currentTrailerUrl
                        )
                    ) {
                        FrameRateUtils.blockDisplayModeChangesForNonPlayerPlayback()
                        if (player.currentMediaItem == null) {
                            prepareTrailerMediaSource(
                                player,
                                currentTrailerUrl!!,
                                currentTrailerAudioUrl
                            )
                            player.prepare()
                        }
                        player.playWhenReady = true
                    }
                }
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> {
                    player.playWhenReady = false
                    player.pause()
                    player.stop()
                    player.clearMediaItems()
                }
                Lifecycle.Event.ON_DESTROY -> {
                    if (releaseCalled.compareAndSet(false, true)) {
                        runCatching { player.stop() }
                        runCatching { player.clearMediaItems() }
                        runCatching { player.release() }
                    }
                }
                else -> Unit
            }
        }
        player.addListener(listener)
        lifecycleOwner.lifecycle.addObserver(observer)
        lifecycleState = lifecycleOwner.lifecycle.currentState
        onDispose {
            runCatching { lifecycleOwner.lifecycle.removeObserver(observer) }
            runCatching { player.removeListener(listener) }
            if (releaseCalled.compareAndSet(false, true)) {
                runCatching { player.stop() }
                runCatching { player.clearMediaItems() }
                runCatching { player.release() }
            }
        }
    }

    if (trailerPlayer != null) {
        AnimatedVisibility(
            visible = isPlaying,
            enter = enter,
            exit = exit
        ) {
            AndroidView(
                factory = { ctx ->
                    (LayoutInflater.from(ctx)
                        .inflate(R.layout.exo_trailer_player_view, null, false) as PlayerView)
                        .apply {
                            bindTrailerPlayerView(this, trailerPlayer, subtitleStyleForView, currentOverlayCues)
                            useController = false
                            isFocusable = true
                            isFocusableInTouchMode = true
                            setOnKeyListener { _, keyCode, event ->
                                if (shouldConsumeTrailerKey(keyCode)) return@setOnKeyListener true
                                currentOnRemoteKey(keyCode, event.action, event.repeatCount)
                            }
                            keepScreenOn = shouldKeepScreenOn
                            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                            resizeMode = if (cropToFill) {
                                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            } else {
                                AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                        }
                },
                update = { view ->
                    bindTrailerPlayerView(view, trailerPlayer, subtitleStyleForView, currentOverlayCues)
                    view.keepScreenOn = shouldKeepScreenOn
                    view.resizeMode = if (cropToFill) {
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    } else {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                modifier = modifier
                    .clipToBounds()
                    .graphicsLayer {
                        alpha = playerAlpha
                        scaleX = zoomScale
                        scaleY = zoomScale
                    }
            )
        }
    }
}

