package com.nexio.tv.core.integration

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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

    private data class TestStreamHandle<T>(
        override val value: T
    ) : IntegrationStreamHandle<T> {
        override fun close() = Unit
    }
}
