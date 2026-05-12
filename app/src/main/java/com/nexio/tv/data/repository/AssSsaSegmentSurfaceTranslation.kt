package com.nexio.tv.data.repository

import org.json.JSONArray
import org.json.JSONObject

internal sealed interface AssSsaSurfaceParseResult {
    data class Translatable(val surface: AssSsaTranslationSurface) : AssSsaSurfaceParseResult
    data class PreserveOnly(val rawText: String) : AssSsaSurfaceParseResult
}

internal data class AssSsaTranslationSurface(
    val id: String,
    val originalText: String,
    val prefixRaw: String,
    val segments: List<String>,
    val separators: List<String>,
    val suffixRaw: String,
    val inlineMarkers: Map<String, String>,
    val context: String
) {
    init {
        require(separators.size == (segments.size - 1).coerceAtLeast(0)) {
            "ASS/SSA surface separators must be one less than segments"
        }
    }

    fun validateTranslatedSegments(translatedSegments: List<String>): Result<List<String>> {
        return runCatching {
            require(translatedSegments.size == segments.size) {
                "Expected ${segments.size} translated ASS/SSA segments, got ${translatedSegments.size}"
            }

            val sourceLiteralMarkers = sourceLiteralMarkers()
            val repaired = translatedSegments.mapIndexed { index, segment ->
                require(!segment.contains('\n') && !segment.contains('\r')) {
                    "Translated ASS/SSA segment contains a raw line break"
                }

                var normalized = segment
                inlineMarkers.keys.forEach { marker ->
                    normalized = normalized
                        .replace(Regex("""[ \t]+${Regex.escape(marker)}"""), marker)
                        .replace(Regex("""${Regex.escape(marker)}[ \t]+"""), marker)
                }

                require(!normalized.contains('{') && !normalized.contains('}')) {
                    "Translated ASS/SSA segment introduced raw override syntax"
                }
                require(!RAW_ASS_ESCAPE_PATTERN.containsMatchIn(normalized)) {
                    "Translated ASS/SSA segment introduced raw ASS/SSA escape syntax"
                }
                MARKER_PATTERN.findAll(normalized).forEach { match ->
                    require(inlineMarkers.containsKey(match.value) || match.value in sourceLiteralMarkers) {
                        "Translated ASS/SSA segment contains unknown marker ${match.value}"
                    }
                }
                require(segments[index].isBlank() || normalized.isNotBlank()) {
                    "Translated ASS/SSA segment $index is empty"
                }
                normalized
            }

            inlineMarkers.keys.forEach { marker ->
                val count = repaired.sumOf { segment ->
                    Regex.escape(marker).toRegex().findAll(segment).count()
                }
                require(count == 1) {
                    "Translated ASS/SSA segments must contain marker $marker exactly once"
                }
            }

            repaired
        }
    }

    fun recomposeOrThrow(translatedSegments: List<String>): String {
        val validatedSegments = validateTranslatedSegments(translatedSegments).getOrThrow()
        val restoredSegments = validatedSegments.map { segment ->
            inlineMarkers.entries.fold(segment) { restored, (marker, raw) ->
                restored.replace(marker, raw)
            }
        }

        return buildString {
            append(prefixRaw)
            restoredSegments.forEachIndexed { index, segment ->
                if (index > 0) {
                    append(separators[index - 1])
                }
                append(segment)
            }
            append(suffixRaw)
        }
    }

    fun toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("context", context)
            .put("segments", JSONArray(segments))
    }

    private fun sourceLiteralMarkers(): Set<String> {
        return segments
            .flatMap { segment -> MARKER_PATTERN.findAll(segment).map { it.value } }
            .filterNot { marker -> inlineMarkers.containsKey(marker) }
            .toSet()
    }
}

internal object AssSsaSegmentSurfaceParser {
    fun parse(id: String, text: String): AssSsaSurfaceParseResult {
        val tokens = AssSsaTextTokenizer.tokenize(text)
        if (tokens.any { it is AssSsaTextToken.Malformed }) {
            return AssSsaSurfaceParseResult.PreserveOnly(text)
        }
        if (tokens.none { it is AssSsaTextToken.Text && it.raw.isNotBlank() }) {
            return AssSsaSurfaceParseResult.PreserveOnly(text)
        }

        val prefixRaw = StringBuilder()
        val suffixRaw = StringBuilder()
        val pendingSeparator = StringBuilder()
        val segments = mutableListOf<String>()
        val separators = mutableListOf<String>()
        val inlineMarkers = linkedMapOf<String, String>()
        val occupiedMarkerTokens = tokens
            .filterIsInstance<AssSsaTextToken.Text>()
            .flatMap { token -> MARKER_PATTERN.findAll(token.raw).map { it.value } }
            .toMutableSet()
        var currentSegment: StringBuilder? = null
        var markerIndex = 1
        var index = 0

        fun nextInlineMarker(): String {
            while ("<$markerIndex/>" in occupiedMarkerTokens) {
                markerIndex += 1
            }
            return "<${markerIndex++}/>".also { marker ->
                occupiedMarkerTokens += marker
            }
        }

        fun startOrContinueText(raw: String) {
            val current = currentSegment
            if (current != null) {
                current.append(raw)
                return
            }

            val leadingWhitespace = raw.takeWhile { it.isWhitespace() }
            val visibleText = raw.drop(leadingWhitespace.length)
            if (segments.isEmpty()) {
                prefixRaw.append(leadingWhitespace)
            } else {
                pendingSeparator.append(leadingWhitespace)
            }
            if (visibleText.isNotEmpty()) {
                if (segments.isNotEmpty()) {
                    separators += pendingSeparator.toString()
                    pendingSeparator.clear()
                }
                currentSegment = StringBuilder(visibleText)
            }
        }

        fun finalizeCurrentSegmentIntoPendingSeparator(rawSyntax: String) {
            val current = currentSegment
            if (current == null) {
                if (segments.isEmpty()) {
                    prefixRaw.append(rawSyntax)
                } else {
                    pendingSeparator.append(rawSyntax)
                }
                return
            }

            val fullText = current.toString()
            val trailingWhitespace = fullText.takeLastWhile { it.isWhitespace() }
            val visibleText = fullText.dropLast(trailingWhitespace.length)
            if (visibleText.isNotEmpty()) {
                segments += visibleText
                pendingSeparator.append(trailingWhitespace)
                pendingSeparator.append(rawSyntax)
            } else if (segments.isEmpty()) {
                prefixRaw.append(trailingWhitespace)
                prefixRaw.append(rawSyntax)
            } else {
                pendingSeparator.append(trailingWhitespace)
                pendingSeparator.append(rawSyntax)
            }
            currentSegment = null
        }

        while (index < tokens.size) {
            val token = tokens[index]
            if (token is AssSsaTextToken.Text) {
                startOrContinueText(token.raw)
                index += 1
                continue
            }

            val runStart = index
            var runEnd = index
            while (runEnd < tokens.size && tokens[runEnd] !is AssSsaTextToken.Text) {
                runEnd += 1
            }
            val rawSyntax = tokens.subList(runStart, runEnd).joinToString("") { it.raw }
            val previousChar = tokens.getOrNull(runStart - 1)?.raw?.lastOrNull()
            val nextChar = tokens.getOrNull(runEnd)?.raw?.firstOrNull()
            val isIntraword = previousChar.isSurfaceWordChar() && nextChar.isSurfaceWordChar()

            if (isIntraword) {
                val marker = nextInlineMarker()
                inlineMarkers[marker] = rawSyntax
                currentSegment?.append(marker) ?: prefixRaw.append(rawSyntax)
            } else {
                finalizeCurrentSegmentIntoPendingSeparator(rawSyntax)
            }
            index = runEnd
        }

        currentSegment?.let { current ->
            val fullText = current.toString()
            val trailingWhitespace = fullText.takeLastWhile { it.isWhitespace() }
            val visibleText = fullText.dropLast(trailingWhitespace.length)
            if (visibleText.isNotEmpty()) {
                segments += visibleText
                suffixRaw.append(trailingWhitespace)
            } else if (segments.isEmpty()) {
                prefixRaw.append(trailingWhitespace)
            } else {
                suffixRaw.append(trailingWhitespace)
            }
        }
        if (pendingSeparator.isNotEmpty()) {
            suffixRaw.append(pendingSeparator)
        }

        if (segments.isEmpty()) {
            return AssSsaSurfaceParseResult.PreserveOnly(text)
        }

        return AssSsaSurfaceParseResult.Translatable(
            AssSsaTranslationSurface(
                id = id,
                originalText = text,
                prefixRaw = prefixRaw.toString(),
                segments = segments,
                separators = separators,
                suffixRaw = suffixRaw.toString(),
                inlineMarkers = inlineMarkers,
                context = segments.joinToString(" ") { segment ->
                    segment.withoutGeneratedMarkers(inlineMarkers.keys)
                }
            )
        )
    }
}

private val MARKER_PATTERN = Regex("""<\d+/>""")
private val RAW_ASS_ESCAPE_PATTERN = Regex("""\\[Nnh]""")

private fun String.withoutGeneratedMarkers(markers: Set<String>): String {
    return markers.fold(this) { text, marker -> text.replace(marker, "") }
}

private fun Char?.isSurfaceWordChar(): Boolean {
    if (this == null) return false
    if (isLetterOrDigit()) return true
    return when (Character.getType(this)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }
}
