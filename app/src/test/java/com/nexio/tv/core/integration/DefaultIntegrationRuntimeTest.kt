package com.nexio.tv.core.integration

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DefaultIntegrationRuntimeTest {
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
    }

    @Test
    fun `single flight deduplicates concurrent cache misses`() = runTest {
        val fixture = realRuntimeFixture()
        val calls = AtomicInteger(0)
        val spec = IntegrationSpec(
            provider = IntegrationProvider.KITSU,
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
    }
}
