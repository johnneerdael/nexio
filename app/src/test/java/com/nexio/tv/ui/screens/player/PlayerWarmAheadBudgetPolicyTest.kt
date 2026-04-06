package com.nexio.tv.ui.screens.player

import com.nexio.tv.data.repository.benchmark.CapabilityEnvelope
import com.nexio.tv.data.repository.benchmark.RuntimeTransportHintsV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerWarmAheadBudgetPolicyTest {

    private val rdEnvelope = CapabilityEnvelope(
        maxSafeUrgentWorkers = 3,
        maxSafePrefetchWorkers = 2,
        maxSafeUrgentChunkBytes = 16L * 1024L * 1024L,
        maxSafePrefetchChunkBytes = 32L * 1024L * 1024L,
        sustainedThroughputMbps = 400.0,
        measuredAtMs = System.currentTimeMillis()
    )

    private val pmEnvelope = CapabilityEnvelope(
        maxSafeUrgentWorkers = 3,
        maxSafePrefetchWorkers = 2,
        maxSafeUrgentChunkBytes = 8L * 1024L * 1024L,
        maxSafePrefetchChunkBytes = 16L * 1024L * 1024L,
        sustainedThroughputMbps = 350.0,
        measuredAtMs = System.currentTimeMillis()
    )

    private fun rdHints() = RuntimeTransportHintsV2(
        artifactVersion = 2,
        serviceKey = "RD",
        measuredAtMs = System.currentTimeMillis(),
        observedTransportClass = "CONNECTION_CLOSE",
        observedHostScope = "host:file",
        recommendedUrgentChunkBytes = 16L * 1024L * 1024L,
        recommendedUrgentWorkers = 2,
        connectionBudgetHint = 8,
        retryMode = "CONNECTION_CLOSE"
    )

    private fun pmHints() = RuntimeTransportHintsV2(
        artifactVersion = 2,
        serviceKey = "PM",
        measuredAtMs = System.currentTimeMillis(),
        observedTransportClass = "KEEP_ALIVE",
        observedHostScope = "host:pm-cdn",
        recommendedUrgentChunkBytes = 16L * 1024L * 1024L,
        recommendedUrgentWorkers = 3,
        connectionBudgetHint = null,
        retryMode = "DEFAULT"
    )

    @Test
    fun `RD confirmed warm-ahead limited to max 1 speculative worker`() {
        val controller = TransportPolicyController(rdEnvelope)
        controller.onFirstFrame()
        controller.onSteady(20000)

        val rdSpecialization = RuntimeTransportSpecialization(
            allowUrgentChunkAbove8MiB = true,
            connectionBudgetHint = 8,
            retryMode = RuntimeTransportRetryMode.CONNECTION_CLOSE,
            recommendedPrefetchWorkers = 1,
            recommendedPrefetchChunkBytes = 32L * 1024L * 1024L
        )

        val policy = controller.currentPolicy.applyRuntimeTransportSpecialization(
            specialization = rdSpecialization,
            runtimeHints = rdHints()
        )

        assertTrue("warm-ahead should be enabled in STEADY", policy.warmAheadEnabled)
        assertEquals("RD warm-ahead budget max should be 1", 1, policy.warmAheadBudgetMax)
        assertEquals("RD prefetch workers should be 1", 1, policy.prefetchWorkers)
        assertEquals(8, policy.connectionBudgetHint)
    }

    @Test
    fun `PM confirmed warm-ahead has no budget enforcement`() {
        val controller = TransportPolicyController(pmEnvelope)
        controller.onFirstFrame()
        controller.onSteady(20000)

        val pmSpecialization = RuntimeTransportSpecialization(
            allowUrgentChunkAbove8MiB = true,
            connectionBudgetHint = null,
            retryMode = RuntimeTransportRetryMode.DEFAULT,
            recommendedPrefetchWorkers = 1,
            recommendedPrefetchChunkBytes = 32L * 1024L * 1024L
        )

        val policy = controller.currentPolicy.applyRuntimeTransportSpecialization(
            specialization = pmSpecialization,
            runtimeHints = pmHints()
        )

        assertTrue("warm-ahead should be enabled in STEADY", policy.warmAheadEnabled)
        assertNull("PM should have no warm-ahead budget max", policy.warmAheadBudgetMax)
        assertNull("PM should have no connection budget", policy.connectionBudgetHint)
    }

    @Test
    fun `warm-ahead is disabled during startup state`() {
        val controller = TransportPolicyController(rdEnvelope)
        assertEquals(TransportState.STARTUP, controller.state)
        assertFalse("warm-ahead must be off during STARTUP", controller.currentPolicy.warmAheadEnabled)
    }

    @Test
    fun `warm-ahead is disabled during rebuffer recovery`() {
        val controller = TransportPolicyController(rdEnvelope)
        controller.onRebuffer()
        assertEquals(TransportState.REBUFFER, controller.state)
        assertFalse("warm-ahead must be off during REBUFFER", controller.currentPolicy.warmAheadEnabled)
        assertEquals("no prefetch during rebuffer", 0, controller.currentPolicy.prefetchWorkers)
    }

    @Test
    fun `warm-ahead is disabled after seek resets to startup`() {
        val controller = TransportPolicyController(rdEnvelope)
        controller.onFirstFrame()
        controller.onSteady(20000)
        assertTrue(controller.currentPolicy.warmAheadEnabled)

        controller.onSeek()
        assertEquals(TransportState.STARTUP, controller.state)
        assertFalse("warm-ahead must be off after seek", controller.currentPolicy.warmAheadEnabled)
    }

    @Test
    fun `unconfirmed specialization revokes warm-ahead budget`() {
        val controller = TransportPolicyController(rdEnvelope)
        controller.onFirstFrame()
        controller.onSteady(20000)

        // Unconfirmed — baseline specialization
        val policy = controller.currentPolicy.applyRuntimeTransportSpecialization(
            specialization = RuntimeTransportSpecialization(),
            runtimeHints = rdHints()
        )

        assertNull("unconfirmed should have no warm-ahead budget", policy.warmAheadBudgetMax)
        assertNull("unconfirmed should have no connection budget", policy.connectionBudgetHint)
    }
}
