package com.nexio.tv.core.integration

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * F2-A-01 pin: a successful call() / open() result MUST clear backoff for (provider, scope).
 * Mirrors the existing get()-path behavior in executeProviderLoad that already calls
 * backoffManager.clear on IntegrationLoadResult.Success.
 */
@RunWith(RobolectricTestRunner::class)
class IntegrationCallRuntimeBackoffClearTest {

    private val scope = IntegrationScope.GlobalContent
    private val scopeKey = scope.storageKey // "global:content"

    @Test
    fun `successful call clears a pre-existing backoff entry for provider scope`() = runTest {
        val fixture = realRuntimeFixture()
        // Seed a stale (expired window) backoff record.  The record still exists in the DAO;
        // a successful call should delete it (F2-A-01).
        fixture.backoffManager.noteHttpFailure(
            provider = IntegrationProvider.TMDB,
            scope = scope,
            statusCode = 503,
            retryAfterMs = 0L, // expired immediately, so not blocked
            reason = "transient"
        )
        val before = fixture.backoffDao.get("TMDB", scopeKey)
        assertEquals("pre-condition: backoff entry should exist", 503, before?.statusCode)

        val result = fixture.runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.TMDB,
                apiShapeId = TmdbApiShapes.MOVIE_CORE,
                operationKey = "test:backoff-clear:call",
                scope = scope,
                workClass = IntegrationWorkClass.USER_VISIBLE,
                call = { IntegrationCallResult.Success("cleared") }
            )
        )

        assertEquals(IntegrationCallResult.Success("cleared"), result)
        val after = fixture.backoffDao.get("TMDB", scopeKey)
        assertEquals(
            "call() success must clear stale backoff entry — F2-A-01",
            null,
            after
        )
    }

    @Test
    fun `successful open clears a pre-existing backoff entry for provider scope`() = runTest {
        val fixture = realRuntimeFixture()
        fixture.backoffManager.noteHttpFailure(
            provider = IntegrationProvider.YOUTUBE_TRAILER,
            scope = scope,
            statusCode = 503,
            retryAfterMs = 0L,
            reason = "transient stream"
        )
        val before = fixture.backoffDao.get("YOUTUBE_TRAILER", scopeKey)
        assertEquals("pre-condition: backoff entry should exist", 503, before?.statusCode)

        val handle = TestStreamHandle("trailer-stream")
        val opens = AtomicInteger(0)
        val result = fixture.runtime.open(
            IntegrationStreamSpec(
                provider = IntegrationProvider.YOUTUBE_TRAILER,
                apiShapeId = YouTubeTrailerApiShapes.TRANSPORT_PROBE,
                operationKey = "test:backoff-clear:open",
                scope = scope,
                workClass = IntegrationWorkClass.USER_VISIBLE,
                open = {
                    opens.incrementAndGet()
                    handle
                }
            )
        )

        assertEquals(1, opens.get())
        assertEquals(handle, result)
        val after = fixture.backoffDao.get("YOUTUBE_TRAILER", scopeKey)
        assertEquals(
            "open() success must clear stale backoff entry — F2-A-01",
            null,
            after
        )
    }

    private data class TestStreamHandle<T>(
        override val value: T
    ) : IntegrationStreamHandle<T> {
        override fun close() = Unit
    }
}
