package com.nexio.tv.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.nexio.tv.data.repository.DebridBenchmarkProvider
import com.nexio.tv.data.repository.DebridBenchmarkResult
import com.nexio.tv.data.repository.DebridBenchmarkSummary
import com.nexio.tv.data.repository.DebridBenchmarkTerminationReason
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DebridBenchmarkStoreTest {

    @Test
    fun `saving a completed provider result persists across store reopen`() = runTest {
        val file = File.createTempFile("debrid_benchmark_store", ".preferences_pb")
        file.deleteOnExit()
        val storeJob = SupervisorJob()
        val storeScope = CoroutineScope(backgroundScope.coroutineContext + storeJob)
        val firstStore = buildStore(storeScope, file)
        firstStore.saveLatest(sampleResult(provider = DebridBenchmarkProvider.REAL_DEBRID, measuredAtMs = 7L))
        storeJob.cancelAndJoin()

        val reopenedStore = buildStore(backgroundScope, file)

        assertEquals(7L, reopenedStore.latestResult(DebridBenchmarkProvider.REAL_DEBRID).first()?.measuredAtMs)
    }

    @Test
    fun `saving a completed provider result overwrites the previous result for that provider`() = runTest {
        val store = buildStore(backgroundScope)
        store.saveLatest(sampleResult(provider = DebridBenchmarkProvider.REAL_DEBRID, measuredAtMs = 1L))
        store.saveLatest(sampleResult(provider = DebridBenchmarkProvider.REAL_DEBRID, measuredAtMs = 2L))

        assertEquals(2L, store.latestResult(DebridBenchmarkProvider.REAL_DEBRID).first()?.measuredAtMs)
    }

    @Test
    fun `premiumize and real debrid latest results remain independent`() = runTest {
        val store = buildStore(backgroundScope)
        store.saveLatest(sampleResult(provider = DebridBenchmarkProvider.REAL_DEBRID))
        store.saveLatest(sampleResult(provider = DebridBenchmarkProvider.PREMIUMIZE))

        assertNotNull(store.latestResult(DebridBenchmarkProvider.REAL_DEBRID).first())
        assertNotNull(store.latestResult(DebridBenchmarkProvider.PREMIUMIZE).first())
    }

    @Test
    fun `clear removes only the requested provider result`() = runTest {
        val store = buildStore(backgroundScope)
        store.saveLatest(sampleResult(provider = DebridBenchmarkProvider.REAL_DEBRID))
        store.saveLatest(sampleResult(provider = DebridBenchmarkProvider.PREMIUMIZE))

        store.clear(DebridBenchmarkProvider.REAL_DEBRID)

        assertEquals(null, store.latestResult(DebridBenchmarkProvider.REAL_DEBRID).first())
        assertNotNull(store.latestResult(DebridBenchmarkProvider.PREMIUMIZE).first())
    }

    private fun buildStore(scope: CoroutineScope): DebridBenchmarkStore {
        val tempFile = File.createTempFile("debrid_benchmark_store", ".preferences_pb")
        tempFile.deleteOnExit()
        return buildStore(scope, tempFile)
    }

    private fun buildStore(scope: CoroutineScope, file: File): DebridBenchmarkStore {
        return DebridBenchmarkStore(
            dataStore = PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { file }
            )
        )
    }

    private fun sampleResult(
        provider: DebridBenchmarkProvider,
        measuredAtMs: Long = 100L
    ): DebridBenchmarkResult {
        return DebridBenchmarkResult(
            provider = provider,
            measuredAtMs = measuredAtMs,
            summary = DebridBenchmarkSummary(
                startupTimeMs = 123L,
                sustainedThroughputMbps = 456.0,
                transferredBytes = 789L,
                elapsedMs = 1_000L
            ),
            terminationReason = DebridBenchmarkTerminationReason.COMPLETED
        )
    }
}
