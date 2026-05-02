package com.nexio.tv.core.trace

import com.nexio.tv.core.integration.RecordingTraceSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceMetadataEventsTest {
    @Test
    fun `emitFirstPaint emits metadata_first_paint envelope with required fields`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })

        events.emitFirstPaint(
            contentId = "tt12343534",
            itemType = "series",
            surface = SourceSurface.HOME,
            source = "ADDON_META_PREVIEW",
            routerExecuted = false,
            networkExecuted = false,
            fieldsUsed = listOf("title", "poster", "description"),
            profileHash = "ph_abc"
        )

        assertEquals(1, sink.events.size)
        val envelope = sink.events.first()
        assertEquals("metadata.first_paint", envelope.eventType)
        val payload = envelope.payload as Map<*, *>
        assertEquals("tt12343534", payload["contentId"])
        assertEquals("series", payload["itemType"])
        assertEquals("HOME", payload["surface"])
        assertEquals("ADDON_META_PREVIEW", payload["source"])
        assertEquals(false, payload["routerExecuted"])
        assertEquals(false, payload["networkExecuted"])
        assertEquals("ph_abc", payload["profileHash"])
        @Suppress("UNCHECKED_CAST")
        val fields = payload["fieldsUsed"] as List<String>
        assertTrue(fields.containsAll(listOf("title", "poster", "description")))
    }

    @Test
    fun `no emission when sessionId returns null`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { null })
        events.emitFirstPaint(
            contentId = "x",
            itemType = "movie",
            surface = SourceSurface.HOME,
            source = "ADDON_META_PREVIEW",
            routerExecuted = false,
            networkExecuted = false,
            fieldsUsed = emptyList(),
            profileHash = null
        )
        assertTrue("must not emit when no active session", sink.events.isEmpty())
    }

    @Test
    fun `home hydration lifecycle emits ordered envelopes with shared sequence`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "home-session" })

        events.emitHomeHydrationStarted(
            itemKey = "home:tmdb:movie:550",
            rowKey = "trending",
            source = "VISIBLE",
            profileHash = "profile-a",
            language = "en-US",
            cacheDecision = "MISS",
            networkExecuted = true
        )
        events.emitHomeHydrationOverlayWritten(
            itemKey = "home:tmdb:movie:550",
            overlayKey = "tmdb:movie:550:en-US",
            changedFields = listOf("title", "poster", "rating"),
            overlayHash = "overlay-hash",
            cacheDecision = "WRITE",
            networkExecuted = true
        )
        events.emitHomeHydrationApplied(
            itemKey = "home:tmdb:movie:550",
            rowKey = "trending",
            changedFields = listOf("title", "poster", "rating"),
            beforeHash = "preview-hash",
            afterHash = "overlay-hash",
            rowOrderStable = true,
            focusedItemStable = true,
            cacheDecision = "MISS_THEN_WRITE",
            networkExecuted = true
        )
        events.emitHomeHydrationIgnored(
            itemKey = "home:tmdb:movie:550",
            rowKey = "trending",
            reason = "profile_generation_changed",
            startedProfileHash = "profile-a",
            activeProfileHash = "profile-b"
        )
        events.emitHomeHydrationFailedUsingPreview(
            itemKey = "home:tmdb:movie:550",
            rowKey = "trending",
            reason = "identity_resolution_failed",
            previewHash = "preview-hash",
            cacheDecision = "MISS",
            networkExecuted = true
        )

        assertEquals(
            listOf(
                "home.hydration_started",
                "home.hydration_overlay_written",
                "home.hydration_applied",
                "home.hydration_ignored",
                "home.hydration_failed_using_preview"
            ),
            sink.events.map { it.eventType }
        )
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), sink.events.map { it.sequence })
        assertTrue(sink.events.all { it.traceSessionId == "home-session" })
    }

    @Test
    fun `home hydration applied includes item hashes stability network and cache info`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s-home" })

        events.emitHomeHydrationApplied(
            itemKey = "home:tmdb:series:1399",
            rowKey = "popular-series",
            changedFields = listOf("title", "description", "rating"),
            beforeHash = "before-123",
            afterHash = "after-456",
            rowOrderStable = true,
            focusedItemStable = false,
            cacheDecision = "CACHE_HIT",
            networkExecuted = false
        )

        val envelope = sink.events.single()
        assertEquals("home.hydration_applied", envelope.eventType)
        val payload = envelope.payload as Map<*, *>
        assertEquals("home:tmdb:series:1399", payload["itemKey"])
        assertEquals("popular-series", payload["rowKey"])
        assertEquals(listOf("title", "description", "rating"), payload["changedFields"])
        assertEquals("before-123", payload["beforeHash"])
        assertEquals("after-456", payload["afterHash"])
        assertEquals(true, payload["rowOrderStable"])
        assertEquals(false, payload["focusedItemStable"])
        assertEquals("CACHE_HIT", payload["cacheDecision"])
        assertEquals(false, payload["networkExecuted"])
    }

    @Test
    fun `home hydration ignored records ignore reason`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s-home" })

        events.emitHomeHydrationIgnored(
            itemKey = "home:tmdb:movie:11",
            rowKey = "watchlist",
            reason = "language_changed",
            startedProfileHash = "profile-en",
            activeProfileHash = "profile-fr"
        )

        val envelope = sink.events.single()
        assertEquals("home.hydration_ignored", envelope.eventType)
        val payload = envelope.payload as Map<*, *>
        assertEquals("home:tmdb:movie:11", payload["itemKey"])
        assertEquals("watchlist", payload["rowKey"])
        assertEquals("language_changed", payload["reason"])
        assertEquals("profile-en", payload["startedProfileHash"])
        assertEquals("profile-fr", payload["activeProfileHash"])
    }
}
