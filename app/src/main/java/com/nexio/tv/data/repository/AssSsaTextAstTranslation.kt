package com.nexio.tv.data.repository

internal data class AssSsaTextAstPlaceholder(
    val token: String,
    val raw: String,
    val nodeIndex: Int,
    val kind: AssSsaTextAstPlaceholderKind
)

internal enum class AssSsaTextAstPlaceholderKind {
    Override,
    LineBreak,
    HardSpace,
    Drawing,
    Malformed
}

internal data class AssSsaTextAstTranslationUnit(
    val id: String,
    val ast: AssSsaTextAst,
    val protectedText: String,
    val placeholders: List<AssSsaTextAstPlaceholder>
) {
    fun reconstruct(translatedText: String): Result<String> = runCatching {
        validate(translatedText)
        var result = translatedText
        placeholders.forEach { placeholder ->
            result = result.replace(placeholder.token, placeholder.raw)
        }
        result
    }

    private fun validate(translatedText: String) {
        placeholders.forEach { placeholder ->
            check(translatedText.contains(placeholder.token)) {
                "Missing placeholder ${placeholder.token}"
            }
        }
        check(!translatedText.contains("{") && !translatedText.contains("}")) {
            "Translated text introduced raw ASS override braces"
        }
    }

    companion object {
        fun fromText(id: String, text: String): AssSsaTextAstTranslationUnit {
            val ast = AssSsaTextAst.parse(text)
            val placeholders = mutableListOf<AssSsaTextAstPlaceholder>()
            val protected = StringBuilder()
            var placeholderIndex = 0

            fun nextToken(kind: AssSsaTextAstPlaceholderKind): String {
                val prefix = when (kind) {
                    AssSsaTextAstPlaceholderKind.Override -> "ASS"
                    AssSsaTextAstPlaceholderKind.LineBreak -> "LB"
                    AssSsaTextAstPlaceholderKind.HardSpace -> "HS"
                    AssSsaTextAstPlaceholderKind.Drawing -> "DRAW"
                    AssSsaTextAstPlaceholderKind.Malformed -> "BAD"
                }
                return "⟦${prefix}_${(placeholderIndex++).toString().padStart(3, '0')}⟧"
            }

            fun addPlaceholder(nodeIndex: Int, raw: String, kind: AssSsaTextAstPlaceholderKind) {
                val token = nextToken(kind)
                placeholders += AssSsaTextAstPlaceholder(
                    token = token,
                    raw = raw,
                    nodeIndex = nodeIndex,
                    kind = kind
                )
                protected.append(token)
            }

            ast.nodes.forEachIndexed { nodeIndex, node ->
                when (node) {
                    is AssSsaTextNode.TextSpan -> protected.append(node.raw)
                    is AssSsaTextNode.OverrideBlock -> addPlaceholder(
                        nodeIndex,
                        node.raw,
                        AssSsaTextAstPlaceholderKind.Override
                    )
                    is AssSsaTextNode.LineBreak -> addPlaceholder(
                        nodeIndex,
                        node.raw,
                        AssSsaTextAstPlaceholderKind.LineBreak
                    )
                    is AssSsaTextNode.HardSpace -> addPlaceholder(
                        nodeIndex,
                        node.raw,
                        AssSsaTextAstPlaceholderKind.HardSpace
                    )
                    is AssSsaTextNode.DrawingSpan -> addPlaceholder(
                        nodeIndex,
                        node.raw,
                        AssSsaTextAstPlaceholderKind.Drawing
                    )
                    is AssSsaTextNode.Malformed -> addPlaceholder(
                        nodeIndex,
                        node.raw,
                        AssSsaTextAstPlaceholderKind.Malformed
                    )
                }
            }

            return AssSsaTextAstTranslationUnit(
                id = id,
                ast = ast,
                protectedText = protected.toString(),
                placeholders = placeholders
            )
        }
    }
}
