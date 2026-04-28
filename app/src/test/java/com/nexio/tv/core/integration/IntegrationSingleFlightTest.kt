package com.nexio.tv.core.integration

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class IntegrationSingleFlightTest {

    @Test
    fun `concurrent calls with the same key invoke loader exactly once`() = runTest {
        val singleFlight = IntegrationSingleFlight()
        val invocations = AtomicInteger(0)

        val (resultA, resultB) = coroutineScope {
            val a = async {
                singleFlight.run("k1") {
                    invocations.incrementAndGet()
                    delay(50)
                    IntegrationFetchResult.Updated("loaded")
                }
            }
            val b = async {
                singleFlight.run("k1") {
                    invocations.incrementAndGet()
                    delay(50)
                    IntegrationFetchResult.Updated("loaded")
                }
            }
            a.await() to b.await()
        }

        assertEquals(1, invocations.get())
        assertEquals(IntegrationFetchResult.Updated("loaded"), resultA)
        assertEquals(IntegrationFetchResult.Updated("loaded"), resultB)
    }

    @Test
    fun `different keys do not coalesce`() = runTest {
        val singleFlight = IntegrationSingleFlight()
        val invocations = AtomicInteger(0)

        coroutineScope {
            async { singleFlight.run("k1") { invocations.incrementAndGet(); IntegrationFetchResult.Updated("a") } }.await()
            async { singleFlight.run("k2") { invocations.incrementAndGet(); IntegrationFetchResult.Updated("b") } }.await()
        }

        assertEquals(2, invocations.get())
    }

    @Test
    fun `loader exception propagates and clears the in-flight slot for retry`() = runTest {
        val singleFlight = IntegrationSingleFlight()
        val attempted = AtomicInteger(0)

        val first = runCatching {
            singleFlight.run<String>("k-fail") {
                attempted.incrementAndGet()
                throw RuntimeException("loader failed")
            }
        }
        assertEquals(1, attempted.get())
        assertEquals("loader failed", first.exceptionOrNull()?.message)

        // Subsequent call to the same key must re-attempt (slot was cleared)
        val second = singleFlight.run<String>("k-fail") {
            attempted.incrementAndGet()
            IntegrationFetchResult.Updated("recovered")
        }
        assertEquals(2, attempted.get())
        assertEquals(IntegrationFetchResult.Updated("recovered"), second)
    }

    @Test
    fun `same key with different result types does not produce ClassCastException`() = runTest {
        val singleFlight = IntegrationSingleFlight()
        // First call returns Updated(Int)
        val intResult = singleFlight.run<Int>(
            key = TypedSingleFlightKey(cacheKey = "clash-key", mimeType = "application/int")
        ) { IntegrationFetchResult.Updated(42) }
        // Second call with same cacheKey but different mimeType (different T) must NOT reuse the slot
        val stringResult = singleFlight.run<String>(
            key = TypedSingleFlightKey(cacheKey = "clash-key", mimeType = "application/string")
        ) { IntegrationFetchResult.Updated("forty-two") }

        assertEquals(IntegrationFetchResult.Updated(42), intResult)
        assertEquals(IntegrationFetchResult.Updated("forty-two"), stringResult)
    }
}
