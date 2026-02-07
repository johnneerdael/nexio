package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.components.FadeInAsyncImage
import com.nuvio.tv.ui.components.LoadingIndicator

@Composable
fun LoadingOverlay(
    visible: Boolean,
    backdropUrl: String?,
    logoUrl: String?,
    modifier: Modifier = Modifier
) {
    val logoAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 700, delayMillis = 400, easing = LinearEasing),
        label = "loadingLogoAlpha"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "loadingLogoPulse")
    val logoScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "loadingLogoScale"
    )

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(250)),
        exit = fadeOut(animationSpec = tween(200)),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (!backdropUrl.isNullOrBlank()) {
                FadeInAsyncImage(
                    model = backdropUrl,
                    contentDescription = "Loading backdrop",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to Color(0x4D000000),
                                0.35f to Color(0x99000000),
                                0.7f to Color(0xCC000000),
                                1f to Color(0xE6000000)
                            )
                        )
                    )
            )

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (!logoUrl.isNullOrBlank()) {
                    FadeInAsyncImage(
                        model = logoUrl,
                        contentDescription = "Loading logo",
                        modifier = Modifier
                            .width(320.dp)
                            .height(180.dp)
                            .graphicsLayer {
                                alpha = logoAlpha
                                scaleX = logoScale
                                scaleY = logoScale
                            },
                        contentScale = ContentScale.Fit
                    )
                } else {
                    LoadingIndicator()
                }
            }
        }
    }
}
