package com.nexio.tv.data.repository

internal sealed interface AssSsaTextToken {
    val raw: String

    data class Text(override val raw: String) : AssSsaTextToken
    data class OverrideBlock(override val raw: String) : AssSsaTextToken
    data class LineBreak(override val raw: String) : AssSsaTextToken
    data class HardSpace(override val raw: String) : AssSsaTextToken
    data class Drawing(override val raw: String) : AssSsaTextToken
    data class Malformed(override val raw: String) : AssSsaTextToken
}

internal object AssSsaTextTokenizer {
    fun tokenize(text: String): List<AssSsaTextToken> {
        val tokens = mutableListOf<AssSsaTextToken>()
        var drawingMode = 0
        var index = 0

        fun addPlain(raw: String) {
            if (raw.isEmpty()) return
            if (drawingMode > 0) {
                tokens += AssSsaTextToken.Drawing(raw)
                return
            }

            var cursor = 0
            val escapeRegex = Regex("""\\[Nnh]""")
            for (match in escapeRegex.findAll(raw)) {
                if (match.range.first > cursor) {
                    tokens += AssSsaTextToken.Text(raw.substring(cursor, match.range.first))
                }
                val escape = match.value
                tokens += when (escape) {
                    """\N""",
                    """\n""" -> AssSsaTextToken.LineBreak(escape)
                    """\h""" -> AssSsaTextToken.HardSpace(escape)
                    else -> AssSsaTextToken.Text(escape)
                }
                cursor = match.range.last + 1
            }
            if (cursor < raw.length) {
                tokens += AssSsaTextToken.Text(raw.substring(cursor))
            }
        }

        while (index < text.length) {
            val blockStart = text.indexOf('{', startIndex = index)
            if (blockStart < 0) {
                addPlain(text.substring(index))
                break
            }
            if (blockStart > index) {
                addPlain(text.substring(index, blockStart))
            }
            val blockEnd = text.indexOf('}', startIndex = blockStart + 1)
            if (blockEnd < 0) {
                tokens += AssSsaTextToken.Malformed(text.substring(blockStart))
                break
            }
            val block = text.substring(blockStart, blockEnd + 1)
            drawingMode = assSsaDrawingModeAfterOverrideBlock(block, drawingMode)
            tokens += AssSsaTextToken.OverrideBlock(block)
            index = blockEnd + 1
        }

        return tokens.mergeAdjacentTextLikeTokens()
    }
}

private fun List<AssSsaTextToken>.mergeAdjacentTextLikeTokens(): List<AssSsaTextToken> {
    if (isEmpty()) return this
    val merged = mutableListOf<AssSsaTextToken>()
    for (token in this) {
        val previous = merged.lastOrNull()
        if (previous is AssSsaTextToken.Text && token is AssSsaTextToken.Text) {
            merged[merged.lastIndex] = AssSsaTextToken.Text(previous.raw + token.raw)
        } else if (previous is AssSsaTextToken.Drawing && token is AssSsaTextToken.Drawing) {
            merged[merged.lastIndex] = AssSsaTextToken.Drawing(previous.raw + token.raw)
        } else {
            merged += token
        }
    }
    return merged
}

internal fun assSsaDrawingModeAfterOverrideBlock(block: String, current: Int): Int {
    var drawingMode = current
    val inner = block.removePrefix("{").removeSuffix("}")
    var index = 0
    while (index < inner.length) {
        val slash = inner.indexOf('\\', startIndex = index)
        if (slash < 0 || slash == inner.lastIndex) break
        val nameStart = slash + 1
        if (inner[nameStart] == 'p') {
            val argStart = nameStart + 1
            if (argStart >= inner.length || !inner[argStart].isLetter()) {
                var argEnd = argStart
                while (argEnd < inner.length && inner[argEnd] != '\\') {
                    argEnd += 1
                }
                inner.substring(argStart, argEnd).trim().toIntOrNull()?.let { value ->
                    drawingMode = value
                }
                index = argEnd
                continue
            }
        }
        var nameEnd = nameStart
        while (nameEnd < inner.length && inner[nameEnd].isLetterOrDigit()) {
            nameEnd += 1
        }
        index = nameEnd
    }
    return drawingMode
}
