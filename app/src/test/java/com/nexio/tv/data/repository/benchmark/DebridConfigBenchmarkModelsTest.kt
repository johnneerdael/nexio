package com.nexio.tv.data.repository.benchmark

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nexio.tv.data.local.DebridConfigBenchmarkStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DebridConfigBenchmarkModelsTest {

    // ---------------------------------------------------------------------------
    // toCapabilityEnvelope — RD locked-merge path
    // ---------------------------------------------------------------------------

    @Test
    fun `RD profile with 748 Mbps returns LOCKED_REAL_DEBRID shape with measured throughput`() {
        val summary = summaryWithBest(
            connections = 8,      // profile claims 8 — must be ignored
            chunkMb = 64,         // profile claims 64 MiB — must be ignored
            throughput = 748.72
        )

        val result = summary.toCapabilityEnvelope(
            provider = DebridBenchmarkProvider.REAL_DEBRID.storageKey,
            measuredAtMs = 1_700_000_000_000L
        )

        // Shape fields must equal the locked constants exactly
        assertEquals(CapabilityEnvelope.LOCKED_REAL_DEBRID.maxSafeUrgentWorkers, result.maxSafeUrgentWorkers)
        assertEquals(CapabilityEnvelope.LOCKED_REAL_DEBRID.maxSafePrefetchWorkers, result.maxSafePrefetchWorkers)
        assertEquals(CapabilityEnvelope.LOCKED_REAL_DEBRID.maxSafeUrgentChunkBytes, result.maxSafeUrgentChunkBytes)
        assertEquals(CapabilityEnvelope.LOCKED_REAL_DEBRID.maxSafePrefetchChunkBytes, result.maxSafePrefetchChunkBytes)
        assertEquals(CapabilityEnvelope.LOCKED_REAL_DEBRID.supportsRangeRequests, result.supportsRangeRequests)

        // Measurement fields must come from the input
        assertEquals(748.72, result.sustainedThroughputMbps, 0.001)
        assertEquals(1_700_000_000_000L, result.measuredAtMs)
    }

    @Test
    fun `PM profile with 745 Mbps returns LOCKED_PREMIUMIZE shape with measured throughput`() {
        val summary = summaryWithBest(
            connections = 4,
            chunkMb = 32,
            throughput = 745.31
        )

        val result = summary.toCapabilityEnvelope(
            provider = DebridBenchmarkProvider.PREMIUMIZE.storageKey,
            measuredAtMs = 1_700_000_000_001L
        )

        assertEquals(CapabilityEnvelope.LOCKED_PREMIUMIZE.maxSafeUrgentWorkers, result.maxSafeUrgentWorkers)
        assertEquals(CapabilityEnvelope.LOCKED_PREMIUMIZE.maxSafePrefetchWorkers, result.maxSafePrefetchWorkers)
        assertEquals(CapabilityEnvelope.LOCKED_PREMIUMIZE.maxSafeUrgentChunkBytes, result.maxSafeUrgentChunkBytes)
        assertEquals(CapabilityEnvelope.LOCKED_PREMIUMIZE.maxSafePrefetchChunkBytes, result.maxSafePrefetchChunkBytes)
        assertEquals(CapabilityEnvelope.LOCKED_PREMIUMIZE.supportsRangeRequests, result.supportsRangeRequests)

        assertEquals(745.31, result.sustainedThroughputMbps, 0.001)
    }

    @Test
    fun `RD profile claiming chunkSizeMb=64 is ignored — urgent stays 32 MiB, prefetch stays 64 MiB`() {
        val summary = summaryWithBest(connections = 8, chunkMb = 64, throughput = 700.0)

        val result = summary.toCapabilityEnvelope(
            provider = DebridBenchmarkProvider.REAL_DEBRID.storageKey,
            measuredAtMs = 1L
        )

        // No 8 MiB clamp: urgent must be 32 MiB (locked), NOT min(64, 8)*1024^2
        assertEquals(33_554_432L, result.maxSafeUrgentChunkBytes)   // 32 MiB
        assertEquals(67_108_864L, result.maxSafePrefetchChunkBytes)  // 64 MiB
        // prefetchWorkers must be 2 (locked), not derived from profile
        assertEquals(2, result.maxSafePrefetchWorkers)
        // urgentWorkers must be 1 (locked), not min(8, 3)
        assertEquals(1, result.maxSafeUrgentWorkers)
    }

    // ---------------------------------------------------------------------------
    // ColdStartReturnsLockedShapeWithZeroMeasurement
    // ---------------------------------------------------------------------------

    @Test
    fun `ColdStartReturnsLockedShapeWithZeroMeasurement - null best profile returns locked RD shape with zero measurement`() {
        val summary = DebridConfigBenchmarkSessionSummary(
            totalProfileCount = 0,
            successfulProfileCount = 0,
            failedProfileCount = 0,
            unsupportedProfileCount = 0,
            bestProfile = null
        )

        val result = summary.toCapabilityEnvelope(
            provider = DebridBenchmarkProvider.REAL_DEBRID.storageKey,
            measuredAtMs = 99L
        )

        // Shape must be locked
        assertEquals(CapabilityEnvelope.LOCKED_REAL_DEBRID.maxSafeUrgentWorkers, result.maxSafeUrgentWorkers)
        assertEquals(CapabilityEnvelope.LOCKED_REAL_DEBRID.maxSafeUrgentChunkBytes, result.maxSafeUrgentChunkBytes)
        assertEquals(CapabilityEnvelope.LOCKED_REAL_DEBRID.maxSafePrefetchChunkBytes, result.maxSafePrefetchChunkBytes)
        // Measurement fields must be zero (cold-start)
        assertEquals(0.0, result.sustainedThroughputMbps, 0.0)
        assertEquals(0.0, result.stabilityPenalty, 0.0)
        assertEquals(0L, result.measuredAtMs)
    }

    @Test
    fun `ColdStartReturnsLockedShapeWithZeroMeasurement - failed best profile returns locked RD shape with zero measurement`() {
        val failedProfile = DebridConfigBenchmarkProfileResult(
            parallelConnectionCount = 2,
            chunkSizeMb = 8,
            status = DebridConfigBenchmarkStatus.FAILED,
            failureReason = "timeout"
        )
        val summary = DebridConfigBenchmarkSessionSummary(
            totalProfileCount = 1,
            successfulProfileCount = 0,
            failedProfileCount = 1,
            unsupportedProfileCount = 0,
            bestProfile = failedProfile
        )

        val result = summary.toCapabilityEnvelope(
            provider = DebridBenchmarkProvider.REAL_DEBRID.storageKey,
            measuredAtMs = 50L
        )

        assertEquals(CapabilityEnvelope.LOCKED_REAL_DEBRID.maxSafeUrgentChunkBytes, result.maxSafeUrgentChunkBytes)
        assertEquals(0.0, result.sustainedThroughputMbps, 0.0)
        assertEquals(0L, result.measuredAtMs)
    }

    // ---------------------------------------------------------------------------
    // Legacy / non-RD/PM provider regression guard
    // ---------------------------------------------------------------------------

    @Test
    fun `TorBox profile uses legacy synthesis path unchanged`() {
        val summary = summaryWithBest(connections = 2, chunkMb = 8, throughput = 400.0)

        val result = summary.toCapabilityEnvelope(
            provider = DebridBenchmarkProvider.TORBOX.storageKey,
            measuredAtMs = 1L
        )

        // Legacy path: urgentWorkers = min(connections, 3) = 2
        assertEquals(2, result.maxSafeUrgentWorkers)
        // Legacy path: urgentChunk = min(8, 8)*1024^2 = 8 MiB
        assertEquals(8L * 1024L * 1024L, result.maxSafeUrgentChunkBytes)
        // Legacy path: prefetchChunk = 8 MiB
        assertEquals(8L * 1024L * 1024L, result.maxSafePrefetchChunkBytes)
        assertEquals(400.0, result.sustainedThroughputMbps, 0.001)
    }

    @Test
    fun `EasyDebrid null best profile uses legacy synthesis path returning DEFAULT`() {
        val summary = DebridConfigBenchmarkSessionSummary(
            totalProfileCount = 0,
            successfulProfileCount = 0,
            failedProfileCount = 0,
            unsupportedProfileCount = 0,
            bestProfile = null
        )

        val result = summary.toCapabilityEnvelope(
            provider = DebridBenchmarkProvider.EASY_DEBRID.storageKey,
            measuredAtMs = 1L
        )

        // Legacy path falls back to DEFAULT when no best profile
        assertEquals(CapabilityEnvelope.DEFAULT.maxSafeUrgentWorkers, result.maxSafeUrgentWorkers)
        assertEquals(CapabilityEnvelope.DEFAULT.maxSafeUrgentChunkBytes, result.maxSafeUrgentChunkBytes)
    }

    // ---------------------------------------------------------------------------
    // LegacyStoredEnvelopeWith8MiBUrgentIsMigratedToLocked — store path
    // ---------------------------------------------------------------------------

    @Test
    fun `LegacyStoredEnvelopeWith8MiBUrgentIsMigratedToLocked`() = runTest {
        val file = newTempStoreFile()

        // Phase 1: write legacy JSON via a dedicated scope, then cancel it so the file is released.
        val writeJob = SupervisorJob()
        val writeScope = CoroutineScope(backgroundScope.coroutineContext + writeJob)
        val writeDataStore = buildDataStore(writeScope, file)
        val key = stringPreferencesKey("debrid_config_benchmark_latest_real_debrid")
        writeDataStore.edit { it[key] = legacyRdResultJsonWith8MiBUrgent() }
        writeJob.cancelAndJoin()

        // Phase 2: read via a fresh store instance — must NOT throw.
        val readStore = buildStore(backgroundScope, file)
        val storeResult = readStore.latestResult(DebridBenchmarkProvider.REAL_DEBRID).first()

        // The stored envelope had 8 MiB urgent which fails matchesLockedShape;
        // the migration discards it (capabilityEnvelope = null in summary).
        // The result itself is still returned (the outer result is valid; only the envelope is stripped).
        assertNotNull("result should be readable after migration", storeResult)
        assertNull(
            "legacy envelope should have been discarded",
            storeResult!!.summary.capabilityEnvelope
        )
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun summaryWithBest(
        connections: Int,
        chunkMb: Int,
        throughput: Double
    ): DebridConfigBenchmarkSessionSummary {
        val best = DebridConfigBenchmarkProfileResult(
            parallelConnectionCount = connections,
            chunkSizeMb = chunkMb,
            status = DebridConfigBenchmarkStatus.SUCCESS,
            averageThroughputMbps = throughput
        )
        return DebridConfigBenchmarkSessionSummary(
            totalProfileCount = 1,
            successfulProfileCount = 1,
            failedProfileCount = 0,
            unsupportedProfileCount = 0,
            bestProfile = best
        )
    }

    private fun buildStore(scope: CoroutineScope, file: File): DebridConfigBenchmarkStore {
        return DebridConfigBenchmarkStore(buildDataStore(scope, file))
    }

    private fun buildDataStore(scope: CoroutineScope, file: File): DataStore<Preferences> {
        return PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = { file }
        )
    }

    private fun newTempStoreFile(): File {
        return File.createTempFile("debrid_config_benchmark_models_test", ".preferences_pb")
            .also { it.deleteOnExit() }
    }

    /**
     * Builds a JSON string representing a valid RD benchmark result whose
     * persisted [CapabilityEnvelope] has [maxSafeUrgentChunkBytes] = 8 MiB
     * (legacy value that must be migrated away on read).
     */
    private fun legacyRdResultJsonWith8MiBUrgent(): String {
        // envelope with legacy 8 MiB urgent chunk
        val envelopeJson = """{"maxSafeUrgentWorkers":1,"maxSafePrefetchWorkers":1,"maxSafeUrgentChunkBytes":8388608,"maxSafePrefetchChunkBytes":67108864,"sustainedThroughputMbps":748.72,"stabilityPenalty":0.0,"supportsRangeRequests":true,"measuredAtMs":1700000000000}"""

        val profileJson = """{"profile":{"parallelConnectionCount":1,"chunkSizeMb":8},"status":"success","averageThroughputMbps":748.72,"transferredBytes":104857600,"elapsedMs":1000}"""

        return """
        {
          "provider": "real_debrid",
          "measuredAtMs": 1700000000000,
          "candidate": {"filename": "Test.mkv", "sizeBytes": 1073741824, "host": "cdn.example.com", "directUrlFingerprint": "fp1"},
          "summary": {
            "totalProfiles": 1,
            "successfulProfiles": 1,
            "failedProfiles": 0,
            "unsupportedProfiles": 0,
            "bestProfile": $profileJson,
            "capabilityEnvelope": $envelopeJson
          },
          "orderedProfileResults": [$profileJson]
        }
        """.trimIndent()
    }
}
