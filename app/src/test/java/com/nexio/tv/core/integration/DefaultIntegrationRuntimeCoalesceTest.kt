package com.nexio.tv.core.integration

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Coverage for IntegrationCallSpec.coalesceConcurrent (F-A-02): the opt-in single-flight
 * path for non-cache calls. Default-false preserves the prior duplicate-fire behavior so
 * mutations that must duplicate stay un-coalesced.
 */
@RunWith(RobolectricTestRunner::class)
class DefaultIntegrationRuntimeCoalesceTest {

    @Test
    fun `runtime call with coalesceConcurrent=true dedupes concurrent identical calls`() = runTest {
        val fixture = realRuntimeFixture()
        val invocations = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()

        val spec = IntegrationCallSpec(
            provider = IntegrationProvider.TMDB,
            apiShapeId = TmdbApiShapes.MOVIE_CORE,
            operationKey = "test:call:coalesce-on",
            workClass = IntegrationWorkClass.USER_VISIBLE,
            coalesceConcurrent = true,
            call = {
                invocations.incrementAndGet()
                gate.await()
                IntegrationCallResult.Success("ok")
            }
        )

        coroutineScope {
            val a = async { fixture.runtime.call(spec) }
            val b = async { fixture.runtime.call(spec) }
            // Release once both have entered the single-flight slot.
            gate.complete(Unit)
            val resultA = a.await()
            val resultB = b.await()
            assertEquals(IntegrationCallResult.Success("ok"), resultA)
            assertEquals(IntegrationCallResult.Success("ok"), resultB)
        }

        assertEquals(1, invocations.get())
    }

    @Test
    fun `runtime call with coalesceConcurrent=false default does not dedupe`() = runTest {
        val fixture = realRuntimeFixture()
        val invocations = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()

        val spec = IntegrationCallSpec(
            provider = IntegrationProvider.TMDB,
            apiShapeId = TmdbApiShapes.MOVIE_CORE,
            operationKey = "test:call:coalesce-off",
            workClass = IntegrationWorkClass.USER_VISIBLE,
            call = {
                invocations.incrementAndGet()
                gate.await()
                IntegrationCallResult.Success("ok")
            }
        )

        coroutineScope {
            val a = async { fixture.runtime.call(spec) }
            val b = async { fixture.runtime.call(spec) }
            gate.complete(Unit)
            a.await(); b.await()
        }

        // Both fired — preserves mutation behavior for the default path.
        assertEquals(2, invocations.get())
    }
}
