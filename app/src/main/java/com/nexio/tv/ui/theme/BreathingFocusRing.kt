package com.nexio.tv.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color

private const val BREATHING_PERIOD_MS = 3500
private const val MIN_ALPHA = 0.7f
private const val MAX_ALPHA = 1.0f

/** R3: alpha-breathing focus ring keeps saturated borders from aging OLED subpixels. */
@Composable
fun rememberBreathingFocusRing(base: Color = NexioColors.FocusRing): Color {
    val transition = rememberInfiniteTransition(label = "focusRingBreathing")
    val alpha by transition.animateFloat(
        initialValue = MIN_ALPHA,
        targetValue = MAX_ALPHA,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = BREATHING_PERIOD_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "focusRingAlpha"
    )
    return base.copy(alpha = alpha)
}
