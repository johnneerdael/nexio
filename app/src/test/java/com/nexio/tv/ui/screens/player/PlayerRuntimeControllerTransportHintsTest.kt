package com.nexio.tv.ui.screens.player

import com.nexio.tv.data.repository.benchmark.CapabilityEnvelope
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkProvider
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkProfileMetadata
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkProfileResult
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkResult
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkSessionSummary
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkStatus
import com.nexio.tv.data.repository.benchmark.RuntimeTransportHintsV2
import com.nexio.tv.data.repository.benchmark.benchmarkProviderForServiceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerRuntimeControllerTransportHintsTest {

    @Test
    fun `service key maps to matching benchmark provider`() {
        assertEquals(DebridBenchmarkProvider.REAL_DEBRID, benchmarkProviderForServiceKey("rd"))
        assertEquals(DebridBenchmarkProvider.PREMIUMIZE, benchmarkProviderForServiceKey(" PM "))
        assertEquals(DebridBenchmarkProvider.TORBOX, benchmarkProviderForServiceKey("Tb"))
        assertEquals(DebridBenchmarkProvider.EASY_DEBRID, benchmarkProviderForServiceKey("ed"))
        assertNull(benchmarkProviderForServiceKey("https"))
        assertNull(benchmarkProviderForServiceKey(null))
    }

    @Test
    fun `matching service key selects same-provider capability envelope only`() {
        val realDebrid = configResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            measuredAtMs = 10L,
            urgentWorkers = 2,
            urgentChunkMb = 16
        )
        val premiumize = configResult(
            provider = DebridBenchmarkProvider.PREMIUMIZE,
            measuredAtMs = 11L,
            urgentWorkers = 3,
            urgentChunkMb = 8
        )

        val selected = selectCapabilityEnvelopeForServiceKey(
            serviceKey = "RD",
            latestResults = mapOf(
                DebridBenchmarkProvider.PREMIUMIZE to premiumize,
                DebridBenchmarkProvider.REAL_DEBRID to realDebrid
            ),
            nowMs = 10L
        )

        requireNotNull(selected)
        assertEquals(2, selected.maxSafeUrgentWorkers)
        assertEquals(16L * 1024L * 1024L, selected.maxSafeUrgentChunkBytes)
        assertEquals(10L, selected.measuredAtMs)
    }

    @Test
    fun `matching service key also selects runtime transport hints from same provider`() {
        val realDebrid = configResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            measuredAtMs = 10L,
            urgentWorkers = 2,
            urgentChunkMb = 16
        )
        val premiumize = configResult(
            provider = DebridBenchmarkProvider.PREMIUMIZE,
            measuredAtMs = 11L,
            urgentWorkers = 3,
            urgentChunkMb = 8
        )

        val selected = selectTransportBenchmarkForServiceKey(
            serviceKey = "RD",
            latestResults = mapOf(
                DebridBenchmarkProvider.PREMIUMIZE to premiumize,
                DebridBenchmarkProvider.REAL_DEBRID to realDebrid
            ),
            nowMs = 10L
        )

        assertEquals("RD", selected?.runtimeTransportHints?.serviceKey)
        assertEquals(10L, selected?.runtimeTransportHints?.measuredAtMs)
        assertEquals("host:file", selected?.runtimeTransportHints?.observedHostScope)
    }

    @Test
    fun `wrong-provider result does not cross-fill capability envelope`() {
        val premiumize = configResult(
            provider = DebridBenchmarkProvider.PREMIUMIZE,
            measuredAtMs = 11L,
            urgentWorkers = 3,
            urgentChunkMb = 8
        )

        val selected = selectCapabilityEnvelopeForServiceKey(
            serviceKey = "RD",
            latestResults = mapOf(DebridBenchmarkProvider.PREMIUMIZE to premiumize),
            nowMs = 11L
        )

        assertNull(selected)
    }

    @Test
    fun `unknown service key does not select capability envelope`() {
        val realDebrid = configResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            measuredAtMs = 10L,
            urgentWorkers = 2,
            urgentChunkMb = 16
        )

        val selected = selectCapabilityEnvelopeForServiceKey(
            serviceKey = "http",
            latestResults = mapOf(DebridBenchmarkProvider.REAL_DEBRID to realDebrid),
            nowMs = 10L
        )

        assertNull(selected)
    }

    @Test
    fun `stale v2 result does not specialize playback selection`() {
        val staleRealDebrid = configResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            measuredAtMs = 0L,
            urgentWorkers = 2,
            urgentChunkMb = 16
        )

        val selected = selectTransportBenchmarkForServiceKey(
            serviceKey = "RD",
            latestResults = mapOf(DebridBenchmarkProvider.REAL_DEBRID to staleRealDebrid),
            nowMs = 8L * 24L * 60L * 60L * 1000L
        )

        assertEquals(2, selected?.capabilityEnvelope?.maxSafeUrgentWorkers)
        assertNull(selected?.runtimeTransportHints)
    }

    @Test
    fun `legacy artifact version does not specialize playback selection`() {
        val legacyRealDebrid = configResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            measuredAtMs = 10L,
            urgentWorkers = 2,
            urgentChunkMb = 16,
            artifactVersion = 1
        )

        val selected = selectTransportBenchmarkForServiceKey(
            serviceKey = "RD",
            latestResults = mapOf(DebridBenchmarkProvider.REAL_DEBRID to legacyRealDebrid),
            nowMs = 10L
        )

        assertEquals(2, selected?.capabilityEnvelope?.maxSafeUrgentWorkers)
        assertNull(selected?.runtimeTransportHints)
    }

    private fun configResult(
        provider: DebridBenchmarkProvider,
        measuredAtMs: Long,
        urgentWorkers: Int,
        urgentChunkMb: Int,
        artifactVersion: Int = 2
    ): DebridConfigBenchmarkResult {
        val bestProfile = DebridConfigBenchmarkProfileResult(
            profile = DebridConfigBenchmarkProfileMetadata(
                parallelConnectionCount = urgentWorkers,
                chunkSizeMb = urgentChunkMb
            ),
            status = DebridConfigBenchmarkStatus.SUCCESS,
            averageThroughputMbps = 250.0
        )
        return DebridConfigBenchmarkResult(
            provider = provider,
            measuredAtMs = measuredAtMs,
            summary = DebridConfigBenchmarkSessionSummary(
                totalProfileCount = 1,
                successfulProfileCount = 1,
                failedProfileCount = 0,
                unsupportedProfileCount = 0,
                bestProfile = bestProfile,
                capabilityEnvelope = CapabilityEnvelope(
                    maxSafeUrgentWorkers = urgentWorkers,
                    maxSafePrefetchWorkers = 1,
                    maxSafeUrgentChunkBytes = urgentChunkMb.toLong() * 1024L * 1024L,
                    maxSafePrefetchChunkBytes = (urgentChunkMb * 2L) * 1024L * 1024L,
                    sustainedThroughputMbps = 250.0,
                    measuredAtMs = measuredAtMs
                ),
                runtimeTransportHints = RuntimeTransportHintsV2(
                    artifactVersion = artifactVersion,
                    serviceKey = when (provider) {
                        DebridBenchmarkProvider.REAL_DEBRID -> "RD"
                        DebridBenchmarkProvider.PREMIUMIZE -> "PM"
                        DebridBenchmarkProvider.TORBOX -> "TB"
                        DebridBenchmarkProvider.EASY_DEBRID -> "ED"
                    },
                    measuredAtMs = measuredAtMs,
                    observedHostScope = "host:file",
                    recommendedUrgentChunkBytes = urgentChunkMb.toLong() * 1024L * 1024L,
                    recommendedUrgentWorkers = urgentWorkers
                )
            ),
            profiles = listOf(bestProfile)
        )
    }
}
