package com.nexio.tv.data.repository

import com.nexio.tv.data.remote.api.TheIntroDbMediaResponse
import com.nexio.tv.data.remote.api.TheIntroDbSegmentTimestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TheIntroDbSegmentMapperTest {

    @Test
    fun `maps intro recap credits and preview with expected semantics`() {
        val response = TheIntroDbMediaResponse(
            intro = listOf(TheIntroDbSegmentTimestamp(startMs = null, endMs = 90_000)),
            recap = listOf(TheIntroDbSegmentTimestamp(startMs = 90_000, endMs = 150_000)),
            credits = listOf(TheIntroDbSegmentTimestamp(startMs = 1_200_000, endMs = null)),
            preview = listOf(TheIntroDbSegmentTimestamp(startMs = 1_260_000, endMs = null))
        )

        val mapped = TheIntroDbSegmentMapper.map(
            response = response,
            preferences = TheIntroDbSegmentPreferences()
        )

        assertEquals(listOf("intro", "recap", "credits", "preview"), mapped.map { it.type })
        assertEquals(0.0, mapped[0].startTime, 0.0)
        assertEquals(90.0, mapped[0].endTime, 0.0)
        assertEquals(Double.MAX_VALUE, mapped[2].endTime, 0.0)
        assertEquals("theintrodb", mapped[3].provider)
    }

    @Test
    fun `filters disabled segment types and invalid required end times`() {
        val response = TheIntroDbMediaResponse(
            intro = listOf(TheIntroDbSegmentTimestamp(startMs = 0, endMs = null)),
            recap = listOf(TheIntroDbSegmentTimestamp(startMs = 40_000, endMs = 60_000)),
            credits = listOf(TheIntroDbSegmentTimestamp(startMs = 500_000, endMs = null)),
            preview = listOf(TheIntroDbSegmentTimestamp(startMs = 520_000, endMs = 530_000))
        )

        val mapped = TheIntroDbSegmentMapper.map(
            response = response,
            preferences = TheIntroDbSegmentPreferences(
                showIntroButton = true,
                showRecapButton = false,
                showCreditsButton = true,
                showPreviewButton = false
            )
        )

        assertEquals(1, mapped.size)
        assertEquals("credits", mapped.single().type)
    }

    @Test
    fun `sorts multiple segments by start time`() {
        val response = TheIntroDbMediaResponse(
            credits = listOf(
                TheIntroDbSegmentTimestamp(startMs = 900_000, endMs = null),
                TheIntroDbSegmentTimestamp(startMs = 800_000, endMs = 850_000)
            )
        )

        val mapped = TheIntroDbSegmentMapper.map(
            response = response,
            preferences = TheIntroDbSegmentPreferences(showIntroButton = false, showRecapButton = false)
        )

        assertEquals(listOf(800.0, 900.0), mapped.map { it.startTime })
        assertTrue(mapped.all { it.type == "credits" })
    }
}
