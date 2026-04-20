package com.nexio.tv.data.repository

internal object AssSsaTextAstParser {
    fun parse(raw: String): AssSsaTextAst {
        val nodes = mutableListOf<AssSsaTextNode>()
        var textSpanIndex = 0
        var index = 0

        fun nextTextId(): String = "txt_${(textSpanIndex++).toString().padStart(3, '0')}"

        fun addTextSpan(text: String) {
            if (text.isNotEmpty()) {
                nodes += AssSsaTextNode.TextSpan(id = nextTextId(), raw = text)
            }
        }

        fun addPlain(rawText: String) {
            var cursor = 0
            val escapeRegex = Regex("""\\[Nnh]""")
            escapeRegex.findAll(rawText).forEach { match ->
                addTextSpan(rawText.substring(cursor, match.range.first))
                when (match.value) {
                    "\\N", "\\n" -> nodes += AssSsaTextNode.LineBreak(match.value)
                    "\\h" -> nodes += AssSsaTextNode.HardSpace(match.value)
                }
                cursor = match.range.last + 1
            }
            addTextSpan(rawText.substring(cursor))
        }

        while (index < raw.length) {
            val blockStart = raw.indexOf('{', startIndex = index)
            if (blockStart < 0) {
                addPlain(raw.substring(index))
                break
            }
            addPlain(raw.substring(index, blockStart))
            val blockEnd = raw.indexOf('}', startIndex = blockStart + 1)
            if (blockEnd < 0) {
                nodes += AssSsaTextNode.Malformed(raw.substring(blockStart))
                break
            }
            val block = raw.substring(blockStart, blockEnd + 1)
            nodes += AssSsaTextNode.OverrideBlock(
                raw = block,
                tags = AssSsaOverrideTagParser.parseBlock(block)
            )
            index = blockEnd + 1
        }

        return AssSsaTextAst(raw = raw, nodes = nodes)
    }
}

internal object AssSsaOverrideTagParser {
    fun parseBlock(block: String): List<AssSsaOverrideTag> {
        val inner = block.removePrefix("{").removeSuffix("}")
        val tags = mutableListOf<AssSsaOverrideTag>()
        var index = 0
        while (index < inner.length) {
            val slash = inner.indexOf('\\', startIndex = index)
            if (slash < 0 || slash == inner.lastIndex) break
            val nameStart = slash + 1
            var nameEnd = nameStart
            while (nameEnd < inner.length && inner[nameEnd].isLetterOrDigit()) {
                nameEnd += 1
            }
            if (nameEnd == nameStart) {
                index = slash + 1
                continue
            }
            val name = inner.substring(nameStart, nameEnd)
            val nextSlash = findNextTopLevelSlash(inner, nameEnd)
            val rawTag = inner.substring(slash, nextSlash ?: inner.length)
            val arguments = rawTag.removePrefix("\\$name").takeIf { it.isNotBlank() }
            tags += AssSsaOverrideTag(name = name, raw = rawTag, arguments = arguments)
            index = nextSlash ?: inner.length
        }
        return tags
    }

    private fun findNextTopLevelSlash(value: String, startIndex: Int): Int? {
        var depth = 0
        var index = startIndex
        while (index < value.length) {
            when (value[index]) {
                '(' -> depth += 1
                ')' -> depth = (depth - 1).coerceAtLeast(0)
                '\\' -> if (depth == 0) return index
            }
            index += 1
        }
        return null
    }
}
