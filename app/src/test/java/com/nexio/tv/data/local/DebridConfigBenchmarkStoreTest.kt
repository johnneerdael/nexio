package com.nexio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkCandidateMetadata
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkProvider
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkProfileMetadata
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkProfileResult
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkResult
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkSessionSummary
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkStatus
import com.nexio.tv.data.repository.benchmark.RuntimeTransportHintsV2
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DebridConfigBenchmarkStoreTest {

    @Test
    fun `saving a latest config benchmark result persists the full result shape across store reopen`() = runTest {
        val file = newTempBenchmarkStoreFile()
        val storeJob = SupervisorJob()
        val storeScope = CoroutineScope(backgroundScope.coroutineContext + storeJob)
        val expected = sampleMatrixResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            measuredAtMs = 7L,
            bestProfile = sampleProfileResult(
                parallelConnectionCount = 4,
                chunkSizeMb = 16,
                averageThroughputMbps = 720.0
            )
        )
        val firstStore = buildStore(storeScope, file)

        firstStore.saveLatest(expected)
        storeJob.cancelAndJoin()

        val reopenedStore = buildStore(backgroundScope, file)
        val restored = reopenedStore.latestResult(DebridBenchmarkProvider.REAL_DEBRID).first()

        assertEquals(expected, restored)
    }

    @Test
    fun `saving a latest config benchmark result overwrites the previous result for that provider`() = runTest {
        val store = buildStore(backgroundScope)
        store.saveLatest(
            sampleMatrixResult(
                provider = DebridBenchmarkProvider.REAL_DEBRID,
                measuredAtMs = 1L,
                bestProfile = sampleProfileResult(
                    parallelConnectionCount = 2,
                    chunkSizeMb = 8,
                    averageThroughputMbps = 410.0
                )
            )
        )
        store.saveLatest(
            sampleMatrixResult(
                provider = DebridBenchmarkProvider.REAL_DEBRID,
                measuredAtMs = 2L,
                bestProfile = null
            )
        )

        assertEquals(2L, store.latestResult(DebridBenchmarkProvider.REAL_DEBRID).first()?.measuredAtMs)
        assertEquals(null, store.latestResult(DebridBenchmarkProvider.REAL_DEBRID).first()?.bestProfile)
    }

    @Test
    fun `provider config benchmark results remain isolated`() = runTest {
        val store = buildStore(backgroundScope)
        store.saveLatest(
            sampleMatrixResult(
                provider = DebridBenchmarkProvider.REAL_DEBRID,
                measuredAtMs = 10L,
                bestProfile = sampleProfileResult(
                    parallelConnectionCount = 3,
                    chunkSizeMb = 8,
                    averageThroughputMbps = 530.0
                )
            )
        )
        store.saveLatest(
            sampleMatrixResult(
                provider = DebridBenchmarkProvider.PREMIUMIZE,
                measuredAtMs = 11L,
                bestProfile = null
            )
        )

        assertNotNull(store.latestResult(DebridBenchmarkProvider.REAL_DEBRID).first())
        assertNotNull(store.latestResult(DebridBenchmarkProvider.PREMIUMIZE).first())
        assertEquals(10L, store.latestResult(DebridBenchmarkProvider.REAL_DEBRID).first()?.measuredAtMs)
        assertEquals(11L, store.latestResult(DebridBenchmarkProvider.PREMIUMIZE).first()?.measuredAtMs)
    }

    private fun buildStore(scope: CoroutineScope): DebridConfigBenchmarkStore {
        return buildStore(scope, newTempBenchmarkStoreFile())
    }

    private fun buildStore(scope: CoroutineScope, file: File): DebridConfigBenchmarkStore {
        return DebridConfigBenchmarkStore(buildDataStore(scope, file))
    }

    private fun buildDataStore(
        scope: CoroutineScope,
        file: File
    ): DataStore<Preferences> {
        return PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = { file }
        )
    }

    private fun sampleMatrixResult(
        provider: DebridBenchmarkProvider,
        measuredAtMs: Long = 100L,
        bestProfile: DebridConfigBenchmarkProfileResult? = sampleProfileResult(
            parallelConnectionCount = 4,
            chunkSizeMb = 16,
            averageThroughputMbps = 720.0
        )
    ): DebridConfigBenchmarkResult {
        val orderedProfileResults = listOf(
            sampleProfileResult(
                parallelConnectionCount = 2,
                chunkSizeMb = 8,
                averageThroughputMbps = 410.0
            ),
            sampleProfileResult(
                parallelConnectionCount = 3,
                chunkSizeMb = 8,
                averageThroughputMbps = 530.0
            ),
            sampleProfileResult(
                parallelConnectionCount = 4,
                chunkSizeMb = 16,
                averageThroughputMbps = 720.0
            )
        )
        return DebridConfigBenchmarkResult(
            provider = provider,
            measuredAtMs = measuredAtMs,
            candidate = DebridBenchmarkCandidateMetadata(
                filename = "Example.mkv",
                sizeBytes = 20L * 1024L * 1024L * 1024L,
                host = "cdn.example.com",
                directUrlFingerprint = "candidate-fingerprint"
            ),
            summary = DebridConfigBenchmarkSessionSummary(
                totalProfileCount = orderedProfileResults.size,
                successfulProfileCount = orderedProfileResults.count { it.status == DebridConfigBenchmarkStatus.SUCCESS },
                failedProfileCount = orderedProfileResults.count { it.status == DebridConfigBenchmarkStatus.FAILED },
                unsupportedProfileCount = orderedProfileResults.count { it.status == DebridConfigBenchmarkStatus.UNSUPPORTED },
                bestProfile = bestProfile,
                runtimeTransportHints = RuntimeTransportHintsV2(
                    artifactVersion = 2,
                    serviceKey = when (provider) {
                        DebridBenchmarkProvider.REAL_DEBRID -> "RD"
                        DebridBenchmarkProvider.PREMIUMIZE -> "PM"
                        DebridBenchmarkProvider.TORBOX -> "TB"
                        DebridBenchmarkProvider.EASY_DEBRID -> "ED"
                    },
                    measuredAtMs = measuredAtMs,
                    observedHostScope = "host:file",
                    recommendedUrgentChunkBytes = 8L * 1024L * 1024L,
                    recommendedUrgentWorkers = 3
                )
            ),
            profiles = orderedProfileResults
        )
    }

    private fun sampleProfileResult(
        parallelConnectionCount: Int,
        chunkSizeMb: Int,
        averageThroughputMbps: Double,
        status: DebridConfigBenchmarkStatus = DebridConfigBenchmarkStatus.SUCCESS,
        failureReason: String? = null,
        unsupportedReason: String? = null
    ): DebridConfigBenchmarkProfileResult {
        return DebridConfigBenchmarkProfileResult(
            profile = DebridConfigBenchmarkProfileMetadata(
                parallelConnectionCount = parallelConnectionCount,
                chunkSizeMb = chunkSizeMb
            ),
            status = status,
            averageThroughputMbps = averageThroughputMbps,
            failureReason = failureReason,
            unsupportedReason = unsupportedReason
        )
    }

    private fun newTempBenchmarkStoreFile(): File {
        return File.createTempFile("debrid_config_benchmark_store", ".preferences_pb").also { it.deleteOnExit() }
    }
}
