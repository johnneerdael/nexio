package com.nexio.tv.core.integration

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
            scope = IntegrationScope.Global,
            statusCode = 429,
            retryAfterMs = 60_000L,
            reason = "retry later"
        )
        val calls = AtomicInteger(0)

        val result = fixture.runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.TMDB,
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
                workClass = IntegrationWorkClass.USER_VISIBLE,
                call = { throw failure }
            )
        )

        assertEquals(1, fixture.requestGate.acquireCount)
        assertTrue(result is IntegrationCallResult.NetworkError)
        assertEquals(failure, (result as IntegrationCallResult.NetworkError).throwable)
    }
}
