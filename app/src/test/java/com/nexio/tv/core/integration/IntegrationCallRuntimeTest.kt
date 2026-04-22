package com.nexio.tv.core.integration

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}
