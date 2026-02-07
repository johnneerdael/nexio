package com.nuvio.tv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun TrailerPlayer(
    trailerUrl: String?,
    isPlaying: Boolean,
    onEnded: () -> Unit,
    modifier: Modifier = Modifier,
    enter: EnterTransition = fadeIn(animationSpec = tween(800)),
    exit: ExitTransition = fadeOut(animationSpec = tween(500))
) {
    val context = LocalContext.current
    val trailerPlayer = remember(trailerUrl) {
        if (trailerUrl != null) {
            ExoPlayer.Builder(context)
                .build()
                .apply {
                    repeatMode = Player.REPEAT_MODE_OFF
                    volume = 1f
                }
        } else {
            null
        }
    }

    DisposableEffect(trailerPlayer) {
        onDispose {
            trailerPlayer?.stop()
            trailerPlayer?.clearMediaItems()
            trailerPlayer?.release()
        }
    }

    LaunchedEffect(isPlaying, trailerUrl) {
        val player = trailerPlayer ?: return@LaunchedEffect
        if (isPlaying && trailerUrl != null) {
            player.setMediaItem(MediaItem.fromUri(trailerUrl))
            player.prepare()
            player.playWhenReady = true
        } else {
            player.stop()
            player.clearMediaItems()
        }
    }

    DisposableEffect(trailerPlayer) {
        val player = trailerPlayer ?: return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    onEnded()
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
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
                    PlayerView(ctx).apply {
                        player = trailerPlayer
                        useController = false
                        keepScreenOn = true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    }
                },
                modifier = modifier
            )
        }
    }
}
