package com.nexio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class AssSsaTranslationPlannerTest {
    private val format = AssSsaEventFormat.standardDialogue()

    @Test
    fun normalDialogueIsTranslated() {
        val records = listOf(record(style = "Default 3", effect = "", text = "I will destroy them."))

        val plan = AssSsaTranslationPlanner.plan(records)

        assertEquals(AssSsaTranslationAction.Translate(canonicalIndex = 0), plan.actions.single())
        assertEquals("I will destroy them.", plan.visibleTexts.single())
    }

    @Test
    fun drawingEventIsPreserved() {
        val records = listOf(record(style = "Signs", effect = "", text = "{\\p1}m 0 0 l 100 0{\\p0}"))

        val plan = AssSsaTranslationPlanner.plan(records)

        assertEquals(
            AssSsaTranslationAction.Preserve(AssSsaPreserveReason.Drawing),
            plan.actions.single()
        )
    }

    @Test
    fun nonEnglishFxKaraokeFragmentIsPreserved() {
        val records = listOf(
            record(style = "Shingeki OP Romaji", effect = "fx", text = "{\\pos(339,69)\\K22}ie")
        )

        val plan = AssSsaTranslationPlanner.plan(records)

        assertEquals(
            AssSsaTranslationAction.Preserve(AssSsaPreserveReason.NonReadableEffect),
            plan.actions.single()
        )
    }

    @Test
    fun nonEnglishFxFullRomajiLineIsPreserved() {
        val records = listOf(
            record(style = "Shingeki OP Romaji", effect = "fx", text = "{\\fad(200,0)}yogi no hanei")
        )

        val plan = AssSsaTranslationPlanner.plan(records)

        assertEquals(
            AssSsaTranslationAction.Preserve(AssSsaPreserveReason.NonReadableEffect),
            plan.actions.single()
        )
    }

    @Test
    fun englishStyleFxReadableLineIsTranslated() {
        val records = listOf(
            record(
                style = "Shingeki OP English",
                effect = "fx",
                text = "{\\pos(357,697)}You're the prey, and we're the hunters."
            )
        )

        val plan = AssSsaTranslationPlanner.plan(records)

        assertEquals(AssSsaTranslationAction.Translate(canonicalIndex = 0), plan.actions.single())
        assertEquals("You're the prey, and we're the hunters.", plan.visibleTexts.single())
    }

    @Test
    fun globalVisibleTextDuplicateReusesCanonicalTranslationAcrossDifferentTimes() {
        val records = listOf(
            record(
                start = "0:23:54.60",
                end = "0:23:55.00",
                style = "Signs",
                effect = "",
                text = "{\\pos(653,55)}Preview"
            ),
            record(
                start = "0:23:55.20",
                end = "0:23:55.60",
                style = "Signs",
                effect = "",
                text = "{\\pos(652,55)}Preview"
            )
        )

        val plan = AssSsaTranslationPlanner.plan(records)

        assertEquals(
            listOf(
                AssSsaTranslationAction.Translate(canonicalIndex = 0),
                AssSsaTranslationAction.DuplicateOf(canonicalIndex = 0)
            ),
            plan.actions
        )
    }

    @Test
    fun windowBudgetPreservesSignOverflowInsideDenseWindow() {
        val records = listOf(
            record(start = "0:10:52.90", style = "Signs", effect = "", text = "{\\pos(1,1)}A"),
            record(start = "0:10:52.91", style = "Signs", effect = "", text = "{\\pos(2,1)}B"),
            record(start = "0:10:52.92", style = "Signs", effect = "", text = "{\\pos(3,1)}C"),
            record(start = "0:10:52.93", style = "Signs", effect = "", text = "{\\pos(4,1)}D"),
            record(start = "0:10:52.94", style = "Default 3", effect = "", text = "Keep dialogue")
        )

        val plan = AssSsaTranslationPlanner.plan(
            records = records,
            config = AssSsaTranslationPlanConfig(
                liveWindowMs = 250,
                maxTranslatePerWindow = 3,
                budgetPreferPreserveStyles = setOf("signs")
            )
        )

        assertEquals(AssSsaTranslationAction.Translate(canonicalIndex = 0), plan.actions[0])
        assertEquals(AssSsaTranslationAction.Translate(canonicalIndex = 1), plan.actions[1])
        assertEquals(AssSsaTranslationAction.Translate(canonicalIndex = 2), plan.actions[2])
        assertEquals(
            AssSsaTranslationAction.Preserve(AssSsaPreserveReason.WindowBudget),
            plan.actions[3]
        )
        assertEquals(AssSsaTranslationAction.Translate(canonicalIndex = 4), plan.actions[4])
    }

    private fun record(
        layer: String = "0",
        start: String = "0:00:01.00",
        end: String = "0:00:03.00",
        style: String,
        effect: String,
        text: String
    ): AssSsaEventRecord {
        return AssSsaEventRecord(
            kind = "Dialogue",
            prefix = "Dialogue: ",
            format = format,
            values = listOf(layer, start, end, style, "", "0", "0", "0", effect, text)
        )
    }
}
