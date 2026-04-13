package com.nexio.tv.ui.screens.player.spool

import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

internal class DiskSpoolStorageDiagnostic(
    private val directory: File,
    private val totalBytes: Long = 512L * 1024L * 1024L,
    private val sequentialBlockBytes: Int = 1024 * 1024,
    private val randomBlockBytes: Int = 4 * 1024,
    private val randomWriteEnabled: Boolean,
    private val readCachePurgeBytes: Long = totalBytes,
    private val randomSeed: Long = System.nanoTime(),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val shouldContinue: () -> Boolean = { !Thread.currentThread().isInterrupted },
    private val availableBytesProvider: (File) -> Long = { it.usableSpace }
) {
    companion object {
        private const val DIAGNOSTIC_PREFIX = "spool-diagnostic-"

        fun cleanupStaleDiagnosticFiles(directory: File) {
            directory.listFiles()?.forEach { file ->
                if (file.isFile && file.name.startsWith(DIAGNOSTIC_PREFIX)) {
                    file.delete()
                }
            }
        }
    }

    fun run(): SpoolStorageProbeResult {
        require(totalBytes > 0L) { "totalBytes must be positive" }
        require(sequentialBlockBytes > 0) { "sequentialBlockBytes must be positive" }
        require(randomBlockBytes > 0) { "randomBlockBytes must be positive" }
        require(readCachePurgeBytes >= 0L) { "readCachePurgeBytes must not be negative" }

        directory.mkdirs()
        runCatching { cleanupStaleDiagnosticFiles(directory) }
        ensureCapacity()
        ensureRunning()
        val measuredAtMs = nowMs()
        val file = File(directory, "spool-diagnostic-${SystemClock.elapsedRealtimeNanos()}.bin")
        val randomFile = File(directory, "${file.name}.random")
        val concurrentFile = File(directory, "${file.name}.concurrent")
        val cachePurgeFile = File(directory, "${file.name}.purge")
        return try {
            val sequentialWriteMbps = sequentialWrite(file)
            ensureRunning()
            purgeReadCache(cachePurgeFile)
            val sequentialReadMbps = sequentialRead(file)
            ensureRunning()
            purgeReadCache(cachePurgeFile)
            val concurrentSequential = concurrentSequentialReadWrite(
                readFile = file,
                writeFile = concurrentFile
            )
            ensureRunning()
            val concurrentRandom = if (randomWriteEnabled) {
                sequentialWrite(randomFile)
                ensureRunning()
                purgeReadCache(cachePurgeFile)
                concurrentSequentialReadRandomWrite(readFile = file, writeFile = randomFile)
            } else {
                null
            }
            val durationMs = (nowMs() - measuredAtMs).coerceAtLeast(1L)
            val stallSource = concurrentRandom ?: concurrentSequential

            SpoolStorageProbeResult(
                writeMbps = sequentialWriteMbps,
                readMbps = sequentialReadMbps,
                combinedMbps = concurrentSequential.writeMbps + concurrentSequential.readMbps,
                p99ReadLatencyMs = stallSource.p99ReadLatencyMs,
                maxReadStallMs = stallSource.maxReadStallMs,
                measuredAtMs = measuredAtMs,
                durationMs = durationMs,
                bytesWritten = totalBytes,
                bytesRead = totalBytes,
                spoolDirectoryPath = directory.absolutePath,
                concurrentSequentialWriteMbps = concurrentSequential.writeMbps,
                concurrentSequentialReadMbps = concurrentSequential.readMbps,
                concurrentRandomWriteMbps = concurrentRandom?.writeMbps
            )
        } finally {
            file.delete()
            randomFile.delete()
            concurrentFile.delete()
            cachePurgeFile.delete()
        }
    }

    private fun sequentialWrite(file: File): Double {
        val buffer = ByteArray(sequentialBlockBytes) { (it and 0xFF).toByte() }
        val elapsedNs = timedNs {
            RandomAccessFile(file, "rw").use { writer ->
                writer.setLength(totalBytes)
                var position = 0L
                while (position < totalBytes && shouldContinue()) {
                    val length = minOf(buffer.size.toLong(), totalBytes - position).toInt()
                    writer.seek(position)
                    writer.write(buffer, 0, length)
                    position += length.toLong()
                }
                ensureRunning()
                writer.fd.sync()
            }
        }
        return mbps(totalBytes, elapsedNs)
    }

    private fun sequentialRead(file: File): Double {
        val buffer = ByteArray(sequentialBlockBytes)
        val elapsedNs = timedNs {
            RandomAccessFile(file, "r").use { reader ->
                var position = 0L
                while (position < totalBytes && shouldContinue()) {
                    val length = minOf(buffer.size.toLong(), totalBytes - position).toInt()
                    reader.seek(position)
                    reader.readFully(buffer, 0, length)
                    position += length.toLong()
                }
                ensureRunning()
            }
        }
        return mbps(totalBytes, elapsedNs)
    }

    private fun purgeReadCache(file: File) {
        if (readCachePurgeBytes == 0L) return
        val buffer = ByteArray(sequentialBlockBytes)
        try {
            RandomAccessFile(file, "rw").use { writer ->
                writer.setLength(readCachePurgeBytes)
                var position = 0L
                var seed = 0
                while (position < readCachePurgeBytes && shouldContinue()) {
                    val length = minOf(buffer.size.toLong(), readCachePurgeBytes - position).toInt()
                    for (index in 0 until length step 256) {
                        buffer[index] = (seed++ and 0xFF).toByte()
                    }
                    writer.seek(position)
                    writer.write(buffer, 0, length)
                    position += length.toLong()
                }
                ensureRunning()
                writer.fd.sync()
            }
        } finally {
            file.delete()
        }
    }

    private fun concurrentSequentialReadWrite(
        readFile: File,
        writeFile: File
    ): ConcurrentResult {
        val writeBuffer = ByteArray(sequentialBlockBytes) { 0x33.toByte() }
        val readBuffer = ByteArray(sequentialBlockBytes)
        return runConcurrent(
            writeBytes = totalBytes,
            readBytes = totalBytes,
            writeWork = { writer ->
                var position = 0L
                while (position < totalBytes && shouldContinue()) {
                    val length = minOf(writeBuffer.size.toLong(), totalBytes - position).toInt()
                    writer.seek(position)
                    writer.write(writeBuffer, 0, length)
                    position += length.toLong()
                }
                ensureRunning()
                writer.fd.sync()
            },
            readWork = { reader, readTiming ->
                var position = 0L
                while (position < totalBytes && shouldContinue()) {
                    val length = minOf(readBuffer.size.toLong(), totalBytes - position).toInt()
                    reader.seek(position)
                    readTiming.record(timedNs { reader.readFully(readBuffer, 0, length) })
                    position += length.toLong()
                }
                ensureRunning()
            },
            readFile = readFile,
            writeFile = writeFile
        )
    }

    private fun concurrentSequentialReadRandomWrite(
        readFile: File,
        writeFile: File
    ): ConcurrentResult {
        val writeBuffer = ByteArray(randomBlockBytes) { 0x55.toByte() }
        val readBuffer = ByteArray(sequentialBlockBytes)
        val random = Random(randomSeed)
        val randomBlocks = (totalBytes / randomBlockBytes.toLong()).coerceAtLeast(1L)
        return runConcurrent(
            writeBytes = totalBytes,
            readBytes = totalBytes,
            writeWork = { writer ->
                var writtenBytes = 0L
                while (writtenBytes < totalBytes && shouldContinue()) {
                    val length = minOf(writeBuffer.size.toLong(), totalBytes - writtenBytes).toInt()
                    val block = random.nextLong().positiveModulo(randomBlocks)
                    writer.seek(block * randomBlockBytes.toLong())
                    writer.write(writeBuffer, 0, length)
                    writtenBytes += length.toLong()
                }
                ensureRunning()
                writer.fd.sync()
            },
            readWork = { reader, readTiming ->
                var position = 0L
                while (position < totalBytes && shouldContinue()) {
                    val length = minOf(readBuffer.size.toLong(), totalBytes - position).toInt()
                    reader.seek(position)
                    readTiming.record(timedNs { reader.readFully(readBuffer, 0, length) })
                    position += length.toLong()
                }
                ensureRunning()
            },
            readFile = readFile,
            writeFile = writeFile
        )
    }

    private fun runConcurrent(
        writeBytes: Long,
        readBytes: Long,
        writeWork: (RandomAccessFile) -> Unit,
        readWork: (RandomAccessFile, ReadTimingRecorder) -> Unit,
        readFile: File,
        writeFile: File
    ): ConcurrentResult {
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val readTiming = ReadTimingRecorder()
        var writeElapsedNs = 1L
        var readElapsedNs = 1L
        val writer = Thread {
            try {
                RandomAccessFile(writeFile, "rw").use { access ->
                    access.setLength(writeBytes)
                    start.await()
                    writeElapsedNs = timedNs { writeWork(access) }
                }
            } catch (throwable: Throwable) {
                failure.compareAndSet(null, throwable)
            }
        }
        val reader = Thread {
            try {
                RandomAccessFile(readFile, "r").use { access ->
                    start.await()
                    readElapsedNs = timedNs { readWork(access, readTiming) }
                }
            } catch (throwable: Throwable) {
                failure.compareAndSet(null, throwable)
            }
        }
        writer.name = "DiskSpoolDiagnosticWriter"
        reader.name = "DiskSpoolDiagnosticReader"
        writer.start()
        reader.start()
        start.countDown()
        writer.join()
        reader.join()
        failure.get()?.let { throw it }
        return ConcurrentResult(
            writeMbps = mbps(writeBytes, writeElapsedNs),
            readMbps = mbps(readBytes, readElapsedNs),
            p99ReadLatencyMs = readTiming.p99Ms(),
            maxReadStallMs = readTiming.maxMs()
        )
    }

    private fun timedNs(block: () -> Unit): Long {
        val startedAt = System.nanoTime()
        block()
        return (System.nanoTime() - startedAt).coerceAtLeast(1L)
    }

    private fun ensureRunning() {
        if (!shouldContinue()) {
            throw CancellationException("Disk spool storage diagnostic canceled")
        }
    }

    private fun requiredBytes(): Long {
        var fileCount = 2L
        if (randomWriteEnabled) fileCount += 1L
        return totalBytes * fileCount
    }

    private fun ensureCapacity() {
        val availableBytes = availableBytesProvider(directory)
        val requiredBytes = requiredBytes()
        if (availableBytes < requiredBytes) {
            throw IOException(
                "Need ${requiredBytes / 1024L / 1024L} MiB free for disk spool diagnostic, " +
                    "found ${availableBytes / 1024L / 1024L} MiB"
            )
        }
    }

    private fun mbps(bytes: Long, elapsedNs: Long): Double {
        return (bytes.toDouble() * 8.0) / elapsedNs.toDouble() * 1000.0
    }

    private data class ConcurrentResult(
        val writeMbps: Double,
        val readMbps: Double,
        val p99ReadLatencyMs: Long,
        val maxReadStallMs: Long
    )

    private class ReadTimingRecorder {
        private val samplesNs = ArrayList<Long>()

        fun record(elapsedNs: Long) {
            samplesNs += elapsedNs.coerceAtLeast(1L)
        }

        fun p99Ms(): Long {
            if (samplesNs.isEmpty()) return 0L
            val sorted = samplesNs.sorted()
            val index = (((sorted.size * 99) + 99) / 100 - 1).coerceIn(0, sorted.lastIndex)
            return nsToMsCeil(sorted[index])
        }

        fun maxMs(): Long {
            return samplesNs.maxOrNull()?.let(::nsToMsCeil) ?: 0L
        }

        private fun nsToMsCeil(ns: Long): Long {
            return ((ns + 999_999L) / 1_000_000L).coerceAtLeast(1L)
        }
    }
}

private fun Long.positiveModulo(divisor: Long): Long {
    val result = this % divisor
    return if (result >= 0L) result else result + divisor
}
