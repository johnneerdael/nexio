package com.nexio.tv.ui.screens.player

import com.nexio.tv.data.repository.benchmark.CapabilityEnvelope
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkProvider
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkResult
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkSummary
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkTerminationReason
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkResult
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkSessionSummary
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkStatus
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkProfileResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests the Phase A2 bridge precedence in [selectTransportBenchmarkForServiceKey]:
 *  1. [DebridConfigBenchmarkStore] result wins if present.
 *  2. Fallback [DebridBenchmarkStore] attached envelope used when primary is absent.
 *  3. Both absent → null (caller falls back to cold-start).
 */
class BenchmarkStoreBridgeSelectionTest {

    // Service key "RD" maps to REAL_DEBRID via benchmarkProviderForServiceKey
    private val rdServiceKey = "RD"

    // RD locked envelope with the measured throughput we'll assert on
    private val rdMeasuredEnvelope = CapabilityEnvelope.LOCKED_REAL_DEBRID.copy(
        sustainedThroughputMbps = 748.72,
        measuredAtMs = 1_700_000_000_000L
    )

    private fun makeDebridBenchmarkResult(
        provider: DebridBenchmarkProvider,
        envelope: CapabilityEnvelope?
    ): DebridBenchmarkResult {
        return DebridBenchmarkResult(
            provider = provider,
            measuredAtMs = 1_700_000_000_000L,
            summary = DebridBenchmarkSummary(
                startupTimeMs = 90L,
                sustainedThroughputMbps = 748.72,
                transferredBytes = 100_000_000L,
                elapsedMs = 10_000L
            ),
            terminationReason = DebridBenchmarkTerminationReason.COMPLETED,
            capabilityEnvelope = envelope
        )
    }

    private fun makeDebridConfigBenchmarkResult(
        provider: DebridBenchmarkProvider,
        throughput: Double
    ): DebridConfigBenchmarkResult {
        val profile = DebridConfigBenchmarkProfileResult(
            parallelConnectionCount = 1,
            chunkSizeMb = 32,
            status = DebridConfigBenchmarkStatus.SUCCESS,
            averageThroughputMbps = throughput,
            transferredBytes = 100_000_000L,
            elapsedMs = 10_000L
        )
        val envelope = CapabilityEnvelope.LOCKED_REAL_DEBRID.copy(
            sustainedThroughputMbps = throughput,
            measuredAtMs = 1_700_000_000_000L
        )
        return DebridConfigBenchmarkResult(
            provider = provider,
            measuredAtMs = 1_700_000_000_000L,
            summary = DebridConfigBenchmarkSessionSummary(
                totalProfileCount = 1,
                successfulProfileCount = 1,
                failedProfileCount = 0,
                unsupportedProfileCount = 0,
                bestProfile = profile,
                capabilityEnvelope = envelope
            ),
            profiles = listOf(profile)
        )
    }

    // -------------------------------------------------------------------------
    // Case 1: primary store absent, fallback store has envelope → fallback used
    // -------------------------------------------------------------------------

    @Test
    fun `fallback envelope used when primary store returns null`() {
        val latestResults = mapOf(
            DebridBenchmarkProvider.REAL_DEBRID to null,
            DebridBenchmarkProvider.PREMIUMIZE to null,
            DebridBenchmarkProvider.TORBOX to null,
            DebridBenchmarkProvider.EASY_DEBRID to null
        )
        val fallbackResults = mapOf(
            DebridBenchmarkProvider.REAL_DEBRID to makeDebridBenchmarkResult(
                DebridBenchmarkProvider.REAL_DEBRID,
                rdMeasuredEnvelope
            )
        )

        val selected = selectTransportBenchmarkForServiceKey(
            serviceKey = rdServiceKey,
            latestResults = latestResults,
            fallbackResults = fallbackResults
        )

        assertNotNull(selected)
        val envelope = selected!!.capabilityEnvelope
        assertNotNull(envelope)
        assertEquals(33_554_432L, envelope!!.maxSafeUrgentChunkBytes)
        assertEquals(748.72, envelope.sustainedThroughputMbps, 0.001)
    }

    // -------------------------------------------------------------------------
    // Case 2: primary store present → primary wins (even if fallback also set)
    // -------------------------------------------------------------------------

    @Test
    fun `primary store wins over fallback store when both populated`() {
        val primaryThroughput = 1200.0
        val latestResults = mapOf(
            DebridBenchmarkProvider.REAL_DEBRID to makeDebridConfigBenchmarkResult(
                DebridBenchmarkProvider.REAL_DEBRID,
                primaryThroughput
            )
        )
        val fallbackResults = mapOf(
            DebridBenchmarkProvider.REAL_DEBRID to makeDebridBenchmarkResult(
                DebridBenchmarkProvider.REAL_DEBRID,
                rdMeasuredEnvelope // throughput = 748.72
            )
        )

        val selected = selectTransportBenchmarkForServiceKey(
            serviceKey = rdServiceKey,
            latestResults = latestResults,
            fallbackResults = fallbackResults
        )

        assertNotNull(selected)
        // Must reflect the primary store throughput, not the fallback
        assertEquals(primaryThroughput, selected!!.capabilityEnvelope?.sustainedThroughputMbps ?: 0.0, 0.001)
    }

    // -------------------------------------------------------------------------
    // Case 3: both stores absent → null (cold-start handled upstream)
    // -------------------------------------------------------------------------

    @Test
    fun `both stores absent returns null`() {
        val latestResults = mapOf(
            DebridBenchmarkProvider.REAL_DEBRID to null,
            DebridBenchmarkProvider.PREMIUMIZE to null,
            DebridBenchmarkProvider.TORBOX to null,
            DebridBenchmarkProvider.EASY_DEBRID to null
        )
        val fallbackResults = emptyMap<DebridBenchmarkProvider, DebridBenchmarkResult?>()

        val selected = selectTransportBenchmarkForServiceKey(
            serviceKey = rdServiceKey,
            latestResults = latestResults,
            fallbackResults = fallbackResults
        )

        assertNull(selected)
    }

    // -------------------------------------------------------------------------
    // Case 4: fallback result present but capabilityEnvelope is null → still null
    // -------------------------------------------------------------------------

    @Test
    fun `fallback result with null envelope returns null`() {
        val latestResults = mapOf(
            DebridBenchmarkProvider.REAL_DEBRID to null
        )
        val fallbackResults = mapOf(
            DebridBenchmarkProvider.REAL_DEBRID to makeDebridBenchmarkResult(
                DebridBenchmarkProvider.REAL_DEBRID,
                envelope = null
            )
        )

        val selected = selectTransportBenchmarkForServiceKey(
            serviceKey = rdServiceKey,
            latestResults = latestResults,
            fallbackResults = fallbackResults
        )

        assertNull(selected)
    }

    // -------------------------------------------------------------------------
    // Case 5: unknown / null serviceKey → null
    // -------------------------------------------------------------------------

    @Test
    fun `null serviceKey returns null`() {
        val selected = selectTransportBenchmarkForServiceKey(
            serviceKey = null,
            latestResults = emptyMap(),
            fallbackResults = emptyMap()
        )
        assertNull(selected)
    }
}
