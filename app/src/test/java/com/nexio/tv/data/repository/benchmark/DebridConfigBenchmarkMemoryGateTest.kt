package com.nexio.tv.data.repository.benchmark

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DebridConfigBenchmarkMemoryGateTest {

    @Test
    fun `24mb x4 profile is reported unsupported when estimated memory exceeds device budget`() {
        val gate = DebridConfigBenchmarkMemoryGate(
            benchmarkBudgetMb = 64,
            reservedBufferMb = 50
        )

        val decision = gate.evaluate(parallelConnectionCount = 4, chunkSizeMb = 24)

        assertEquals(
            ProfileRunDecision.Unsupported("Exceeds safe memory budget"),
            decision
        )
    }

    @Test
    fun `8mb x2 profile is runnable when within device budget`() {
        val gate = DebridConfigBenchmarkMemoryGate(
            benchmarkBudgetMb = 256,
            reservedBufferMb = 50
        )

        assertEquals(
            ProfileRunDecision.Run,
            gate.evaluate(parallelConnectionCount = 2, chunkSizeMb = 8)
        )
    }
}
