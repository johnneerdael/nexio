package com.nexio.tv.data.repository

internal data class AssSsaTextAst(
    val raw: String,
    val nodes: List<AssSsaTextNode>
) {
    fun render(): String = nodes.joinToString("") { it.raw }

    fun translatableSpans(): List<AssSsaTextNode.TextSpan> {
        return nodes.filterIsInstance<AssSsaTextNode.TextSpan>()
    }

    companion object {
        fun parse(raw: String): AssSsaTextAst = AssSsaTextAstParser.parse(raw)
    }
}

internal sealed interface AssSsaTextNode {
    val raw: String

    data class TextSpan(
        val id: String,
        override val raw: String
    ) : AssSsaTextNode

    data class OverrideBlock(
        override val raw: String,
        val tags: List<AssSsaOverrideTag> = emptyList()
    ) : AssSsaTextNode

    data class LineBreak(override val raw: String) : AssSsaTextNode
    data class HardSpace(override val raw: String) : AssSsaTextNode
    data class DrawingSpan(override val raw: String) : AssSsaTextNode
    data class Malformed(override val raw: String) : AssSsaTextNode
}

internal data class AssSsaOverrideTag(
    val name: String,
    val raw: String,
    val arguments: String?
)
