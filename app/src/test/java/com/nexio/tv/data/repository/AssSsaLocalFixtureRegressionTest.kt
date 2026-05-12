package com.nexio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class AssSsaLocalFixtureRegressionTest {
    @Test
    fun ganbareDefaultSignLineTranslatesTextOnly() {
        val text = "{\\bord3\\shad0\\fs14\\fax0.12\\c&H353135&\\3c&HF5F5F5&\\frz29.62\\pos(63,80)}My best friend?!"
        val surface = (AssSsaSegmentSurfaceParser.parse("evt_0", text) as AssSsaSurfaceParseResult.Translatable).surface

        assertEquals(listOf("My best friend?!"), surface.segments)
        assertEquals(
            "{\\bord3\\shad0\\fs14\\fax0.12\\c&H353135&\\3c&HF5F5F5&\\frz29.62\\pos(63,80)}Mijn beste vriend?!",
            surface.recomposeOrThrow(listOf("Mijn beste vriend?!"))
        )
    }

    @Test
    fun ganbareMovingSignBuildsSegmentSurface() {
        val text = "{\\an8\\fnComic Sans MS\\b1\\fs45\\bord2.5\\shad3\\move(165,182,165,-182)\\3c&H181060&\\4c&H181060&\\c&H3093F2&}Lov{\\c&H55C8F8&}able {\\c&H3093F2&}Lun{\\c&H55C8F8&}ches!"
        val surface = (AssSsaSegmentSurfaceParser.parse("evt_0", text) as AssSsaSurfaceParseResult.Translatable).surface

        assertEquals(
            "{\\an8\\fnComic Sans MS\\b1\\fs45\\bord2.5\\shad3\\move(165,182,165,-182)\\3c&H181060&\\4c&H181060&\\c&H3093F2&}",
            surface.prefixRaw
        )
        assertEquals(listOf("Lov<1/>able", "Lun<2/>ches!"), surface.segments)
        assertEquals(listOf(" {\\c&H3093F2&}"), surface.separators)
        assertEquals(
            "{\\an8\\fnComic Sans MS\\b1\\fs45\\bord2.5\\shad3\\move(165,182,165,-182)\\3c&H181060&\\4c&H181060&\\c&H3093F2&}Lie{\\c&H55C8F8&}felijke {\\c&H3093F2&}lun{\\c&H55C8F8&}ches!",
            surface.recomposeOrThrow(listOf("Lie<1/>felijke", "lun<2/>ches!"))
        )
    }

    @Test
    fun classDeSignsStyleTranslatesTextOnly() {
        val text = "{\\pos(312,198.667)}MAEHARA"
        val surface = (AssSsaSegmentSurfaceParser.parse("evt_0", text) as AssSsaSurfaceParseResult.Translatable).surface

        assertEquals(listOf("MAEHARA"), surface.segments)
        assertEquals("{\\pos(312,198.667)}MAEHARA", surface.recomposeOrThrow(listOf("MAEHARA")))
    }
}
