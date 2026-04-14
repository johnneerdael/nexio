package com.nexio.tv.ui.screens.player

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.text.Cue
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.nexio.tv.data.local.SubtitleStyleSettings

internal data class PlayerSurfaceRenderState(
    val resizeMode: Int,
    val subtitleStyle: SubtitleStyleSettings,
    val overlayCues: List<Cue>,
    val suppressNativeSubtitles: Boolean,
    val keepScreenOn: Boolean = false
)

internal data class PlayerViewMutationPlan(
    val updateResizeMode: Boolean,
    val updateSubtitleStyle: Boolean,
    val updateOverlay: Boolean,
    val updateKeepScreenOn: Boolean
)

internal fun resolveOverlayCues(
    useAiOverlay: Boolean,
    translatedBuiltInCues: List<Cue>,
    addonOverlayCues: List<Cue>
): List<Cue> {
    return when {
        addonOverlayCues.isNotEmpty() -> addonOverlayCues
        useAiOverlay && translatedBuiltInCues.isNotEmpty() -> translatedBuiltInCues
        else -> emptyList()
    }
}

internal fun buildPlayerViewMutationPlan(
    previous: PlayerSurfaceRenderState?,
    current: PlayerSurfaceRenderState
): PlayerViewMutationPlan {
    return PlayerViewMutationPlan(
        updateResizeMode = previous?.resizeMode != current.resizeMode,
        updateSubtitleStyle = previous?.subtitleStyle != current.subtitleStyle,
        updateOverlay = previous?.overlayCues != current.overlayCues ||
            previous.suppressNativeSubtitles != current.suppressNativeSubtitles,
        updateKeepScreenOn = previous?.keepScreenOn != current.keepScreenOn
    )
}

internal fun enableComposeSurfaceSyncWorkaroundIfAvailable(target: Any): Boolean {
    return runCatching {
        target.javaClass
            .getMethod("setEnableComposeSurfaceSyncWorkaround", java.lang.Boolean.TYPE)
            .invoke(target, true)
        true
    }.getOrDefault(false)
}

@Composable
internal fun PlayerVideoSurface(
    player: ExoPlayer,
    renderState: PlayerSurfaceRenderState,
    modifier: Modifier = Modifier
) {
    var lastAppliedState by remember(player) {
        mutableStateOf<PlayerSurfaceRenderState?>(null)
    }

    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                this.player = player
                useController = false
                keepScreenOn = renderState.keepScreenOn
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                enableComposeSurfaceSyncWorkaroundIfAvailable(this)
            }
        },
        update = { playerView ->
            if (playerView.player !== player) {
                playerView.player = player
            }

            val plan = buildPlayerViewMutationPlan(lastAppliedState, renderState)
            if (plan.updateResizeMode) {
                playerView.resizeMode = renderState.resizeMode
            }
            if (plan.updateSubtitleStyle) {
                playerView.subtitleView?.let { applySubtitleStyle(it, renderState.subtitleStyle) }
                playerView.ensureExternalSubtitleOverlay()?.let {
                    applySubtitleStyle(it, renderState.subtitleStyle)
                }
            }
            if (plan.updateOverlay) {
                playerView.ensureExternalSubtitleOverlay()?.let { subtitleOverlay ->
                    val hasCues = renderState.overlayCues.isNotEmpty()
                    subtitleOverlay.visibility = if (hasCues) View.VISIBLE else View.GONE
                    subtitleOverlay.setCues(renderState.overlayCues)
                    playerView.subtitleView?.visibility =
                        if (hasCues || renderState.suppressNativeSubtitles) {
                            View.INVISIBLE
                        } else {
                            View.VISIBLE
                        }
                }
            }
            if (plan.updateKeepScreenOn) {
                playerView.keepScreenOn = renderState.keepScreenOn
            }

            lastAppliedState = renderState
        },
        modifier = modifier
    )
}
