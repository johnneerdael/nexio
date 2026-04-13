package com.nexio.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

private const val FilenameLinePrefix = "📄 "
private val TextStyleLineTokenPattern = Regex("""^\[\[text:([0-9]+(?:\.[0-9]+)?)(?::([0-9]+(?:\.[0-9]+)?))?\]\]\s*""")

@Composable
fun StreamDetailLines(
    detailLines: List<String>,
    colorStyle: TextStyle
) {
    detailLines.forEachIndexed { index, detail ->
        InlineIconText(
            text = streamDetailLineDisplayText(detail),
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
    streamDetailLineTextStyleToken(detailLines.getOrNull(index).orEmpty())?.let { token ->
        return baseStyle.copy(
            fontSize = token.fontSizeSp.sp,
            lineHeight = token.lineHeightSp.sp
        )
    }
    if (!shouldCompactFinalFilenameLine(detailLines, index)) return baseStyle
    return baseStyle.copy(fontSize = 7.sp, lineHeight = 10.sp)
}

internal fun streamDetailLineDisplayText(detail: String): String {
    return detail.replaceFirst(TextStyleLineTokenPattern, "")
}

internal fun shouldCompactFinalFilenameLine(
    detailLines: List<String>,
    index: Int
): Boolean {
    val detail = detailLines.getOrNull(index)?.trim().orEmpty()
    return detail.startsWith(FilenameLinePrefix)
}

private data class TextStyleLineToken(
    val fontSizeSp: Float,
    val lineHeightSp: Float
)

private fun streamDetailLineTextStyleToken(detail: String): TextStyleLineToken? {
    val match = TextStyleLineTokenPattern.find(detail.trimStart()) ?: return null
    val fontSizeSp = match.groupValues[1].toFloatOrNull() ?: return null
    val lineHeightSp = match.groupValues[2].toFloatOrNull() ?: fontSizeSp
    return TextStyleLineToken(fontSizeSp, lineHeightSp)
}
