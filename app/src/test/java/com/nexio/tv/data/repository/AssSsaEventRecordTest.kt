package com.nexio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssSsaEventRecordTest {
    @Test
    fun parsesDialogueWithFormatDefinedTextFieldAndCommasInText() {
        val format = AssSsaEventFormat.parse(
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text"
        )!!
        val record = AssSsaEventRecord.parseDialogueLine(
            line = "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hello, world, again",
            format = format
        )!!

        assertEquals("Dialogue", record.kind)
        assertEquals("0:00:01.00", record.field("Start"))
        assertEquals("0:00:03.00", record.field("End"))
        assertEquals("Hello, world, again", record.text)
        assertEquals(
            "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hallo, wereld, opnieuw",
            record.withText("Hallo, wereld, opnieuw").render()
        )
    }

    @Test
    fun returnsNullWhenTextFieldIsMissing() {
        val format = AssSsaEventFormat.parse("Format: Start, End, Style")!!

        assertNull(
            AssSsaEventRecord.parseDialogueLine(
                line = "Dialogue: 0:00:01.00,0:00:03.00,Default",
                format = format
            )
        )
    }

    @Test
    fun parsesMatroskaAssPayloadUsingContainerTiming() {
        val format = AssSsaEventFormat.matroskaAss()
        val record = AssSsaEventRecord.parseMatroskaSample(
            sampleText = "17,0,Default,,0,0,0,,Hello from mkv",
            format = format,
            timeUs = 1_000_000L,
            durationUs = 2_000_000L
        )!!

        assertEquals("Dialogue", record.kind)
        assertEquals("0:00:01.00", record.field("Start"))
        assertEquals("0:00:03.00", record.field("End"))
        assertEquals("Hello from mkv", record.text)
        assertEquals(
            "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hallo uit mkv",
            record.withText("Hallo uit mkv").render()
        )
    }
}
