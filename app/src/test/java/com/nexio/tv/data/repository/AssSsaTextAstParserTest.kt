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
}
