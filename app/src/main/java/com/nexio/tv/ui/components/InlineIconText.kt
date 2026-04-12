package com.nexio.tv.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

@Composable
fun InlineIconText(
    text: String,
    style: TextStyle,
    maxLines: Int,
    modifier: Modifier = Modifier,
    overflow: TextOverflow = TextOverflow.Clip
) {
    val appContext = LocalContext.current.applicationContext
    val segments = remember(text) { InlineIconTokenRegistry.tokenize(text) }
    val baseFontSize = if (style.fontSize != TextUnit.Unspecified) style.fontSize else 16.sp
    val knownIcons = remember(segments) {
        segments
            .filterIsInstance<InlineIconSegment.IconSegment>()
            .map { it.token }
    }
    val iconBitmaps by produceState<Map<Int, android.graphics.Bitmap>>(initialValue = InlineIconBitmapCache.snapshot(knownIcons.map { it.drawableRes }), text) {
        val icons = segments
            .filterIsInstance<InlineIconSegment.IconSegment>()
            .map { it.token.drawableRes }
            .distinct()
        value = withContext(Dispatchers.IO) {
            InlineIconBitmapCache.loadAll(appContext, icons)
        }
    }

    val annotatedText = remember(segments, iconBitmaps) {
        buildAnnotatedString {
            segments.forEachIndexed { index, segment ->
                when (segment) {
                    is InlineIconSegment.IconSegment -> {
                        if (InlineIconBitmapCache.canRender(segment.token.drawableRes)) {
                            appendInlineContent(id = "inline-icon-$index", alternateText = segment.token.fallbackLabel)
                        } else {
                            append(segment.token.fallbackLabel)
                        }
                    }
                    is InlineIconSegment.TextSegment -> append(segment.text)
                }
            }
        }
    }

    val inlineContent = remember(segments, baseFontSize, iconBitmaps) {
        segments
            .mapIndexedNotNull { index, segment ->
                val iconSegment = segment as? InlineIconSegment.IconSegment ?: return@mapIndexedNotNull null
                val token = iconSegment.token
                val scale = inlineIconScale(iconSegment)
                val height = baseFontSize * scale
                val width = baseFontSize * (scale * token.aspectRatio)
                "inline-icon-$index" to InlineTextContent(
                    placeholder = Placeholder(
                        width = width,
                        height = height,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                    )
                ) {
                    val bitmap = iconBitmaps[token.drawableRes]
                    if (bitmap != null) {
                        Image(
                            painter = BitmapPainter(bitmap.asImageBitmap()),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize())
                    }
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

internal fun inlineIconScale(iconSegment: InlineIconSegment.IconSegment): Float {
    iconSegment.scaleOverride?.let { return it }

    return when (iconSegment.token.scaleClass) {
        ScaleClass.TITLE_PROMINENT -> 1.1f
        ScaleClass.INLINE -> 1.0f
    }
}

private fun decodeBitmapResource(context: Context, drawableRes: Int): android.graphics.Bitmap? {
    return BitmapFactory.decodeResource(context.resources, drawableRes)
}

private fun <K, V : Any> Iterable<K>.associateWithNotNull(transform: (K) -> V?): Map<K, V> {
    val result = LinkedHashMap<K, V>()
    for (key in this) {
        val value = transform(key) ?: continue
        result[key] = value
    }
    return result
}

private object InlineIconBitmapCache {
    private val bitmaps = ConcurrentHashMap<Int, android.graphics.Bitmap>()
    private val cacheLock = Any()

    fun canRender(drawableRes: Int): Boolean = true

    fun snapshot(drawableResIds: List<Int>): Map<Int, android.graphics.Bitmap> {
        return drawableResIds.associateWithNotNull { bitmaps[it] }
    }

    fun loadAll(context: Context, drawableResIds: List<Int>): Map<Int, android.graphics.Bitmap> {
        drawableResIds.forEach { drawableRes ->
            if (!bitmaps.containsKey(drawableRes)) {
                synchronized(cacheLock) {
                    if (!bitmaps.containsKey(drawableRes)) {
                        val decoded = decodeBitmapResource(context, drawableRes)
                        if (decoded != null) {
                            bitmaps[drawableRes] = decoded
                        }
                    }
                }
            }
        }
        return snapshot(drawableResIds)
    }
}
