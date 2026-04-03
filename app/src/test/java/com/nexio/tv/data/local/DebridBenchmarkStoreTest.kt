package com.nexio.tv.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkProvider
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkCandidateMetadata
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkComparisonSummary
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkPhase
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkPhaseExecution
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkResult
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkRawSamples
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkSeekMetrics
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkSeekSample
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkSessionMetadata
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkSummary
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkTransportDecisionMetrics
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkStartupMetrics
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkSustainedMetrics
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkTerminationReason
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkThroughputBucketSample
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkTransportConfigSnapshot
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkTransportMode
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkTransportProfile
import com.nexio.tv.data.repository.benchmark.AudioEncodingSupport
import com.nexio.tv.data.repository.benchmark.AudioDirectProfileEvidence
import com.nexio.tv.data.repository.benchmark.AudioOutputDeviceEvidence
import com.nexio.tv.data.repository.benchmark.CodecSupport
import com.nexio.tv.data.repository.benchmark.DeviceAudioCapabilityEvidence
import com.nexio.tv.data.repository.benchmark.DeviceCapabilityEvidence
import com.nexio.tv.data.repository.benchmark.DeviceAudioOutputCapabilities
import com.nexio.tv.data.repository.benchmark.DeviceCapabilitySnapshot
import com.nexio.tv.data.repository.benchmark.DeviceHdrCapabilityEvidence
import com.nexio.tv.data.repository.benchmark.DeviceHdrType
import com.nexio.tv.data.repository.benchmark.DeviceVideoDecoderEvidence
import com.nexio.tv.data.repository.benchmark.DeviceVideoDecodeCapabilities
import com.nexio.tv.data.repository.benchmark.VideoDecoderEvidence
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
    fun `saving a completed comparison result persists both transport profiles across store reopen`() = runTest {
        val file = File.createTempFile("debrid_benchmark_store_comparison", ".preferences_pb")
        file.deleteOnExit()
        val storeJob = SupervisorJob()
        val storeScope = CoroutineScope(backgroundScope.coroutineContext + storeJob)
        val expected = sampleComparisonResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            measuredAtMs = 17L
        )
        val firstStore = buildStore(storeScope, file)

        firstStore.saveLatest(expected)
        storeJob.cancelAndJoin()

        val reopenedStore = buildStore(backgroundScope, file)
        val restored = reopenedStore.latestResult(DebridBenchmarkProvider.REAL_DEBRID).first()

        assertEquals(expected, restored)
    }

    @Test
    fun `saving a completed comparison result persists embedded device capabilities`() = runTest {
        val file = File.createTempFile("debrid_benchmark_store_device", ".preferences_pb")
        file.deleteOnExit()
        val storeJob = SupervisorJob()
        val storeScope = CoroutineScope(backgroundScope.coroutineContext + storeJob)
        val expected = sampleComparisonResult(
            provider = DebridBenchmarkProvider.PREMIUMIZE,
            measuredAtMs = 27L
        ).copy(device = sampleDeviceSnapshot())
        val firstStore = buildStore(storeScope, file)

        firstStore.saveLatest(expected)
        storeJob.cancelAndJoin()

        val reopenedStore = buildStore(backgroundScope, file)
        val restored = reopenedStore.latestResult(DebridBenchmarkProvider.PREMIUMIZE).first()

        assertEquals(expected, restored)
    }

    @Test
    fun `saving a completed comparison result persists derived decision metrics across store reopen`() = runTest {
        val file = File.createTempFile("debrid_benchmark_store_decision", ".preferences_pb")
        file.deleteOnExit()
        val storeJob = SupervisorJob()
        val storeScope = CoroutineScope(backgroundScope.coroutineContext + storeJob)
        val expected = sampleComparisonResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            measuredAtMs = 37L
        ).copy(
            direct = sampleTransportProfile(
                startupTimeMs = 180L,
                averageMbps = 170.0,
                p10Mbps = 150.0,
                peakMbps = 190.0,
                seekP95Ms = 330L,
                seekP99Ms = 420L,
                decisionSafeBudgetMbps = 127.5,
                decisionActionable = true
            ),
            optimized = sampleTransportProfile(
                startupTimeMs = 140L,
                averageMbps = 220.0,
                p10Mbps = 200.0,
                peakMbps = 240.0,
                seekP95Ms = 240L,
                seekP99Ms = 320L,
                decisionSafeBudgetMbps = 170.0,
                decisionActionable = false,
                configSnapshot = DebridBenchmarkTransportConfigSnapshot(
                    useParallelConnections = true,
                    parallelConnectionCount = 4,
                    parallelChunkSizeMb = 8
                )
            )
        )
        val firstStore = buildStore(storeScope, file)

        firstStore.saveLatest(expected)
        storeJob.cancelAndJoin()

        val reopenedStore = buildStore(backgroundScope, file)
        val restored = reopenedStore.latestResult(DebridBenchmarkProvider.REAL_DEBRID).first()

        assertEquals(expected, restored)
        assertEquals(127.5, restored?.direct?.decision?.safeSustainedBudgetMbps ?: 0.0, 0.0)
        assertEquals(false, restored?.optimized?.decision?.actionable)
    }

    @Test
    fun `saving a completed fallback result allows non actionable optimized profile with incomplete seek metrics`() = runTest {
        val store = buildStore(backgroundScope)
        val result = sampleComparisonResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            measuredAtMs = 47L
        ).copy(
            summary = DebridBenchmarkSummary(
                startupTimeMs = 180L,
                sustainedThroughputMbps = 220.0,
                transferredBytes = 4_000_000_000L,
                elapsedMs = 120_000L
            ),
            direct = sampleTransportProfile(
                startupTimeMs = 180L,
                averageMbps = 220.0,
                p10Mbps = 180.0,
                peakMbps = 240.0,
                seekP95Ms = 240L,
                seekP99Ms = 320L,
                decisionSafeBudgetMbps = 153.0,
                decisionActionable = true
            ),
            optimized = DebridBenchmarkTransportProfile(
                startup = DebridBenchmarkStartupMetrics(
                    initialTtfbMs = 320L,
                    startupFailureRate = 0.0
                ),
                sustained = DebridBenchmarkSustainedMetrics(
                    collectorVersion = 2,
                    samplingMode = "fixed_time_bucket",
                    bucketMs = 1_000L,
                    averageThroughputMbps = 260.0,
                    derivedAverageThroughputMbps = 260.0,
                    actionable = false,
                    recoverableFailureCount = 3,
                    recoverableTimeoutCount = 0,
                    p10ThroughputMbps = 210.0,
                    p50ThroughputMbps = 260.0,
                    peakThroughputMbps = 280.0,
                    throughputStddevMbps = 8.0,
                    throughputCv = 0.05,
                    stallCount = 0,
                    maxReadGapMs = 140L,
                    bytesTransferred = 2_000_000_000L,
                    elapsedMs = 60_000L
                ),
                seek = DebridBenchmarkSeekMetrics(),
                decision = DebridBenchmarkTransportDecisionMetrics(
                    safeSustainedBudgetMbps = null,
                    actionable = false
                ),
                configSnapshot = DebridBenchmarkTransportConfigSnapshot(
                    useParallelConnections = true,
                    parallelConnectionCount = 4,
                    parallelChunkSizeMb = 8
                ),
                rawSamples = DebridBenchmarkRawSamples()
            ),
            comparison = DebridBenchmarkComparisonSummary(
                sustainedWinner = DebridBenchmarkTransportMode.DIRECT,
                seekWinner = DebridBenchmarkTransportMode.DIRECT,
                stabilityWinner = DebridBenchmarkTransportMode.DIRECT
            )
        )

        store.saveLatest(result)

        val restored = store.latestResult(DebridBenchmarkProvider.REAL_DEBRID).first()

        assertEquals(result, restored)
        assertEquals(false, restored?.optimized?.decision?.actionable)
        assertEquals(null, restored?.optimized?.seek?.seekTtfbP95Ms)
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
    fun `latest result best effort restores legacy payloads without evidence`() = runTest {
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
                  "terminationReason":"completed",
                  "device":{
                    "model":"Legacy Box",
                    "manufacturer":"Example",
                    "sdkInt":34,
                    "displayHdrTypes":["hdr10"],
                    "videoDecode":{
                      "h264":{"hardwareAccelerated":true,"softwareOnlyAvailable":false,"secureSupported":true}
                    },
                    "audioOutput":{
                      "ac3":{"supported":true,"passthroughLikely":true},
                      "eac3":{"supported":true,"passthroughLikely":true},
                      "truehd":{"supported":false,"passthroughLikely":false},
                      "dts":{"supported":false,"passthroughLikely":false},
                      "dtshd":{"supported":false,"passthroughLikely":false}
                    },
                    "capturedAtMs":99
                  }
                }
            """.trimIndent()
        }

        val restored = store.latestResult(DebridBenchmarkProvider.REAL_DEBRID).first()
        assertNotNull(restored)
        assertEquals(null, restored?.device?.evidence)
        assertEquals("Legacy Box", restored?.device?.model)
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
    fun `invalid stored payload does not erase a later valid overwrite`() = runTest {
        val dataStore = buildDataStore(backgroundScope)
        val store = DebridBenchmarkStore(dataStore)
        val key = stringPreferencesKey("debrid_benchmark_latest_real_debrid")
        dataStore.edit { prefs ->
            prefs[key] = """{"provider":"real_debrid","measuredAtMs":"oops"}"""
        }

        assertEquals(null, store.latestResult(DebridBenchmarkProvider.REAL_DEBRID).first())

        val expected = sampleResult(provider = DebridBenchmarkProvider.REAL_DEBRID, measuredAtMs = 88L)
        store.saveLatest(expected)

        assertEquals(expected, store.latestResult(DebridBenchmarkProvider.REAL_DEBRID).first())
    }

    @Test
    fun `saving an incomplete comparison result does not replace a previously valid session`() = runTest {
        val store = buildStore(backgroundScope)
        val valid = sampleComparisonResult(
            provider = DebridBenchmarkProvider.PREMIUMIZE,
            measuredAtMs = 21L
        )
        store.saveLatest(valid)

        val invalid = valid.copy(
            optimized = valid.optimized?.copy(
                seek = valid.optimized.seek.copy(seekTtfbP95Ms = null)
            )
        )

        val failure = try {
            store.saveLatest(invalid)
            null
        } catch (t: Throwable) {
            t
        }

        assertTrue(failure is IllegalArgumentException)
        assertEquals(valid, store.latestResult(DebridBenchmarkProvider.PREMIUMIZE).first())
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
    ): androidx.datastore.core.DataStore<Preferences> {
        val dataStoreFile = (file ?: newTempBenchmarkStoreFile()).also { it.deleteOnExit() }
        return PreferenceDataStoreFactory.create(
            corruptionHandler = corruptionHandler,
            scope = scope,
            produceFile = { dataStoreFile }
        )
    }

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

    private fun sampleComparisonResult(
        provider: DebridBenchmarkProvider,
        measuredAtMs: Long = 100L
    ): DebridBenchmarkResult {
        val direct = sampleTransportProfile(
            startupTimeMs = 110L,
            averageMbps = 180.0,
            p10Mbps = 150.0,
            peakMbps = 220.0,
            seekP95Ms = 330L,
            seekP99Ms = 440L
        )
        val optimized = sampleTransportProfile(
            startupTimeMs = 85L,
            averageMbps = 260.0,
            p10Mbps = 210.0,
            peakMbps = 310.0,
            seekP95Ms = 200L,
            seekP99Ms = 260L,
            configSnapshot = DebridBenchmarkTransportConfigSnapshot(
                useParallelConnections = true,
                parallelConnectionCount = 4,
                parallelChunkSizeMb = 8
            )
        )
        return DebridBenchmarkResult(
            provider = provider,
            measuredAtMs = measuredAtMs,
            summary = DebridBenchmarkSummary(
                startupTimeMs = optimized.startup.initialTtfbMs,
                sustainedThroughputMbps = optimized.sustained.averageThroughputMbps,
                transferredBytes = requireNotNull(optimized.sustained.bytesTransferred),
                elapsedMs = requireNotNull(optimized.sustained.elapsedMs)
            ),
            terminationReason = DebridBenchmarkTerminationReason.COMPLETED,
            candidate = DebridBenchmarkCandidateMetadata(
                filename = "Example.mkv",
                sizeBytes = 20L * 1024L * 1024L * 1024L,
                host = "cdn.example.com",
                directUrlFingerprint = "abc123"
            ),
            device = sampleDeviceSnapshot(),
            session = DebridBenchmarkSessionMetadata(
                benchmarkVersion = 3,
                executionOrder = listOf(
                    DebridBenchmarkPhaseExecution(
                        phase = DebridBenchmarkPhase.STARTUP,
                        order = listOf(
                            DebridBenchmarkTransportMode.DIRECT,
                            DebridBenchmarkTransportMode.OPTIMIZED
                        )
                    )
                ),
                totalElapsedMs = 185_000L
            ),
            direct = direct,
            optimized = optimized,
            comparison = DebridBenchmarkComparisonSummary(
                sustainedWinner = DebridBenchmarkTransportMode.OPTIMIZED,
                seekWinner = DebridBenchmarkTransportMode.OPTIMIZED,
                stabilityWinner = DebridBenchmarkTransportMode.OPTIMIZED
            )
        )
    }

    private fun sampleDeviceSnapshot(): DeviceCapabilitySnapshot {
        return DeviceCapabilitySnapshot(
            model = "Shield",
            manufacturer = "NVIDIA",
            sdkInt = 35,
            displayHdrTypes = setOf(DeviceHdrType.DOLBY_VISION, DeviceHdrType.HDR10),
            videoDecode = DeviceVideoDecodeCapabilities(
                h264 = CodecSupport(
                    hardwareAccelerated = true,
                    softwareOnlyAvailable = false,
                    secureSupported = true
                ),
                hevc = CodecSupport(
                    hardwareAccelerated = true,
                    softwareOnlyAvailable = false,
                    secureSupported = true
                ),
                av1 = CodecSupport(
                    hardwareAccelerated = false,
                    softwareOnlyAvailable = true,
                    secureSupported = false
                ),
                dolbyVision = CodecSupport(
                    hardwareAccelerated = true,
                    softwareOnlyAvailable = false,
                    secureSupported = true
                )
            ),
            audioOutput = DeviceAudioOutputCapabilities(
                ac3 = AudioEncodingSupport(supported = true, passthroughLikely = true),
                eac3 = AudioEncodingSupport(supported = true, passthroughLikely = true),
                truehd = AudioEncodingSupport(supported = true, passthroughLikely = true),
                dts = AudioEncodingSupport(supported = true, passthroughLikely = true),
                dtshd = AudioEncodingSupport(supported = false, passthroughLikely = false)
            ),
            evidence = DeviceCapabilityEvidence(
                hdr = DeviceHdrCapabilityEvidence(
                    displayId = 0,
                    rawSupportedHdrTypes = listOf("dolby_vision", "hdr10")
                ),
                audio = DeviceAudioCapabilityEvidence(
                    discoveryMode = "direct_profiles",
                    routedDeviceTypes = listOf("hdmi_earc"),
                    outputDevices = listOf(
                        AudioOutputDeviceEvidence(
                            id = 3,
                            type = "hdmi_earc",
                            productName = "AVR",
                            encodings = listOf("ac3", "eac3", "truehd")
                        )
                    ),
                    directProfiles = listOf(
                        AudioDirectProfileEvidence(
                            format = "truehd",
                            channelMasks = listOf(12),
                            sampleRates = listOf(48000)
                        )
                    )
                ),
                video = DeviceVideoDecoderEvidence(
                    scannedDecoderCount = 4,
                    decoders = listOf(
                        VideoDecoderEvidence(
                            codecName = "c2.qti.hevc.decoder",
                            mimeType = "video/hevc",
                            hardwareAccelerated = true,
                            softwareOnly = false,
                            secureSupported = true
                        )
                    )
                )
            ),
            capturedAtMs = 123_456L
        )
    }

    private fun sampleTransportProfile(
        startupTimeMs: Long,
        averageMbps: Double,
        p10Mbps: Double,
        peakMbps: Double,
        seekP95Ms: Long,
        seekP99Ms: Long,
        decisionSafeBudgetMbps: Double = p10Mbps * 0.85,
        decisionActionable: Boolean = true,
        configSnapshot: DebridBenchmarkTransportConfigSnapshot? = null
    ): DebridBenchmarkTransportProfile {
        return DebridBenchmarkTransportProfile(
            startup = DebridBenchmarkStartupMetrics(
                initialTtfbMs = startupTimeMs,
                startupFailureRate = 0.0
            ),
            sustained = DebridBenchmarkSustainedMetrics(
                collectorVersion = 2,
                samplingMode = "fixed_time_bucket",
                bucketMs = 1_000L,
                averageThroughputMbps = averageMbps,
                derivedAverageThroughputMbps = averageMbps,
                actionable = true,
                p10ThroughputMbps = p10Mbps,
                p50ThroughputMbps = averageMbps,
                peakThroughputMbps = peakMbps,
                throughputStddevMbps = 12.0,
                throughputCv = 0.08,
                stallCount = 0,
                maxReadGapMs = 40L,
                bytesTransferred = 900L * 1024L * 1024L,
                elapsedMs = 180_000L
            ),
            seek = DebridBenchmarkSeekMetrics(
                seekTtfbP50Ms = 140L,
                seekTtfbP95Ms = seekP95Ms,
                seekTtfbP99Ms = seekP99Ms,
                seekTtfbStddevMs = 25.0,
                seekFailRate = 0.0
            ),
            decision = DebridBenchmarkTransportDecisionMetrics(
                safeSustainedBudgetMbps = decisionSafeBudgetMbps,
                actionable = decisionActionable
            ),
            configSnapshot = configSnapshot,
            rawSamples = DebridBenchmarkRawSamples(
                throughputWindowsMbps = listOf(averageMbps, p10Mbps, peakMbps),
                throughputBuckets = listOf(
                    DebridBenchmarkThroughputBucketSample(
                        startOffsetMs = 0L,
                        durationMs = 1_000L,
                        bytesTransferred = ((averageMbps * 1_000_000.0 / 8.0) / 1_000.0).toLong(),
                        throughputMbps = averageMbps,
                        complete = true
                    )
                ),
                seekSamples = listOf(
                    DebridBenchmarkSeekSample(
                        targetOffsetBytes = 10_000_000L,
                        ttfbMs = 140L,
                        succeeded = true
                    )
                )
            )
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
