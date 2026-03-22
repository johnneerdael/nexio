package com.nexio.tv.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

@Composable
fun InlineIconText(
    text: String,
    style: TextStyle,
    maxLines: Int,
    modifier: Modifier = Modifier,
    overflow: TextOverflow = TextOverflow.Clip
) {
    val context = LocalContext.current
    val segments = remember(text) { InlineIconTokenRegistry.tokenize(text) }
    val baseFontSize = if (style.fontSize != TextUnit.Unspecified) style.fontSize else 16.sp
    val aspectRatios = remember(text, context) {
        segments
            .filterIsInstance<InlineIconSegment.IconSegment>()
            .map { it.token.drawableRes }
            .distinct()
            .associateWith { drawableRes -> loadAspectRatio(context, drawableRes) }
    }

    val annotatedText = remember(segments) {
        buildAnnotatedString {
            segments.forEachIndexed { index, segment ->
                when (segment) {
                    is InlineIconSegment.IconSegment -> appendInlineContent(id = "inline-icon-$index", alternateText = segment.token.fallbackLabel)
                    is InlineIconSegment.TextSegment -> append(segment.text)
                }
            }
        }
    }

    val inlineContent = remember(segments, aspectRatios, baseFontSize) {
        segments
            .mapIndexedNotNull { index, segment ->
                val iconSegment = segment as? InlineIconSegment.IconSegment ?: return@mapIndexedNotNull null
                val token = iconSegment.token
                val scale = when (token.scaleClass) {
                    ScaleClass.TITLE_PROMINENT -> 1.1f
                    ScaleClass.INLINE -> 1.0f
                }
                val height = baseFontSize * scale
                val width = baseFontSize * (scale * (aspectRatios[token.drawableRes] ?: 1f))
                "inline-icon-$index" to InlineTextContent(
                    placeholder = Placeholder(
                        width = width,
                        height = height,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                    )
                ) {
                    Image(
                        painter = painterResource(id = token.drawableRes),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }
            .toMap()
    }

    Text(
        text = annotatedText,
        modifier = modifier,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
        inlineContent = inlineContent
    )
}

private fun loadAspectRatio(context: Context, @DrawableRes drawableRes: Int): Float {
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    context.resources.openRawResource(drawableRes).use { input ->
        BitmapFactory.decodeStream(input, null, options)
    }
    val width = options.outWidth
    val height = options.outHeight
    return if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 1f
}
