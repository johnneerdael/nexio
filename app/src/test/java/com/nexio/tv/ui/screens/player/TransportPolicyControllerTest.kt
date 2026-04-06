package com.nexio.tv.ui.screens.player

import com.nexio.tv.data.repository.benchmark.CapabilityEnvelope
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
    fun `seek from steady resets to startup with no prefetch workers`() {
        val controller = TransportPolicyController()
        controller.onFirstFrame()
        controller.onSteady(20000)
        assertEquals(TransportState.STEADY, controller.state)

        controller.onSeek()
        assertEquals(TransportState.STARTUP, controller.state)
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
}
