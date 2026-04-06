package com.nexio.tv.ui.screens.player

import com.nexio.tv.data.repository.benchmark.RuntimeTransportHintsV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTransportRevocationContractTest {

    // -- Fixtures --

    private val freshNowMs = System.currentTimeMillis()

    private fun rdConfirmedHints() = RuntimeTransportHintsV2(
        artifactVersion = 2,
        serviceKey = "RD",
        measuredAtMs = freshNowMs,
        observedTransportClass = "connection_close",
        observedHostScope = "host:file",
        recommendedUrgentChunkBytes = 16L * 1024L * 1024L,
        recommendedUrgentWorkers = 2,
        connectionBudgetHint = 8,
        retryMode = "connection_close"
    )

    private fun pmConfirmedHints() = RuntimeTransportHintsV2(
        artifactVersion = 2,
        serviceKey = "PM",
        measuredAtMs = freshNowMs,
        observedTransportClass = "keep_alive",
        observedHostScope = "host:pm-cdn",
        recommendedUrgentChunkBytes = 8L * 1024L * 1024L,
        recommendedUrgentWorkers = 2,
        connectionBudgetHint = null,
        retryMode = null
    )

    // -- Service switch revocation --

    @Test
    fun `service switch from RD to PM revokes all specialization atomically`() {
        // Confirm with RD
        val confirmed = resolveRuntimeTransportSpecialization(
            enabled = true,
            activeServiceKey = "RD",
            activeHostScope = "host:file",
            activeTransportClass = "connection_close",
            runtimeHints = rdConfirmedHints()
        )
        assertTrue(confirmed.allowUrgentChunkAbove8MiB)
        assertEquals(8, confirmed.connectionBudgetHint)
        assertEquals(RuntimeTransportRetryMode.CONNECTION_CLOSE, confirmed.retryMode)

        // Switch to PM — must revoke everything atomically
        val revoked = resolveRuntimeTransportSpecialization(
            enabled = true,
            activeServiceKey = "PM",
            activeHostScope = "host:file",
            activeTransportClass = "connection_close",
            runtimeHints = rdConfirmedHints()
        )
        assertFalse(revoked.allowUrgentChunkAbove8MiB)
        assertNull(revoked.connectionBudgetHint)
        assertEquals(RuntimeTransportRetryMode.DEFAULT, revoked.retryMode)
    }

    // -- Host scope change revocation --

    @Test
    fun `host scope change revokes all knobs atomically`() {
        val confirmed = resolveRuntimeTransportSpecialization(
            enabled = true,
            activeServiceKey = "RD",
            activeHostScope = "host:file",
            activeTransportClass = "connection_close",
            runtimeHints = rdConfirmedHints()
        )
        assertTrue(confirmed.allowUrgentChunkAbove8MiB)

        val revoked = resolveRuntimeTransportSpecialization(
            enabled = true,
            activeServiceKey = "RD",
            activeHostScope = "host:different-cdn",
            activeTransportClass = "connection_close",
            runtimeHints = rdConfirmedHints()
        )
        assertFalse(revoked.allowUrgentChunkAbove8MiB)
        assertNull(revoked.connectionBudgetHint)
        assertEquals(RuntimeTransportRetryMode.DEFAULT, revoked.retryMode)
    }

    // -- Transport class mismatch revocation --

    @Test
    fun `transport class mismatch revokes all knobs atomically`() {
        val confirmed = resolveRuntimeTransportSpecialization(
            enabled = true,
            activeServiceKey = "RD",
            activeHostScope = "host:file",
            activeTransportClass = "connection_close",
            runtimeHints = rdConfirmedHints()
        )
        assertTrue(confirmed.allowUrgentChunkAbove8MiB)

        val revoked = resolveRuntimeTransportSpecialization(
            enabled = true,
            activeServiceKey = "RD",
            activeHostScope = "host:file",
            activeTransportClass = "keep_alive",
            runtimeHints = rdConfirmedHints()
        )
        assertFalse(revoked.allowUrgentChunkAbove8MiB)
        assertNull(revoked.connectionBudgetHint)
        assertEquals(RuntimeTransportRetryMode.DEFAULT, revoked.retryMode)
    }

    // -- Shared CDN with RD confirmed --

    @Test
    fun `shared CDN hostname with RD confirmed activates RD specialization`() {
        val result = resolveRuntimeTransportSpecialization(
            enabled = true,
            activeServiceKey = "RD",
            activeHostScope = "host:file",
            activeTransportClass = "connection_close",
            runtimeHints = rdConfirmedHints()
        )
        assertTrue(result.allowUrgentChunkAbove8MiB)
        assertEquals(8, result.connectionBudgetHint)
        assertEquals(RuntimeTransportRetryMode.CONNECTION_CLOSE, result.retryMode)
    }

    // -- Shared CDN mismatch --

    @Test
    fun `shared CDN hostname with wrong service key does not specialize`() {
        val result = resolveRuntimeTransportSpecialization(
            enabled = true,
            activeServiceKey = "PM",
            activeHostScope = "host:file",
            activeTransportClass = "connection_close",
            runtimeHints = rdConfirmedHints()
        )
        assertFalse(result.allowUrgentChunkAbove8MiB)
        assertNull(result.connectionBudgetHint)
        assertEquals(RuntimeTransportRetryMode.DEFAULT, result.retryMode)
    }

    // -- Pre-confirmation baseline enforced --

    @Test
    fun `pre-confirmation baseline enforced before any observation`() {
        val result = resolveRuntimeTransportSpecialization(
            enabled = true,
            activeServiceKey = "RD",
            activeHostScope = null,
            activeTransportClass = null,
            runtimeHints = rdConfirmedHints()
        )
        assertFalse(result.allowUrgentChunkAbove8MiB)
        assertNull(result.connectionBudgetHint)
        assertEquals(RuntimeTransportRetryMode.DEFAULT, result.retryMode)
    }

    // -- Confirmation activates all knobs together --

    @Test
    fun `confirmation activates all knobs together not incrementally`() {
        val result = resolveRuntimeTransportSpecialization(
            enabled = true,
            activeServiceKey = "RD",
            activeHostScope = "host:file",
            activeTransportClass = "connection_close",
            runtimeHints = rdConfirmedHints()
        )

        // All three must be active — never partially specialized
        assertTrue(result.allowUrgentChunkAbove8MiB)
        assertEquals(8, result.connectionBudgetHint)
        assertEquals(RuntimeTransportRetryMode.CONNECTION_CLOSE, result.retryMode)
    }

    // -- Revocation is atomic (transition helper) --

    @Test
    fun `revocation transition emits revoke event with all knob names`() {
        val confirmed = nextRuntimeTransportSpecializationTransition(
            previousStatus = null,
            enabled = true,
            activeServiceKey = "RD",
            runtimeHints = rdConfirmedHints(),
            observation = RuntimeTransportObservation(
                hostScope = "host:file",
                transportClass = "connection_close"
            )
        )
        assertEquals(RuntimeTransportSpecializationStatus.CONFIRMED, confirmed.status)

        val revoked = nextRuntimeTransportSpecializationTransition(
            previousStatus = confirmed.status,
            enabled = true,
            activeServiceKey = "RD",
            runtimeHints = rdConfirmedHints(),
            observation = RuntimeTransportObservation(
                hostScope = "other-host",
                transportClass = "keep_alive"
            )
        )

        assertEquals(RuntimeTransportSpecializationStatus.MISMATCH, revoked.status)
        val revokeEvent = revoked.events.find { it.type == "transport_specialization_revoked" }
        assertTrue("revoke event must be present", revokeEvent != null)
    }
}
