package com.nexio.tv.core.trace

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.toHomeDisplayMetadata
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDisplayMetadataFirstPaintTraceTest {
    @After
    fun reset() {
        FirstPaintTracer.install(
            events = TraceMetadataEvents(NoopRuntimeTraceSink, sessionId = { null }),
            profileHashProvider = { null }
        )
    }

    @Test
    fun `MetaPreview to HomeDisplayMetadata emits metadata_first_paint`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        FirstPaintTracer.install(events, profileHashProvider = { "ph_abc" })

        val preview = MetaPreview(
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
        preview.toHomeDisplayMetadata()

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
