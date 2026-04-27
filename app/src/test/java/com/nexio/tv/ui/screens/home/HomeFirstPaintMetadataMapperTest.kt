package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.trace.FirstPaintTracer
import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.toHomeDisplayMetadata
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFirstPaintMetadataMapperTest {
    @After
    fun reset() {
        FirstPaintTracer.install(
            events = TraceMetadataEvents(NoopRuntimeTraceSink, sessionId = { null }),
            profileHashProvider = { null }
        )
    }

    private fun preview(): MetaPreview = MetaPreview(
        id = "tt0111161",
        type = ContentType.MOVIE,
        name = "The Shawshank Redemption",
        poster = "https://example.com/poster.jpg",
        posterShape = PosterShape.POSTER,
        background = "https://example.com/bg.jpg",
        logo = null,
        description = "Two imprisoned men bond over a number of years.",
        releaseInfo = "1994",
        imdbRating = 9.3f,
        genres = listOf("Drama")
    )

    @Test
    fun `pure domain conversion does not emit first_paint`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        FirstPaintTracer.install(events, profileHashProvider = { "ph_abc" })

        preview().toHomeDisplayMetadata() // pure domain function — must NOT emit

        assertTrue(
            "pure conversion must not emit first_paint, got: ${sink.events.map { it.eventType }}",
            sink.events.none { it.eventType == "metadata.first_paint" }
        )
    }

    @Test
    fun `UI wrapper emits metadata_first_paint with full payload`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        FirstPaintTracer.install(events, profileHashProvider = { "ph_abc" })

        preview().toFirstPaintHomeDisplayMetadata()

        val firstPaint = sink.events.filter { it.eventType == "metadata.first_paint" }
        assertEquals(1, firstPaint.size)
        val payload = firstPaint.first().payload as Map<*, *>
        assertEquals("tt0111161", payload["contentId"])
        assertEquals("movie", payload["itemType"])
        assertEquals("HOME", payload["surface"])
        assertEquals("ADDON_META_PREVIEW", payload["source"])
        assertEquals(false, payload["routerExecuted"])
        assertEquals(false, payload["networkExecuted"])
        assertEquals("ph_abc", payload["profileHash"])
        @Suppress("UNCHECKED_CAST")
        val fields = payload["fieldsUsed"] as List<String>
        assertTrue(fields.contains("title"))
        assertTrue(fields.contains("poster"))
        assertTrue(fields.contains("background"))
        assertTrue(fields.contains("description"))
        assertTrue(fields.contains("releaseInfo"))
    }
}
