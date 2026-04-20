package com.nexio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class AssSsaTextAstTranslationTest {
    @Test
    fun buildsPayloadFromTextSpansAndPreservesOverrideBlocks() {
        val unit = AssSsaTextAstTranslationUnit.fromText(
            id = "evt_0",
            text = "{\\bord3\\shad0\\fs14\\pos(475.43,40)}My best friend?!"
        )

        assertEquals("⟦ASS_000⟧My best friend?!", unit.protectedText)
        assertEquals(
            "{\\bord3\\shad0\\fs14\\pos(475.43,40)}Mijn beste vriend?!",
            unit.reconstruct("⟦ASS_000⟧Mijn beste vriend?!").getOrThrow()
        )
    }

    @Test
    fun preservesInlineEmphasisPlaceholdersAroundTranslatedText() {
        val unit = AssSsaTextAstTranslationUnit.fromText(
            id = "evt_0",
            text = "I am {\\i1}not{\\i0} angry"
        )

        assertEquals("I am ⟦ASS_000⟧not⟦ASS_001⟧ angry", unit.protectedText)
        assertEquals(
            "Ik ben {\\i1}niet{\\i0} boos",
            unit.reconstruct("Ik ben ⟦ASS_000⟧niet⟦ASS_001⟧ boos").getOrThrow()
        )
    }

    @Test
    fun preservesLineBreakPlaceholders() {
        val unit = AssSsaTextAstTranslationUnit.fromText(
            id = "evt_0",
            text = "Hello\\Nworld"
        )

        assertEquals("Hello⟦LB_000⟧world", unit.protectedText)
        assertEquals("Hallo\\Nwereld", unit.reconstruct("Hallo⟦LB_000⟧wereld").getOrThrow())
    }

    @Test
    fun drawingSpansAreAnchoredAsPlaceholdersAndRemainInReconstruction() {
        val unit = AssSsaTextAstTranslationUnit.fromText(
            id = "evt_0",
            text = "{\\p1}m 0 0 l 100 0{\\p0}Logo"
        )

        assertEquals("⟦ASS_000⟧⟦DRAW_001⟧⟦ASS_002⟧Logo", unit.protectedText)
        assertEquals(
            "{\\p1}m 0 0 l 100 0{\\p0}Merk",
            unit.reconstruct("⟦ASS_000⟧⟦DRAW_001⟧⟦ASS_002⟧Merk").getOrThrow()
        )
    }

    @Test
    fun rejectsTranslatedTextThatDropsAPlaceholder() {
        val unit = AssSsaTextAstTranslationUnit.fromText(
            id = "evt_0",
            text = "I am {\\i1}not{\\i0} angry"
        )

        val result = unit.reconstruct("Ik ben niet⟦ASS_001⟧ boos")

        assertEquals(true, result.isFailure)
    }
}
