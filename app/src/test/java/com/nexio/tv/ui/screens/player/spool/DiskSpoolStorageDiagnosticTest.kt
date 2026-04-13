package com.nexio.tv.ui.screens.player.spool

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CancellationException

class DiskSpoolStorageDiagnosticTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `default diagnostic workload is bounded for shield class devices`() {
        assertEquals(
            128L * 1024L * 1024L,
            DiskSpoolStorageDiagnostic.resolveDefaultTotalBytesForTesting(
                availableBytes = 2L * 1024L * 1024L * 1024L,
                randomWriteEnabled = true
            )
        )
        assertEquals(
            32L * 1024L * 1024L,
            DiskSpoolStorageDiagnostic.resolveDefaultReadCachePurgeBytesForTesting()
        )
    }

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
        assertTrue(result.p99ReadLatencyMs > 0L)
        assertTrue(result.maxReadStallMs > 0L)
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

    @Test
    fun `diagnostic deletes temp files after successful run`() {
        val directory = temp.newFolder("spool-diagnostic-clean-success")

        DiskSpoolStorageDiagnostic(
            directory = directory,
            totalBytes = 8L * 1024L * 1024L,
            sequentialBlockBytes = 1024 * 1024,
            randomBlockBytes = 4 * 1024,
            randomWriteEnabled = true,
            randomSeed = 7L
        ).run()

        assertEquals(emptyList<String>(), diagnosticFileNames(directory))
    }

    @Test
    fun `diagnostic deletes temp files after failed run`() {
        val directory = temp.newFolder("spool-diagnostic-clean-failure")
        var shouldContinue = true

        val failure = runCatching {
            DiskSpoolStorageDiagnostic(
                directory = directory,
                totalBytes = 8L * 1024L * 1024L,
                sequentialBlockBytes = 1024 * 1024,
                randomBlockBytes = 4 * 1024,
                randomWriteEnabled = true,
                randomSeed = 7L,
                shouldContinue = {
                    shouldContinue.also { shouldContinue = false }
                }
            ).run()
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(emptyList<String>(), diagnosticFileNames(directory))
    }

    @Test
    fun `cleanup removes only stale diagnostic files`() {
        val directory = temp.newFolder("spool-diagnostic-stale-cleanup")
        val diagnosticFile = File(directory, "spool-diagnostic-123.bin").also { it.writeText("old") }
        val diagnosticRandomFile = File(directory, "spool-diagnostic-123.bin.random").also { it.writeText("old") }
        val diagnosticConcurrentFile = File(directory, "spool-diagnostic-123.bin.concurrent").also { it.writeText("old") }
        val playbackSpoolFile = File(directory, "spool-123.bin").also { it.writeText("active") }
        val unrelatedFile = File(directory, "keep.txt").also { it.writeText("keep") }

        DiskSpoolStorageDiagnostic.cleanupStaleDiagnosticFiles(directory)

        assertEquals(false, diagnosticFile.exists())
        assertEquals(false, diagnosticRandomFile.exists())
        assertEquals(false, diagnosticConcurrentFile.exists())
        assertEquals(true, playbackSpoolFile.exists())
        assertEquals(true, unrelatedFile.exists())
    }

    @Test
    fun `diagnostic fails before creating files when capacity is insufficient`() {
        val directory = temp.newFolder("spool-diagnostic-capacity")

        val failure = runCatching {
            DiskSpoolStorageDiagnostic(
                directory = directory,
                totalBytes = 8L * 1024L * 1024L,
                sequentialBlockBytes = 1024 * 1024,
                randomBlockBytes = 4 * 1024,
                randomWriteEnabled = true,
                randomSeed = 7L,
                availableBytesProvider = { 1L }
            ).run()
        }.exceptionOrNull()

        assertTrue(failure is java.io.IOException)
        assertEquals(emptyList<String>(), diagnosticFileNames(directory))
    }

    private fun diagnosticFileNames(directory: File): List<String> {
        return directory.listFiles()
            ?.filter { it.name.startsWith("spool-diagnostic-") }
            ?.map { it.name }
            ?: emptyList()
    }
}
