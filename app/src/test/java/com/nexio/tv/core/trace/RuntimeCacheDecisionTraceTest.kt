package com.nexio.tv.core.trace

import com.nexio.tv.core.integration.ByteArrayIntegrationCacheStore
import com.nexio.tv.core.integration.DefaultIntegrationRuntime
import com.nexio.tv.core.integration.IntegrationBackoffManager
import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationFetchOptions
import com.nexio.tv.core.integration.InMemoryIntegrationProviderBackoffDao
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationPlaybackGate
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationSingleFlight
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.ProviderRequestGate
import com.nexio.tv.core.integration.RecordingIntegrationAuditSink
import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.integration.StringIntegrationCodec
import com.nexio.tv.core.integration.defaultIntegrationPolicyRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCacheDecisionTraceTest {
    @Test
    fun `runtime get emits runtime cache_decision events with decision tag`() = runTest {
        val registry = defaultIntegrationPolicyRegistry()
        val traceSink = RecordingTraceSink()
        val runtime = DefaultIntegrationRuntime(
            cacheStore = ByteArrayIntegrationCacheStore(),
            requestGate = ProviderRequestGate(registry),
            backoffManager = IntegrationBackoffManager(InMemoryIntegrationProviderBackoffDao()),
            singleFlight = IntegrationSingleFlight(),
            playbackGate = IntegrationPlaybackGate(),
            registry = registry,
            auditSink = RecordingIntegrationAuditSink(),
            traceSink = traceSink
        )

        val spec = IntegrationSpec(
            provider = IntegrationProvider.TMDB,
            apiShapeId = "tmdb.movie.core",
            operationKey = "tmdb.movie.core:550:en",
            cacheKey = "metadata:TMDB:tmdb.movie.core:550:en",
            codec = StringIntegrationCodec,
            cachePolicy = IntegrationCachePolicy.CacheFirst(ttlMs = 60_000L),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.GlobalContent,
            profileContext = null,
            load = { IntegrationLoadResult.Success("payload") }
        )

        // First call: cache miss -> network -> cache write
        runtime.get(spec)
        // Second call: cache hit
        runtime.get(spec)

        val cacheEvents = traceSink.events.filter { it.eventType == "runtime.cache_decision" }
        assertTrue(
            "expected at least one cache_decision event, got: ${traceSink.events.map { it.eventType }}",
            cacheEvents.isNotEmpty()
        )
        val decisions = cacheEvents.map { (it.payload as Map<*, *>)["decision"] }
        assertTrue("expected a HIT decision in $decisions", decisions.contains(TraceCacheDecision.HIT.name))
    }

    private fun buildRuntime(traceSink: RecordingTraceSink): DefaultIntegrationRuntime {
        val registry = defaultIntegrationPolicyRegistry()
        return DefaultIntegrationRuntime(
            cacheStore = ByteArrayIntegrationCacheStore(),
            requestGate = ProviderRequestGate(registry),
            backoffManager = IntegrationBackoffManager(InMemoryIntegrationProviderBackoffDao()),
            singleFlight = IntegrationSingleFlight(),
            playbackGate = IntegrationPlaybackGate(),
            registry = registry,
            auditSink = RecordingIntegrationAuditSink(),
            traceSink = traceSink
        )
    }

    private fun assertDecision(traceSink: RecordingTraceSink, expected: TraceCacheDecision) {
        val cacheEvents = traceSink.events.filter { it.eventType == "runtime.cache_decision" }
        val decisions = cacheEvents.map { (it.payload as Map<*, *>)["decision"] }
        val event = cacheEvents.firstOrNull { (it.payload as Map<*, *>)["decision"] == expected.name }
        assertNotNull(
            "expected ${expected.name} cache_decision event, got decisions=$decisions",
            event
        )
        assertEquals(expected.name, (event!!.payload as Map<*, *>)["decision"])
    }

    @Test
    fun `MISS_THEN_NETWORK cache decision emitted on CacheFirst with empty cache`() = runTest {
        val traceSink = RecordingTraceSink()
        val runtime = buildRuntime(traceSink)

        val spec = IntegrationSpec(
            provider = IntegrationProvider.TMDB,
            apiShapeId = "tmdb.movie.core",
            operationKey = "tmdb.movie.core:miss-then-network:en",
            cacheKey = "metadata:TMDB:tmdb.movie.core:miss-then-network:en",
            codec = StringIntegrationCodec,
            cachePolicy = IntegrationCachePolicy.CacheFirst(ttlMs = 60_000L),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.GlobalContent,
            profileContext = null,
            load = { IntegrationLoadResult.Success("payload") }
        )

        runtime.get(spec)

        assertDecision(traceSink, TraceCacheDecision.MISS_THEN_NETWORK)
    }

    @Test
    fun `EXPIRED_MISS cache decision emitted on CacheFirst cache-only with empty cache`() = runTest {
        val traceSink = RecordingTraceSink()
        val runtime = buildRuntime(traceSink)

        val spec = IntegrationSpec(
            provider = IntegrationProvider.TMDB,
            apiShapeId = "tmdb.movie.core",
            operationKey = "tmdb.movie.core:expired-miss:en",
            cacheKey = "metadata:TMDB:tmdb.movie.core:expired-miss:en",
            codec = StringIntegrationCodec,
            cachePolicy = IntegrationCachePolicy.CacheFirst(ttlMs = 60_000L),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.GlobalContent,
            profileContext = null,
            load = { IntegrationLoadResult.Success("payload") }
        )

        runtime.get(spec, options = IntegrationFetchOptions(cacheOnly = true))

        assertDecision(traceSink, TraceCacheDecision.EXPIRED_MISS)
    }

    @Test
    fun `BYPASS_DISABLED cache decision emitted when policy is Disabled`() = runTest {
        val traceSink = RecordingTraceSink()
        val runtime = buildRuntime(traceSink)

        val spec = IntegrationSpec(
            provider = IntegrationProvider.TMDB,
            apiShapeId = "tmdb.movie.core",
            operationKey = "tmdb.movie.core:bypass-disabled:en",
            cacheKey = "metadata:TMDB:tmdb.movie.core:bypass-disabled:en",
            codec = StringIntegrationCodec,
            cachePolicy = IntegrationCachePolicy.Disabled,
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.GlobalContent,
            profileContext = null,
            load = { IntegrationLoadResult.Success("payload") }
        )

        runtime.get(spec)

        assertDecision(traceSink, TraceCacheDecision.BYPASS_DISABLED)
    }

    @Test
    fun `OBSERVE_ONLY cache decision emitted when policy is ObserveOnly`() = runTest {
        val traceSink = RecordingTraceSink()
        val runtime = buildRuntime(traceSink)

        val spec = IntegrationSpec(
            provider = IntegrationProvider.TMDB,
            apiShapeId = "tmdb.movie.core",
            operationKey = "tmdb.movie.core:observe-only:en",
            cacheKey = "metadata:TMDB:tmdb.movie.core:observe-only:en",
            codec = StringIntegrationCodec,
            cachePolicy = IntegrationCachePolicy.ObserveOnly(reason = "pin-observe"),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.GlobalContent,
            profileContext = null,
            load = { IntegrationLoadResult.Success("payload") }
        )

        runtime.get(spec)

        assertDecision(traceSink, TraceCacheDecision.OBSERVE_ONLY)
    }

    @Test
    fun `WRITE cache decision emitted after successful network fetch and cache write`() = runTest {
        val traceSink = RecordingTraceSink()
        val runtime = buildRuntime(traceSink)

        val spec = IntegrationSpec(
            provider = IntegrationProvider.TMDB,
            apiShapeId = "tmdb.movie.core",
            operationKey = "tmdb.movie.core:write-decision:en",
            cacheKey = "metadata:TMDB:tmdb.movie.core:write-decision:en",
            codec = StringIntegrationCodec,
            cachePolicy = IntegrationCachePolicy.CacheFirst(ttlMs = 60_000L),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.GlobalContent,
            profileContext = null,
            load = { IntegrationLoadResult.Success("payload") }
        )

        // First call: cache miss -> network -> cache write -> emits WRITE decision
        runtime.get(spec)

        assertDecision(traceSink, TraceCacheDecision.WRITE)
    }
}
