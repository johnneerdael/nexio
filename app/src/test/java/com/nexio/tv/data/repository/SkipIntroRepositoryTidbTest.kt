package com.nexio.tv.data.repository

import com.nexio.tv.data.remote.api.TheIntroDbMediaResponse
import com.nexio.tv.data.remote.api.TheIntroDbSegmentTimestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipIntroRepositoryTidbTest {

    @Test
    fun `mapper includes all enabled TIDB segment types and sorts by start`() {
        val response = TheIntroDbMediaResponse(
            intro = listOf(TheIntroDbSegmentTimestamp(startMs = null, endMs = 61_000)),
            recap = listOf(TheIntroDbSegmentTimestamp(startMs = 5_000, endMs = 20_000)),
            credits = listOf(TheIntroDbSegmentTimestamp(startMs = 1_200_000, endMs = null)),
            preview = listOf(TheIntroDbSegmentTimestamp(startMs = 1_260_000, endMs = null))
        )

        val mapped = TheIntroDbSegmentMapper.map(
            response,
            TheIntroDbSegmentPreferences()
        )

        assertEquals(listOf("intro", "recap", "credits", "preview"), mapped.map { it.type })
        assertEquals(0.0, mapped.first().startTime, 0.0001)
        assertEquals(Double.MAX_VALUE, mapped[2].endTime, 0.0)
        assertEquals(Double.MAX_VALUE, mapped[3].endTime, 0.0)
    }

    @Test
    fun `mapper respects per-segment preferences`() {
        val response = TheIntroDbMediaResponse(
            intro = listOf(TheIntroDbSegmentTimestamp(startMs = 0, endMs = 10_000)),
            recap = listOf(TheIntroDbSegmentTimestamp(startMs = 10_000, endMs = 20_000)),
            credits = listOf(TheIntroDbSegmentTimestamp(startMs = 30_000, endMs = null)),
            preview = listOf(TheIntroDbSegmentTimestamp(startMs = 40_000, endMs = null))
        )

        val mapped = TheIntroDbSegmentMapper.map(
            response,
            TheIntroDbSegmentPreferences(
                showIntroButton = false,
                showRecapButton = true,
                showCreditsButton = false,
                showPreviewButton = true
            )
        )

        assertEquals(listOf("recap", "preview"), mapped.map { it.type })
    }

    @Test
    fun `arbiter routes explicit anime paths to anime primary`() {
        assertEquals(
            SkipProviderRoute.ANIME_PRIMARY,
            SkipProviderArbiter.resolve(contentType = "anime", effectiveId = "tt1234567", fallbackId = "tt1234567")
        )
        assertEquals(
            SkipProviderRoute.ANIME_PRIMARY,
            SkipProviderArbiter.resolve(contentType = "series", effectiveId = "mal:57658:1", fallbackId = "tt1234567")
        )
        assertEquals(
            SkipProviderRoute.ANIME_PRIMARY,
            SkipProviderArbiter.resolve(contentType = "series", effectiveId = "tt1234567", fallbackId = "addon:anime:tt1234567")
        )
    }

    @Test
    fun `arbiter routes normal movie and tv content to TheIntroDB`() {
        assertEquals(
            SkipProviderRoute.THEINTRODB,
            SkipProviderArbiter.resolve(contentType = "movie", effectiveId = "tt0111161", fallbackId = "tt0111161")
        )
        assertEquals(
            SkipProviderRoute.THEINTRODB,
            SkipProviderArbiter.resolve(contentType = "series", effectiveId = "1399:1:1", fallbackId = "1399")
        )
    }

    @Test
    fun `mapper supports multiple same-type segments`() {
        val response = TheIntroDbMediaResponse(
            intro = listOf(
                TheIntroDbSegmentTimestamp(startMs = 0, endMs = 10_000),
                TheIntroDbSegmentTimestamp(startMs = 20_000, endMs = 25_000)
            )
        )

        val mapped = TheIntroDbSegmentMapper.map(response, TheIntroDbSegmentPreferences())

        assertEquals(2, mapped.size)
        assertTrue(mapped.all { it.type == "intro" })
        assertEquals(20.0, mapped[1].startTime, 0.0001)
    }
}
