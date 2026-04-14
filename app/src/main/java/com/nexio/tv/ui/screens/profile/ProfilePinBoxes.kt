package com.nexio.tv.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.nexio.tv.ui.theme.NexioColors

/**
 * 4-dot PIN progress indicator.
 *
 * Renders 4 circles representing PIN digit entry progress. Dots are filled as
 * digits are entered. Supports error state (red highlight + shake via shakeOffset)
 * and disabled state (muted) for rate-limit lockout.
 *
 * The shake animation is driven externally via [shakeOffset] — typically an
 * [androidx.compose.animation.core.Animatable] driven from [ProfilePinOverlay].
 */
@Composable
internal fun ProfilePinBoxes(
    enteredLength: Int,
    isError: Boolean,
    isDisabled: Boolean,
    shakeOffset: Float,
    modifier: Modifier = Modifier
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.graphicsLayer { translationX = shakeOffset }
    ) {
        repeat(4) { index ->
            val isFilled = index < enteredLength

            val boxModifier = when {
                isDisabled -> Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(NexioColors.TextSecondary.copy(alpha = 0.3f))
                    .border(2.dp, NexioColors.TextSecondary.copy(alpha = 0.4f), CircleShape)

                isError -> Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(NexioColors.Error.copy(alpha = 0.3f))
                    .border(2.dp, NexioColors.Error, CircleShape)

                isFilled -> Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(NexioColors.TextPrimary)

                else -> Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(androidx.compose.ui.graphics.Color.Transparent)
                    .border(2.dp, NexioColors.TextSecondary, CircleShape)
            }

            Box(modifier = boxModifier)
        }
    }
}
