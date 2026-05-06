package com.nexio.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import coil.compose.AsyncImage

internal sealed class FallbackArtworkImageState {
    data class Image(
        val model: Any?,
        val isFallback: Boolean
    ) : FallbackArtworkImageState()

    object Placeholder : FallbackArtworkImageState()
}

internal fun fallbackArtworkImageState(
    model: Any?,
    fallbackModel: Any?,
    failedPrimary: Boolean,
    failedFallback: Boolean
): FallbackArtworkImageState {
    return when {
        model != null && !failedPrimary -> FallbackArtworkImageState.Image(
            model = model,
            isFallback = false
        )
        fallbackModel != null && !failedFallback -> FallbackArtworkImageState.Image(
            model = fallbackModel,
            isFallback = true
        )
        else -> FallbackArtworkImageState.Placeholder
    }
}

@Composable
fun FallbackArtworkImage(
    model: Any?,
    fallbackModel: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    testTag: String = "fallback-artwork-image",
    testForceError: Boolean = false
) {
    var failedPrimary by remember(model, testForceError) {
        mutableStateOf(testForceError && model != null)
    }
    var failedFallback by remember(fallbackModel) { mutableStateOf(false) }

    when (
        val state = fallbackArtworkImageState(
            model = model,
            fallbackModel = fallbackModel,
            failedPrimary = failedPrimary,
            failedFallback = failedFallback
        )
    ) {
        is FallbackArtworkImageState.Image -> {
            AsyncImage(
                model = state.model,
                contentDescription = contentDescription,
                modifier = modifier.testTag(testTag),
                contentScale = contentScale,
                onError = {
                    if (state.isFallback) {
                        failedFallback = true
                    } else {
                        failedPrimary = true
                    }
                }
            )
        }
        FallbackArtworkImageState.Placeholder -> {
            MonochromePosterPlaceholder(
                modifier = modifier.testTag("fallback-artwork-placeholder")
            )
        }
    }
}
