package com.nexio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AssSsaTimedTextDocumentTest {
    @Test
    fun assDialogueTranslatesVisibleTextAndPreservesOverrideBlocks() {
        val document = TimedTextDocument.parse(
            raw = """
                [Script Info]
                ScriptType: v4.00+

                [V4+ Styles]
                Format: Name, Fontname, Fontsize
                Style: Default,Arial,20

                [Events]
                Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\an8}Hello {\i1}world{\i0}\NNext line
            """.trimIndent(),
            url = "https://example.test/subtitle.ass"
        )

        assertNotNull(document)
        val parsed = document!!
        assertEquals("ass", parsed.extension)
        assertEquals(listOf("Hello ", "world", "Next line"), parsed.translatableBlocks.map { it.text })
        assertEquals(
            """
            [Script Info]
            ScriptType: v4.00+

            [V4+ Styles]
            Format: Name, Fontname, Fontsize
            Style: Default,Arial,20

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\an8}Hallo {\i1}wereld{\i0}\NVolgende regel
            """.trimIndent() + "\n",
            parsed.render(
                mapOf(
                    0 to "Hallo ",
                    1 to "wereld",
                    2 to "Volgende regel"
                )
            )
        )
    }

    @Test
    fun drawingPayloadIsPreservedUntilPZero() {
        val document = TimedTextDocument.parse(
            raw = """
                [Events]
                Format: Start, End, Text
                Dialogue: 0:00:01.00,0:00:02.00,{\p1}m 0 0 l 100 0 100 100 0 100{\p0}Square
            """.trimIndent(),
            url = "file:///tmp/subtitle.ass"
        )

        assertNotNull(document)
        val parsed = document!!
        assertEquals(listOf("Square"), parsed.translatableBlocks.map { it.text })
        assertEquals(
            """
            [Events]
            Format: Start, End, Text
            Dialogue: 0:00:01.00,0:00:02.00,{\p1}m 0 0 l 100 0 100 100 0 100{\p0}Vierkant
            """.trimIndent() + "\n",
            parsed.render(mapOf(0 to "Vierkant"))
        )
    }

    @Test
    fun commasInsideTextFieldStayInsideTextField() {
        val document = TimedTextDocument.parse(
            raw = """
                [Events]
                Format: Start, End, Text
                Dialogue: 0:00:01.00,0:00:02.00,Hello, world, again
            """.trimIndent(),
            url = "file:///tmp/subtitle.ssa"
        )

        assertNotNull(document)
        val parsed = document!!
        assertEquals("ssa", parsed.extension)
        assertEquals(listOf("Hello, world, again"), parsed.translatableBlocks.map { it.text })
        assertEquals(
            """
            [Events]
            Format: Start, End, Text
            Dialogue: 0:00:01.00,0:00:02.00,Hallo, wereld, opnieuw
            """.trimIndent() + "\n",
            parsed.render(mapOf(0 to "Hallo, wereld, opnieuw"))
        )
    }

    @Test
    fun karaokeTagsStayUntouchedAndSyllableTextTranslates() {
        val document = TimedTextDocument.parse(
            raw = """
                [Events]
                Format: Start, End, Text
                Dialogue: 0:00:01.00,0:00:02.00,{\k20}Good {\K30}morning
            """.trimIndent(),
            url = "file:///tmp/subtitle.ass"
        )

        assertNotNull(document)
        val parsed = document!!
        assertEquals(listOf("Good ", "morning"), parsed.translatableBlocks.map { it.text })
        assertEquals(
            """
            [Events]
            Format: Start, End, Text
            Dialogue: 0:00:01.00,0:00:02.00,{\k20}Goed {\K30}morgen
            """.trimIndent() + "\n",
            parsed.render(mapOf(0 to "Goed ", 1 to "morgen"))
        )
    }

    @Test
    fun unknownOverrideContentIsPreserved() {
        val document = TimedTextDocument.parse(
            raw = """
                [Events]
                Format: Start, End, Text
                Dialogue: 0:00:01.00,0:00:02.00,{\i1 some comment}Hello
            """.trimIndent(),
            url = "file:///tmp/subtitle.ass"
        )

        assertNotNull(document)
        val parsed = document!!
        assertEquals(listOf("Hello"), parsed.translatableBlocks.map { it.text })
        assertEquals(
            """
            [Events]
            Format: Start, End, Text
            Dialogue: 0:00:01.00,0:00:02.00,{\i1 some comment}Hallo
            """.trimIndent() + "\n",
            parsed.render(mapOf(0 to "Hallo"))
        )
    }

    @Test
    fun segmentSurfaceKeepsInlineStyleSeparatorsAroundTranslatedEquivalent() {
        val document = TimedTextDocument.parse(
            raw = """
                [Events]
                Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,I am {\i1}not{\i0} amused.\NReally.
            """.trimIndent(),
            url = "file:///tmp/subtitle.ass"
        )!!

        val surfaces = document.assSsaSegmentSurfaces()

        assertEquals(
            listOf(listOf("I am", "not", "amused.", "Really.")),
            surfaces.map { it.segments }
        )
        assertEquals(
            """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Ik ben {\i1}niet{\i0} geamuseerd.\NEcht niet.
            """.trimIndent() + "\n",
            document.renderAssSsaSegmentSurfaces(
                mapOf(
                    "ass_0" to listOf("Ik ben", "niet", "geamuseerd.", "Echt niet.")
                )
            )
        )
    }

    @Test
    fun segmentSurfaceRenderTranslatesDialogueAndCommentEvents() {
        val document = TimedTextDocument.parse(
            raw = """
                [Events]
                Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hello {\i1}world{\i0}
                Comment: 0,0:00:03.00,0:00:04.00,Default,,0,0,0,,Sign {\b1}text{\b0}
            """.trimIndent(),
            url = "file:///tmp/subtitle.ass"
        )!!

        val surfaces = document.assSsaSegmentSurfaces()

        assertEquals(listOf("ass_0", "ass_1"), surfaces.map { it.id })
        assertEquals(listOf("Hello", "world"), surfaces[0].segments)
        assertEquals(listOf("Sign", "text"), surfaces[1].segments)
        assertEquals(
            """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hallo {\i1}wereld{\i0}
            Comment: 0,0:00:03.00,0:00:04.00,Default,,0,0,0,,Bord {\b1}tekst{\b0}
            """.trimIndent() + "\n",
            document.renderAssSsaSegmentSurfaces(
                mapOf(
                    "ass_0" to listOf("Hallo", "wereld"),
                    "ass_1" to listOf("Bord", "tekst")
                )
            )
        )
    }

    @Test
    fun segmentSurfaceRenderPreservesOneFailedEventOnly() {
        val document = TimedTextDocument.parse(
            raw = """
                [Events]
                Format: Start, End, Text
                Dialogue: 0:00:01.00,0:00:02.00,Hello
                Dialogue: 0:00:03.00,0:00:04.00,World
            """.trimIndent(),
            url = "file:///tmp/subtitle.ass"
        )!!

        assertEquals(
            """
            [Events]
            Format: Start, End, Text
            Dialogue: 0:00:01.00,0:00:02.00,Hallo
            Dialogue: 0:00:03.00,0:00:04.00,World
            """.trimIndent() + "\n",
            document.renderAssSsaSegmentSurfaces(mapOf("ass_0" to listOf("Hallo")))
        )
    }

    @Test
    fun segmentSurfaceRenderPreservesRomajiFxButTranslatesEnglishFxLayer() {
        val document = TimedTextDocument.parse(
            raw = """
                [Events]
                Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                Dialogue: 0,0:00:43.20,0:00:45.00,Shingeki OP Romaji,,0,0,0,fx,{\fad(200,0)}Sie sind das Essen und wir sind die Jäger
                Dialogue: 0,0:00:43.20,0:00:45.00,Shingeki OP English,,0,0,0,fx,{\fad(200,0)}You're the prey, and we're the hunters.
            """.trimIndent(),
            url = "file:///tmp/subtitle.ass"
        )!!

        val surfaces = document.assSsaSegmentSurfaces()

        assertEquals(listOf("ass_1"), surfaces.map { it.id })
        assertEquals(listOf("You're the prey, and we're the hunters."), surfaces.single().segments)
        assertEquals(
            """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:43.20,0:00:45.00,Shingeki OP Romaji,,0,0,0,fx,{\fad(200,0)}Sie sind das Essen und wir sind die Jäger
            Dialogue: 0,0:00:43.20,0:00:45.00,Shingeki OP English,,0,0,0,fx,{\fad(200,0)}Jij bent de prooi, en wij zijn de jagers.
            """.trimIndent() + "\n",
            document.renderAssSsaSegmentSurfaces(
                mapOf("ass_1" to listOf("Jij bent de prooi, en wij zijn de jagers."))
            )
        )
    }

    @Test
    fun segmentSurfaceRenderReusesGlobalVisibleTextTranslationAcrossAnimatedSignCopies() {
        val document = TimedTextDocument.parse(
            raw = """
                [Events]
                Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                Dialogue: 0,0:23:54.60,0:23:54.90,Signs,,0,0,0,,{\pos(653,55)}Preview
                Dialogue: 1,0:23:55.20,0:23:55.60,Signs,,0,0,0,,{\pos(652,55)}Preview
            """.trimIndent(),
            url = "file:///tmp/subtitle.ass"
        )!!

        val surfaces = document.assSsaSegmentSurfaces()

        assertEquals(listOf("ass_0"), surfaces.map { it.id })
        assertEquals(
            """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:23:54.60,0:23:54.90,Signs,,0,0,0,,{\pos(653,55)}Vooruitblik
            Dialogue: 1,0:23:55.20,0:23:55.60,Signs,,0,0,0,,{\pos(652,55)}Vooruitblik
            """.trimIndent() + "\n",
            document.renderAssSsaSegmentSurfaces(mapOf("ass_0" to listOf("Vooruitblik")))
        )
    }
}
