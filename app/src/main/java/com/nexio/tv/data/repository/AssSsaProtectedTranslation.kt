package com.nexio.tv.data.repository

internal enum class AssSsaRisk {
    Normal,
    Complex,
    PreserveOnly
}

internal data class AssSsaPlaceholder(
    val token: String,
    val raw: String,
    val sourceToken: AssSsaTextToken
)

internal data class AssSsaProtectedTranslationUnit(
    val id: String,
    val originalTokens: List<AssSsaTextToken>,
    val protectedText: String,
    val placeholders: List<AssSsaPlaceholder>,
    val risk: AssSsaRisk,
    private val astUnit: AssSsaTextAstTranslationUnit? = null
) {
    fun reconstruct(translatedText: String): Result<String> {
        astUnit?.let { return it.reconstruct(translatedText) }
        return runCatching {
            validateTranslatedText(translatedText)
            var result = translatedText
            placeholders.forEach { placeholder ->
                result = result.replace(placeholder.token, placeholder.raw)
            }
            rebuildOriginalPrefixAndProtectedText(result)
        }
    }

    private fun validateTranslatedText(translatedText: String) {
        placeholders.forEach { placeholder ->
            if (!translatedText.contains(placeholder.token)) {
                throw IllegalStateException("Missing placeholder ${placeholder.token}")
            }
        }
        if (translatedText.contains('{') ||
            translatedText.contains('}') ||
            RAW_ASS_SYNTAX_PATTERN.containsMatchIn(translatedText)
        ) {
            throw IllegalStateException("Translated text introduced raw ASS syntax")
        }
    }

    private fun rebuildOriginalPrefixAndProtectedText(reconstructedVisibleText: String): String {
        val leadingStructural = originalTokens
            .takeWhile { token ->
                token is AssSsaTextToken.OverrideBlock && token.raw.hasAssSsaLineLevelTag()
            }
            .joinToString("") { it.raw }
        return leadingStructural + reconstructedVisibleText
    }

    companion object {
        fun fromTokens(id: String, tokens: List<AssSsaTextToken>): AssSsaProtectedTranslationUnit {
            return fromText(id = id, text = tokens.joinToString("") { it.raw })
        }

        fun fromText(id: String, text: String): AssSsaProtectedTranslationUnit {
            val astUnit = AssSsaTextAstTranslationUnit.fromText(id, text)
            val sawDrawing = astUnit.ast.nodes.any {
                it is AssSsaTextNode.DrawingSpan || it is AssSsaTextNode.Malformed
            }
            val sawKaraoke = astUnit.ast.nodes
                .filterIsInstance<AssSsaTextNode.OverrideBlock>()
                .any { block -> KARAOKE_PATTERN.containsMatchIn(block.raw) }
            val risk = when {
                astUnit.protectedText.isBlank() -> AssSsaRisk.PreserveOnly
                sawDrawing -> AssSsaRisk.Complex
                sawKaraoke -> AssSsaRisk.Complex
                astUnit.placeholders.size >= COMPLEX_PLACEHOLDER_COUNT -> AssSsaRisk.Complex
                else -> AssSsaRisk.Normal
            }
            return AssSsaProtectedTranslationUnit(
                id = id,
                originalTokens = AssSsaTextTokenizer.tokenize(text),
                protectedText = astUnit.protectedText,
                placeholders = emptyList(),
                risk = risk,
                astUnit = astUnit
            )
        }
    }
}

private const val COMPLEX_PLACEHOLDER_COUNT = 8

private val LINE_LEVEL_PATTERN = Regex("""\\(?:an\d+|pos\(|move\(|clip\(|iclip\(|org\(|fad\(|fade\()""")
private val KARAOKE_PATTERN = Regex("""\\(?:k|K|kf|ko|kt)\d*""")
private val DRAWING_MODE_PATTERN = Regex("""\\p-?\d*""")
private val RAW_ASS_SYNTAX_PATTERN =
    Regex("""\\(?:pos|move|clip|iclip|p\d*|t|fad|fade|org|an\d*|c&H|\d?c&H|alpha|\d?alpha)""")

private fun String.hasAssSsaLineLevelTag(): Boolean {
    return LINE_LEVEL_PATTERN.containsMatchIn(this)
}
