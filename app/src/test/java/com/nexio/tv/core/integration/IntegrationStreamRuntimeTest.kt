package com.nexio.tv.core.integration

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IntegrationStreamRuntimeTest {
    @Test
    fun `stream open executes opener and returns typed handle`() = runTest {
        val fixture = realRuntimeFixture()
        val opens = AtomicInteger(0)
        val handle = TestStreamHandle("stream")

        val result = fixture.runtime.open(
            IntegrationStreamSpec(
                provider = IntegrationProvider.REAL_DEBRID,
                apiShapeId = DebridApiShapes.REAL_DEBRID_TORRENT_INFO,
                operationKey = "test:stream:success",
                workClass = IntegrationWorkClass.PLAYBACK_CRITICAL,
                open = {
                    opens.incrementAndGet()
                    handle
                }
            )
        )

        assertEquals(1, opens.get())
        assertEquals(1, fixture.requestGate.acquireCount)
        assertSame(handle, result)
        assertEquals("stream", result?.value)
    }

    @Test
    fun `playback blocked open returns null without entering gate`() = runTest {
        val fixture = realRuntimeFixture()
        fixture.playbackGate.setPlaybackActive(true)
        val opens = AtomicInteger(0)

        val result = fixture.runtime.open(
            IntegrationStreamSpec(
                provider = IntegrationProvider.REAL_DEBRID,
                apiShapeId = DebridApiShapes.REAL_DEBRID_TORRENT_INFO,
                operationKey = "test:stream:playback-blocked",
                workClass = IntegrationWorkClass.USER_VISIBLE,
                open = {
                    opens.incrementAndGet()
                    TestStreamHandle("stream")
                }
            )
        )

        assertEquals(0, opens.get())
        assertEquals(0, fixture.requestGate.acquireCount)
        assertNull(result)
    }

    @Test
    fun `backoff blocked open returns null without entering gate`() = runTest {
        val fixture = realRuntimeFixture()
        fixture.backoffManager.noteHttpFailure(
            provider = IntegrationProvider.REAL_DEBRID,
            scope = IntegrationScope.Global,
            statusCode = 429,
            retryAfterMs = 60_000L,
            reason = "retry later"
        )
        val opens = AtomicInteger(0)

        val result = fixture.runtime.open(
            IntegrationStreamSpec(
                provider = IntegrationProvider.REAL_DEBRID,
                apiShapeId = DebridApiShapes.REAL_DEBRID_TORRENT_INFO,
                operationKey = "test:stream:backoff-blocked",
                workClass = IntegrationWorkClass.PLAYBACK_CRITICAL,
                open = {
                    opens.incrementAndGet()
                    TestStreamHandle("stream")
                }
            )
        )

        assertEquals(0, opens.get())
        assertEquals(0, fixture.requestGate.acquireCount)
        assertNull(result)
    }

    private data class TestStreamHandle<T>(
        override val value: T
    ) : IntegrationStreamHandle<T> {
        override fun close() = Unit
    }
}
