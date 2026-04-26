package com.nexio.tv.core.integration

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderRequestGateTest {
    @Test
    fun `configured concurrency greater than one allows bounded parallel starts`() = runTest {
        val gate = ProviderRequestGate(
            IntegrationPolicyRegistry(
                mapOf(
                    IntegrationProvider.ADDON to IntegrationProviderPolicy(
                        maxConcurrentNetworkStarts = 2
                    )
                )
            )
        )
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)

        val jobs = (1..4).map {
            async {
                gate.withPermit(IntegrationProvider.ADDON) {
                    val current = active.incrementAndGet()
                    maxActive.recordMax(current)
                    delay(100)
                    active.decrementAndGet()
                }
            }
        }

        jobs.awaitAll()

        assertEquals(4, gate.acquireCount)
        assertEquals(2, maxActive.get())
    }

    private fun AtomicInteger.recordMax(value: Int) {
        while (true) {
            val previous = get()
            if (value <= previous || compareAndSet(previous, value)) return
        }
    }
}
