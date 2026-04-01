package com.nexio.tv.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
                    "transferredBytes":12,
                    "elapsedMs":34
                  },
                  "terminationReason":"COMPLETED"
                }
            """.trimIndent()
        }

        assertEquals(null, store.latestResult(DebridBenchmarkProvider.REAL_DEBRID).first())
    }

    @Test
    fun `latest result ignores payloads with non completed termination reason`() = runTest {
        val dataStore = buildDataStore(backgroundScope)
        val store = DebridBenchmarkStore(dataStore)
        val key = stringPreferencesKey("debrid_benchmark_latest_real_debrid")
        dataStore.edit { prefs ->
            prefs[key] = """
                {
                  "provider":"real_debrid",
                  "measuredAtMs":5,
                  "summary":{
                    "startupTimeMs":1,
                    "sustainedThroughputMbps":2.5,
                    "transferredBytes":12,
                    "elapsedMs":34
                  },
                  "terminationReason":"failed"
                }
            """.trimIndent()
        }

        assertEquals(null, store.latestResult(DebridBenchmarkProvider.REAL_DEBRID).first())
    }

    @Test
    fun `latest result ignores completed payloads missing startup or throughput metrics`() = runTest {
        val dataStore = buildDataStore(backgroundScope)
        val store = DebridBenchmarkStore(dataStore)
        val key = stringPreferencesKey("debrid_benchmark_latest_real_debrid")
        dataStore.edit { prefs ->
            prefs[key] = """
                {
                  "provider":"real_debrid",
                  "measuredAtMs":5,
                  "summary":{
                    "transferredBytes":12,
                    "elapsedMs":34
                  },
                  "terminationReason":"completed"
                }
            """.trimIndent()
        }

        assertEquals(null, store.latestResult(DebridBenchmarkProvider.REAL_DEBRID).first())
    }

    @Test
    fun `saving an invalid result does not replace a previously valid result`() = runTest {
        val store = buildStore(backgroundScope)
        val valid = sampleResult(provider = DebridBenchmarkProvider.REAL_DEBRID, measuredAtMs = 10L)
        store.saveLatest(valid)

        val invalid = DebridBenchmarkResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            measuredAtMs = 11L,
            summary = DebridBenchmarkSummary(
                startupTimeMs = -1L,
                sustainedThroughputMbps = Double.NaN,
                transferredBytes = 12L,
                elapsedMs = 34L
            ),
            terminationReason = DebridBenchmarkTerminationReason.COMPLETED
        )

        val failure = try {
            store.saveLatest(invalid)
            null
        } catch (t: Throwable) {
            t
        }

        assertTrue(failure is IllegalArgumentException)
        assertEquals(valid, store.latestResult(DebridBenchmarkProvider.REAL_DEBRID).first())
    }

    @Test
    fun `saving a non completed result does not replace a previously valid completed result`() = runTest {
        val store = buildStore(backgroundScope)
        val valid = sampleResult(provider = DebridBenchmarkProvider.REAL_DEBRID, measuredAtMs = 10L)
        store.saveLatest(valid)

        val invalid = DebridBenchmarkResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            measuredAtMs = 11L,
            summary = DebridBenchmarkSummary(
                startupTimeMs = 1L,
                sustainedThroughputMbps = 2.0,
                transferredBytes = 3L,
                elapsedMs = 4L
            ),
            terminationReason = DebridBenchmarkTerminationReason.FAILED
        )

        val failure = try {
            store.saveLatest(invalid)
            null
        } catch (t: Throwable) {
            t
        }

        assertTrue(failure is IllegalArgumentException)
        assertEquals(valid, store.latestResult(DebridBenchmarkProvider.REAL_DEBRID).first())
    }

    @Test
    fun `latest result rejects fractional numeric payloads`() = runTest {
        val dataStore = buildDataStore(backgroundScope)
        val store = DebridBenchmarkStore(dataStore)
        val key = stringPreferencesKey("debrid_benchmark_latest_real_debrid")
        dataStore.edit { prefs ->
            prefs[key] = """
                {
                  "provider":"REAL_DEBRID",
                  "measuredAtMs":5.9,
                  "summary":{
                    "startupTimeMs":1.5,
                    "sustainedThroughputMbps":2.5,
                    "transferredBytes":12.75,
                    "elapsedMs":34.25
                  },
                  "terminationReason":"COMPLETED"
                }
            """.trimIndent()
        }

        assertEquals(null, store.latestResult(DebridBenchmarkProvider.REAL_DEBRID).first())
    }

    @Test
    fun `saveLatest writes canonical benchmark payload keys`() = runTest {
        val recordingDataStore = recordingDataStore()
        val store = DebridBenchmarkStore(recordingDataStore)
        val expected = sampleResult(provider = DebridBenchmarkProvider.REAL_DEBRID, measuredAtMs = 42L)

        store.saveLatest(expected)

        val key = stringPreferencesKey("debrid_benchmark_latest_real_debrid")
        val raw = recordingDataStore.snapshot.value[key]

        assertEquals(
            """{"provider":"real_debrid","measuredAtMs":42,"summary":{"startupTimeMs":123,"sustainedThroughputMbps":456.0,"transferredBytes":789,"elapsedMs":1000},"terminationReason":"completed"}""",
            raw
        )
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

    @Test
    fun `corrupt preferences file is recovered and still accepts later saves`() = runTest {
        val file = newTempBenchmarkStoreFile()
        file.writeBytes(byteArrayOf(0x01, 0x02, 0x03))
        val store = buildStore(backgroundScope, file, replaceCorruptionHandler())

        assertEquals(null, store.latestResult(DebridBenchmarkProvider.REAL_DEBRID).first())

        val expected = sampleResult(provider = DebridBenchmarkProvider.REAL_DEBRID, measuredAtMs = 12L)
        store.saveLatest(expected)

        assertEquals(expected, store.latestResult(DebridBenchmarkProvider.REAL_DEBRID).first())
    }

    private fun buildDataStore(
        scope: CoroutineScope,
        file: File? = null,
        corruptionHandler: ReplaceFileCorruptionHandler<androidx.datastore.preferences.core.Preferences> = replaceCorruptionHandler()
    ) =
        PreferenceDataStoreFactory.create(
            corruptionHandler = corruptionHandler,
            scope = scope,
            produceFile = {
                (file ?: newTempBenchmarkStoreFile()).also { it.deleteOnExit() }
            }
        )

    private fun buildStore(scope: CoroutineScope): DebridBenchmarkStore {
        return buildStore(scope, newTempBenchmarkStoreFile())
    }

    private fun buildStore(scope: CoroutineScope, file: File): DebridBenchmarkStore {
        return buildStore(scope, file, replaceCorruptionHandler())
    }

    private fun buildStore(
        scope: CoroutineScope,
        file: File,
        corruptionHandler: ReplaceFileCorruptionHandler<androidx.datastore.preferences.core.Preferences>
    ): DebridBenchmarkStore {
        return DebridBenchmarkStore(buildDataStore(scope, file, corruptionHandler))
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

    private fun newTempBenchmarkStoreFile(): File {
        return File.createTempFile("debrid_benchmark_store", ".preferences_pb").also { it.deleteOnExit() }
    }

    private fun replaceCorruptionHandler() =
        ReplaceFileCorruptionHandler<androidx.datastore.preferences.core.Preferences> {
            emptyPreferences()
        }

    private fun recordingDataStore(): RecordingDataStore {
        return RecordingDataStore()
    }

    private class RecordingDataStore : androidx.datastore.core.DataStore<Preferences> {
        val snapshot = MutableStateFlow(emptyPreferences())

        override val data = snapshot

        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences
        ): Preferences {
            val updated = transform(snapshot.value)
            snapshot.value = updated
            return updated
        }
    }
}
