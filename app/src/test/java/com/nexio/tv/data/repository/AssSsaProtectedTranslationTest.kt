package com.nexio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssSsaProtectedTranslationTest {
    @Test
    fun buildsPhraseUnitWithStyleAndLineBreakPlaceholders() {
        val unit = AssSsaProtectedTranslationUnit.fromTokens(
            id = "evt_1",
            tokens = AssSsaTextTokenizer.tokenize(
                """{\an8\pos(320,40)}I am {\i1}not{\i0} amused.\NReally."""
            )
        )

        assertEquals(AssSsaRisk.Normal, unit.risk)
        assertEquals(
            "I am ⟦ASS_000⟧not⟦ASS_001⟧ amused.⟦LB_002⟧Really.",
            unit.protectedText
        )
        assertEquals(
            """{\an8\pos(320,40)}Ik ben {\i1}niet{\i0} geamuseerd.\NEcht niet.""",
            unit.reconstruct("Ik ben ⟦ASS_000⟧niet⟦ASS_001⟧ geamuseerd.⟦LB_002⟧Echt niet.").getOrThrow()
        )
    }

    @Test
    fun rejectsMissingPlaceholder() {
        val unit = AssSsaProtectedTranslationUnit.fromTokens(
            id = "evt_1",
            tokens = AssSsaTextTokenizer.tokenize("""Hello {\i1}world{\i0}""")
        )

        val result = unit.reconstruct("Hallo wereld")

        assertTrue(result.isFailure)
        assertEquals("Missing placeholder ⟦ASS_000⟧", result.exceptionOrNull()?.message)
    }

    @Test
    fun rejectsRawAssSyntaxIntroducedByModel() {
        val unit = AssSsaProtectedTranslationUnit.fromTokens(
            id = "evt_1",
            tokens = AssSsaTextTokenizer.tokenize("Hello world")
        )

        val result = unit.reconstruct("""Hallo {\pos(1,2)}wereld""")

        assertTrue(result.isFailure)
        assertEquals("Translated text introduced raw ASS syntax", result.exceptionOrNull()?.message)
    }

    @Test
    fun classifiesDrawingModeAsPreserveOnly() {
        val unit = AssSsaProtectedTranslationUnit.fromTokens(
            id = "evt_1",
            tokens = AssSsaTextTokenizer.tokenize("""{\p1}m 0 0 l 100 0{\p0}Square""")
        )

        assertEquals(AssSsaRisk.PreserveOnly, unit.risk)
        assertEquals("Square", unit.protectedText)
    }
}
