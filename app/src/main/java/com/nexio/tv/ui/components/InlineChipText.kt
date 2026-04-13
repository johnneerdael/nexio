package com.nexio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexio.tv.R
import com.nexio.tv.ui.theme.NexioColors
import com.nexio.tv.ui.theme.NexioTheme
import java.util.Locale

internal sealed interface InlineChipSegment {
    data class TextSegment(val text: String) : InlineChipSegment
    data class ChipSegment(val kind: StreamBadgeKind) : InlineChipSegment
}

internal object InlineChipTokenRegistry {
    private val tokenPattern = Regex("""\[\[chip:([a-z0-9_]+)]]""", RegexOption.IGNORE_CASE)

    fun containsToken(text: String): Boolean = tokenPattern.containsMatchIn(text)

    fun tokenize(text: String): List<InlineChipSegment> {
        if (text.isEmpty()) return emptyList()

        val segments = mutableListOf<InlineChipSegment>()
        var cursor = 0
        tokenPattern.findAll(text).forEach { match ->
            if (match.range.first > cursor) {
                segments.appendText(text.substring(cursor, match.range.first))
            }

            val token = match.groupValues[1]
            val kind = streamBadgeKindForToken(token)
            if (kind != null) {
                segments += InlineChipSegment.ChipSegment(kind)
            } else {
                segments.appendText(token)
            }
            cursor = match.range.last + 1
        }

        if (cursor < text.length) {
            segments.appendText(text.substring(cursor))
        }
        return segments
    }

    private fun MutableList<InlineChipSegment>.appendText(value: String) {
        if (value.isEmpty()) return

        val previous = lastOrNull()
        if (previous is InlineChipSegment.TextSegment) {
            this[lastIndex] = previous.copy(text = previous.text + value)
        } else {
            add(InlineChipSegment.TextSegment(value))
        }
    }

    private fun streamBadgeKindForToken(token: String): StreamBadgeKind? {
        return when (token.trim().lowercase(Locale.US)) {
            "cached" -> StreamBadgeKind.CACHED
            "torrent" -> StreamBadgeKind.TORRENT
            "youtube" -> StreamBadgeKind.YOUTUBE
            "external" -> StreamBadgeKind.EXTERNAL
            else -> null
        }
    }
}

@Composable
internal fun InlineChipText(
    text: String,
    style: TextStyle,
    maxLines: Int,
    modifier: Modifier = Modifier,
    overflow: TextOverflow = TextOverflow.Clip
) {
    val segments = remember(text) { InlineChipTokenRegistry.tokenize(text) }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        segments.forEach { segment ->
            when (segment) {
                is InlineChipSegment.TextSegment -> {
                    if (segment.text.isNotEmpty()) {
                        InlineIconText(
                            text = segment.text,
                            style = style,
                            maxLines = maxLines,
                            overflow = overflow
                        )
                    }
                }
                is InlineChipSegment.ChipSegment -> StreamTypeChip(segment.kind)
            }
        }
    }
}

@Composable
internal fun FormatterBadgeRow(
    text: String,
    modifier: Modifier = Modifier
) {
    val segments = remember(text) { InlineChipTokenRegistry.tokenize(text) }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        segments.forEach { segment ->
            when (segment) {
                is InlineChipSegment.TextSegment -> {
                    val label = segment.text.trim()
                    if (label.isNotEmpty()) {
                        InlineIconText(
                            text = label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = NexioTheme.extendedColors.textSecondary
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                is InlineChipSegment.ChipSegment -> StreamTypeChip(segment.kind)
            }
        }
    }
}

@Composable
internal fun StreamTypeChip(kind: StreamBadgeKind) {
    StreamTypeChip(
        text = when (kind) {
            StreamBadgeKind.CACHED -> stringResource(R.string.stream_type_cached)
            StreamBadgeKind.TORRENT -> stringResource(R.string.stream_type_torrent)
            StreamBadgeKind.YOUTUBE -> stringResource(R.string.stream_type_youtube)
            StreamBadgeKind.EXTERNAL -> stringResource(R.string.stream_type_external)
        },
        color = when (kind) {
            StreamBadgeKind.CACHED -> NexioColors.Success
            StreamBadgeKind.TORRENT -> NexioColors.Secondary
            StreamBadgeKind.YOUTUBE -> Color(0xFFFF0000)
            StreamBadgeKind.EXTERNAL -> NexioColors.Primary
        }
    )
}

@Composable
internal fun StreamTypeChip(
    text: String,
    color: Color
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.2f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}
