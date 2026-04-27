package com.nexio.tv.ui.screens.player

import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.text.Cue
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.nexio.tv.core.player.BurnInProtectionState
import com.nexio.tv.data.local.SubtitleStyleSettings
import com.nexio.tv.ui.screens.player.ass.AssSsaRenderOverlayView

internal const val ASS_SSA_RENDER_OVERLAY_TAG = "ass-ssa-render-overlay"

internal data class PlayerSurfaceRenderState(
    val resizeMode: Int,
    val subtitleStyle: SubtitleStyleSettings,
    val burnInProtection: BurnInProtectionState,
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
        updateSubtitleStyle = previous?.subtitleStyle != current.subtitleStyle ||
            previous?.burnInProtection != current.burnInProtection,
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

internal fun PlayerView.ensureAssSsaRenderOverlay(): AssSsaRenderOverlayView? {
    val overlayFrameLayout = overlayFrameLayout ?: return null
    val existing = overlayFrameLayout.findViewWithTag<AssSsaRenderOverlayView>(
        ASS_SSA_RENDER_OVERLAY_TAG
    )
    if (existing != null) {
        return existing
    }

    return AssSsaRenderOverlayView(context).apply {
        tag = ASS_SSA_RENDER_OVERLAY_TAG
        visibility = View.VISIBLE
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        overlayFrameLayout.addView(this)
    }
}

@Composable
internal fun PlayerVideoSurface(
    player: ExoPlayer,
    renderState: PlayerSurfaceRenderState,
    modifier: Modifier = Modifier,
    assSsaRenderOverlayProvider: (((() -> AssSsaRenderOverlayView?)?) -> Unit)? = null
) {
    var lastAppliedState by remember(player) {
        mutableStateOf<PlayerSurfaceRenderState?>(null)
    }
    var assSsaOverlayView by remember(player) {
        mutableStateOf<AssSsaRenderOverlayView?>(null)
    }

    DisposableEffect(player, assSsaRenderOverlayProvider) {
        assSsaRenderOverlayProvider?.invoke { assSsaOverlayView }
        onDispose {
            assSsaOverlayView = null
            assSsaRenderOverlayProvider?.invoke(null)
        }
    }

    // The runtime controller creates AssSsaRenderController before Compose runs the
    // AndroidView update block (where ensureAssSsaRenderOverlay actually attaches the
    // overlay to the PlayerView). Re-push the provider whenever assSsaOverlayView
    // flips so setOverlayView() fires as soon as attachment completes, instead of
    // waiting for an unrelated renderState change to recompose the AndroidView.
    LaunchedEffect(assSsaOverlayView, assSsaRenderOverlayProvider) {
        if (assSsaOverlayView != null) {
            assSsaRenderOverlayProvider?.invoke { assSsaOverlayView }
        }
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
            if (assSsaRenderOverlayProvider != null && assSsaOverlayView == null) {
                assSsaOverlayView = playerView.ensureAssSsaRenderOverlay()
            }

            val plan = buildPlayerViewMutationPlan(lastAppliedState, renderState)
            if (plan.updateResizeMode) {
                playerView.resizeMode = renderState.resizeMode
            }
            if (plan.updateSubtitleStyle) {
                playerView.subtitleView?.let {
                    applySubtitleStyle(it, renderState.subtitleStyle, renderState.burnInProtection)
                }
                playerView.ensureExternalSubtitleOverlay()?.let {
                    applySubtitleStyle(it, renderState.subtitleStyle, renderState.burnInProtection)
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
