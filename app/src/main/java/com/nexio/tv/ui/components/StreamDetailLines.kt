package com.nexio.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

private const val FilenameLinePrefix = "📄 "

@Composable
fun StreamDetailLines(
    detailLines: List<String>,
    colorStyle: TextStyle
) {
    detailLines.forEachIndexed { index, detail ->
        InlineIconText(
            text = detail,
            style = streamDetailLineStyle(
                detailLines = detailLines,
                index = index,
                baseStyle = colorStyle
            ),
            maxLines = if (index == detailLines.lastIndex) 1 else 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

internal fun streamDetailLineStyle(
    detailLines: List<String>,
    index: Int,
    baseStyle: TextStyle
): TextStyle {
    if (!shouldCompactFinalFilenameLine(detailLines, index)) return baseStyle
    return baseStyle.copy(fontSize = 7.sp, lineHeight = 10.sp)
}

internal fun shouldCompactFinalFilenameLine(
    detailLines: List<String>,
    index: Int
): Boolean {
    val detail = detailLines.getOrNull(index)?.trim().orEmpty()
    return detail.startsWith(FilenameLinePrefix)
}
