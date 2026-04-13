package com.nexio.tv.ui.screens.player.spool

import java.io.File
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpoolStorageCapabilityProbeTest {
    @Test
    fun `summarize computes rates and latency percentiles`() {
        val result = SpoolStorageCapabilityProbe.summarizeForTesting(
            durationMs = 10_000L,
            bytesWritten = 100_000_000L,
            bytesRead = 80_000_000L,
            readLatenciesMs = listOf(5L, 8L, 10L, 20L, 100L),
            measuredAtMs = 123L,
            spoolDirectoryPath = "/cache/player_disk_spool"
        )

        assertEquals(80.0, result.writeMbps, 0.01)
        assertEquals(64.0, result.readMbps, 0.01)
        assertEquals(144.0, result.combinedMbps, 0.01)
        assertEquals(100L, result.p99ReadLatencyMs)
        assertEquals(100L, result.maxReadStallMs)
        assertEquals("/cache/player_disk_spool", result.spoolDirectoryPath)
    }

    @Test
    fun `overlap helper detects concurrent read write`() {
        assertTrue(
            SpoolStorageCapabilityProbe.concurrentProbeEventsForTesting(
                writeStartedAtMs = 10L,
                readStartedAtMs = 12L,
                writeEndedAtMs = 60L,
                readEndedAtMs = 62L
            ).overlapped
        )
    }

    @Test
    fun `awaitWorkersReady times out when workers never count down`() {
        val probe = SpoolStorageCapabilityProbe(File("/tmp"))

        assertFalse(probe.awaitWorkersReady(CountDownLatch(1), 1L))
    }
}
