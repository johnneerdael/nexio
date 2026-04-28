package com.nexio.tv.core.integration

import com.nexio.tv.core.trace.TraceCacheDecision
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-D-01: When [DefaultIntegrationRuntime.executeProviderLoad] receives an HTTP
 * 429 response AND a stale cache entry exists for the spec, the runtime must
 * surface the stale value as [IntegrationFetchResult.Stale] rather than
 * dropping back to [IntegrationFetchResult.Missing], and must emit a
 * `runtime.cache_decision` trace event with decision = STALE_HIT.
 *
 * The current HttpError branch in executeProviderLoad returns Missing
 * regardless of stale cache state — so this test is expected to FAIL until
 * the runtime is taught the stale-on-429 fallback.
 */
class DefaultIntegrationRuntimeStaleOn429Test {

    @Test
    fun `executeProviderLoad HTTP 429 with stale cache returns Stale not Missing`() = runTest {
        val cacheStore = mockk<IntegrationCacheStore>(relaxed = false)
        coEvery { cacheStore.readFresh(any<IntegrationSpec<String>>()) } returns null
        coEvery { cacheStore.readStale(any<IntegrationSpec<String>>()) } returns "cached-value"
        coEvery { cacheStore.write(any<IntegrationSpec<String>>(), any()) } returns Unit
        coEvery { cacheStore.deleteOwnedMedia(any()) } returns 0

        val sink = RecordingTraceSink()
        val registry = defaultIntegrationPolicyRegistry()
        val runtime = DefaultIntegrationRuntime(
            cacheStore = cacheStore,
            requestGate = ProviderRequestGate(registry),
            backoffManager = IntegrationBackoffManager(InMemoryIntegrationProviderBackoffDao()),
            singleFlight = IntegrationSingleFlight(),
            playbackGate = IntegrationPlaybackGate(),
            registry = registry,
            auditSink = RecordingIntegrationAuditSink(),
            traceSink = sink
        )

        val spec = IntegrationSpec(
            provider = IntegrationProvider.TMDB,
            apiShapeId = TmdbApiShapes.MOVIE_CORE,
            operationKey = "test:get:f-d-01",
            cacheKey = "tmdb:movie:f-d-01",
            codec = StringIntegrationCodec,
            cachePolicy = IntegrationCachePolicy.ObserveOnly(reason = "f-d-01-pin"),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.GlobalContent,
            load = {
                IntegrationLoadResult.HttpError(
                    statusCode = 429,
                    retryAfterMs = 5_000L,
                    reason = "rate-limited"
                )
            }
        )

        val result = runtime.get(spec)

        assertTrue(
            "F-D-01: 429 with stale cache must return Stale, got $result",
            result is IntegrationFetchResult.Stale
        )
        assertEquals("cached-value", (result as IntegrationFetchResult.Stale).value)
        val decisions = sink.events
            .filter { it.eventType == "runtime.cache_decision" }
            .mapNotNull { (it.payload as? Map<*, *>)?.get("decision") }
        assertTrue(
            "expected STALE_HIT cache_decision event, got decisions=$decisions",
            decisions.contains(TraceCacheDecision.STALE_HIT.name)
        )
    }
}
