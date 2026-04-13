package com.nexio.tv.ui.screens.player.spool

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiskSpoolStorageDiagnosticTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `diagnostic returns sequential read write and random write results`() {
        val directory = temp.newFolder("spool-diagnostic")

        val result = DiskSpoolStorageDiagnostic(
            directory = directory,
            totalBytes = 8L * 1024L * 1024L,
            sequentialBlockBytes = 1024 * 1024,
            randomBlockBytes = 4 * 1024,
            randomWriteEnabled = true,
            randomSeed = 7L
        ).run()

        assertTrue(result.sequentialWriteMbps > 0.0)
        assertTrue(result.sequentialReadMbps > 0.0)
        assertTrue((result.concurrentSequentialWriteMbps ?: -1.0) > 0.0)
        assertTrue((result.concurrentSequentialReadMbps ?: -1.0) > 0.0)
        assertTrue(result.concurrentRandomWriteMbps != null)
        assertEquals(File(temp.root, "spool-diagnostic").absolutePath, result.spoolDirectoryPath)
    }

    @Test
    fun `diagnostic can skip random write workload when parallel is disabled`() {
        val result = DiskSpoolStorageDiagnostic(
            directory = temp.newFolder("spool-diagnostic-no-random"),
            totalBytes = 8L * 1024L * 1024L,
            sequentialBlockBytes = 1024 * 1024,
            randomBlockBytes = 4 * 1024,
            randomWriteEnabled = false,
            randomSeed = 7L
        ).run()

        assertEquals(null, result.concurrentRandomWriteMbps)
    }
}
