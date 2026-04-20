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
}
