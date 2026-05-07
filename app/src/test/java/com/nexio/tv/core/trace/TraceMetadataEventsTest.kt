package com.nexio.tv.core.trace

import com.google.gson.Gson
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
    fun `emits logcat only envelope when sessionId returns null`() {
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
        assertEquals(1, sink.events.size)
        assertEquals("logcat-only", sink.events.single().traceSessionId)
    }

    @Test
    fun `screensaver events include shared surface source parity fields and optional values`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "screensaver-session" })

        events.emitScreensaverCandidatePoolBuilt(
            profileHash = "profile-hash",
            source = "RESOLVED_DISPLAY_SURFACE",
            imageCandidateCount = 2,
            trailerCandidateCount = 1
        )
        events.emitScreensaverSlideSelected(
            itemKey = "movie:tmdb:550",
            source = "RESOLVED_DISPLAY_SURFACE",
            ratingSource = null,
            artworkSource = "TOP_POSTERS",
            matchesHomeSurface = true
        )
        events.emitScreensaverTrailerCandidateSelected(
            itemKey = "movie:tmdb:550",
            source = "RESOLVED_DISPLAY_SURFACE",
            trailerSource = null,
            fallbackYouTubeIdsOnly = true
        )
        events.emitScreensaverSurfacePublished(
            surface = "screensaver",
            published = true,
            itemCount = 40,
            logoCount = 31,
            trailerCandidateCount = 40,
            selectedRefCount = 25,
            fallbackIdCount = 15
        )
        events.emitScreensaverSchedulerState(
            stage = "start_prepared",
            route = "home",
            eligible = true,
            visible = false,
            slideCount = 40,
            trailerCandidateCount = 40,
            trailerEnabled = true,
            trailerSessionReady = true,
            lifecycleState = "RESUMED",
            reason = "ready"
        )

        assertEquals(
            listOf(
                "screensaver.candidate_pool_built",
                "screensaver.slide_selected",
                "screensaver.trailer_candidate_selected",
                "screensaver.surface_published",
                "screensaver.scheduler_state"
            ),
            sink.events.map { it.eventType }
        )
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), sink.events.map { it.sequence })
        assertTrue(sink.events.all { it.traceSessionId == "screensaver-session" })

        val poolPayload = sink.events[0].payload as Map<*, *>
        assertEquals("profile-hash", poolPayload["profileHash"])
        assertEquals("RESOLVED_DISPLAY_SURFACE", poolPayload["source"])
        assertEquals(2, poolPayload["imageCandidateCount"])
        assertEquals(1, poolPayload["trailerCandidateCount"])

        val slidePayload = sink.events[1].payload as Map<*, *>
        assertEquals("movie:tmdb:550", slidePayload["itemKey"])
        assertEquals("none", slidePayload["ratingSource"])
        assertEquals("TOP_POSTERS", slidePayload["artworkSource"])
        assertEquals(true, slidePayload["matchesHomeSurface"])

        val trailerPayload = sink.events[2].payload as Map<*, *>
        assertEquals("none", trailerPayload["trailerSource"])
        assertEquals(true, trailerPayload["fallbackYouTubeIdsOnly"])

        val surfacePayload = sink.events[3].payload as Map<*, *>
        assertEquals("screensaver", surfacePayload["surface"])
        assertEquals(true, surfacePayload["published"])
        assertEquals(40, surfacePayload["itemCount"])
        assertEquals(31, surfacePayload["logoCount"])
        assertEquals(40, surfacePayload["trailerCandidateCount"])

        val schedulerPayload = sink.events[4].payload as Map<*, *>
        assertEquals("start_prepared", schedulerPayload["stage"])
        assertEquals(true, schedulerPayload["eligible"])
        assertEquals(true, schedulerPayload["trailerEnabled"])
        assertEquals(true, schedulerPayload["trailerSessionReady"])
    }

    @Test
    fun `screensaver candidate pool preserves null profile hash when unavailable`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "screensaver-session" })

        events.emitScreensaverCandidatePoolBuilt(
            profileHash = null,
            source = "RESOLVED_DISPLAY_SURFACE",
            imageCandidateCount = 0,
            trailerCandidateCount = 0
        )

        val payload = sink.events.single().payload as Map<*, *>
        assertTrue(payload.containsKey("profileHash"))
        assertEquals(null, payload["profileHash"])
    }

    @Test
    fun `trailer diagnostics emit through metadata screensaver and runtime channels`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "trailer-session" })

        events.emitTrailerPreviewRequest(
            itemId = "tt123",
            itemType = "movie",
            fallbackRef = "abc123",
            published = false,
            negativeCached = false,
            forceRefresh = false
        )
        events.emitTrailerSurfaceSynced(
            surface = "home",
            itemCount = 5,
            selectedRefCount = 2,
            youtubeRefCount = 1,
            inAppRefCount = 1,
            externalRefCount = 0,
            fallbackIdCount = 3
        )
        events.emitScreensaverTrailerPlaybackResolution(
            itemId = "tt123",
            itemType = "movie",
            inputRef = "item_lookup",
            selectedRef = "youtube",
            result = "playback",
            reason = "playback_ready"
        )
        events.emitRuntimeTrailerPlaybackSource(
            ref = "youtube",
            result = "playback",
            hasVideo = true,
            hasAudio = false,
            hasUserAgent = true
        )

        assertEquals(
            listOf(
                "metadata.trailer_preview_request",
                "metadata.trailer_surface_synced",
                "screensaver.trailer_playback_resolution",
                "runtime.trailer_playback_source"
            ),
            sink.events.map { it.eventType }
        )
        assertEquals(LogcatTraceChannel.META_ROUTE, LogcatTraceChannel.forEventType("metadata.trailer_preview_request"))
        assertEquals(LogcatTraceChannel.META_ROUTE, LogcatTraceChannel.forEventType("screensaver.trailer_playback_resolution"))
        assertEquals(LogcatTraceChannel.INT_RUNTIME, LogcatTraceChannel.forEventType("runtime.trailer_playback_source"))
    }

    @Test
    fun `media clip events emit durable candidate diagnostics`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "clip-session" })

        events.emitMediaClipCandidateStored(
            itemKey = "tmdb:550",
            provider = "TMDB",
            clipType = "TRAILER",
            site = "YOUTUBE",
            videoId = "abc123",
            scope = "title",
            cacheDecision = "WRITE"
        )
        events.emitMediaClipCandidateSelected(
            surface = "SCREENSAVER",
            itemKey = "tmdb:550",
            provider = "TMDB",
            clipType = "TRAILER",
            site = "YOUTUBE",
            videoId = "abc123",
            cacheDecision = "HIT",
            playbackUrlResolvedAtPlayTime = true
        )

        assertEquals(
            listOf("media_clip.candidate_stored", "media_clip.candidate_selected"),
            sink.events.map { it.eventType }
        )
        val selectedPayload = sink.events[1].payload as Map<*, *>
        assertEquals("SCREENSAVER", selectedPayload["surface"])
        assertEquals("tmdb:550", selectedPayload["itemKey"])
        assertEquals("TMDB", selectedPayload["provider"])
        assertEquals("abc123", selectedPayload["videoId"])
        assertEquals(true, selectedPayload["playbackUrlResolvedAtPlayTime"])
        assertEquals(LogcatTraceChannel.META_ROUTE, LogcatTraceChannel.forEventType("media_clip.candidate_selected"))
    }

    @Test
    fun `modern home trailer autoplay gate emits metadata diagnostics`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "trailer-session" })

        events.emitModernHomeTrailerAutoplayGate(
            stage = "request_eval",
            focusKey = "row:item",
            itemId = "tt123",
            itemType = "movie",
            autoplayEnabled = true,
            delaySeconds = 60,
            screensaverVisible = false,
            startupSplashVisible = false,
            externalTakeoverActive = false,
            selectionStillFocused = true,
            lifecycleResumed = true,
            trailerPlaybackUnlocked = true,
            hasTrailerMetadata = false,
            hasResolvedPreview = false,
            hasResolvedExternalPreview = false,
            loading = false,
            negativeCached = false,
            alreadyRetried = false,
            shouldProceed = true,
            reason = "request_preview"
        )

        assertEquals("metadata.trailer_autoplay_gate", sink.events.single().eventType)
        assertEquals(LogcatTraceChannel.META_ROUTE, LogcatTraceChannel.forEventType("metadata.trailer_autoplay_gate"))
        val payload = sink.events.single().payload as Map<*, *>
        assertEquals("request_eval", payload["stage"])
        assertEquals("tt123", payload["itemId"])
        assertEquals(true, payload["trailerPlaybackUnlocked"])
        assertEquals(false, payload["hasTrailerMetadata"])
        assertEquals("request_preview", payload["reason"])
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
        assertEquals("none", payload["railId"])
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
        assertEquals("none", payload["railId"])
        assertEquals("none", payload["cacheDecision"])
    }

    @Test
    fun `home hydration nullable optional fields survive default gson serialization`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s-home" })

        events.emitHomeHydrationOverlayWritten(
            itemKey = "home:tmdb:series:1399",
            canonicalProvider = "tvdb",
            canonicalId = "121361",
            imdbId = null,
            displayHash = "overlay-456"
        )
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

        val jsonLines = sink.events.map { Gson().toJson(it) }
        assertTrue(jsonLines[0].contains("\"imdbId\":\"none\""))
        assertTrue(jsonLines[1].contains("\"railId\":\"none\""))
        assertTrue(jsonLines[1].contains("\"imdbId\":\"none\""))
        assertTrue(jsonLines[1].contains("\"cacheDecision\":\"none\""))
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
