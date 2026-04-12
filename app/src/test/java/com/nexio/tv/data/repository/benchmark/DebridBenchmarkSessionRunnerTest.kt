package com.nexio.tv.data.repository.benchmark

import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.VodCacheSizeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebridBenchmarkSessionRunnerTest {

    @Test
    fun `transport config snapshot records current player transport settings`() {
        val snapshot = PlayerSettings(
            vodCacheSizeMode = VodCacheSizeMode.ON,
            useParallelConnections = true,
            parallelConnectionCount = 4,
            parallelChunkSizeMb = 32
        ).toBenchmarkTransportConfigSnapshot()

        assertEquals(true, snapshot.parallelConnectionsEnabled)
        assertEquals(4, snapshot.parallelConnectionCount)
        assertEquals(true, snapshot.vodCacheEnabled)
        assertEquals(32, snapshot.parallelChunkSizeMb)
    }

    @Test
    fun `autoplay benchmark validity requires safe budget and matching transport config`() {
        val settings = PlayerSettings(
            vodCacheSizeMode = VodCacheSizeMode.ON,
            useParallelConnections = true,
            parallelConnectionCount = 4,
            parallelChunkSizeMb = 32
        )

        assertTrue(
            benchmarkResult(
                safeBudgetMbps = 60.0,
                configSnapshot = settings.toBenchmarkTransportConfigSnapshot()
            ).hasValidAutoplayBenchmarkFor(settings)
        )

        assertFalse(
            benchmarkResult(
                safeBudgetMbps = null,
                configSnapshot = settings.toBenchmarkTransportConfigSnapshot()
            ).hasValidAutoplayBenchmarkFor(settings)
        )

        assertFalse(
            benchmarkResult(
                safeBudgetMbps = 60.0,
                configSnapshot = settings.copy(vodCacheSizeMode = VodCacheSizeMode.OFF)
                    .toBenchmarkTransportConfigSnapshot()
            ).hasValidAutoplayBenchmarkFor(settings)
        )

        assertFalse(
            benchmarkResult(
                safeBudgetMbps = 60.0,
                configSnapshot = settings.copy(parallelConnectionCount = 2)
                    .toBenchmarkTransportConfigSnapshot()
            ).hasValidAutoplayBenchmarkFor(settings)
        )
    }

    private fun benchmarkResult(
        safeBudgetMbps: Double?,
        configSnapshot: DebridBenchmarkTransportConfigSnapshot?
    ): DebridBenchmarkResult {
        return DebridBenchmarkResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            measuredAtMs = 1L,
            summary = DebridBenchmarkSummary(),
            terminationReason = DebridBenchmarkTerminationReason.COMPLETED,
            optimized = DebridBenchmarkTransportProfile(
                startup = DebridBenchmarkStartupMetrics(),
                sustained = DebridBenchmarkSustainedMetrics(actionable = true),
                seek = DebridBenchmarkSeekMetrics(),
                decision = DebridBenchmarkTransportDecisionMetrics(
                    safeSustainedBudgetMbps = safeBudgetMbps,
                    actionable = true
                ),
                configSnapshot = configSnapshot
            )
        )
    }
}
