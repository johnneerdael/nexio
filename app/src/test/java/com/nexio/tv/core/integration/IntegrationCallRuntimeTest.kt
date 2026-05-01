package com.nexio.tv.core.integration

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IntegrationCallRuntimeTest {
    @Test
    fun `uncached call executes loader and returns typed success`() = runTest {
        val fixture = realRuntimeFixture()
        val calls = AtomicInteger(0)

        val result = fixture.runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.TMDB,
                apiShapeId = TmdbApiShapes.MOVIE_CORE,
                operationKey = "test:call:success",
                workClass = IntegrationWorkClass.USER_VISIBLE,
                call = {
                    calls.incrementAndGet()
                    IntegrationCallResult.Success("network")
                }
            )
        )

        assertEquals(1, calls.get())
        assertEquals(1, fixture.requestGate.acquireCount)
        assertEquals(IntegrationCallResult.Success("network"), result)
        assertEquals("network", result.valueOrNull())
    }

    @Test
    fun `playback blocked call returns missing without entering gate`() = runTest {
        val fixture = realRuntimeFixture()
        fixture.playbackGate.setPlaybackActive(true)
        val calls = AtomicInteger(0)

        val result = fixture.runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.TMDB,
                apiShapeId = TmdbApiShapes.MOVIE_CORE,
                operationKey = "test:call:playback-blocked",
                workClass = IntegrationWorkClass.USER_VISIBLE,
                call = {
                    calls.incrementAndGet()
                    IntegrationCallResult.Success("network")
                }
            )
        )

        assertEquals(0, calls.get())
        assertEquals(0, fixture.requestGate.acquireCount)
        assertEquals(IntegrationCallResult.Missing, result)
    }

    @Test
    fun `backoff blocked call returns missing without entering gate`() = runTest {
        val fixture = realRuntimeFixture()
        fixture.backoffManager.noteHttpFailure(
            provider = IntegrationProvider.TMDB,
            scope = IntegrationScope.GlobalContent,
            statusCode = 429,
            retryAfterMs = 60_000L,
            reason = "retry later"
        )
        val calls = AtomicInteger(0)

        val result = fixture.runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.TMDB,
                apiShapeId = TmdbApiShapes.MOVIE_CORE,
                operationKey = "test:call:backoff-blocked",
                workClass = IntegrationWorkClass.USER_VISIBLE,
                call = {
                    calls.incrementAndGet()
                    IntegrationCallResult.Success("network")
                }
            )
        )

        assertEquals(0, calls.get())
        assertEquals(0, fixture.requestGate.acquireCount)
        assertEquals(IntegrationCallResult.Missing, result)
    }

    @Test
    fun `unexpected call exception becomes typed network error`() = runTest {
        val fixture = realRuntimeFixture()
        val failure = IllegalStateException("boom")

        val result: IntegrationCallResult<String> = fixture.runtime.call(
            IntegrationCallSpec<String>(
                provider = IntegrationProvider.TMDB,
                apiShapeId = TmdbApiShapes.MOVIE_CORE,
                operationKey = "test:call:exception",
                workClass = IntegrationWorkClass.USER_VISIBLE,
                call = { throw failure }
            )
        )

        assertEquals(1, fixture.requestGate.acquireCount)
        assertTrue(result is IntegrationCallResult.NetworkError)
        val throwable = (result as IntegrationCallResult.NetworkError).throwable
        assertTrue(throwable is IllegalStateException)
        assertEquals(failure.message, throwable.message)
    }

    @Test
    fun `network error call records synthetic 503 backoff`() = runTest {
        val fixture = realRuntimeFixture()
        val failure = IOException("socket closed")

        val result: IntegrationCallResult<String> = fixture.runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.TMDB,
                apiShapeId = TmdbApiShapes.MOVIE_CORE,
                operationKey = "test:call:network-error",
                workClass = IntegrationWorkClass.USER_VISIBLE,
                call = {
                    IntegrationCallResult.NetworkError(
                        throwable = failure,
                        retryAfterMs = 60_000L
                    )
                }
            )
        )

        val entry = fixture.backoffDao.get("TMDB", "global:content")
        assertTrue(result is IntegrationCallResult.NetworkError)
        assertEquals(503, entry?.statusCode)
        assertEquals("socket closed", entry?.reason)
    }

    @Test
    fun `wrapped call cancellation is not converted to backoff`() = runTest {
        val fixture = realRuntimeFixture()

        try {
            fixture.runtime.call(
                IntegrationCallSpec<String>(
                    provider = IntegrationProvider.TMDB,
                    apiShapeId = TmdbApiShapes.MOVIE_CORE,
                    operationKey = "test:call:wrapped-cancelled",
                    workClass = IntegrationWorkClass.USER_VISIBLE,
                    call = {
                        IntegrationCallResult.NetworkError(
                            throwable = CancellationException("wrapped cancelled")
                        )
                    }
                )
            )
            fail("Expected CancellationException")
        } catch (exception: CancellationException) {
            assertEquals("wrapped cancelled", exception.message)
        }

        assertEquals(null, fixture.backoffDao.get("TMDB", "global"))
    }
}
