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
            railId = "trending",
            itemKey = "home:tmdb:movie:550",
            firstPaintSource = "RAIL_PREVIEW",
            trigger = "VISIBLE",
            priority = "HIGH",
            workClass = "NETWORK"
        )
        events.emitHomeHydrationOverlayWritten(
            itemKey = "home:tmdb:movie:550",
            canonicalProvider = "tmdb",
            canonicalId = "550",
            imdbId = "tt0137523",
            displayHash = "overlay-hash"
        )
        events.emitHomeHydrationApplied(
            railId = "trending",
            itemKey = "home:tmdb:movie:550",
            firstPaintSource = "RAIL_PREVIEW",
            canonicalProvider = "tmdb",
            canonicalId = "550",
            imdbId = "tt0137523",
            trigger = "VISIBLE",
            priority = "HIGH",
            workClass = "NETWORK",
            changedFields = listOf("title", "poster", "rating"),
            displayHashBefore = "preview-hash",
            displayHashAfter = "overlay-hash",
            rowOrderChanged = false,
            focusChanged = false,
            networkExecuted = true,
            cacheDecision = "MISS_THEN_WRITE"
        )
        events.emitHomeHydrationIgnored(
            itemKey = "home:tmdb:movie:550",
            reason = "profile_generation_changed",
            trigger = "VISIBLE"
        )
        events.emitHomeHydrationFailedUsingPreview(
            itemKey = "home:tmdb:movie:550",
            reason = "identity_resolution_failed",
            trigger = "VISIBLE"
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
    fun `home hydration started includes planned scheduling fields only`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s-home" })

        events.emitHomeHydrationStarted(
            railId = "popular-series",
            itemKey = "home:tmdb:series:1399",
            firstPaintSource = "ADDON_META_PREVIEW",
            trigger = "FOCUS",
            priority = "MEDIUM",
            workClass = "CACHE"
        )

        val envelope = sink.events.single()
        assertEquals("home.hydration_started", envelope.eventType)
        val payload = envelope.payload as Map<*, *>
        assertEquals(
            setOf("railId", "itemKey", "firstPaintSource", "trigger", "priority", "workClass"),
            payload.keys
        )
        assertEquals("popular-series", payload["railId"])
        assertEquals("home:tmdb:series:1399", payload["itemKey"])
        assertEquals("ADDON_META_PREVIEW", payload["firstPaintSource"])
        assertEquals("FOCUS", payload["trigger"])
        assertEquals("MEDIUM", payload["priority"])
        assertEquals("CACHE", payload["workClass"])
    }

    @Test
    fun `home hydration started accepts and emits null rail id`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s-home" })

        events.emitHomeHydrationStarted(
            railId = null,
            itemKey = "home:tmdb:series:1399",
            firstPaintSource = "ADDON_META_PREVIEW",
            trigger = "HERO",
            priority = "LOW",
            workClass = "CACHE"
        )

        val payload = sink.events.single().payload as Map<*, *>
        assertEquals(
            setOf("railId", "itemKey", "firstPaintSource", "trigger", "priority", "workClass"),
            payload.keys
        )
        assertTrue(payload.containsKey("railId"))
        assertEquals(null, payload["railId"])
    }

    @Test
    fun `home hydration overlay written includes planned canonical identity fields only`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s-home" })

        events.emitHomeHydrationOverlayWritten(
            itemKey = "home:tmdb:series:1399",
            canonicalProvider = "tvdb",
            canonicalId = "121361",
            imdbId = "tt0944947",
            displayHash = "overlay-456"
        )

        val envelope = sink.events.single()
        assertEquals("home.hydration_overlay_written", envelope.eventType)
        val payload = envelope.payload as Map<*, *>
        assertEquals(
            setOf("itemKey", "canonicalProvider", "canonicalId", "imdbId", "displayHash"),
            payload.keys
        )
        assertEquals("home:tmdb:series:1399", payload["itemKey"])
        assertEquals("tvdb", payload["canonicalProvider"])
        assertEquals("121361", payload["canonicalId"])
        assertEquals("tt0944947", payload["imdbId"])
        assertEquals("overlay-456", payload["displayHash"])
    }

    @Test
    fun `home hydration applied includes planned canonical hashes stability network and cache fields only`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s-home" })

        events.emitHomeHydrationApplied(
            railId = "popular-series",
            itemKey = "home:tmdb:series:1399",
            firstPaintSource = "RAIL_PREVIEW",
            canonicalProvider = "tvdb",
            canonicalId = "121361",
            imdbId = "tt0944947",
            trigger = "VISIBLE",
            priority = "HIGH",
            workClass = "NETWORK",
            changedFields = listOf("title", "description", "rating"),
            displayHashBefore = "before-123",
            displayHashAfter = "after-456",
            rowOrderChanged = false,
            focusChanged = true,
            networkExecuted = false,
            cacheDecision = "CACHE_HIT"
        )

        val envelope = sink.events.single()
        assertEquals("home.hydration_applied", envelope.eventType)
        val payload = envelope.payload as Map<*, *>
        assertEquals(
            setOf(
                "railId",
                "itemKey",
                "firstPaintSource",
                "canonicalProvider",
                "canonicalId",
                "imdbId",
                "trigger",
                "priority",
                "workClass",
                "changedFields",
                "displayHashBefore",
                "displayHashAfter",
                "rowOrderChanged",
                "focusChanged",
                "networkExecuted",
                "cacheDecision"
            ),
            payload.keys
        )
        assertEquals("popular-series", payload["railId"])
        assertEquals("home:tmdb:series:1399", payload["itemKey"])
        assertEquals("RAIL_PREVIEW", payload["firstPaintSource"])
        assertEquals("tvdb", payload["canonicalProvider"])
        assertEquals("121361", payload["canonicalId"])
        assertEquals("tt0944947", payload["imdbId"])
        assertEquals("VISIBLE", payload["trigger"])
        assertEquals("HIGH", payload["priority"])
        assertEquals("NETWORK", payload["workClass"])
        assertEquals(listOf("title", "description", "rating"), payload["changedFields"])
        assertEquals("before-123", payload["displayHashBefore"])
        assertEquals("after-456", payload["displayHashAfter"])
        assertEquals(false, payload["rowOrderChanged"])
        assertEquals(true, payload["focusChanged"])
        assertEquals(false, payload["networkExecuted"])
        assertEquals("CACHE_HIT", payload["cacheDecision"])
    }

    @Test
    fun `home hydration applied accepts and emits null rail id and cache decision`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s-home" })

        events.emitHomeHydrationApplied(
            railId = null,
            itemKey = "home:tmdb:series:1399",
            firstPaintSource = "RAIL_PREVIEW",
            canonicalProvider = "tvdb",
            canonicalId = "121361",
            imdbId = null,
            trigger = "VISIBLE",
            priority = "LOW",
            workClass = "NETWORK",
            changedFields = emptyList(),
            displayHashBefore = "before-123",
            displayHashAfter = "after-456",
            rowOrderChanged = false,
            focusChanged = false,
            networkExecuted = false,
            cacheDecision = null
        )

        val payload = sink.events.single().payload as Map<*, *>
        assertEquals(
            setOf(
                "railId",
                "itemKey",
                "firstPaintSource",
                "canonicalProvider",
                "canonicalId",
                "imdbId",
                "trigger",
                "priority",
                "workClass",
                "changedFields",
                "displayHashBefore",
                "displayHashAfter",
                "rowOrderChanged",
                "focusChanged",
                "networkExecuted",
                "cacheDecision"
            ),
            payload.keys
        )
        assertTrue(payload.containsKey("railId"))
        assertTrue(payload.containsKey("cacheDecision"))
        assertEquals(null, payload["railId"])
        assertEquals(null, payload["cacheDecision"])
    }

    @Test
    fun `home hydration ignored records ignore reason and trigger only`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s-home" })

        events.emitHomeHydrationIgnored(
            itemKey = "home:tmdb:movie:11",
            reason = "language_changed",
            trigger = "FOCUS"
        )

        val envelope = sink.events.single()
        assertEquals("home.hydration_ignored", envelope.eventType)
        val payload = envelope.payload as Map<*, *>
        assertEquals(setOf("itemKey", "reason", "trigger"), payload.keys)
        assertEquals("home:tmdb:movie:11", payload["itemKey"])
        assertEquals("language_changed", payload["reason"])
        assertEquals("FOCUS", payload["trigger"])
    }

    @Test
    fun `home hydration failed using preview records reason and trigger only`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s-home" })

        events.emitHomeHydrationFailedUsingPreview(
            itemKey = "home:tmdb:movie:11",
            reason = "identity_resolution_failed",
            trigger = "VISIBLE"
        )

        val envelope = sink.events.single()
        assertEquals("home.hydration_failed_using_preview", envelope.eventType)
        val payload = envelope.payload as Map<*, *>
        assertEquals(setOf("itemKey", "reason", "trigger"), payload.keys)
        assertEquals("home:tmdb:movie:11", payload["itemKey"])
        assertEquals("identity_resolution_failed", payload["reason"])
        assertEquals("VISIBLE", payload["trigger"])
    }
}
