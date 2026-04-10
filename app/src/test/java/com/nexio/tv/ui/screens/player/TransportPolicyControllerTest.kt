package com.nexio.tv.ui.screens.player

import com.nexio.tv.data.repository.benchmark.CapabilityEnvelope
import com.nexio.tv.data.repository.benchmark.RuntimeTransportHintsV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportPolicyControllerTest {

    @Test
    fun `startup to stabilizing to steady transitions produce correct policies`() {
        val controller = TransportPolicyController()

        // STARTUP state
        assertEquals(TransportState.STARTUP, controller.state)
        val startupPolicy = controller.currentPolicy
        assertEquals(0, startupPolicy.prefetchWorkers)
        assertFalse(startupPolicy.warmAheadEnabled)

        // STARTUP -> STABILIZING via onFirstFrame
        controller.onFirstFrame()
        assertEquals(TransportState.STABILIZING, controller.state)
        val stabilizingPolicy = controller.currentPolicy
        assertTrue(stabilizingPolicy.prefetchWorkers >= 1)
        assertFalse(stabilizingPolicy.warmAheadEnabled)

        // STABILIZING -> STEADY via onSteady with sufficient buffer
        controller.onSteady(20000)
        assertEquals(TransportState.STEADY, controller.state)
        val steadyPolicy = controller.currentPolicy
        assertTrue(steadyPolicy.warmAheadEnabled)
        assertTrue(steadyPolicy.prefetchWorkers > 0)
    }

    @Test
    fun `seek from steady transitions to SEEK state with no prefetch workers`() {
        val controller = TransportPolicyController()
        controller.onFirstFrame()
        controller.onSteady(20000)
        assertEquals(TransportState.STEADY, controller.state)

        controller.onSeek()
        assertEquals(TransportState.SEEK, controller.state)
        assertEquals(0, controller.currentPolicy.prefetchWorkers)
    }

    @Test
    fun `rebuffer from stabilizing transitions to rebuffer state`() {
        val controller = TransportPolicyController()
        controller.onFirstFrame()
        assertEquals(TransportState.STABILIZING, controller.state)

        controller.onRebuffer()
        assertEquals(TransportState.REBUFFER, controller.state)
    }

    @Test
    fun `policy never exceeds envelope limits for any state`() {
        val envelope = CapabilityEnvelope(
            maxSafeUrgentWorkers = 3,
            maxSafePrefetchWorkers = 2,
            maxSafeUrgentChunkBytes = 8L * 1024L * 1024L,
            maxSafePrefetchChunkBytes = 16L * 1024L * 1024L,
            sustainedThroughputMbps = 100.0,
            measuredAtMs = 1000L
        )
        val controller = TransportPolicyController(envelope)

        for (state in TransportState.entries) {
            controller.updateEnvelope(envelope)
            // Force state by reflection-free method using public API
        }

        // Verify each state via a fresh controller driven through transitions
        val c1 = TransportPolicyController(envelope)
        assertTrue(c1.currentPolicy.urgentWorkers <= 3) // STARTUP

        c1.onFirstFrame()
        assertTrue(c1.currentPolicy.urgentWorkers <= 3) // STABILIZING
        assertTrue(c1.currentPolicy.prefetchWorkers <= 2)

        c1.onSteady(20000)
        assertTrue(c1.currentPolicy.urgentWorkers <= 3) // STEADY
        assertTrue(c1.currentPolicy.prefetchWorkers <= 2)

        val c2 = TransportPolicyController(envelope)
        c2.onRebuffer()
        assertTrue(c2.currentPolicy.urgentWorkers <= 3) // REBUFFER
        assertEquals(0, c2.currentPolicy.prefetchWorkers)
    }

    @Test
    fun `onStable with insufficient buffer does not transition from startup`() {
        val controller = TransportPolicyController()
        assertEquals(TransportState.STARTUP, controller.state)

        controller.onStable(2000)
        assertEquals(TransportState.STARTUP, controller.state)
    }

    @Test
    fun `default envelope produces working policies for all reachable states`() {
        val controller = TransportPolicyController()

        // STARTUP policy
        val startupPolicy = controller.currentPolicy
        assertTrue(startupPolicy.urgentWorkers > 0)
        assertEquals(0, startupPolicy.prefetchWorkers)
        assertTrue(startupPolicy.urgentChunkBytes > 0)
        assertEquals(0L, startupPolicy.prefetchChunkBytes)
        assertFalse(startupPolicy.warmAheadEnabled)

        // STABILIZING policy
        controller.onFirstFrame()
        val stabilizingPolicy = controller.currentPolicy
        assertTrue(stabilizingPolicy.urgentWorkers > 0)
        assertTrue(stabilizingPolicy.prefetchWorkers >= 1)
        assertTrue(stabilizingPolicy.urgentChunkBytes > 0)
        assertTrue(stabilizingPolicy.prefetchChunkBytes > 0)
        assertFalse(stabilizingPolicy.warmAheadEnabled)

        // STEADY policy
        controller.onSteady(20000)
        val steadyPolicy = controller.currentPolicy
        assertTrue(steadyPolicy.urgentWorkers > 0)
        assertTrue(steadyPolicy.prefetchWorkers > 0)
        assertTrue(steadyPolicy.urgentChunkBytes > 0)
        assertTrue(steadyPolicy.prefetchChunkBytes > 0)
        assertTrue(steadyPolicy.warmAheadEnabled)
    }

    @Test
    fun `unconfirmed runtime hints keep policy on generic safe baseline`() {
        val basePolicy = TransportPolicy(
            urgentWorkers = 3,
            prefetchWorkers = 1,
            urgentChunkBytes = 16L * 1024L * 1024L,
            prefetchChunkBytes = 32L * 1024L * 1024L,
            warmAheadEnabled = false
        )

        val effective = basePolicy.applyRuntimeTransportSpecialization(
            specialization = RuntimeTransportSpecialization(),
            runtimeHints = hints()
        )

        // Baseline specialization no longer clamps urgentChunkBytes — the locked-envelope
        // chunk size (here 16 MiB from the base policy) is preserved as-is. The STARTUP
        // 2 MiB clamp is applied earlier by policyForState(STARTUP), not here.
        assertEquals(16L * 1024L * 1024L, effective.urgentChunkBytes)
        assertEquals(null, effective.connectionBudgetHint)
        assertEquals(RuntimeTransportRetryMode.DEFAULT, effective.retryMode)
    }

    @Test
    fun `confirmed runtime hints activate chunk budget and retry overrides together`() {
        val basePolicy = TransportPolicy(
            urgentWorkers = 3,
            prefetchWorkers = 1,
            urgentChunkBytes = 8L * 1024L * 1024L,
            prefetchChunkBytes = 32L * 1024L * 1024L,
            warmAheadEnabled = false
        )

        val effective = basePolicy.applyRuntimeTransportSpecialization(
            specialization = RuntimeTransportSpecialization(
                allowUrgentChunkAbove8MiB = true,
                connectionBudgetHint = 8,
                retryMode = RuntimeTransportRetryMode.CONNECTION_CLOSE
            ),
            runtimeHints = hints()
        )

        assertEquals(16L * 1024L * 1024L, effective.urgentChunkBytes)
        assertEquals(8, effective.connectionBudgetHint)
        assertEquals(RuntimeTransportRetryMode.CONNECTION_CLOSE, effective.retryMode)
    }

    @Test
    fun `RD confirmed specialization sets urgentWorkers 2 prefetchWorkers 1 with connection budget`() {
        val basePolicy = TransportPolicy(
            urgentWorkers = 3,
            prefetchWorkers = 2,
            urgentChunkBytes = 8L * 1024L * 1024L,
            prefetchChunkBytes = 16L * 1024L * 1024L,
            warmAheadEnabled = true
        )

        val rdSpecialization = RuntimeTransportSpecialization(
            allowUrgentChunkAbove8MiB = true,
            connectionBudgetHint = 8,
            retryMode = RuntimeTransportRetryMode.CONNECTION_CLOSE,
            recommendedPrefetchWorkers = 1,
            recommendedPrefetchChunkBytes = 32L * 1024L * 1024L
        )

        val effective = basePolicy.applyRuntimeTransportSpecialization(
            specialization = rdSpecialization,
            runtimeHints = rdHints()
        )

        assertEquals(2, effective.urgentWorkers)
        assertEquals(1, effective.prefetchWorkers)
        assertEquals(16L * 1024L * 1024L, effective.urgentChunkBytes)
        assertEquals(32L * 1024L * 1024L, effective.prefetchChunkBytes)
        assertEquals(8, effective.connectionBudgetHint)
        assertEquals(RuntimeTransportRetryMode.CONNECTION_CLOSE, effective.retryMode)
        assertEquals(1, effective.warmAheadBudgetMax)
    }

    @Test
    fun `PM confirmed specialization sets urgentWorkers 3 prefetchWorkers 1 without connection budget`() {
        val basePolicy = TransportPolicy(
            urgentWorkers = 2,
            prefetchWorkers = 2,
            urgentChunkBytes = 8L * 1024L * 1024L,
            prefetchChunkBytes = 16L * 1024L * 1024L,
            warmAheadEnabled = true
        )

        val pmSpecialization = RuntimeTransportSpecialization(
            allowUrgentChunkAbove8MiB = true,
            connectionBudgetHint = null,
            retryMode = RuntimeTransportRetryMode.DEFAULT,
            recommendedPrefetchWorkers = 1,
            recommendedPrefetchChunkBytes = 32L * 1024L * 1024L
        )

        val effective = basePolicy.applyRuntimeTransportSpecialization(
            specialization = pmSpecialization,
            runtimeHints = pmHints()
        )

        assertEquals(3, effective.urgentWorkers)
        assertEquals(1, effective.prefetchWorkers)
        assertEquals(16L * 1024L * 1024L, effective.urgentChunkBytes)
        assertEquals(32L * 1024L * 1024L, effective.prefetchChunkBytes)
        assertEquals(null, effective.connectionBudgetHint)
        assertEquals(RuntimeTransportRetryMode.DEFAULT, effective.retryMode)
        assertEquals(null, effective.warmAheadBudgetMax)
    }

    @Test
    fun `revocation atomically resets all specialized knobs to baseline`() {
        val basePolicy = TransportPolicy(
            urgentWorkers = 3,
            prefetchWorkers = 2,
            urgentChunkBytes = 16L * 1024L * 1024L,
            prefetchChunkBytes = 32L * 1024L * 1024L,
            warmAheadEnabled = true
        )

        // Unconfirmed specialization (revocation) — all knobs reset
        val effective = basePolicy.applyRuntimeTransportSpecialization(
            specialization = RuntimeTransportSpecialization(),
            runtimeHints = rdHints()
        )

        assertEquals(3, effective.urgentWorkers) // unchanged from base
        assertEquals(2, effective.prefetchWorkers) // unchanged from base
        assertEquals(16L * 1024L * 1024L, effective.urgentChunkBytes) // preserved from base (no 8 MiB clamp)
        assertEquals(32L * 1024L * 1024L, effective.prefetchChunkBytes) // unchanged
        assertEquals(null, effective.connectionBudgetHint)
        assertEquals(RuntimeTransportRetryMode.DEFAULT, effective.retryMode)
        assertEquals(null, effective.warmAheadBudgetMax)
    }

    @Test
    fun `specialization with prefetch overrides applies prefetch workers and chunk bytes`() {
        val basePolicy = TransportPolicy(
            urgentWorkers = 3,
            prefetchWorkers = 2,
            urgentChunkBytes = 8L * 1024L * 1024L,
            prefetchChunkBytes = 16L * 1024L * 1024L,
            warmAheadEnabled = false
        )

        val specialization = RuntimeTransportSpecialization(
            allowUrgentChunkAbove8MiB = true,
            recommendedPrefetchWorkers = 1,
            recommendedPrefetchChunkBytes = 32L * 1024L * 1024L
        )

        val effective = basePolicy.applyRuntimeTransportSpecialization(
            specialization = specialization,
            runtimeHints = rdHints()
        )

        assertEquals(1, effective.prefetchWorkers)
        assertEquals(32L * 1024L * 1024L, effective.prefetchChunkBytes)
    }

    private fun hints(): RuntimeTransportHintsV2 {
        return rdHints()
    }

    private fun rdHints(): RuntimeTransportHintsV2 {
        return RuntimeTransportHintsV2(
            artifactVersion = 2,
            serviceKey = "RD",
            measuredAtMs = 42L,
            observedTransportClass = "CONNECTION_CLOSE",
            observedHostScope = "host:localhost",
            recommendedUrgentChunkBytes = 16L * 1024L * 1024L,
            recommendedUrgentWorkers = 2,
            connectionBudgetHint = 8,
            retryMode = "CONNECTION_CLOSE"
        )
    }

    private fun pmHints(): RuntimeTransportHintsV2 {
        return RuntimeTransportHintsV2(
            artifactVersion = 2,
            serviceKey = "PM",
            measuredAtMs = 42L,
            observedTransportClass = "KEEP_ALIVE",
            observedHostScope = "host:pm-cdn.example.com",
            recommendedUrgentChunkBytes = 16L * 1024L * 1024L,
            recommendedUrgentWorkers = 3,
            connectionBudgetHint = null,
            retryMode = "DEFAULT"
        )
    }
}
