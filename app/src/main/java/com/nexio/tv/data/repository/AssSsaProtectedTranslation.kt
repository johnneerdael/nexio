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
    val risk: AssSsaRisk
) {
    fun reconstruct(translatedText: String): Result<String> = runCatching {
        validateTranslatedText(translatedText)
        var result = translatedText
        placeholders.forEach { placeholder ->
            result = result.replace(placeholder.token, placeholder.raw)
        }
        rebuildOriginalPrefixAndProtectedText(result)
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
            val placeholders = mutableListOf<AssSsaPlaceholder>()
            val protected = StringBuilder()
            var index = 0
            var sawDrawing = false
            var sawKaraoke = false

            tokens.forEach { token ->
                when (token) {
                    is AssSsaTextToken.Text -> protected.append(token.raw)
                    is AssSsaTextToken.LineBreak -> {
                        val marker = "⟦LB_${index.toString().padStart(3, '0')}⟧"
                        index += 1
                        placeholders += AssSsaPlaceholder(marker, token.raw, token)
                        protected.append(marker)
                    }
                    is AssSsaTextToken.HardSpace -> {
                        val marker = "⟦HS_${index.toString().padStart(3, '0')}⟧"
                        index += 1
                        placeholders += AssSsaPlaceholder(marker, token.raw, token)
                        protected.append(marker)
                    }
                    is AssSsaTextToken.OverrideBlock -> {
                        if (KARAOKE_PATTERN.containsMatchIn(token.raw)) {
                            sawKaraoke = true
                        }
                        if (DRAWING_MODE_PATTERN.containsMatchIn(token.raw)) {
                            return@forEach
                        }
                        if (token.raw.hasAssSsaLineLevelTag()) {
                            return@forEach
                        }
                        val marker = "⟦ASS_${index.toString().padStart(3, '0')}⟧"
                        index += 1
                        placeholders += AssSsaPlaceholder(marker, token.raw, token)
                        protected.append(marker)
                    }
                    is AssSsaTextToken.Drawing -> {
                        sawDrawing = true
                    }
                    is AssSsaTextToken.Malformed -> {
                        sawDrawing = true
                    }
                }
            }

            val risk = when {
                sawDrawing -> AssSsaRisk.PreserveOnly
                sawKaraoke -> AssSsaRisk.Complex
                placeholders.size >= COMPLEX_PLACEHOLDER_COUNT -> AssSsaRisk.Complex
                else -> AssSsaRisk.Normal
            }
            return AssSsaProtectedTranslationUnit(
                id = id,
                originalTokens = tokens,
                protectedText = protected.toString(),
                placeholders = placeholders,
                risk = risk
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
