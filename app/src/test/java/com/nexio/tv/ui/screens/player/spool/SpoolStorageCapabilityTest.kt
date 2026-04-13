package com.nexio.tv.ui.screens.player.spool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class SpoolStorageCapabilityTest {
    @Test
    fun storageProbe_stopsBeforeRunningWhenCancelled() {
        val probe = SpoolStorageCapabilityProbe(
            directory = java.io.File(System.getProperty("java.io.tmpdir"), "spool-probe-cancelled"),
            shouldContinue = { false }
        )

        try {
            probe.run(durationMs = 60_000L, blockBytes = 4 * 1024)
        } catch (_: CancellationException) {
            return
        }

        throw AssertionError("Expected cancelled storage probe to throw CancellationException")
    }

    @Test
    fun targetBitrate_prefersStreamThenUserCapWithUnknownStreamFallbackFloor() {
        assertEquals(63.0, SpoolStoragePolicy.targetBitrateMbps(63.0, 40.0), 0.01)
        assertEquals(80.0, SpoolStoragePolicy.targetBitrateMbps(0.0, 40.0), 0.01)
        assertEquals(80.0, SpoolStoragePolicy.targetBitrateMbps(Double.NaN, 40.0), 0.01)
        assertEquals(80.0, SpoolStoragePolicy.targetBitrateMbps(null, -1.0), 0.01)
        assertEquals(80.0, SpoolStoragePolicy.targetBitrateMbps(null, 40.0), 0.01)
        assertEquals(120.0, SpoolStoragePolicy.targetBitrateMbps(null, 120.0), 0.01)
        assertEquals(80.0, SpoolStoragePolicy.targetBitrateMbps(null, null), 0.01)
    }

    @Test
    fun canSustain_respectsThroughputAndLatencyThresholds() {
        val pass = SpoolStorageProbeResult(
            writeMbps = 180.0,
            readMbps = 180.0,
            combinedMbps = 360.0,
            p99ReadLatencyMs = 40L,
            maxReadStallMs = 70L,
            measuredAtMs = 1_776_047_817_725L,
            durationMs = 60_000L,
            bytesWritten = 1_350_000_000L,
            bytesRead = 1_350_000_000L,
            spoolDirectoryPath = "/data/user/0/com.nexio.tv/cache/player_disk_spool"
        )

        assertTrue(SpoolStoragePolicy.canSustain(pass, 80.0))

        val insufficientThroughput = pass.copy(
            writeMbps = 82.0,
            readMbps = 82.0,
            combinedMbps = 164.0
        )
        assertFalse(SpoolStoragePolicy.canSustain(insufficientThroughput, 80.0))

        val excessiveLatency = pass.copy(
            p99ReadLatencyMs = 250L,
            maxReadStallMs = 900L
        )
        assertFalse(SpoolStoragePolicy.canSustain(excessiveLatency, 80.0))

        assertFalse(SpoolStoragePolicy.canSustain(pass, 0.0))

        val tooShort = pass.copy(durationMs = 29_999L)
        assertFalse(SpoolStoragePolicy.canSustain(tooShort, 80.0))
    }

    @Test
    fun isFresh_requiresRecentMeasurementAndMatchingDirectory() {
        val result = SpoolStorageProbeResult(
            writeMbps = 180.0,
            readMbps = 180.0,
            combinedMbps = 360.0,
            p99ReadLatencyMs = 40L,
            maxReadStallMs = 70L,
            measuredAtMs = 1_776_047_817_725L,
            durationMs = 60_000L,
            bytesWritten = 1_350_000_000L,
            bytesRead = 1_350_000_000L,
            spoolDirectoryPath = "/data/user/0/com.nexio.tv/cache/player_disk_spool"
        )

        assertTrue(
            SpoolStoragePolicy.isFresh(
                result,
                nowMs = result.measuredAtMs + 1_000L,
                spoolDirectoryPath = result.spoolDirectoryPath
            )
        )
        assertFalse(
            SpoolStoragePolicy.isFresh(
                result,
                nowMs = result.measuredAtMs + 8L * 24L * 60L * 60L * 1000L,
                spoolDirectoryPath = result.spoolDirectoryPath
            )
        )
        assertFalse(
            SpoolStoragePolicy.isFresh(
                result,
                nowMs = result.measuredAtMs + 1_000L,
                spoolDirectoryPath = "/data/user/0/com.nexio.tv/cache/other"
            )
        )
    }

    @Test
    fun jsonRoundTrip_preservesProbeResult() {
        val result = SpoolStorageProbeResult(
            writeMbps = 180.0,
            readMbps = 180.0,
            combinedMbps = 360.0,
            p99ReadLatencyMs = 40L,
            maxReadStallMs = 70L,
            measuredAtMs = 1_776_047_817_725L,
            durationMs = 60_000L,
            bytesWritten = 1_350_000_000L,
            bytesRead = 1_350_000_000L,
            spoolDirectoryPath = "/data/user/0/com.nexio.tv/cache/player_disk_spool"
        )

        val restored = SpoolStorageProbeResult.fromJson(result.toJson())

        assertEquals(result, restored)
    }

    @Test
    fun malformedJson_returnsNull() {
        assertEquals(null, SpoolStorageProbeResult.fromJsonOrNull("{not json}"))
    }

    @Test
    fun missingRequiredField_returnsNull() {
        val raw = """
            {
              "writeMbps": 180.0,
              "readMbps": 180.0,
              "combinedMbps": 360.0,
              "p99ReadLatencyMs": 40,
              "maxReadStallMs": 70,
              "measuredAtMs": 1776047817725,
              "durationMs": 60000,
              "bytesWritten": 1350000000,
              "bytesRead": 1350000000
            }
        """.trimIndent()

        assertEquals(null, SpoolStorageProbeResult.fromJsonOrNull(raw))
    }

    @Test
    fun nonFiniteSerializedRate_returnsNull() {
        val raw = """
            {
              "writeMbps": NaN,
              "readMbps": 180.0,
              "combinedMbps": 360.0,
              "p99ReadLatencyMs": 40,
              "maxReadStallMs": 70,
              "measuredAtMs": 1776047817725,
              "durationMs": 60000,
              "bytesWritten": 1350000000,
              "bytesRead": 1350000000,
              "spoolDirectoryPath": "/data/user/0/com.nexio.tv/cache/player_disk_spool"
            }
        """.trimIndent()

        assertEquals(null, SpoolStorageProbeResult.fromJsonOrNull(raw))
    }

    @Test
    fun nonIntegralMilliseconds_returnsNull() {
        val measuredAtRaw = """
            {
              "writeMbps": 180.0,
              "readMbps": 180.0,
              "combinedMbps": 360.0,
              "p99ReadLatencyMs": 40,
              "maxReadStallMs": 70,
              "measuredAtMs": 1776047817725.9,
              "durationMs": 60000,
              "bytesWritten": 1350000000,
              "bytesRead": 1350000000,
              "spoolDirectoryPath": "/data/user/0/com.nexio.tv/cache/player_disk_spool"
            }
        """.trimIndent()
        val durationRaw = """
            {
              "writeMbps": 180.0,
              "readMbps": 180.0,
              "combinedMbps": 360.0,
              "p99ReadLatencyMs": 40,
              "maxReadStallMs": 70,
              "measuredAtMs": 1776047817725,
              "durationMs": "60000.5",
              "bytesWritten": 1350000000,
              "bytesRead": 1350000000,
              "spoolDirectoryPath": "/data/user/0/com.nexio.tv/cache/player_disk_spool"
            }
        """.trimIndent()

        assertEquals(null, SpoolStorageProbeResult.fromJsonOrNull(measuredAtRaw))
        assertEquals(null, SpoolStorageProbeResult.fromJsonOrNull(durationRaw))
    }

    @Test
    fun negativeMeasuredAt_returnsFalseForFreshness() {
        val result = SpoolStorageProbeResult(
            writeMbps = 180.0,
            readMbps = 180.0,
            combinedMbps = 360.0,
            p99ReadLatencyMs = 40L,
            maxReadStallMs = 70L,
            measuredAtMs = Long.MIN_VALUE,
            durationMs = 60_000L,
            bytesWritten = 1_350_000_000L,
            bytesRead = 1_350_000_000L,
            spoolDirectoryPath = "/data/user/0/com.nexio.tv/cache/player_disk_spool"
        )

        assertFalse(
            SpoolStoragePolicy.isFresh(
                result,
                nowMs = 1_776_047_818_725L,
                spoolDirectoryPath = result.spoolDirectoryPath
            )
        )
    }

    @Test
    fun toJsonOrNull_returnsNullForNonFiniteRates() {
        val nanResult = SpoolStorageProbeResult(
            writeMbps = Double.NaN,
            readMbps = 180.0,
            combinedMbps = 360.0,
            p99ReadLatencyMs = 40L,
            maxReadStallMs = 70L,
            measuredAtMs = 1_776_047_817_725L,
            durationMs = 60_000L,
            bytesWritten = 1_350_000_000L,
            bytesRead = 1_350_000_000L,
            spoolDirectoryPath = "/data/user/0/com.nexio.tv/cache/player_disk_spool"
        )
        val infinityResult = nanResult.copy(writeMbps = Double.POSITIVE_INFINITY)

        assertEquals(null, nanResult.toJsonOrNull())
        assertEquals(null, infinityResult.toJsonOrNull())
    }
}
