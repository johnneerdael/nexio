package com.nexio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class AssSsaTextAstParserTest {
    @Test
    fun parsesPlainTextAsOneTranslatableSpan() {
        val ast = AssSsaTextAst.parse("Hello world")

        assertEquals("Hello world", ast.raw)
        assertEquals("Hello world", ast.render())
        assertEquals(listOf("Hello world"), ast.translatableSpans().map { it.raw })
    }

    @Test
    fun parserKeepsStableSpanIds() {
        val ast = AssSsaTextAst.parse("Hello world")

        assertEquals("txt_000", ast.translatableSpans().single().id)
    }

    @Test
    fun parsesOverrideBlocksAndTextSpansWithoutChangingRawText() {
        val source = "{\\an8\\pos(320,40)}I am {\\i1}not{\\i0} amused."

        val ast = AssSsaTextAst.parse(source)

        assertEquals(source, ast.render())
        assertEquals(
            listOf("I am ", "not", " amused."),
            ast.translatableSpans().map { it.raw }
        )
        assertEquals(
            listOf("{\\an8\\pos(320,40)}", "{\\i1}", "{\\i0}"),
            ast.nodes.filterIsInstance<AssSsaTextNode.OverrideBlock>().map { it.raw }
        )
    }

    @Test
    fun parsesLineBreaksAndHardSpacesAsStructuralTokens() {
        val source = "Hello\\Nworld\\hagain\\nsoft"

        val ast = AssSsaTextAst.parse(source)

        assertEquals(source, ast.render())
        assertEquals(listOf("Hello", "world", "again", "soft"), ast.translatableSpans().map { it.raw })
        assertEquals(
            listOf("\\N", "\\h", "\\n"),
            ast.nodes.filterNot { it is AssSsaTextNode.TextSpan }.map { it.raw }
        )
    }

    @Test
    fun drawingModePlainContentIsNotTranslatableText() {
        val source = "{\\p1}m 0 0 l 100 0 100 100{\\p0}Label"

        val ast = AssSsaTextAst.parse(source)

        assertEquals(source, ast.render())
        assertEquals(
            listOf("m 0 0 l 100 0 100 100"),
            ast.nodes.filterIsInstance<AssSsaTextNode.DrawingSpan>().map { it.raw }
        )
        assertEquals(listOf("Label"), ast.translatableSpans().map { it.raw })
    }

    @Test
    fun vectorClipInsideOverrideBlockIsPreservedInsideRawBlock() {
        val source = "{\\clip(m 0 0 l 100 0 100 100)\\pos(40,50)}Cafe"

        val ast = AssSsaTextAst.parse(source)

        assertEquals(source, ast.render())
        assertEquals(listOf("Cafe"), ast.translatableSpans().map { it.raw })
        assertEquals(
            "{\\clip(m 0 0 l 100 0 100 100)\\pos(40,50)}",
            ast.nodes.filterIsInstance<AssSsaTextNode.OverrideBlock>().single().raw
        )
    }
}
