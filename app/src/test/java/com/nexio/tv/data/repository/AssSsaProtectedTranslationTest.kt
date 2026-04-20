package com.nexio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class AssSsaProtectedTranslationTest {
    @Test
    fun signLineWithPositionAndTinyFontIsStillTranslatable() {
        val unit = AssSsaProtectedTranslationUnit.fromText(
            id = "evt_0",
            text = "{\\bord3\\shad0\\fs14\\pos(475.43,40)}My best friend?!"
        )

        assertEquals("⟦ASS_000⟧My best friend?!", unit.protectedText)
        assertEquals(AssSsaRisk.Normal, unit.risk)
        assertEquals(
            "{\\bord3\\shad0\\fs14\\pos(475.43,40)}Mijn beste vriend?!",
            unit.reconstruct("⟦ASS_000⟧Mijn beste vriend?!").getOrThrow()
        )
    }

    @Test
    fun vectorDrawingIsPreservedWhileTextAfterDrawingCanTranslate() {
        val unit = AssSsaProtectedTranslationUnit.fromText(
            id = "evt_0",
            text = "{\\p1}m 0 0 l 100 0{\\p0}Logo"
        )

        assertEquals("⟦ASS_000⟧⟦DRAW_001⟧⟦ASS_002⟧Logo", unit.protectedText)
        assertEquals(AssSsaRisk.Complex, unit.risk)
        assertEquals(
            "{\\p1}m 0 0 l 100 0{\\p0}Merk",
            unit.reconstruct("⟦ASS_000⟧⟦DRAW_001⟧⟦ASS_002⟧Merk").getOrThrow()
        )
    }
}
