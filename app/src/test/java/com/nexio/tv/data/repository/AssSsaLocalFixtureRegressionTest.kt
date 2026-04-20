package com.nexio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class AssSsaLocalFixtureRegressionTest {
    @Test
    fun ganbareDefaultSignLineTranslatesTextOnly() {
        val text = "{\\bord3\\shad0\\fs14\\fax0.12\\c&H353135&\\3c&HF5F5F5&\\frz29.62\\pos(63,80)}My best friend?!"
        val unit = AssSsaProtectedTranslationUnit.fromText("evt_0", text)

        assertEquals("⟦ASS_000⟧My best friend?!", unit.protectedText)
        assertEquals(
            "{\\bord3\\shad0\\fs14\\fax0.12\\c&H353135&\\3c&HF5F5F5&\\frz29.62\\pos(63,80)}Mijn beste vriend?!",
            unit.reconstruct("⟦ASS_000⟧Mijn beste vriend?!").getOrThrow()
        )
    }

    @Test
    fun ganbareMovingSignTranslatesVisibleTextOnly() {
        val text = "{\\an8\\fnComic Sans MS\\b1\\fs45\\bord2.5\\shad3\\move(165,182,165,-182)\\3c&H181060&\\4c&H181060&\\c&H3093F2&}Lov{\\c&H55C8F8&}able {\\c&H3093F2&}Lun{\\c&H55C8F8&}ches!"
        val unit = AssSsaProtectedTranslationUnit.fromText("evt_0", text)

        assertEquals(
            "⟦ASS_000⟧Lov⟦ASS_001⟧able ⟦ASS_002⟧Lun⟦ASS_003⟧ches!",
            unit.protectedText
        )
        assertEquals(
            "{\\an8\\fnComic Sans MS\\b1\\fs45\\bord2.5\\shad3\\move(165,182,165,-182)\\3c&H181060&\\4c&H181060&\\c&H3093F2&}Lie{\\c&H55C8F8&}felijke {\\c&H3093F2&}lun{\\c&H55C8F8&}ches!",
            unit.reconstruct("⟦ASS_000⟧Lie⟦ASS_001⟧felijke ⟦ASS_002⟧lun⟦ASS_003⟧ches!").getOrThrow()
        )
    }

    @Test
    fun classDeSignsStyleTranslatesTextOnly() {
        val text = "{\\pos(312,198.667)}MAEHARA"
        val unit = AssSsaProtectedTranslationUnit.fromText("evt_0", text)

        assertEquals("⟦ASS_000⟧MAEHARA", unit.protectedText)
        assertEquals("{\\pos(312,198.667)}MAEHARA", unit.reconstruct("⟦ASS_000⟧MAEHARA").getOrThrow())
    }
}
