package com.nexio.tv.core.integration

import java.io.IOException
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DefaultIntegrationRuntimeTest {
    @Test
    fun `generated runtime audit sample uses real phase names and network flags`() = runTest {
        val fixture = realRuntimeFixture()
        fixture.seedCache(
            cacheKey = "tmdb:movie:sample",
            codec = StringIntegrationCodec,
            value = "cached",
            freshForMs = 60_000L,
            staleAfterMs = 60_000L
        )
        fixture.runtime.get(
            IntegrationSpec(
                provider = IntegrationProvider.TMDB,
                apiShapeId = TmdbApiShapes.MOVIE_CORE,
                operationKey = "sample:tmdb:fresh",
                cacheKey = "tmdb:movie:sample",
                codec = StringIntegrationCodec,
                cachePolicy = IntegrationCachePolicy.CacheFirst(60_000L, 60_000L),
                workClass = IntegrationWorkClass.USER_VISIBLE,
                load = { IntegrationLoadResult.Success("network") }
            )
        )
        fixture.runtime.get(
            IntegrationSpec(
                provider = IntegrationProvider.KITSU,
                apiShapeId = KitsuApiShapes.ANIME_CORE,
                operationKey = "sample:kitsu:network",
                cacheKey = "kitsu:anime:sample",
                codec = StringIntegrationCodec,
                cachePolicy = IntegrationCachePolicy.CacheFirst(60_000L, 60_000L),
                workClass = IntegrationWorkClass.USER_VISIBLE,
                load = { IntegrationLoadResult.Success("payload") }
            )
        )
        fixture.playbackGate.setPlaybackActive(true)
        fixture.runtime.get(
            IntegrationSpec(
                provider = IntegrationProvider.TVDB,
                apiShapeId = TvdbApiShapes.SERIES_EXTENDED,
                operationKey = "sample:tvdb:playback-blocked",
                cacheKey = null,
                codec = StringIntegrationCodec,
                cachePolicy = IntegrationCachePolicy.Disabled,
                workClass = IntegrationWorkClass.BACKGROUND_HYDRATION,
                load = { IntegrationLoadResult.Success("blocked") }
            )
        )
        fixture.playbackGate.setPlaybackActive(false)
        fixture.backoffManager.noteHttpFailure(
            provider = IntegrationProvider.TMDB,
            scope = IntegrationScope.Global,
            statusCode = 429,
            retryAfterMs = 60_000L,
            reason = "sample rate limited"
        )
        fixture.runtime.get(
            IntegrationSpec(
                provider = IntegrationProvider.TMDB,
                apiShapeId = TmdbApiShapes.MOVIE_CORE,
                operationKey = "sample:tmdb:backoff",
                cacheKey = null,
                codec = StringIntegrationCodec,
                cachePolicy = IntegrationCachePolicy.Disabled,
                workClass = IntegrationWorkClass.USER_VISIBLE,
                load = { IntegrationLoadResult.Success("blocked") }
            )
        )

        assertTrue(fixture.auditSink.events.any { it.phase == IntegrationAuditPhase.FRESH_CACHE_HIT && !it.loaderInvoked && !it.networkStarted })
        assertTrue(fixture.auditSink.events.any { it.phase == IntegrationAuditPhase.NETWORK_START && it.loaderInvoked && it.networkStarted })
        assertTrue(fixture.auditSink.events.any { it.phase == IntegrationAuditPhase.CACHE_WRITE })
        assertTrue(fixture.auditSink.events.any { it.phase == IntegrationAuditPhase.PLAYBACK_BLOCKED && !it.networkStarted })
        assertTrue(fixture.auditSink.events.any { it.phase == IntegrationAuditPhase.BACKOFF_BLOCKED && !it.networkStarted })

        writeRuntimeAuditSample(fixture.auditSink.events)
        val fixtureRows = File("app/build/reports/integration-runtime-audit/runtime-event-sample.generated.jsonl")
            .readLines()
            .filter { it.isNotBlank() }

        val requiredPhases = setOf(
            IntegrationAuditPhase.REQUEST_RECEIVED,
            IntegrationAuditPhase.FRESH_CACHE_HIT,
            IntegrationAuditPhase.LANE_QUEUED,
            IntegrationAuditPhase.NETWORK_START,
            IntegrationAuditPhase.NETWORK_END,
            IntegrationAuditPhase.CACHE_WRITE,
            IntegrationAuditPhase.PLAYBACK_BLOCKED,
            IntegrationAuditPhase.BACKOFF_BLOCKED
        )

        requiredPhases.forEach { phase ->
            assertTrue(
                "Fixture missing ${phase.name}",
                fixtureRows.any { it.contains("\"phase\":\"${phase.name}\"") }
            )
        }
        assertTrue(
            "Fresh cache fixture must prove loader/network are not invoked.",
            fixtureRows.any {
                it.contains("\"phase\":\"FRESH_CACHE_HIT\"") &&
                    it.contains("\"networkStarted\":false") &&
                    it.contains("\"loaderInvoked\":false")
            }
        )
        assertTrue(
            "Network start fixture must prove loader/network are invoked.",
            fixtureRows.any {
                it.contains("\"phase\":\"NETWORK_START\"") &&
                    it.contains("\"networkStarted\":true") &&
                    it.contains("\"loaderInvoked\":true")
            }
        )
        assertTrue(
            "Backoff fixture must prove loader/network are blocked.",
            fixtureRows.any {
                it.contains("\"phase\":\"BACKOFF_BLOCKED\"") &&
                    it.contains("\"networkStarted\":false") &&
                    it.contains("\"loaderInvoked\":false")
            }
        )
    }

    private fun writeRuntimeAuditSample(events: List<IntegrationAuditEvent>) {
        val output = File("app/build/reports/integration-runtime-audit/runtime-event-sample.generated.jsonl")
        output.parentFile?.mkdirs()
        output.writeText(
            events.joinToString(separator = "\n", postfix = "\n") { event ->
                val outcome = event.outcome?.let { "\"$it\"" } ?: "null"
                """{"traceId":"${event.traceId}","provider":"${event.provider}","apiShapeId":"${event.apiShapeId}","phase":"${event.phase}","workClass":"${event.workClass}","cachePolicy":"${event.cachePolicy}","networkStarted":${event.networkStarted},"loaderInvoked":${event.loaderInvoked},"outcome":$outcome}"""
            }
        )
    }

    @Test
    fun `fresh cache hit never enters provider gate or loader`() = runTest {
        val fixture = realRuntimeFixture()
        fixture.seedCache(
            cacheKey = "tmdb:movie:550",
            codec = StringIntegrationCodec,
            value = "cached",
            freshForMs = 60_000L,
            staleAfterMs = 60_000L
        )

        val calls = AtomicInteger(0)
        val result = fixture.runtime.get(
            IntegrationSpec(
                provider = IntegrationProvider.TMDB,
                apiShapeId = TmdbApiShapes.MOVIE_CORE,
                operationKey = "test:tmdb:movie:550",
                cacheKey = "tmdb:movie:550",
                codec = StringIntegrationCodec,
                cachePolicy = IntegrationCachePolicy.CacheFirst(
                    ttlMs = 60_000L,
                    staleAfterExpiryMs = 60_000L
                ),
                workClass = IntegrationWorkClass.USER_VISIBLE,
                load = {
                    calls.incrementAndGet()
                    IntegrationLoadResult.Success("network")
                }
            )
        )

        assertEquals(0, calls.get())
        assertEquals(0, fixture.requestGate.acquireCount)
        assertEquals(IntegrationFetchResult.Fresh("cached"), result)
        assertTrue(fixture.auditSink.events.any { event ->
            event.phase == IntegrationAuditPhase.FRESH_CACHE_HIT &&
                event.loaderInvoked == false &&
                event.networkStarted == false
        })
    }

    @Test
    fun `single flight deduplicates concurrent cache misses`() = runTest {
        val fixture = realRuntimeFixture()
        val calls = AtomicInteger(0)
        val spec = IntegrationSpec(
            provider = IntegrationProvider.KITSU,
            apiShapeId = KitsuApiShapes.ANIME_CORE,
            operationKey = "test:kitsu:anime:1",
            cacheKey = "kitsu:anime:1",
            codec = StringIntegrationCodec,
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 60_000L,
                staleAfterExpiryMs = 60_000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = {
                calls.incrementAndGet()
                IntegrationLoadResult.Success("payload")
            }
        )

        val results = listOf(
            async { fixture.runtime.get(spec) },
            async { fixture.runtime.get(spec) }
        ).awaitAll()

        assertEquals(1, calls.get())
        assertTrue(results.all { it == IntegrationFetchResult.Updated("payload") })
        assertTrue(fixture.auditSink.events.any { event ->
            event.phase == IntegrationAuditPhase.LANE_QUEUED
        })
        assertTrue(fixture.auditSink.events.any { event ->
            event.phase == IntegrationAuditPhase.NETWORK_START &&
                event.loaderInvoked &&
                event.networkStarted
        })
        assertTrue(fixture.auditSink.events.any { event ->
            event.phase == IntegrationAuditPhase.CACHE_WRITE
        })
    }

    @Test
    fun `active provider backoff emits blocked audit event without loader or network`() = runTest {
        val fixture = realRuntimeFixture()
        fixture.backoffManager.noteHttpFailure(
            provider = IntegrationProvider.TMDB,
            scope = IntegrationScope.Global,
            statusCode = 429,
            retryAfterMs = 60_000L,
            reason = "rate limited"
        )
        val calls = AtomicInteger(0)

        val result = fixture.runtime.get(
            IntegrationSpec(
                provider = IntegrationProvider.TMDB,
                apiShapeId = TmdbApiShapes.MOVIE_CORE,
                operationKey = "test:tmdb:blocked",
                cacheKey = null,
                codec = StringIntegrationCodec,
                cachePolicy = IntegrationCachePolicy.Disabled,
                workClass = IntegrationWorkClass.USER_VISIBLE,
                load = {
                    calls.incrementAndGet()
                    IntegrationLoadResult.Success("network")
                }
            )
        )

        assertEquals(IntegrationFetchResult.Missing, result)
        assertEquals(0, calls.get())
        assertTrue(fixture.auditSink.events.any { event ->
            event.phase == IntegrationAuditPhase.BACKOFF_BLOCKED &&
                event.networkStarted == false &&
                event.loaderInvoked == false
        })
    }

    @Test
    fun `provider load network error records synthetic 503 backoff`() = runTest {
        val fixture = realRuntimeFixture()
        val failure = IOException("timeout")

        val result = fixture.runtime.get(
            IntegrationSpec(
                provider = IntegrationProvider.TMDB,
                apiShapeId = TmdbApiShapes.MOVIE_CORE,
                operationKey = "test:tmdb:network-error",
                cacheKey = null,
                codec = StringIntegrationCodec,
                cachePolicy = IntegrationCachePolicy.Disabled,
                workClass = IntegrationWorkClass.USER_VISIBLE,
                load = {
                    IntegrationLoadResult.NetworkError(
                        throwable = failure,
                        retryAfterMs = 60_000L
                    )
                }
            )
        )

        val entry = fixture.backoffDao.get("TMDB", "global")
        assertEquals(IntegrationFetchResult.Missing, result)
        assertEquals(503, entry?.statusCode)
        assertEquals("timeout", entry?.reason)
    }

    @Test
    fun `provider load cancellation is not converted to backoff`() = runTest {
        val fixture = realRuntimeFixture()

        try {
            fixture.runtime.get(
                IntegrationSpec(
                    provider = IntegrationProvider.TMDB,
                    apiShapeId = TmdbApiShapes.MOVIE_CORE,
                    operationKey = "test:tmdb:cancelled",
                    cacheKey = null,
                    codec = StringIntegrationCodec,
                    cachePolicy = IntegrationCachePolicy.Disabled,
                    workClass = IntegrationWorkClass.USER_VISIBLE,
                    load = { throw CancellationException("cancelled") }
                )
            )
            fail("Expected CancellationException")
        } catch (exception: CancellationException) {
            assertEquals("cancelled", exception.message)
        }

        assertEquals(null, fixture.backoffDao.get("TMDB", "global"))
    }

    @Test
    fun `wrapped provider load cancellation is not converted to backoff`() = runTest {
        val fixture = realRuntimeFixture()

        try {
            fixture.runtime.get(
                IntegrationSpec(
                    provider = IntegrationProvider.TMDB,
                    apiShapeId = TmdbApiShapes.MOVIE_CORE,
                    operationKey = "test:tmdb:wrapped-cancelled",
                    cacheKey = null,
                    codec = StringIntegrationCodec,
                    cachePolicy = IntegrationCachePolicy.Disabled,
                    workClass = IntegrationWorkClass.USER_VISIBLE,
                    load = { IntegrationLoadResult.NetworkError(CancellationException("wrapped cancelled")) }
                )
            )
            fail("Expected CancellationException")
        } catch (exception: CancellationException) {
            assertEquals("wrapped cancelled", exception.message)
        }

        assertEquals(null, fixture.backoffDao.get("TMDB", "global"))
    }
}
