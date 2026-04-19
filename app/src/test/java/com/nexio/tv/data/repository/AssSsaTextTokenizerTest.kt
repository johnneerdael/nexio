package com.nexio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class AssSsaTextTokenizerTest {
    @Test
    fun tokenizesOverrideBlocksEscapesAndText() {
        val tokens = AssSsaTextTokenizer.tokenize(
            """{\an8\pos(320,40)}I am {\i1}not{\i0} amused.\NReally."""
        )

        assertEquals(
            listOf(
                AssSsaTextToken.OverrideBlock("""{\an8\pos(320,40)}"""),
                AssSsaTextToken.Text("I am "),
                AssSsaTextToken.OverrideBlock("""{\i1}"""),
                AssSsaTextToken.Text("not"),
                AssSsaTextToken.OverrideBlock("""{\i0}"""),
                AssSsaTextToken.Text(" amused."),
                AssSsaTextToken.LineBreak("""\N"""),
                AssSsaTextToken.Text("Really.")
            ),
            tokens
        )
    }

    @Test
    fun treatsDrawingModePayloadAsDrawing() {
        val tokens = AssSsaTextTokenizer.tokenize(
            """{\p1}m 0 0 l 100 0 100 100 0 100{\p0}Square"""
        )

        assertEquals(
            listOf(
                AssSsaTextToken.OverrideBlock("""{\p1}"""),
                AssSsaTextToken.Drawing("m 0 0 l 100 0 100 100 0 100"),
                AssSsaTextToken.OverrideBlock("""{\p0}"""),
                AssSsaTextToken.Text("Square")
            ),
            tokens
        )
    }

    @Test
    fun preservesMalformedOverrideBlock() {
        val tokens = AssSsaTextTokenizer.tokenize("""Hello {\i1broken""")

        assertEquals(
            listOf(
                AssSsaTextToken.Text("Hello "),
                AssSsaTextToken.Malformed("""{\i1broken""")
            ),
            tokens
        )
    }

    @Test
    fun preservesHardSpaceSeparately() {
        val tokens = AssSsaTextTokenizer.tokenize("""Hello\hworld""")

        assertEquals(
            listOf(
                AssSsaTextToken.Text("Hello"),
                AssSsaTextToken.HardSpace("""\h"""),
                AssSsaTextToken.Text("world")
            ),
            tokens
        )
    }
}
