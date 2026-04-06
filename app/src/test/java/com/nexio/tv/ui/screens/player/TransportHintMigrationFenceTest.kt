package com.nexio.tv.ui.screens.player

import com.nexio.tv.data.repository.benchmark.RuntimeTransportHintsV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TransportHintMigrationFenceTest {

    // -- Fixtures --

    private val v1LegacyArtifact = RuntimeTransportHintsV2(
        artifactVersion = 1,
        serviceKey = "RD",
        measuredAtMs = 100L,
        observedTransportClass = "connection_close",
        observedHostScope = "host:file",
        recommendedUrgentChunkBytes = 16L * 1024L * 1024L,
        recommendedUrgentWorkers = 2,
        connectionBudgetHint = 8,
        retryMode = "connection_close"
    )

    private val v2FreshArtifact = RuntimeTransportHintsV2(
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

    private val v2StaleArtifact = RuntimeTransportHintsV2(
        artifactVersion = 2,
        serviceKey = "RD",
        measuredAtMs = 1L, // very old timestamp
        observedTransportClass = "connection_close",
        observedHostScope = "host:file",
        recommendedUrgentChunkBytes = 16L * 1024L * 1024L,
        recommendedUrgentWorkers = 2,
        connectionBudgetHint = 8,
        retryMode = "connection_close"
    )

    private val v2ExpiredArtifact = RuntimeTransportHintsV2(
        artifactVersion = 2,
        serviceKey = "RD",
        measuredAtMs = 0L, // epoch = expired
        observedTransportClass = "connection_close",
        observedHostScope = "host:file",
        recommendedUrgentChunkBytes = 16L * 1024L * 1024L,
        recommendedUrgentWorkers = 2,
        connectionBudgetHint = 8,
        retryMode = "connection_close"
    )

    // -- v1 legacy artifact never specializes --

    @Test
    fun `v1 legacy artifact never activates specialization`() {
        val result = resolveRuntimeTransportSpecialization(
            enabled = true,
            activeServiceKey = "RD",
            activeHostScope = "host:file",
            activeTransportClass = "connection_close",
            runtimeHints = v1LegacyArtifact
        )

        assertFalse(result.allowUrgentChunkAbove8MiB)
        assertNull(result.connectionBudgetHint)
        assertEquals(RuntimeTransportRetryMode.DEFAULT, result.retryMode)
    }

    // -- v2 fresh artifact is eligible for specialization --

    @Test
    fun `v2 fresh artifact with matching observation activates specialization`() {
        val result = resolveRuntimeTransportSpecialization(
            enabled = true,
            activeServiceKey = "RD",
            activeHostScope = "host:file",
            activeTransportClass = "connection_close",
            runtimeHints = v2FreshArtifact
        )

        assertEquals(true, result.allowUrgentChunkAbove8MiB)
        assertEquals(8, result.connectionBudgetHint)
        assertEquals(RuntimeTransportRetryMode.CONNECTION_CLOSE, result.retryMode)
    }

    // -- stale artifact never specializes --

    @Test
    fun `v2 stale artifact never activates specialization`() {
        val result = resolveRuntimeTransportSpecialization(
            enabled = true,
            activeServiceKey = "RD",
            activeHostScope = "host:file",
            activeTransportClass = "connection_close",
            runtimeHints = v2StaleArtifact
        )

        assertFalse(result.allowUrgentChunkAbove8MiB)
        assertNull(result.connectionBudgetHint)
        assertEquals(RuntimeTransportRetryMode.DEFAULT, result.retryMode)
    }

    // -- expired artifact never specializes --

    @Test
    fun `v2 expired artifact never activates specialization`() {
        val result = resolveRuntimeTransportSpecialization(
            enabled = true,
            activeServiceKey = "RD",
            activeHostScope = "host:file",
            activeTransportClass = "connection_close",
            runtimeHints = v2ExpiredArtifact
        )

        assertFalse(result.allowUrgentChunkAbove8MiB)
        assertNull(result.connectionBudgetHint)
        assertEquals(RuntimeTransportRetryMode.DEFAULT, result.retryMode)
    }

    // -- Only v2 + fresh is eligible --

    @Test
    fun `only v2 fresh artifact is eligible for specialization among all fixture variants`() {
        val fixtures = listOf(
            "v1_legacy" to v1LegacyArtifact,
            "v2_stale" to v2StaleArtifact,
            "v2_expired" to v2ExpiredArtifact
        )

        for ((label, hints) in fixtures) {
            val result = resolveRuntimeTransportSpecialization(
                enabled = true,
                activeServiceKey = "RD",
                activeHostScope = "host:file",
                activeTransportClass = "connection_close",
                runtimeHints = hints
            )
            assertFalse(
                "$label should not specialize",
                result.allowUrgentChunkAbove8MiB
            )
        }

        // Only fresh should specialize
        val fresh = resolveRuntimeTransportSpecialization(
            enabled = true,
            activeServiceKey = "RD",
            activeHostScope = "host:file",
            activeTransportClass = "connection_close",
            runtimeHints = v2FreshArtifact
        )
        assertEquals(true, fresh.allowUrgentChunkAbove8MiB)
    }
}
