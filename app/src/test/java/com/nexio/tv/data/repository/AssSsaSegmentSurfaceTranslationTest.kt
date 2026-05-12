package com.nexio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssSsaSegmentSurfaceTranslationTest {
    @Test
    fun leadingStyleBlockBecomesRawPrefix() {
        val surface = parseSurface(
            "{\\fad(390,350)\\shad0\\fnArial\\an3\\blur2\\fs17\\b1\\pos(600,307)\\c&H2D6E87&}Initiative"
        )

        assertEquals(
            "{\\fad(390,350)\\shad0\\fnArial\\an3\\blur2\\fs17\\b1\\pos(600,307)\\c&H2D6E87&}",
            surface.prefixRaw
        )
        assertEquals(listOf("Initiative"), surface.segments)
        assertEquals(emptyList<String>(), surface.separators)
        assertEquals("Initiative", surface.context)
        assertEquals(
            "{\\fad(390,350)\\shad0\\fnArial\\an3\\blur2\\fs17\\b1\\pos(600,307)\\c&H2D6E87&}Initiatief",
            surface.recomposeOrThrow(listOf("Initiatief"))
        )
    }

    @Test
    fun intrawordOverrideBlockBecomesLocalMarker() {
        val surface = parseSurface("I{\\c&H0F00A1&}nitiative")

        assertEquals("", surface.prefixRaw)
        assertEquals(listOf("I<1/>nitiative"), surface.segments)
        assertEquals(mapOf("<1/>" to "{\\c&H0F00A1&}"), surface.inlineMarkers)
        assertEquals(
            "I{\\c&H0F00A1&}nitiatief",
            surface.recomposeOrThrow(listOf("I<1/>nitiatief"))
        )
    }

    @Test
    fun formattingAroundWordUsesSegmentSeparators() {
        val surface = parseSurface("with {\\i1}me{\\i0} today")

        assertEquals(listOf("with", "me", "today"), surface.segments)
        assertEquals(listOf(" {\\i1}", "{\\i0} "), surface.separators)
        assertEquals(
            "met {\\i1}mij{\\i0} vandaag",
            surface.recomposeOrThrow(listOf("met", "mij", "vandaag"))
        )
    }

    @Test
    fun lineBreakAndItalicTagsBecomeSeparators() {
        val surface = parseSurface("On the contrary \\Nfrom the start that he {\\i1}couldn't{\\i0} be X.")

        assertEquals(
            listOf("On the contrary", "from the start that he", "couldn't", "be X."),
            surface.segments
        )
        assertEquals(listOf(" \\N", " {\\i1}", "{\\i0} "), surface.separators)
        assertEquals(
            "Integendeel \\Nvanaf het begin dat hij {\\i1}niet{\\i0} X kon zijn.",
            surface.recomposeOrThrow(
                listOf("Integendeel", "vanaf het begin dat hij", "niet", "X kon zijn.")
            )
        )
    }

    @Test
    fun karaokeBetweenWordsUsesSeparators() {
        val surface = parseSurface("{\\k20}Good {\\K30}morning")

        assertEquals("{\\k20}", surface.prefixRaw)
        assertEquals(listOf("Good", "morning"), surface.segments)
        assertEquals(listOf(" {\\K30}"), surface.separators)
        assertEquals("{\\k20}Goed {\\K30}morgen", surface.recomposeOrThrow(listOf("Goed", "morgen")))
    }

    @Test
    fun karaokeInsideWordUsesInlineMarker() {
        val surface = parseSurface("go{\\k10}od")

        assertEquals(listOf("go<1/>od"), surface.segments)
        assertEquals(mapOf("<1/>" to "{\\k10}"), surface.inlineMarkers)
        assertEquals("go{\\k10}ed", surface.recomposeOrThrow(listOf("go<1/>ed")))
    }

    @Test
    fun drawingOnlyIsPreserveOnly() {
        val result = AssSsaSegmentSurfaceParser.parse("evt_0", "{\\p1}m 0 0 l 100 0{\\p0}")

        assertTrue(result is AssSsaSurfaceParseResult.PreserveOnly)
    }

    @Test
    fun validationRepairsSpacesAroundKnownMarkers() {
        val surface = parseSurface("I{\\c&H0F00A1&}nitiative")
        val repaired = surface.validateTranslatedSegments(listOf("I <1/> nitiatief")).getOrThrow()

        assertEquals(listOf("I<1/>nitiatief"), repaired)
        assertEquals("I{\\c&H0F00A1&}nitiatief", surface.recomposeOrThrow(repaired))
    }

    @Test
    fun validationRejectsRawAssSyntax() {
        val surface = parseSurface("Hello")
        val result = surface.validateTranslatedSegments(listOf("{\\i1}Hallo"))

        assertTrue(result.isFailure)
    }

    private fun parseSurface(text: String): AssSsaTranslationSurface {
        return when (val result = AssSsaSegmentSurfaceParser.parse("evt_0", text)) {
            is AssSsaSurfaceParseResult.Translatable -> result.surface
            is AssSsaSurfaceParseResult.PreserveOnly -> error("Expected translatable surface for $text")
        }
    }
}
