package com.nexio.tv.ui.screens.player

import com.nexio.tv.data.repository.benchmark.CapabilityEnvelope
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkSummary
import com.nexio.tv.data.repository.benchmark.toCapabilityEnvelopeForBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the Phase A2 bridge invariant: a drifted [CapabilityEnvelope] that somehow leaks
 * into [com.nexio.tv.data.repository.benchmark.DebridBenchmarkResult.capabilityEnvelope]
 * cannot silently reach playback.
 *
 * **Chosen approach**: downstream Phase 5 guard (not reader-side discard).
 *
 * Rationale: the `DebridBenchmarkStore` reader intentionally stores and returns whatever JSON
 * is persisted (best-effort, permissive). The shape-drift guard lives at init time in
 * `PlayerRuntimeControllerInitialization` via:
 *
 *   `require(lockedEnvelope.matchesLockedShape(envelope)) { "CapabilityEnvelope shape drift…" }`
 *
 * This keeps the read path neutral (consistent with Phase 2/5 of the locked-envelope plan) and
 * lets the fail-fast guard fire exactly once — at session initialization — rather than
 * silently discarding valid data during reads.
 *
 * Tests here assert:
 *  1. A correctly-shaped RD envelope passes [CapabilityEnvelope.matchesLockedShape].
 *  2. A drifted envelope (wrong chunk bytes) fails [CapabilityEnvelope.matchesLockedShape],
 *     confirming the Phase 5 guard would throw if it reached the init site.
 *  3. The bridge envelope (from [toCapabilityEnvelopeForBridge]) always passes shape check
 *     since it is built exclusively from the locked constant.
 */
class BenchmarkStoreBridgeDoesNotOverwriteLockedShapeTest {

    private val rdLockedShape = CapabilityEnvelope.LOCKED_REAL_DEBRID

    // -------------------------------------------------------------------------
    // 1. Correctly merged envelope passes shape check
    // -------------------------------------------------------------------------

    @Test
    fun `merged RD envelope passes matchesLockedShape`() {
        val merged = rdLockedShape.copy(
            sustainedThroughputMbps = 748.72,
            measuredAtMs = 1_700_000_000_000L
        )
        assertTrue(
            "Merged RD envelope must pass shape check",
            rdLockedShape.matchesLockedShape(merged)
        )
    }

    // -------------------------------------------------------------------------
    // 2. Drifted envelope (wrong chunk size) fails shape check → Phase 5 guard throws
    // -------------------------------------------------------------------------

    @Test
    fun `drifted envelope with wrong chunk size fails matchesLockedShape`() {
        val drifted = rdLockedShape.copy(
            maxSafeUrgentChunkBytes = 8L * 1024L * 1024L, // wrong: RD should be 32 MiB
            sustainedThroughputMbps = 748.72,
            measuredAtMs = 1_700_000_000_000L
        )
        assertFalse(
            "Drifted envelope must fail shape check — Phase 5 guard catches this at init",
            rdLockedShape.matchesLockedShape(drifted)
        )
    }

    @Test
    fun `drifted envelope with wrong worker count fails matchesLockedShape`() {
        val drifted = rdLockedShape.copy(
            maxSafeUrgentWorkers = 4, // wrong: RD should be 1
            sustainedThroughputMbps = 748.72,
            measuredAtMs = 1_700_000_000_000L
        )
        assertFalse(
            "Drifted worker count must fail shape check",
            rdLockedShape.matchesLockedShape(drifted)
        )
    }

    // -------------------------------------------------------------------------
    // 3. Phase 5 guard simulation: require() throws for a drifted envelope
    // -------------------------------------------------------------------------

    @Test
    fun `phase 5 guard throws IllegalArgumentException for drifted envelope`() {
        val drifted = rdLockedShape.copy(
            maxSafeUrgentChunkBytes = 8L * 1024L * 1024L,
            sustainedThroughputMbps = 748.72,
            measuredAtMs = 1_700_000_000_000L
        )
        // Replicate the Phase 5 guard from PlayerRuntimeControllerInitialization
        var threw = false
        try {
            require(rdLockedShape.matchesLockedShape(drifted)) {
                "CapabilityEnvelope shape drift for provider=real_debrid"
            }
        } catch (e: IllegalArgumentException) {
            threw = true
            assertTrue(e.message?.contains("shape drift") == true)
        }
        assertTrue("Phase 5 guard must throw for drifted envelope", threw)
    }

    // -------------------------------------------------------------------------
    // 4. The bridge helper (toCapabilityEnvelopeForBridge) always produces locked shape
    // -------------------------------------------------------------------------

    @Test
    fun `toCapabilityEnvelopeForBridge produces shape-correct RD envelope`() {
        val summary = DebridBenchmarkSummary(
            startupTimeMs = 90L,
            sustainedThroughputMbps = 748.72,
            transferredBytes = 100_000_000L,
            elapsedMs = 10_000L
        )
        val envelope = summary.toCapabilityEnvelopeForBridge(
            provider = "real_debrid",
            measuredAtMs = 1_700_000_000_000L
        )
        assertTrue(
            "Bridge envelope must satisfy Phase 5 shape check",
            rdLockedShape.matchesLockedShape(envelope)
        )
        assertEquals(33_554_432L, envelope.maxSafeUrgentChunkBytes)
        assertEquals(748.72, envelope.sustainedThroughputMbps, 0.001)
    }

    @Test
    fun `toCapabilityEnvelopeForBridge with zero throughput falls back to locked cold-start`() {
        val summary = DebridBenchmarkSummary(
            startupTimeMs = 90L,
            sustainedThroughputMbps = 0.0, // not actionable
            transferredBytes = 0L,
            elapsedMs = 0L
        )
        val envelope = summary.toCapabilityEnvelopeForBridge(
            provider = "real_debrid",
            measuredAtMs = 1_700_000_000_000L
        )
        // Shape must still match the locked constant
        assertTrue(rdLockedShape.matchesLockedShape(envelope))
        // Cold-start shape has sustainedThroughputMbps == 0
        assertEquals(0.0, envelope.sustainedThroughputMbps, 0.0)
    }

    @Test
    fun `toCapabilityEnvelopeForBridge produces shape-correct PM envelope`() {
        val pmLocked = CapabilityEnvelope.LOCKED_PREMIUMIZE
        val summary = DebridBenchmarkSummary(
            startupTimeMs = 80L,
            sustainedThroughputMbps = 350.0,
            transferredBytes = 50_000_000L,
            elapsedMs = 5_000L
        )
        val envelope = summary.toCapabilityEnvelopeForBridge(
            provider = "premiumize",
            measuredAtMs = 1_700_000_000_001L
        )
        assertTrue(pmLocked.matchesLockedShape(envelope))
        assertEquals(350.0, envelope.sustainedThroughputMbps, 0.001)
    }
}
