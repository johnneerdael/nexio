package com.nexio.tv.core.trace

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class LogcatRuntimeTraceSinkTest {

    private val allEnabled = object : LogcatChannelGate {
        override fun isEnabled(channel: LogcatTraceChannel): Boolean = true
    }

    private fun envelope(eventType: String, payload: Map<String, Any?>): TraceEventEnvelope<*> =
        TraceEventEnvelope(
            traceSessionId = "test-session",
            sequence = 1L,
            wallClockMs = 0L,
            elapsedRealtimeMs = 0L,
            threadName = "test",
            eventType = eventType,
            payload = payload
        )

    @Before
    fun reset() {
        ShadowLog.clear()
    }

    @After
    fun teardown() {
        ShadowLog.clear()
    }

    @Test
    fun `first_paint event writes to FirstPaint tag with curated fields`() {
        val sink = LogcatRuntimeTraceSink(allEnabled)
        sink.emit(envelope("metadata.first_paint", mapOf(
            "contentId" to "tt0111161",
            "itemType" to "movie",
            "surface" to "HOME",
            "source" to "ROUTER",
            "routerExecuted" to true,
            "networkExecuted" to false,
            "fieldsUsed" to listOf("title", "year", "poster"),
            "profileHash" to "ab12cd34"
        )))
        val logs = ShadowLog.getLogsForTag("Nexio.FirstPaint")
        assertEquals(1, logs.size)
        val msg = logs.first().msg
        assertTrue("expected contentId in $msg", msg.contains("contentId=tt0111161"))
        assertTrue("expected surface in $msg", msg.contains("surface=HOME"))
        assertTrue("expected source in $msg", msg.contains("source=ROUTER"))
        assertTrue("expected routerExecuted in $msg", msg.contains("routerExecuted=true"))
        assertTrue("expected networkExecuted in $msg", msg.contains("networkExecuted=false"))
        assertTrue("expected used fields in $msg", msg.contains("used=[title,year,poster]"))
        assertTrue("expected profile in $msg", msg.contains("profile=ab12cd34"))
    }

    @Test
    fun `route_decision event writes to MetaRoute tag with provider and reason`() {
        val sink = LogcatRuntimeTraceSink(allEnabled)
        sink.emit(envelope("metadata.route_decision", mapOf(
            "contentId" to "tt0111161",
            "parentId" to "",
            "itemType" to "movie",
            "provider" to "TMDB",
            "mediaKind" to "MOVIE",
            "reason" to "primary_for_kind",
            "usedInputs" to listOf("imdbId"),
            "ignoredInputs" to listOf<String>(),
            "targetIdRequiresIdentityResolution" to true,
            "targetIds" to mapOf("imdb" to "tt0111161")
        )))
        val logs = ShadowLog.getLogsForTag("Nexio.MetaRoute")
        assertEquals(1, logs.size)
        val msg = logs.first().msg
        assertTrue(msg.contains("t=metadata.route_decision"))
        assertTrue(msg.contains("contentId=tt0111161"))
        assertTrue(msg.contains("provider=TMDB"))
        assertTrue(msg.contains("reason=primary_for_kind"))
    }

    @Test
    fun `field_selected event writes to MetaRoute tag with field provider and rejected count`() {
        val sink = LogcatRuntimeTraceSink(allEnabled)
        sink.emit(envelope("metadata.field_selected", mapOf(
            "contentId" to "tt0111161",
            "field" to "poster",
            "selectedProvider" to "RPDB",
            "sourceRole" to "ARTWORK",
            "valuePreview" to "https://...",
            "ownershipRule" to "ARTWORK_OVERRIDES_RAIL_PREVIEW",
            "rejectedCandidates" to listOf(mapOf("provider" to "TMDB"))
        )))
        val msg = ShadowLog.getLogsForTag("Nexio.MetaRoute").first().msg
        assertTrue(msg.contains("field=poster"))
        assertTrue(msg.contains("selectedProvider=RPDB"))
        assertTrue(msg.contains("rejectedCount=1"))
    }

    @Test
    fun `stable_id_bundle event writes to MetaRoute tag with canonical and sidecar ids`() {
        val sink = LogcatRuntimeTraceSink(allEnabled)
        sink.emit(envelope("metadata.stable_id_bundle", mapOf(
            "itemKey" to "home:tmdb:series:1399",
            "itemType" to "SERIES",
            "status" to "READY",
            "trigger" to "VISIBLE",
            "tmdbMovieId" to null,
            "tvdbSeriesId" to "121361",
            "kitsuAnimeId" to null,
            "imdbId" to "tt0944947",
            "networkExecuted" to true
        )))

        val logs = ShadowLog.getLogsForTag("Nexio.MetaRoute")
        assertEquals(1, logs.size)
        val msg = logs.first().msg
        assertTrue(msg.contains("t=metadata.stable_id_bundle"))
        assertTrue(msg.contains("itemKey=home:tmdb:series:1399"))
        assertTrue(msg.contains("itemType=SERIES"))
        assertTrue(msg.contains("status=READY"))
        assertTrue(msg.contains("tvdbSeriesId=121361"))
        assertTrue(msg.contains("imdbId=tt0944947"))
        assertTrue(msg.contains("networkExecuted=true"))
    }

    @Test
    fun `home hydration applied event writes to MetaRoute tag with changed fields and stability markers`() {
        val sink = LogcatRuntimeTraceSink(allEnabled)
        sink.emit(envelope("home.hydration_applied", mapOf(
            "railId" to "tmdb.popular.tv",
            "itemKey" to "home:tmdb:series:1399",
            "firstPaintSource" to "RAIL_PREVIEW",
            "canonicalProvider" to "TVDB",
            "canonicalId" to "121361",
            "imdbId" to "tt0944947",
            "trigger" to "VISIBLE",
            "priority" to "HIGH",
            "workClass" to "BACKGROUND_HYDRATION",
            "changedFields" to listOf("poster", "rating"),
            "displayHashBefore" to "preview-hash",
            "displayHashAfter" to "canonical-hash",
            "rowOrderChanged" to false,
            "focusChanged" to false,
            "networkExecuted" to false,
            "cacheDecision" to "CACHE_HIT"
        )))

        val logs = ShadowLog.getLogsForTag("Nexio.MetaRoute")
        assertEquals(1, logs.size)
        val msg = logs.first().msg
        assertTrue(msg.contains("t=home.hydration_applied"))
        assertTrue(msg.contains("railId=tmdb.popular.tv"))
        assertTrue(msg.contains("itemKey=home:tmdb:series:1399"))
        assertTrue(msg.contains("canonicalProvider=TVDB"))
        assertTrue(msg.contains("canonicalId=121361"))
        assertTrue(msg.contains("imdbId=tt0944947"))
        assertTrue(msg.contains("changedFields=[poster,rating]"))
        assertTrue(msg.contains("rowOrderChanged=false"))
        assertTrue(msg.contains("focusChanged=false"))
        assertTrue(msg.contains("cacheDecision=CACHE_HIT"))
    }

    @Test
    fun `screensaver events write to MetaRoute tag with curated parity fields`() {
        val sink = LogcatRuntimeTraceSink(allEnabled)

        sink.emit(envelope("screensaver.candidate_pool_built", mapOf(
            "profileHash" to "profile-hash",
            "source" to "RESOLVED_DISPLAY_SURFACE",
            "imageCandidateCount" to 2,
            "trailerCandidateCount" to 1
        )))
        sink.emit(envelope("screensaver.slide_selected", mapOf(
            "itemKey" to "movie:tmdb:550",
            "source" to "RESOLVED_DISPLAY_SURFACE",
            "ratingSource" to "IMDB",
            "artworkSource" to "TOP_POSTERS",
            "matchesHomeSurface" to true
        )))
        sink.emit(envelope("screensaver.trailer_candidate_selected", mapOf(
            "itemKey" to "movie:tmdb:550",
            "source" to "RESOLVED_DISPLAY_SURFACE",
            "trailerSource" to "FALLBACK_YOUTUBE_IDS",
            "fallbackYouTubeIdsOnly" to true
        )))
        sink.emit(envelope("screensaver.surface_published", mapOf(
            "surface" to "screensaver",
            "published" to true,
            "itemCount" to 40,
            "logoCount" to 31,
            "trailerCandidateCount" to 40,
            "selectedRefCount" to 25,
            "fallbackIdCount" to 15
        )))
        sink.emit(envelope("screensaver.scheduler_state", mapOf(
            "stage" to "start_prepared",
            "route" to "home",
            "eligible" to true,
            "visible" to false,
            "slideCount" to 40,
            "trailerCandidateCount" to 40,
            "trailerEnabled" to true,
            "trailerSessionReady" to true,
            "lifecycleState" to "RESUMED",
            "reason" to "ready"
        )))

        val logs = ShadowLog.getLogsForTag("Nexio.MetaRoute")
        assertEquals(5, logs.size)
        val pool = logs[0].msg
        assertTrue(pool.contains("t=screensaver.candidate_pool_built"))
        assertTrue(pool.contains("profile=profile-hash"))
        assertTrue(pool.contains("source=RESOLVED_DISPLAY_SURFACE"))
        assertTrue(pool.contains("imageCandidateCount=2"))
        assertTrue(pool.contains("trailerCandidateCount=1"))

        val slide = logs[1].msg
        assertTrue(slide.contains("t=screensaver.slide_selected"))
        assertTrue(slide.contains("itemKey=movie:tmdb:550"))
        assertTrue(slide.contains("ratingSource=IMDB"))
        assertTrue(slide.contains("artworkSource=TOP_POSTERS"))
        assertTrue(slide.contains("matchesHomeSurface=true"))

        val trailer = logs[2].msg
        assertTrue(trailer.contains("t=screensaver.trailer_candidate_selected"))
        assertTrue(trailer.contains("trailerSource=FALLBACK_YOUTUBE_IDS"))
        assertTrue(trailer.contains("fallbackYouTubeIdsOnly=true"))

        val surface = logs[3].msg
        assertTrue(surface.contains("t=screensaver.surface_published"))
        assertTrue(surface.contains("surface=screensaver"))
        assertTrue(surface.contains("published=true"))
        assertTrue(surface.contains("logoCount=31"))
        assertTrue(surface.contains("trailerCandidateCount=40"))

        val scheduler = logs[4].msg
        assertTrue(scheduler.contains("t=screensaver.scheduler_state"))
        assertTrue(scheduler.contains("stage=start_prepared"))
        assertTrue(scheduler.contains("eligible=true"))
        assertTrue(scheduler.contains("trailerEnabled=true"))
        assertTrue(scheduler.contains("trailerSessionReady=true"))
    }

    @Test
    fun `media clip events write to MetaRoute tag with durable clip fields`() {
        val sink = LogcatRuntimeTraceSink(allEnabled)

        sink.emit(envelope("media_clip.candidate_stored", mapOf(
            "itemKey" to "tmdb:550",
            "provider" to "TMDB",
            "clipType" to "TRAILER",
            "site" to "YOUTUBE",
            "videoId" to "abc123",
            "scope" to "title",
            "cacheDecision" to "WRITE"
        )))
        sink.emit(envelope("media_clip.candidate_selected", mapOf(
            "surface" to "SCREENSAVER",
            "itemKey" to "tmdb:550",
            "provider" to "TMDB",
            "clipType" to "TRAILER",
            "site" to "YOUTUBE",
            "videoId" to "abc123",
            "cacheDecision" to "HIT",
            "playbackUrlResolvedAtPlayTime" to true
        )))

        val logs = ShadowLog.getLogsForTag("Nexio.MetaRoute")
        assertEquals(2, logs.size)
        val stored = logs[0].msg
        assertTrue(stored.contains("t=media_clip.candidate_stored"))
        assertTrue(stored.contains("itemKey=tmdb:550"))
        assertTrue(stored.contains("provider=TMDB"))
        assertTrue(stored.contains("videoId=abc123"))
        assertTrue(stored.contains("cacheDecision=WRITE"))

        val selected = logs[1].msg
        assertTrue(selected.contains("t=media_clip.candidate_selected"))
        assertTrue(selected.contains("surface=SCREENSAVER"))
        assertTrue(selected.contains("itemKey=tmdb:550"))
        assertTrue(selected.contains("cacheDecision=HIT"))
        assertTrue(selected.contains("playbackUrlResolvedAtPlayTime=true"))
    }

    @Test
    fun `cache_decision event writes to IntRuntime tag with cache proof fields for all decisions`() {
        val sink = LogcatRuntimeTraceSink(allEnabled)
        val cases = listOf(
            Triple("HIT", "fresh-cache-hit", true),
            Triple("MISS_THEN_NETWORK", "cache-miss", false),
            Triple("STALE_HIT", "stale-cache-hit", true),
            Triple("WRITE", "cache-write", false)
        )

        cases.forEachIndexed { index, (decision, reason, networkSuppressed) ->
            ShadowLog.clear()
            sink.emit(envelope("runtime.cache_decision", mapOf(
                "runtimeOperationId" to "op-$index",
                "provider" to "TMDB",
                "apiShapeId" to "tmdb.movie.details",
                "operationKey" to "getDetails:550",
                "decision" to decision,
                "reason" to reason,
                "networkSuppressed" to networkSuppressed,
                "ttlMs" to 300000L,
                "staleWindowMs" to 60000L,
                "cacheKey" to "tmdb:movie:550:$decision"
            )))

            val logs = ShadowLog.getLogsForTag("Nexio.IntRuntime")
            assertEquals(1, logs.size)
            val msg = logs.first().msg
            assertTrue("expected event type in $msg", msg.contains("t=runtime.cache_decision"))
            assertTrue("expected runtimeOperationId in $msg", msg.contains("runtimeOperationId=op-$index"))
            assertTrue("expected provider in $msg", msg.contains("provider=TMDB"))
            assertTrue("expected apiShapeId in $msg", msg.contains("apiShapeId=tmdb.movie.details"))
            assertTrue("expected operationKey in $msg", msg.contains("operationKey=getDetails:550"))
            assertTrue("expected decision in $msg", msg.contains("decision=$decision"))
            assertTrue("expected reason in $msg", msg.contains("reason=$reason"))
            assertTrue("expected networkSuppressed in $msg", msg.contains("networkSuppressed=$networkSuppressed"))
            assertTrue("expected ttlMs in $msg", msg.contains("ttlMs=300000"))
            assertTrue("expected staleWindowMs in $msg", msg.contains("staleWindowMs=60000"))
            assertTrue("expected cacheKey in $msg", msg.contains("cacheKey=tmdb:movie:550:$decision"))
        }
    }

    @Test
    fun `artwork materialization event writes to IntRuntime tag with curated fields`() {
        val sink = LogcatRuntimeTraceSink(allEnabled)
        sink.emit(envelope("artwork.asset_materialized", mapOf(
            "decisionKey" to "decision-rpdb-550",
            "assetKey" to "asset-tmdb-fallback",
            "provider" to "TMDB",
            "imageType" to "POSTER",
            "cacheDecision" to "FALLBACK_MATERIALIZED",
            "networkExecuted" to true,
            "success" to true
        )))

        val logs = ShadowLog.getLogsForTag("Nexio.IntRuntime")
        assertEquals(1, logs.size)
        val msg = logs.first().msg
        assertTrue(msg.contains("t=artwork.asset_materialized"))
        assertTrue(msg.contains("decisionKey=decision-rpdb-550"))
        assertTrue(msg.contains("assetKey=asset-tmdb-fallback"))
        assertTrue(msg.contains("provider=TMDB"))
        assertTrue(msg.contains("imageType=POSTER"))
        assertTrue(msg.contains("cacheDecision=FALLBACK_MATERIALIZED"))
        assertTrue(msg.contains("networkExecuted=true"))
        assertTrue(msg.contains("success=true"))
    }

    @Test
    fun `artwork fallback materialized event writes to IntRuntime tag with fallback provider`() {
        val sink = LogcatRuntimeTraceSink(allEnabled)
        sink.emit(envelope("artwork.fallback_materialized", mapOf(
            "decisionKey" to "decision-rpdb-550",
            "fallbackProvider" to "TMDB",
            "assetKey" to "asset-tmdb-fallback"
        )))

        val logs = ShadowLog.getLogsForTag("Nexio.IntRuntime")
        assertEquals(1, logs.size)
        val msg = logs.first().msg
        assertTrue(msg.contains("t=artwork.fallback_materialized"))
        assertTrue(msg.contains("decisionKey=decision-rpdb-550"))
        assertTrue(msg.contains("fallbackProvider=TMDB"))
        assertTrue(msg.contains("assetKey=asset-tmdb-fallback"))
    }

    @Test
    fun `legacy remote artwork events write safe diagnostics to IntRuntime tag`() {
        val sink = LogcatRuntimeTraceSink(allEnabled)

        sink.emit(envelope("legacy_remote_artwork.fetch_start", mapOf(
            "urlHash" to "url-hash-1",
            "modelKeyHash" to "model-key-hash-1",
            "imageType" to "POSTER",
            "url" to "https://images.example.test/raw-start.jpg",
            "modelKey" to "raw-start-model-key"
        )))
        sink.emit(envelope("legacy_remote_artwork.fetch_success", mapOf(
            "urlHash" to "url-hash-2",
            "imageType" to "BACKDROP",
            "statusCode" to 200,
            "byteCount" to 4096L,
            "url" to "https://images.example.test/raw-success.jpg"
        )))

        val logs = ShadowLog.getLogsForTag("Nexio.IntRuntime")
        assertEquals(2, logs.size)

        val start = logs[0].msg
        assertTrue(start.contains("t=legacy_remote_artwork.fetch_start"))
        assertTrue(start.contains("urlHash=url-hash-1"))
        assertTrue(start.contains("modelKeyHash=model-key-hash-1"))
        assertTrue(start.contains("imageType=POSTER"))
        assertFalse(start.contains("https://images.example.test/raw-start.jpg"))
        assertFalse(start.contains("raw-start-model-key"))

        val success = logs[1].msg
        assertTrue(success.contains("t=legacy_remote_artwork.fetch_success"))
        assertTrue(success.contains("urlHash=url-hash-2"))
        assertTrue(success.contains("imageType=BACKDROP"))
        assertTrue(success.contains("statusCode=200"))
        assertTrue(success.contains("byteCount=4096"))
        assertFalse(success.contains("https://images.example.test/raw-success.jpg"))
    }

    @Test
    fun `legacy remote artwork failure logcat line excludes unsafe payload values`() {
        val sink = LogcatRuntimeTraceSink(allEnabled)
        sink.emit(envelope("legacy_remote_artwork.fetch_failed", mapOf(
            "modelKeyHash" to "model-key-hash-failed",
            "imageType" to "POSTER",
            "reason" to "http_error",
            "statusCode" to 404,
            "byteCount" to 0L,
            "errorClass" to "HttpException",
            "url" to "https://images.example.test/raw-failed.jpg",
            "modelKey" to "raw-failed-model-key",
            "exceptionMessage" to "token leaked in exception message",
            "errorMessage" to "another unsafe exception message"
        )))

        val logs = ShadowLog.getLogsForTag("Nexio.IntRuntime")
        assertEquals(1, logs.size)
        val msg = logs.first().msg
        assertTrue(msg.contains("t=legacy_remote_artwork.fetch_failed"))
        assertTrue(msg.contains("modelKeyHash=model-key-hash-failed"))
        assertTrue(msg.contains("imageType=POSTER"))
        assertTrue(msg.contains("reason=http_error"))
        assertTrue(msg.contains("statusCode=404"))
        assertTrue(msg.contains("byteCount=0"))
        assertTrue(msg.contains("errorClass=HttpException"))
        assertFalse(msg.contains("https://images.example.test/raw-failed.jpg"))
        assertFalse(msg.contains("raw-failed-model-key"))
        assertFalse(msg.contains("token leaked in exception message"))
        assertFalse(msg.contains("another unsafe exception message"))
    }

    @Test
    fun `artwork decision store write event writes to IntRuntime tag with persistence fields`() {
        val sink = LogcatRuntimeTraceSink(allEnabled)
        sink.emit(envelope("artwork.decision_store_write", mapOf(
            "success" to false,
            "decisionCount" to 4,
            "linkCount" to 1,
            "errorClass" to "JsonSyntaxException"
        )))

        val logs = ShadowLog.getLogsForTag("Nexio.IntRuntime")
        assertEquals(1, logs.size)
        val msg = logs.first().msg
        assertTrue(msg.contains("t=artwork.decision_store_write"))
        assertTrue(msg.contains("success=false"))
        assertTrue(msg.contains("decisionCount=4"))
        assertTrue(msg.contains("linkCount=1"))
        assertTrue(msg.contains("errorClass=JsonSyntaxException"))
    }

    @Test
    fun `artwork decision store load event writes to IntRuntime tag with file stats`() {
        val sink = LogcatRuntimeTraceSink(allEnabled)
        sink.emit(envelope("artwork.decision_store_load", mapOf(
            "success" to true,
            "authoritative" to true,
            "loadState" to "LOADED",
            "filePresent" to true,
            "fileReadable" to true,
            "fileBytes" to 81920L,
            "decisionCount" to 748,
            "linkCount" to 2,
            "droppedDecisionCount" to 0,
            "quarantinedDecisionCount" to 1,
            "reason" to null,
            "schemaVersion" to 1,
            "storedSchemaVersion" to 1,
            "errorClass" to null,
            "errorMessageHash" to null,
            "errorTopFrame" to null,
            "firstQuarantinedDecisionKeyHash" to "bad-key-hash"
        )))

        val logs = ShadowLog.getLogsForTag("Nexio.IntRuntime")
        assertEquals(1, logs.size)
        val msg = logs.first().msg
        assertTrue(msg.contains("t=artwork.decision_store_load"))
        assertTrue(msg.contains("success=true"))
        assertTrue(msg.contains("authoritative=true"))
        assertTrue(msg.contains("loadState=LOADED"))
        assertTrue(msg.contains("filePresent=true"))
        assertTrue(msg.contains("fileReadable=true"))
        assertTrue(msg.contains("fileBytes=81920"))
        assertTrue(msg.contains("decisionCount=748"))
        assertTrue(msg.contains("linkCount=2"))
        assertTrue(msg.contains("droppedDecisionCount=0"))
        assertTrue(msg.contains("quarantinedDecisionCount=1"))
        assertTrue(msg.contains("reason=null"))
        assertTrue(msg.contains("schemaVersion=1"))
        assertTrue(msg.contains("storedSchemaVersion=1"))
        assertTrue(msg.contains("errorClass=null"))
        assertTrue(msg.contains("errorMessageHash=null"))
        assertTrue(msg.contains("errorTopFrame=null"))
        assertTrue(msg.contains("firstQuarantinedDecisionKeyHash=bad-key-hash"))
    }

    @Test
    fun `home snapshot decision lookup event writes to MetaRoute tag with cache diagnostics`() {
        val sink = LogcatRuntimeTraceSink(allEnabled)
        sink.emit(envelope("home.snapshot_decision_lookup", mapOf(
            "scope" to "snapshot",
            "decisionLookupCount" to 3,
            "decisionFoundCount" to 0,
            "missingDecisionCount" to 3,
            "cacheNotAuthoritativeCount" to 0,
            "lookupFailedCount" to 0,
            "lookupErrorCount" to 0,
            "lookupResultTypes" to "found=0|missing_authoritative=3|cache_not_authoritative=0|lookup_failed=0",
            "authoritative" to true,
            "loadState" to "LoadedAuthoritative",
            "cacheLoaded" to true,
            "cacheDecisionCount" to 748,
            "cacheLinkCount" to 2,
            "storeFilePresent" to true,
            "storeFileReadable" to true,
            "storeFileBytes" to 81920L,
            "lastLoadSuccess" to true,
            "lastLoadReason" to null,
            "lastLoadErrorClass" to null,
            "droppedDecisionCount" to 0,
            "quarantinedDecisionCount" to 1,
            "errorTopFrame" to "DecisionStore.kt:42",
            "rehydrateRequestCount" to 0,
            "missingDecisionSamples" to "catalogRows[0].items[0]:decision:rpdb"
        )))

        val logs = ShadowLog.getLogsForTag("Nexio.MetaRoute")
        assertEquals(1, logs.size)
        val msg = logs.first().msg
        assertTrue(msg.contains("t=home.snapshot_decision_lookup"))
        assertTrue(msg.contains("scope=snapshot"))
        assertTrue(msg.contains("decisionLookupCount=3"))
        assertTrue(msg.contains("missingDecisionCount=3"))
        assertTrue(msg.contains("lookupResultTypes=found=0|missing_authoritative=3|cache_not_authoritative=0|lookup_failed=0"))
        assertTrue(msg.contains("authoritative=true"))
        assertTrue(msg.contains("loadState=LoadedAuthoritative"))
        assertTrue(msg.contains("cacheLoaded=true"))
        assertTrue(msg.contains("cacheDecisionCount=748"))
        assertTrue(msg.contains("cacheLinkCount=2"))
        assertTrue(msg.contains("storeFilePresent=true"))
        assertTrue(msg.contains("storeFileReadable=true"))
        assertTrue(msg.contains("storeFileBytes=81920"))
        assertTrue(msg.contains("lastLoadSuccess=true"))
        assertTrue(msg.contains("quarantinedDecisionCount=1"))
        assertTrue(msg.contains("errorTopFrame=DecisionStore.kt:42"))
        assertTrue(msg.contains("rehydrateRequestCount=0"))
        assertTrue(msg.contains("missingDecisionSamples=catalogRows[0].items[0]:decision:rpdb"))
        assertFalse(msg.contains("decisionFound="))
        assertFalse(msg.contains("decisionKeyHash="))
        assertFalse(msg.contains("posterKind="))
        assertFalse(msg.contains("posterProviderTag="))
        assertFalse(msg.contains("lookupErrorClass="))
    }

    @Test
    fun `home snapshot sanitization event writes to MetaRoute tag`() {
        val sink = LogcatRuntimeTraceSink(allEnabled)
        sink.emit(envelope("home.snapshot_sanitize_artwork", mapOf(
            "scope" to "snapshot",
            "sanitizedCount" to 3,
            "rawPremiumCount" to 0,
            "legacyIntegrationCount" to 0,
            "missingDecisionCount" to 3,
            "action" to "clear_poster_ref",
            "reasons" to "missing_decision=3",
            "destructive" to true,
            "writeBackAllowed" to false,
            "posterProviderTagAction" to "clear",
            "samples" to "catalogRows[0].items[0]:missing_decision:decision:rpdb:missing_authoritative"
        )))

        val logs = ShadowLog.getLogsForTag("Nexio.MetaRoute")
        assertEquals(1, logs.size)
        val msg = logs.first().msg
        assertTrue(msg.contains("t=home.snapshot_sanitize_artwork"))
        assertTrue(msg.contains("scope=snapshot"))
        assertTrue(msg.contains("sanitizedCount=3"))
        assertTrue(msg.contains("missingDecisionCount=3"))
        assertTrue(msg.contains("action=clear_poster_ref"))
        assertTrue(msg.contains("reasons=missing_decision=3"))
        assertTrue(msg.contains("destructive=true"))
        assertTrue(msg.contains("writeBackAllowed=false"))
        assertTrue(msg.contains("posterProviderTagAction=clear"))
    }

    @Test
    fun `home snapshot artwork rehydrate requested event writes to MetaRoute tag with request diagnostics`() {
        val sink = LogcatRuntimeTraceSink(allEnabled)
        sink.emit(envelope("home.snapshot_artwork_rehydrate_requested", mapOf(
            "scope" to "catalogRows[0].items[0]",
            "reason" to "quarantined_decision",
            "posterKind" to "decision",
            "providerTag" to "rpdb",
            "decisionKeyHash" to "abc123"
        )))

        val logs = ShadowLog.getLogsForTag("Nexio.MetaRoute")
        assertEquals(1, logs.size)
        val msg = logs.first().msg
        assertTrue(msg.contains("t=home.snapshot_artwork_rehydrate_requested"))
        assertTrue(msg.contains("scope=catalogRows[0].items[0]"))
        assertTrue(msg.contains("reason=quarantined_decision"))
        assertTrue(msg.contains("posterKind=decision"))
        assertTrue(msg.contains("providerTag=rpdb"))
        assertTrue(msg.contains("decisionKeyHash=abc123"))
    }

    @Test
    fun `disabled integration runtime channel suppresses artwork logcat events`() {
        val onlyMeta = object : LogcatChannelGate {
            override fun isEnabled(channel: LogcatTraceChannel): Boolean =
                channel == LogcatTraceChannel.META_ROUTE
        }
        val sink = LogcatRuntimeTraceSink(onlyMeta)

        sink.emit(envelope("artwork.asset_materialized", mapOf(
            "decisionKey" to "decision-rpdb-550",
            "success" to false
        )))

        assertEquals(0, ShadowLog.getLogsForTag("Nexio.IntRuntime").size)
    }

    @Test
    fun `http_request event writes to IntRuntime tag with method and url`() {
        val sink = LogcatRuntimeTraceSink(allEnabled)
        sink.emit(envelope("http.request", mapOf(
            "runtimeOperationId" to "op-1",
            "provider" to "TMDB",
            "apiShapeId" to "tmdb.movie.details",
            "method" to "GET",
            "url" to "https://api.themoviedb.org/3/movie/550",
            "headers" to emptyMap<String, String>()
        )))
        val msg = ShadowLog.getLogsForTag("Nexio.IntRuntime").first().msg
        assertTrue(msg.contains("t=http.request"))
        assertTrue(msg.contains("runtimeOperationId=op-1"))
        assertTrue(msg.contains("provider=TMDB"))
        assertTrue(msg.contains("apiShapeId=tmdb.movie.details"))
        assertTrue(msg.contains("method=GET"))
        assertTrue(msg.contains("url=https://api.themoviedb.org/3/movie/550"))
    }

    @Test
    fun `http_response event writes to IntRuntime tag with status and durationMs`() {
        val sink = LogcatRuntimeTraceSink(allEnabled)
        sink.emit(envelope("http.response", mapOf(
            "runtimeOperationId" to "op-1",
            "provider" to "TMDB",
            "apiShapeId" to "tmdb.movie.details",
            "statusCode" to 200,
            "durationMs" to 142L,
            "responseHeaders" to emptyMap<String, String>(),
            "byteCount" to 4096L
        )))
        val msg = ShadowLog.getLogsForTag("Nexio.IntRuntime").first().msg
        assertTrue(msg.contains("runtimeOperationId=op-1"))
        assertTrue(msg.contains("provider=TMDB"))
        assertTrue(msg.contains("apiShapeId=tmdb.movie.details"))
        assertTrue(msg.contains("statusCode=200"))
        assertTrue(msg.contains("durationMs=142"))
        assertTrue(msg.contains("byteCount=4096"))
    }

    @Test
    fun `http_error event writes runtime operation correlation fields`() {
        val sink = LogcatRuntimeTraceSink(allEnabled)
        sink.emit(envelope("http.error", mapOf(
            "runtimeOperationId" to "op-1",
            "provider" to "KITSU",
            "apiShapeId" to "kitsu.anime.episodes",
            "error" to "SocketTimeoutException"
        )))
        val msg = ShadowLog.getLogsForTag("Nexio.IntRuntime").first().msg
        assertTrue(msg.contains("t=http.error"))
        assertTrue(msg.contains("runtimeOperationId=op-1"))
        assertTrue(msg.contains("provider=KITSU"))
        assertTrue(msg.contains("apiShapeId=kitsu.anime.episodes"))
        assertTrue(msg.contains("error=SocketTimeoutException"))
    }

    @Test
    fun `bundle-only event types do not write to logcat`() {
        val sink = LogcatRuntimeTraceSink(allEnabled)
        sink.emit(envelope("policy.unscoped_network", mapOf("url" to "x")))
        sink.emit(envelope("playback.scrobble_rejected", mapOf("operation" to "x", "reason" to "y", "envelopeProfileId" to 1, "activeProfileId" to 2)))
        sink.emit(envelope("profile.boundary_check", emptyMap<String, Any?>()))
        assertEquals(0, ShadowLog.getLogsForTag("Nexio.FirstPaint").size)
        assertEquals(0, ShadowLog.getLogsForTag("Nexio.MetaRoute").size)
        assertEquals(0, ShadowLog.getLogsForTag("Nexio.IntRuntime").size)
    }

    @Test
    fun `disabled channel does not write to logcat`() {
        val onlyMeta = object : LogcatChannelGate {
            override fun isEnabled(channel: LogcatTraceChannel): Boolean =
                channel == LogcatTraceChannel.META_ROUTE
        }
        val sink = LogcatRuntimeTraceSink(onlyMeta)
        sink.emit(envelope("metadata.first_paint", mapOf(
            "contentId" to "x", "itemType" to "movie", "surface" to "HOME",
            "source" to "ROUTER", "routerExecuted" to true, "networkExecuted" to false,
            "fieldsUsed" to emptyList<String>(), "profileHash" to null
        )))
        sink.emit(envelope("metadata.route_decision", mapOf(
            "contentId" to "x", "parentId" to "", "itemType" to "movie",
            "provider" to "TMDB", "mediaKind" to "MOVIE", "reason" to "r",
            "usedInputs" to emptyList<String>(), "ignoredInputs" to emptyList<String>(),
            "targetIdRequiresIdentityResolution" to false, "targetIds" to emptyMap<String, String>()
        )))
        sink.emit(envelope("runtime.cache_decision", mapOf(
            "runtimeOperationId" to "op", "provider" to "TMDB", "apiShapeId" to "x",
            "operationKey" to "k", "cacheKey" to "ck", "decision" to "FRESH"
        )))
        assertEquals(0, ShadowLog.getLogsForTag("Nexio.FirstPaint").size)
        assertEquals(1, ShadowLog.getLogsForTag("Nexio.MetaRoute").size)
        assertEquals(0, ShadowLog.getLogsForTag("Nexio.IntRuntime").size)
    }

    @Test
    fun `eventsWritten counts only emissions actually sent to logcat`() {
        val onlyMeta = object : LogcatChannelGate {
            override fun isEnabled(channel: LogcatTraceChannel): Boolean =
                channel == LogcatTraceChannel.META_ROUTE
        }
        val sink = LogcatRuntimeTraceSink(onlyMeta)
        sink.emit(envelope("metadata.first_paint", mapOf(
            "contentId" to "x", "itemType" to "m", "surface" to "HOME", "source" to "R",
            "routerExecuted" to true, "networkExecuted" to false,
            "fieldsUsed" to emptyList<String>(), "profileHash" to null
        )))
        sink.emit(envelope("metadata.route_decision", mapOf(
            "contentId" to "x", "parentId" to "", "itemType" to "m",
            "provider" to "TMDB", "mediaKind" to "MOVIE", "reason" to "r",
            "usedInputs" to emptyList<String>(), "ignoredInputs" to emptyList<String>(),
            "targetIdRequiresIdentityResolution" to false, "targetIds" to emptyMap<String, String>()
        )))
        assertEquals(1L, sink.eventsWritten())
        assertEquals(0L, sink.eventsDropped())
    }
}
