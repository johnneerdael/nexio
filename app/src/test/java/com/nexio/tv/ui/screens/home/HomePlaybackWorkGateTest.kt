package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.integration.IntegrationPlaybackGate
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.defaultIntegrationPolicyRegistry
import com.nexio.tv.ui.screensaver.PlaybackIdleGateSnapshot
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePlaybackWorkGateTest {

    @Test
    fun `playback gate blocks background work but not scrobble work`() {
        val gate = IntegrationPlaybackGate()
        val registry = defaultIntegrationPolicyRegistry()

        gate.setPlaybackActive(true)

        assertTrue(gate.isBlocked(registry.policyFor(IntegrationProvider.TMDB), IntegrationWorkClass.BACKGROUND_HYDRATION))
        assertFalse(gate.isBlocked(registry.policyFor(IntegrationProvider.TRAKT), IntegrationWorkClass.SCROBBLE))
    }

    @Test
    fun `home background work is blocked while playback session is active`() {
        assertFalse(
            shouldRunHomeBackgroundWork(
                PlaybackIdleGateSnapshot(hasActiveSession = true, isPausedByUser = false)
            )
        )
    }

    @Test
    fun `home background work is allowed when no playback session is active`() {
        assertTrue(
            shouldRunHomeBackgroundWork(
                PlaybackIdleGateSnapshot(hasActiveSession = false, isPausedByUser = false)
            )
        )
    }

    @Test
    fun `continue watching enrichment never exceeds configured concurrency`() = runTest {
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val entered = Channel<Unit>(capacity = Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()

        val job = async {
            mapContinueWatchingEnrichmentWithLimit(
                items = listOf(1, 2, 3),
                maxConcurrency = 2
            ) { item ->
                val current = active.incrementAndGet()
                maxActive.updateAndGet { previous -> maxOf(previous, current) }
                entered.send(Unit)
                release.await()
                active.decrementAndGet()
                item
            }
        }

        entered.receive()
        entered.receive()

        assertFalse(entered.tryReceive().isSuccess)

        release.complete(Unit)

        assertEquals(listOf(1, 2, 3), job.await())
        assertEquals(2, maxActive.get())
    }

    @Test
    fun `continue watching enrichment keeps input order while limiting concurrency`() = runTest {
        val active = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        val inputs = (1..8).toList()

        val result = mapContinueWatchingEnrichmentWithLimit(
            items = inputs,
            maxConcurrency = 2
        ) { value ->
            val nowActive = active.incrementAndGet()
            maxObserved.updateAndGet { current -> maxOf(current, nowActive) }
            kotlinx.coroutines.delay(10)
            active.decrementAndGet()
            "item-$value"
        }

        assertEquals((1..8).map { "item-$it" }, result)
        assertTrue(maxObserved.get() <= 2)
    }
}
