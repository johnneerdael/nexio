package com.nexio.tv.ui.screens.player

import com.nexio.tv.data.repository.benchmark.RuntimeTransportHintsV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTransportSpecializationTest {

    @Test
    fun `baseline stays generic safe before confirmation`() {
        val result = resolveRuntimeTransportSpecialization(
            enabled = true,
            activeServiceKey = "RD",
            activeHostScope = null,
            activeTransportClass = null,
            runtimeHints = hints()
        )

        assertFalse(result.allowUrgentChunkAbove8MiB)
        assertNull(result.connectionBudgetHint)
        assertEquals(RuntimeTransportRetryMode.DEFAULT, result.retryMode)
    }

    @Test
    fun `confirmed hints activate all supported specialization knobs symmetrically`() {
        val result = resolveRuntimeTransportSpecialization(
            enabled = true,
            activeServiceKey = "RD",
            activeHostScope = "host:file",
            activeTransportClass = "connection_close",
            runtimeHints = hints()
        )

        assertEquals(true, result.allowUrgentChunkAbove8MiB)
        assertEquals(8, result.connectionBudgetHint)
        assertEquals(RuntimeTransportRetryMode.CONNECTION_CLOSE, result.retryMode)
    }

    @Test
    fun `service mismatch keeps all specialization knobs at baseline`() {
        val result = resolveRuntimeTransportSpecialization(
            enabled = true,
            activeServiceKey = "PM",
            activeHostScope = "host:file",
            activeTransportClass = "connection_close",
            runtimeHints = hints()
        )

        assertFalse(result.allowUrgentChunkAbove8MiB)
        assertNull(result.connectionBudgetHint)
        assertEquals(RuntimeTransportRetryMode.DEFAULT, result.retryMode)
    }

    @Test
    fun `host or transport mismatch revokes specialization as a whole`() {
        val confirmed = resolveRuntimeTransportSpecialization(
            enabled = true,
            activeServiceKey = "RD",
            activeHostScope = "host:file",
            activeTransportClass = "connection_close",
            runtimeHints = hints()
        )
        val revoked = resolveRuntimeTransportSpecialization(
            enabled = true,
            activeServiceKey = "RD",
            activeHostScope = "other-host",
            activeTransportClass = "keep_alive",
            runtimeHints = hints()
        )

        assertEquals(true, confirmed.allowUrgentChunkAbove8MiB)
        assertEquals(8, confirmed.connectionBudgetHint)
        assertEquals(RuntimeTransportRetryMode.CONNECTION_CLOSE, confirmed.retryMode)

        assertFalse(revoked.allowUrgentChunkAbove8MiB)
        assertNull(revoked.connectionBudgetHint)
        assertEquals(RuntimeTransportRetryMode.DEFAULT, revoked.retryMode)
    }

    @Test
    fun `transition helper emits confirm mismatch and revoke events`() {
        val confirmed = nextRuntimeTransportSpecializationTransition(
            previousStatus = null,
            enabled = true,
            activeServiceKey = "RD",
            runtimeHints = hints(),
            observation = RuntimeTransportObservation(
                hostScope = "host:file",
                transportClass = "connection_close"
            )
        )
        val mismatched = nextRuntimeTransportSpecializationTransition(
            previousStatus = confirmed.status,
            enabled = true,
            activeServiceKey = "RD",
            runtimeHints = hints(),
            observation = RuntimeTransportObservation(
                hostScope = "other-host",
                transportClass = "keep_alive"
            )
        )

        assertEquals(RuntimeTransportSpecializationStatus.CONFIRMED, confirmed.status)
        assertEquals(listOf("transport_specialization_confirmed"), confirmed.events.map { it.type })

        assertEquals(RuntimeTransportSpecializationStatus.MISMATCH, mismatched.status)
        assertEquals(
            listOf("transport_specialization_mismatch", "transport_specialization_revoked"),
            mismatched.events.map { it.type }
        )
        assertTrue(mismatched.events.first().detail.contains("reason=confirmation_lost"))
    }

    private fun hints(): RuntimeTransportHintsV2 {
        return RuntimeTransportHintsV2(
            artifactVersion = 2,
            serviceKey = "RD",
            measuredAtMs = System.currentTimeMillis(),
            observedTransportClass = "connection_close",
            observedHostScope = "host:file",
            recommendedUrgentChunkBytes = 16L * 1024L * 1024L,
            recommendedUrgentWorkers = 2,
            connectionBudgetHint = 8,
            retryMode = "connection_close"
        )
    }
}
