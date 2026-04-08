package com.nexio.tv.ui.screens.player

import com.nexio.tv.data.repository.benchmark.CapabilityEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Verifies the fail-fast shape-drift guards wired in Phase 5.
 *
 * Guards live ONLY in constructors / one-shot init sites — never on DataSource.open/read paths.
 * These tests exercise the [TransportPolicyController] constructor guard directly, mirroring
 * what [PlayerRuntimeControllerInitialization] does when building the controller for RD/PM.
 */
class PlayerRuntimeControllerInitializationTest {

    // ── RD ─────────────────────────────────────────────────────────────────────

    @Test
    fun `RD session builds TransportPolicyController with RD locked shape`() {
        val rdEnvelope = CapabilityEnvelope.LOCKED_REAL_DEBRID
        // Should not throw — shape matches
        val controller = TransportPolicyController(rdEnvelope, provider = "real_debrid")
        // Verify it uses the RD shape (urgent=1)
        assertEquals(TransportState.STARTUP, controller.state)
        val policy = controller.currentPolicy
        assertEquals(rdEnvelope.maxSafeUrgentWorkers, policy.urgentWorkers)
    }

    @Test
    fun `RD session with measured envelope that preserves locked shape does not throw`() {
        // Simulates what toCapabilityEnvelope returns: locked shape + measured throughput fields
        val measured = CapabilityEnvelope.LOCKED_REAL_DEBRID.copy(
            sustainedThroughputMbps = 125.0,
            stabilityPenalty = 0.1,
            measuredAtMs = 1_700_000_000_000L
        )
        // Shape fields are identical to LOCKED_REAL_DEBRID — must not throw
        val controller = TransportPolicyController(measured, provider = "real_debrid")
        assertEquals(TransportState.STARTUP, controller.state)
    }

    @Test
    fun `RD path never falls back to CapabilityEnvelope DEFAULT`() {
        val rdEnvelope = CapabilityEnvelope.LOCKED_REAL_DEBRID
        val controller = TransportPolicyController(rdEnvelope, provider = "real_debrid")
        // DEFAULT has maxSafeUrgentWorkers=2; LOCKED_REAL_DEBRID has 1 — they differ
        val policy = controller.currentPolicy
        // If DEFAULT had been used the urgent workers would be 2, not 1
        assertEquals(1, policy.urgentWorkers)
    }

    // ── PM ─────────────────────────────────────────────────────────────────────

    @Test
    fun `PM session builds TransportPolicyController with PM locked shape`() {
        val pmEnvelope = CapabilityEnvelope.LOCKED_PREMIUMIZE
        val controller = TransportPolicyController(pmEnvelope, provider = "premiumize")
        assertEquals(TransportState.STARTUP, controller.state)
        val policy = controller.currentPolicy
        assertEquals(pmEnvelope.maxSafeUrgentWorkers, policy.urgentWorkers)
    }

    @Test
    fun `PM session with measured envelope that preserves locked shape does not throw`() {
        val measured = CapabilityEnvelope.LOCKED_PREMIUMIZE.copy(
            sustainedThroughputMbps = 200.0,
            stabilityPenalty = 0.05,
            measuredAtMs = 1_700_000_000_000L
        )
        val controller = TransportPolicyController(measured, provider = "premiumize")
        assertEquals(TransportState.STARTUP, controller.state)
    }

    // ── Shape drift → throw ────────────────────────────────────────────────────

    @Test
    fun `divergent envelope for RD throws IllegalStateException with shape drift message`() {
        val divergent = CapabilityEnvelope.LOCKED_REAL_DEBRID.copy(
            maxSafeUrgentChunkBytes = 8L * 1024L * 1024L  // wrong — RD locked is 32 MiB
        )
        try {
            TransportPolicyController(divergent, provider = "real_debrid")
            fail("Expected IllegalStateException for shape drift")
        } catch (e: IllegalStateException) {
            assert(e.message?.contains("shape drift") == true) {
                "Expected 'shape drift' in message but got: ${e.message}"
            }
            assert(e.message?.contains("real_debrid") == true) {
                "Expected provider key in message but got: ${e.message}"
            }
        }
    }

    @Test
    fun `divergent envelope for PM throws IllegalStateException with shape drift message`() {
        val divergent = CapabilityEnvelope.LOCKED_PREMIUMIZE.copy(
            maxSafeUrgentWorkers = 4  // wrong — PM locked is 2
        )
        try {
            TransportPolicyController(divergent, provider = "premiumize")
            fail("Expected IllegalStateException for shape drift")
        } catch (e: IllegalStateException) {
            assert(e.message?.contains("shape drift") == true) {
                "Expected 'shape drift' in message but got: ${e.message}"
            }
            assert(e.message?.contains("premiumize") == true) {
                "Expected provider key in message but got: ${e.message}"
            }
        }
    }

    // ── Non-locked providers (TorBox, EasyDebrid) ──────────────────────────────

    @Test
    fun `TorBox path is unaffected — no guard fires for non-locked provider`() {
        // torbox has no locked shape, so any envelope is accepted
        val anyEnvelope = CapabilityEnvelope(
            maxSafeUrgentWorkers = 5,
            maxSafePrefetchWorkers = 3,
            maxSafeUrgentChunkBytes = 4L * 1024L * 1024L,
            maxSafePrefetchChunkBytes = 8L * 1024L * 1024L,
            sustainedThroughputMbps = 80.0,
            measuredAtMs = 1000L
        )
        // Must not throw
        val controller = TransportPolicyController(anyEnvelope, provider = "torbox")
        assertEquals(TransportState.STARTUP, controller.state)
    }

    @Test
    fun `EasyDebrid path is unaffected — no guard fires for non-locked provider`() {
        val anyEnvelope = CapabilityEnvelope(
            maxSafeUrgentWorkers = 3,
            maxSafePrefetchWorkers = 2,
            maxSafeUrgentChunkBytes = 16L * 1024L * 1024L,
            maxSafePrefetchChunkBytes = 32L * 1024L * 1024L,
            sustainedThroughputMbps = 60.0,
            measuredAtMs = 2000L
        )
        val controller = TransportPolicyController(anyEnvelope, provider = "easy_debrid")
        assertEquals(TransportState.STARTUP, controller.state)
    }

    @Test
    fun `null provider path is unaffected — no guard fires when provider is null`() {
        val controller = TransportPolicyController()
        assertEquals(TransportState.STARTUP, controller.state)
    }
}
