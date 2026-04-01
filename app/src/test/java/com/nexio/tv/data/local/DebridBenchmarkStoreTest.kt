package com.nexio.tv.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkProvider
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkResult
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkSummary
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkTerminationReason
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
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
        val expected = sampleResult(provider = DebridBenchmarkProvider.REAL_DEBRID, measuredAtMs = 7L)
        val firstStore = buildStore(storeScope, file)
        firstStore.saveLatest(expected)
        storeJob.cancelAndJoin()

        val reopenedStore = buildStore(backgroundScope, file)
        val restored = reopenedStore.latestResult(DebridBenchmarkProvider.REAL_DEBRID).first()

        assertEquals(expected, restored)
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
        val realDebrid = sampleResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            measuredAtMs = 10L,
            startupTimeMs = 100L,
            sustainedThroughputMbps = 200.0,
            transferredBytes = 300L,
            elapsedMs = 400L
        )
        val premiumize = sampleResult(
            provider = DebridBenchmarkProvider.PREMIUMIZE,
            measuredAtMs = 11L,
            startupTimeMs = 101L,
            sustainedThroughputMbps = 201.0,
            transferredBytes = 301L,
            elapsedMs = 401L
        )
        store.saveLatest(realDebrid)
        store.saveLatest(premiumize)

        assertEquals(realDebrid, store.latestResult(DebridBenchmarkProvider.REAL_DEBRID).first())
        assertEquals(premiumize, store.latestResult(DebridBenchmarkProvider.PREMIUMIZE).first())
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

    @Test
    fun `latest result ignores malformed payloads`() = runTest {
        val dataStore = buildDataStore(backgroundScope)
        val store = DebridBenchmarkStore(dataStore)
        val key = stringPreferencesKey("debrid_benchmark_latest_real_debrid")
        dataStore.edit { prefs ->
            prefs[key] = """{"provider":"REAL_DEBRID","measuredAtMs":5,"summary":{}}"""
        }

        assertEquals(null, store.latestResult(DebridBenchmarkProvider.REAL_DEBRID).first())
    }

    @Test
    fun `latest result ignores payloads with non object summary`() = runTest {
        val dataStore = buildDataStore(backgroundScope)
        val store = DebridBenchmarkStore(dataStore)
        val key = stringPreferencesKey("debrid_benchmark_latest_real_debrid")
        dataStore.edit { prefs ->
            prefs[key] = """{"provider":"REAL_DEBRID","measuredAtMs":5,"summary":1,"terminationReason":"COMPLETED"}"""
        }

        assertEquals(null, store.latestResult(DebridBenchmarkProvider.REAL_DEBRID).first())
    }

    @Test
    fun `latest result ignores payloads with invalid numeric fields`() = runTest {
        val dataStore = buildDataStore(backgroundScope)
        val store = DebridBenchmarkStore(dataStore)
        val key = stringPreferencesKey("debrid_benchmark_latest_real_debrid")
        dataStore.edit { prefs ->
            prefs[key] = """
                {
                  "provider":"REAL_DEBRID",
                  "measuredAtMs":5,
                  "summary":{
                    "startupTimeMs":-1,
                    "sustainedThroughputMbps":1e309,
                    "transferredBytes":-1,
                    "elapsedMs":-1
                  },
                  "terminationReason":"COMPLETED"
                }
            """.trimIndent()
        }

        assertEquals(null, store.latestResult(DebridBenchmarkProvider.REAL_DEBRID).first())
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `saving another provider does not re emit an unchanged latest result`() = runTest {
        val store = buildStore(backgroundScope)
        val realDebrid = sampleResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            measuredAtMs = 10L
        )
        store.saveLatest(realDebrid)

        val emissions = mutableListOf<DebridBenchmarkResult?>()
        val collectJob = backgroundScope.launch {
            store.latestResult(DebridBenchmarkProvider.REAL_DEBRID).collect { emissions += it }
        }

        runCurrent()
        assertEquals(listOf(realDebrid), emissions)

        store.saveLatest(
            sampleResult(
                provider = DebridBenchmarkProvider.PREMIUMIZE,
                measuredAtMs = 11L,
                startupTimeMs = 101L,
                sustainedThroughputMbps = 201.0,
                transferredBytes = 301L,
                elapsedMs = 401L
            )
        )
        runCurrent()
        assertEquals(listOf(realDebrid), emissions)

        collectJob.cancelAndJoin()
    }

    private fun buildDataStore(scope: CoroutineScope, file: File? = null) =
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = {
                val tempFile = file ?: File.createTempFile("debrid_benchmark_store", ".preferences_pb")
                tempFile.deleteOnExit()
                tempFile
            }
        )

    private fun buildStore(scope: CoroutineScope): DebridBenchmarkStore {
        return buildStore(scope, File.createTempFile("debrid_benchmark_store", ".preferences_pb"))
    }

    private fun buildStore(scope: CoroutineScope, file: File): DebridBenchmarkStore {
        return DebridBenchmarkStore(buildDataStore(scope, file))
    }

    private fun sampleResult(
        provider: DebridBenchmarkProvider,
        measuredAtMs: Long = 100L,
        startupTimeMs: Long = 123L,
        sustainedThroughputMbps: Double = 456.0,
        transferredBytes: Long = 789L,
        elapsedMs: Long = 1_000L
    ): DebridBenchmarkResult {
        return DebridBenchmarkResult(
            provider = provider,
            measuredAtMs = measuredAtMs,
            summary = DebridBenchmarkSummary(
                startupTimeMs = startupTimeMs,
                sustainedThroughputMbps = sustainedThroughputMbps,
                transferredBytes = transferredBytes,
                elapsedMs = elapsedMs
            ),
            terminationReason = DebridBenchmarkTerminationReason.COMPLETED
        )
    }
}
