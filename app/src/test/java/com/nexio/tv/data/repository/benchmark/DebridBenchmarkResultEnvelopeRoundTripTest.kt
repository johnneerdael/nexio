package com.nexio.tv.data.repository.benchmark

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.nexio.tv.data.local.DebridBenchmarkStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DebridBenchmarkResultEnvelopeRoundTripTest {

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun buildStore(scope: CoroutineScope, file: File): DebridBenchmarkStore {
        val dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = { file }
        )
        return DebridBenchmarkStore(dataStore)
    }

    private fun sampleOptimizedOnlyResult(
        provider: DebridBenchmarkProvider,
        measuredAtMs: Long,
        sustainedThroughputMbps: Double
    ): DebridBenchmarkResult {
        val profile = DebridBenchmarkTransportProfile(
            startup = DebridBenchmarkStartupMetrics(
                initialTtfbMs = 90L,
                startupFailureRate = 0.0
            ),
            sustained = DebridBenchmarkSustainedMetrics(
                collectorVersion = 1,
                samplingMode = "bucket",
                bucketMs = 1000L,
                decisionWindowMs = 5000L,
                averageThroughputMbps = sustainedThroughputMbps,
                derivedAverageThroughputMbps = sustainedThroughputMbps,
                actionable = true,
                p10ThroughputMbps = sustainedThroughputMbps * 0.9,
                p50ThroughputMbps = sustainedThroughputMbps,
                peakThroughputMbps = sustainedThroughputMbps * 1.1,
                throughputStddevMbps = 5.0,
                throughputCv = 0.05,
                stallCount = 0,
                maxReadGapMs = 100L,
                bytesTransferred = 100_000_000L,
                elapsedMs = 10_000L
            ),
            seek = DebridBenchmarkSeekMetrics(
                seekTtfbP50Ms = 50L,
                seekTtfbP95Ms = 100L,
                seekTtfbP99Ms = 150L,
                seekTtfbStddevMs = 10.0,
                seekFailRate = 0.0
            ),
            decision = DebridBenchmarkTransportDecisionMetrics(
                safeSustainedBudgetMbps = sustainedThroughputMbps * 0.85,
                startupSafeBudgetMbps = sustainedThroughputMbps * 0.8,
                steadyStateSafeBudgetMbps = sustainedThroughputMbps * 0.85,
                actionable = true
            ),
            configSnapshot = DebridBenchmarkTransportConfigSnapshot(
                useParallelConnections = true,
                parallelConnectionCount = 1,
                parallelChunkSizeMb = 32
            )
        )
        return DebridBenchmarkResult(
            provider = provider,
            measuredAtMs = measuredAtMs,
            summary = DebridBenchmarkSummary(
                startupTimeMs = 90L,
                sustainedThroughputMbps = sustainedThroughputMbps,
                transferredBytes = 100_000_000L,
                elapsedMs = 10_000L
            ),
            terminationReason = DebridBenchmarkTerminationReason.COMPLETED,
            candidate = DebridBenchmarkCandidateMetadata(
                filename = "Test.mkv",
                sizeBytes = 10L * 1024L * 1024L * 1024L,
                host = "cdn.rd.example.com",
                directUrlFingerprint = "abc123"
            ),
            session = DebridBenchmarkSessionMetadata(
                benchmarkVersion = 4,
                executionOrder = listOf(
                    DebridBenchmarkPhaseExecution(
                        phase = DebridBenchmarkPhase.STARTUP,
                        order = listOf(DebridBenchmarkTransportMode.OPTIMIZED)
                    ),
                    DebridBenchmarkPhaseExecution(
                        phase = DebridBenchmarkPhase.SUSTAINED,
                        order = listOf(DebridBenchmarkTransportMode.OPTIMIZED)
                    ),
                    DebridBenchmarkPhaseExecution(
                        phase = DebridBenchmarkPhase.SEEK,
                        order = listOf(DebridBenchmarkTransportMode.OPTIMIZED)
                    )
                ),
                totalElapsedMs = 10_000L
            ),
            optimized = profile
        )
    }

    // ---------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------

    /**
     * A result with an RD locked envelope round-trips through the store and comes back equal.
     */
    @Test
    fun `RD result with attached envelope survives store round-trip`() = runTest {
        val file = File.createTempFile("envelope_roundtrip", ".preferences_pb")
        file.deleteOnExit()
        val storeJob = SupervisorJob()
        val storeScope = CoroutineScope(backgroundScope.coroutineContext + storeJob)

        val measuredAtMs = 1_700_000_000_000L
        val sustainedThroughput = 748.72

        val rdLocked = CapabilityEnvelope.LOCKED_REAL_DEBRID.copy(
            sustainedThroughputMbps = sustainedThroughput,
            measuredAtMs = measuredAtMs
        )

        val original = sampleOptimizedOnlyResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            measuredAtMs = measuredAtMs,
            sustainedThroughputMbps = sustainedThroughput
        ).copy(capabilityEnvelope = rdLocked)

        val firstStore = buildStore(storeScope, file)
        firstStore.saveLatest(original)
        storeJob.cancelAndJoin()

        val reopenedStore = buildStore(backgroundScope, file)
        val restored = reopenedStore.latestResult(DebridBenchmarkProvider.REAL_DEBRID).first()

        assertNotNull(restored)
        val restoredEnvelope = restored!!.capabilityEnvelope
        assertNotNull(restoredEnvelope)
        assertEquals(rdLocked.maxSafeUrgentWorkers, restoredEnvelope!!.maxSafeUrgentWorkers)
        assertEquals(rdLocked.maxSafePrefetchWorkers, restoredEnvelope.maxSafePrefetchWorkers)
        assertEquals(rdLocked.maxSafeUrgentChunkBytes, restoredEnvelope.maxSafeUrgentChunkBytes)
        assertEquals(rdLocked.maxSafePrefetchChunkBytes, restoredEnvelope.maxSafePrefetchChunkBytes)
        assertEquals(sustainedThroughput, restoredEnvelope.sustainedThroughputMbps, 0.001)
        assertEquals(measuredAtMs, restoredEnvelope.measuredAtMs)
        // Shape must match the RD locked constant
        assertTrue(CapabilityEnvelope.LOCKED_REAL_DEBRID.matchesLockedShape(restoredEnvelope))
    }

    /**
     * A result with maxSafeUrgentChunkBytes == 33_554_432 (RD locked shape).
     */
    @Test
    fun `RD envelope maxSafeUrgentChunkBytes is 33554432 after round-trip`() = runTest {
        val file = File.createTempFile("envelope_chunk_rt", ".preferences_pb")
        file.deleteOnExit()
        val storeJob = SupervisorJob()
        val storeScope = CoroutineScope(backgroundScope.coroutineContext + storeJob)

        val measuredAtMs = 1_700_000_001_000L
        val rdLocked = CapabilityEnvelope.LOCKED_REAL_DEBRID.copy(
            sustainedThroughputMbps = 748.72,
            measuredAtMs = measuredAtMs
        )

        val original = sampleOptimizedOnlyResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            measuredAtMs = measuredAtMs,
            sustainedThroughputMbps = 748.72
        ).copy(capabilityEnvelope = rdLocked)

        buildStore(storeScope, file).saveLatest(original)
        storeJob.cancelAndJoin()

        val restored = buildStore(backgroundScope, file)
            .latestResult(DebridBenchmarkProvider.REAL_DEBRID).first()

        assertEquals(33_554_432L, restored?.capabilityEnvelope?.maxSafeUrgentChunkBytes)
        assertEquals(748.72, restored?.capabilityEnvelope?.sustainedThroughputMbps ?: 0.0, 0.001)
    }

    /**
     * A legacy stored result (JSON without the capabilityEnvelope field) deserializes with
     * capabilityEnvelope == null — no migration error.
     */
    @Test
    fun `legacy result without capabilityEnvelope field deserializes as null`() = runTest {
        val file = File.createTempFile("envelope_legacy", ".preferences_pb")
        file.deleteOnExit()
        val storeJob = SupervisorJob()
        val storeScope = CoroutineScope(backgroundScope.coroutineContext + storeJob)

        // Write a result WITHOUT the capabilityEnvelope field
        val original = sampleOptimizedOnlyResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            measuredAtMs = 1_000L,
            sustainedThroughputMbps = 500.0
        ) // capabilityEnvelope defaults to null

        buildStore(storeScope, file).saveLatest(original)
        storeJob.cancelAndJoin()

        val restored = buildStore(backgroundScope, file)
            .latestResult(DebridBenchmarkProvider.REAL_DEBRID).first()

        assertNotNull(restored)
        assertNull(restored!!.capabilityEnvelope)
    }
}
