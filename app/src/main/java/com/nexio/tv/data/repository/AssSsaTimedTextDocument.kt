package com.nexio.tv.data.repository

private const val EVENTS_SECTION = "[Events]"

internal fun parseAssSsaTimedTextDocument(
    raw: String,
    format: TimedTextFormat
): TimedTextDocument {
    val lines = raw.lines()
    val parsed = mutableListOf<TimedTextBlock>()
    var inEventsSection = false
    var eventFormat: List<String> = emptyList()
    var textFieldIndex = -1
    var nextBlockId = 0

    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            inEventsSection = trimmed.equals(EVENTS_SECTION, ignoreCase = true)
            parsed += AssSsaPassthroughLineBlock(line)
            continue
        }

        if (inEventsSection && trimmed.startsWith("Format:", ignoreCase = true)) {
            eventFormat = line.substringAfter(":", "").split(',').map { it.trim() }
            textFieldIndex = eventFormat.indexOfFirst { it.equals("Text", ignoreCase = true) }
            parsed += AssSsaPassthroughLineBlock(line)
            continue
        }

        if (inEventsSection &&
            textFieldIndex >= 0 &&
            (trimmed.startsWith("Dialogue:", ignoreCase = true) ||
                trimmed.startsWith("Comment:", ignoreCase = true))
        ) {
            val parsedDialogue = parseAssSsaDialogueLine(
                line = line,
                eventFieldCount = eventFormat.size,
                textFieldIndex = textFieldIndex,
                nextBlockId = nextBlockId
            )
            if (parsedDialogue != null) {
                parsed += parsedDialogue.block
                nextBlockId = parsedDialogue.nextBlockId
                continue
            }
        }

        parsed += AssSsaPassthroughLineBlock(line)
    }

    return TimedTextDocument(format, parsed)
}

private data class AssSsaParseResult(
    val block: AssSsaDialogueBlock,
    val nextBlockId: Int
)

private data class AssSsaPassthroughLineBlock(
    private val line: String
) : TimedTextBlock() {
    override fun render(translations: Map<Int, String>): String = line
}

internal data class AssSsaDialogueBlock(
    private val prefix: String,
    private val fieldsBeforeText: List<String>,
    private val textSegments: List<AssSsaTextSegment>
) : TimedTextBlock() {
    fun translatableBlocks(): List<TranslatableTimedTextBlock> {
        return textSegments.mapNotNull { segment ->
            when (segment) {
                is TranslatableAssSsaTextSegment -> TranslatableTimedTextBlock(
                    blockId = segment.blockId,
                    prefixLines = emptyList(),
                    text = segment.fallback
                )
                else -> null
            }
        }
    }

    override fun render(translations: Map<Int, String>): String {
        return renderWithText(textSegments.joinToString("") { segment -> segment.render(translations) })
    }

    fun toProtectedTranslationUnit(id: String): AssSsaProtectedTranslationUnit {
        return AssSsaProtectedTranslationUnit.fromTokens(
            id = id,
            tokens = AssSsaTextTokenizer.tokenize(rawText())
        )
    }

    fun renderProtected(translatedProtectedText: String?): String {
        if (translatedProtectedText.isNullOrBlank()) return render(emptyMap())
        val translatedText = toProtectedTranslationUnit("render")
            .reconstruct(translatedProtectedText)
            .getOrElse { return render(emptyMap()) }
        return if (fieldsBeforeText.isEmpty()) {
            prefix + translatedText
        } else {
            prefix + fieldsBeforeText.joinToString(",") + "," + translatedText
        }
    }

    fun rawText(): String {
        return textSegments.joinToString("") { segment -> segment.render(emptyMap()) }
    }

    fun segmentSurface(id: String): AssSsaTranslationSurface? {
        return when (val result = AssSsaSegmentSurfaceParser.parse(id, rawText())) {
            is AssSsaSurfaceParseResult.Translatable -> result.surface
            is AssSsaSurfaceParseResult.PreserveOnly -> null
        }
    }

    fun renderSegmentSurface(surface: AssSsaTranslationSurface?, translatedSegments: List<String>?): String {
        val nextText = if (surface != null && translatedSegments != null) {
            runCatching { surface.recomposeOrThrow(translatedSegments) }.getOrDefault(rawText())
        } else {
            rawText()
        }
        return renderWithText(nextText)
    }

    private fun renderWithText(text: String): String {
        return if (fieldsBeforeText.isEmpty()) {
            prefix + text
        } else {
            prefix + fieldsBeforeText.joinToString(",") + "," + text
        }
    }
}

internal sealed interface AssSsaTextSegment {
    fun render(translations: Map<Int, String>): String
}

private data class LiteralAssSsaTextSegment(
    private val raw: String
) : AssSsaTextSegment {
    override fun render(translations: Map<Int, String>): String = raw
}

internal data class TranslatableAssSsaTextSegment(
    val blockId: Int,
    val fallback: String
) : AssSsaTextSegment {
    override fun render(translations: Map<Int, String>): String {
        return translations[blockId]?.takeIf { it.isNotBlank() } ?: fallback
    }
}

private fun parseAssSsaDialogueLine(
    line: String,
    eventFieldCount: Int,
    textFieldIndex: Int,
    nextBlockId: Int
): AssSsaParseResult? {
    val prefixEnd = line.indexOf(':')
    if (prefixEnd < 0 || eventFieldCount <= 0) return null
    var payloadStart = prefixEnd + 1
    while (payloadStart < line.length && line[payloadStart].isWhitespace()) {
        payloadStart += 1
    }
    val prefix = line.take(payloadStart)
    val payload = line.drop(payloadStart)
    val fields = payload.split(',', limit = eventFieldCount)
    if (fields.size <= textFieldIndex) return null

    val beforeText = fields.take(textFieldIndex)
    val text = fields[textFieldIndex]
    val tokenized = tokenizeAssSsaDialogueText(text, nextBlockId)
    return AssSsaParseResult(
        block = AssSsaDialogueBlock(
            prefix = prefix,
            fieldsBeforeText = beforeText,
            textSegments = tokenized.segments
        ),
        nextBlockId = tokenized.nextBlockId
    )
}

private data class AssSsaTokenizeResult(
    val segments: List<AssSsaTextSegment>,
    val nextBlockId: Int
)

private fun tokenizeAssSsaDialogueText(
    text: String,
    firstBlockId: Int
): AssSsaTokenizeResult {
    val segments = mutableListOf<AssSsaTextSegment>()
    var drawingMode = 0
    var blockId = firstBlockId
    var index = 0

    fun addPlain(raw: String) {
        if (raw.isEmpty()) return
        if (drawingMode > 0) {
            segments += LiteralAssSsaTextSegment(raw)
            return
        }

        var cursor = 0
        val escapeRegex = Regex("""\\[Nnh]""")
        for (match in escapeRegex.findAll(raw)) {
            if (match.range.first > cursor) {
                val visible = raw.substring(cursor, match.range.first)
                if (visible.isNotEmpty()) {
                    segments += TranslatableAssSsaTextSegment(blockId++, visible)
                }
            }
            segments += LiteralAssSsaTextSegment(match.value)
            cursor = match.range.last + 1
        }
        if (cursor < raw.length) {
            val visible = raw.substring(cursor)
            if (visible.isNotEmpty()) {
                segments += TranslatableAssSsaTextSegment(blockId++, visible)
            }
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
            addPlain(text.substring(blockStart))
            break
        }
        val block = text.substring(blockStart, blockEnd + 1)
        drawingMode = drawingModeAfterOverrideBlock(block, drawingMode)
        segments += LiteralAssSsaTextSegment(block)
        index = blockEnd + 1
    }

    return AssSsaTokenizeResult(segments, blockId)
}

private fun drawingModeAfterOverrideBlock(block: String, current: Int): Int {
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
                val value = inner.substring(argStart, argEnd).trim().toIntOrNull()
                if (value != null) {
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
