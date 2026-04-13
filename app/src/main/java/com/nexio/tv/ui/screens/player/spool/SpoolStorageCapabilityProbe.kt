package com.nexio.tv.ui.screens.player.spool

import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

internal class SpoolStorageCapabilityProbe(
    private val directory: File,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val elapsedRealtimeNs: () -> Long = { SystemClock.elapsedRealtimeNanos() },
    private val shouldContinue: () -> Boolean = { !Thread.currentThread().isInterrupted }
) {
    internal data class ConcurrentProbeEvents(
        val overlapped: Boolean
    )

    fun run(durationMs: Long = 60_000L, blockBytes: Int = 256 * 1024): SpoolStorageProbeResult {
        require(durationMs > 0L) { "durationMs must be positive" }
        require(blockBytes > 0) { "blockBytes must be positive" }
        ensureShouldContinue()

        directory.mkdirs()
        ensureShouldContinue()

        val probeFile = File(directory, "spool-probe-${nowMs()}.bin")
        if (probeFile.exists() && !probeFile.delete() && probeFile.exists()) {
            throw IllegalStateException("Unable to delete stale probe file: ${probeFile.absolutePath}")
        }

        var executor = Executors.newFixedThreadPool(2)
        var writerFuture: java.util.concurrent.Future<WriterResult>? = null
        var readerFuture: java.util.concurrent.Future<ReaderResult>? = null
        var failure: Throwable? = null
        try {
            probeFile.createNewFile()

            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val deadlineMs = elapsedRealtimeMs() + durationMs
            val readyTimeoutMs = minOf(durationMs, 5_000L)

            writerFuture = executor.submit(Callable {
                runWriter(probeFile, blockBytes, ready, start, deadlineMs)
            })
            readerFuture = executor.submit(Callable {
                runReader(probeFile, blockBytes, ready, start, deadlineMs)
            })

            if (!awaitWorkersReady(ready, readyTimeoutMs)) {
                writerFuture.cancel(true)
                readerFuture.cancel(true)
                throw IllegalStateException("Timed out waiting for spool probe workers to become ready")
            }
            start.countDown()

            ensureShouldContinue()
            val writerResult = writerFuture.getProbeResult()
            ensureShouldContinue()
            val readerResult = readerFuture.getProbeResult()
            ensureShouldContinue()

            return summarizeForTesting(
                durationMs = durationMs,
                bytesWritten = writerResult.bytesWritten,
                bytesRead = readerResult.bytesRead,
                readLatenciesMs = readerResult.readLatenciesMs,
                measuredAtMs = nowMs(),
                spoolDirectoryPath = directory.absolutePath
            )
        } catch (throwable: Throwable) {
            failure = throwable
            throw throwable
        } finally {
            executor.shutdownNow()
            var cleanupFailure: Throwable? = null
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupFailure = IllegalStateException("Timed out waiting for spool probe executor shutdown")
            }
            if (probeFile.exists() && !probeFile.delete() && probeFile.exists()) {
                val deleteFailure = IllegalStateException("Unable to delete probe file: ${probeFile.absolutePath}")
                if (cleanupFailure == null) {
                    cleanupFailure = deleteFailure
                } else {
                    cleanupFailure.addSuppressed(deleteFailure)
                }
            }
            if (cleanupFailure != null) {
                if (failure != null) {
                    failure.addSuppressed(cleanupFailure)
                } else {
                    throw cleanupFailure
                }
            }
        }
    }

    internal fun awaitWorkersReady(ready: CountDownLatch, timeoutMs: Long): Boolean {
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (ready.count > 0L) {
            ensureShouldContinue()
            val remainingNs = deadlineNs - System.nanoTime()
            if (remainingNs <= 0L) return false
            val pollMs = TimeUnit.NANOSECONDS.toMillis(remainingNs).coerceIn(1L, 50L)
            ready.await(pollMs, TimeUnit.MILLISECONDS)
        }
        return true
    }

    private fun ensureShouldContinue() {
        if (!shouldContinue()) {
            throw CancellationException("Spool storage probe cancelled")
        }
    }

    private fun <T> Future<T>.getProbeResult(): T {
        return try {
            get()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Spool storage probe interrupted").also {
                it.initCause(interrupted)
            }
        } catch (execution: ExecutionException) {
            val cause = execution.cause
            when (cause) {
                is CancellationException -> throw cause
                is RuntimeException -> throw cause
                is Error -> throw cause
                null -> throw execution
                else -> throw cause
            }
        }
    }

    private data class WriterResult(
        val bytesWritten: Long
    )

    private data class ReaderResult(
        val bytesRead: Long,
        val readLatenciesMs: List<Long>
    )

    private fun runWriter(
        probeFile: File,
        blockBytes: Int,
        ready: CountDownLatch,
        start: CountDownLatch,
        deadlineMs: Long
    ): WriterResult {
        var readySignaled = false
        try {
            RandomAccessFile(probeFile, "rw").use { randomAccessFile ->
                val channel = randomAccessFile.channel
                val block = ByteBuffer.allocateDirect(blockBytes)
                for (index in 0 until blockBytes) {
                    block.put((index and 0xFF).toByte())
                }
                block.flip()

                ready.countDown()
                readySignaled = true
                awaitStart(start, deadlineMs)
                ensureShouldContinue()

                var bytesWritten = 0L
                var position = 0L
                while (elapsedRealtimeMs() < deadlineMs && shouldContinue()) {
                    block.rewind()
                    channel.writeFully(block, position, deadlineMs, elapsedRealtimeMs, shouldContinue)
                    position += blockBytes
                    bytesWritten += blockBytes
                }

                return WriterResult(bytesWritten = bytesWritten)
            }
        } finally {
            if (!readySignaled) {
                ready.countDown()
            }
        }
    }

    private fun runReader(
        probeFile: File,
        blockBytes: Int,
        ready: CountDownLatch,
        start: CountDownLatch,
        deadlineMs: Long
    ): ReaderResult {
        var readySignaled = false
        try {
            RandomAccessFile(probeFile, "r").use { randomAccessFile ->
                val channel = randomAccessFile.channel
                val block = ByteBuffer.allocateDirect(blockBytes)
                val readLatenciesMs = ArrayList<Long>()

                ready.countDown()
                readySignaled = true
                awaitStart(start, deadlineMs)
                ensureShouldContinue()

                var bytesRead = 0L
                var position = 0L
                while (shouldContinue()) {
                    val fileSize = channel.size()
                    if (position >= fileSize) {
                        if (elapsedRealtimeMs() >= deadlineMs) {
                            break
                        }
                        ensureShouldContinue()
                        Thread.yield()
                        continue
                    }

                    val bytesToRead = minOf(blockBytes.toLong(), fileSize - position).toInt()
                    block.clear()
                    block.limit(bytesToRead)

                    val readStartedAtNs = elapsedRealtimeNs()
                    val read = channel.read(block, position)
                    val readEndedAtNs = elapsedRealtimeNs()

                    if (read <= 0) {
                        if (elapsedRealtimeMs() >= deadlineMs) {
                            break
                        }
                        ensureShouldContinue()
                        Thread.yield()
                        continue
                    }

                    bytesRead += read
                    position += read
                    readLatenciesMs += TimeUnit.NANOSECONDS.toMillis((readEndedAtNs - readStartedAtNs).coerceAtLeast(0L))
                }

                return ReaderResult(bytesRead = bytesRead, readLatenciesMs = readLatenciesMs)
            }
        } finally {
            if (!readySignaled) {
                ready.countDown()
            }
        }
    }

    private fun awaitStart(start: CountDownLatch, deadlineMs: Long) {
        val remainingProbeMs = (deadlineMs - elapsedRealtimeMs()).coerceAtLeast(1L)
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(remainingProbeMs)
        while (start.count > 0L) {
            ensureShouldContinue()
            val remainingNs = deadlineNs - System.nanoTime()
            if (remainingNs <= 0L) {
                throw CancellationException("Spool storage probe did not start before deadline")
            }
            val pollMs = TimeUnit.NANOSECONDS.toMillis(remainingNs).coerceIn(1L, 50L)
            start.await(pollMs, TimeUnit.MILLISECONDS)
        }
    }

    companion object {
        fun concurrentProbeEventsForTesting(
            writeStartedAtMs: Long,
            readStartedAtMs: Long,
            writeEndedAtMs: Long,
            readEndedAtMs: Long
        ): ConcurrentProbeEvents {
            return ConcurrentProbeEvents(
                overlapped = writeStartedAtMs < readEndedAtMs && readStartedAtMs < writeEndedAtMs
            )
        }

        fun summarizeForTesting(
            durationMs: Long,
            bytesWritten: Long,
            bytesRead: Long,
            readLatenciesMs: List<Long>,
            measuredAtMs: Long,
            spoolDirectoryPath: String
        ): SpoolStorageProbeResult {
            val writeMbps = bytesWritten.toDouble() * 8.0 / durationMs.toDouble() / 1000.0
            val readMbps = bytesRead.toDouble() * 8.0 / durationMs.toDouble() / 1000.0
            val combinedMbps = writeMbps + readMbps
            val maxReadStallMs = readLatenciesMs.maxOrNull() ?: Long.MAX_VALUE
            val p99ReadLatencyMs = if (readLatenciesMs.isEmpty()) {
                Long.MAX_VALUE
            } else {
                val sorted = readLatenciesMs.sorted()
                val p99Index = ceil(sorted.size * 0.99).toLong().coerceIn(1L, sorted.size.toLong()) - 1L
                sorted[p99Index.toInt()]
            }

            return SpoolStorageProbeResult(
                writeMbps = writeMbps,
                readMbps = readMbps,
                combinedMbps = combinedMbps,
                p99ReadLatencyMs = p99ReadLatencyMs,
                maxReadStallMs = maxReadStallMs,
                measuredAtMs = measuredAtMs,
                durationMs = durationMs,
                bytesWritten = bytesWritten,
                bytesRead = bytesRead,
                spoolDirectoryPath = spoolDirectoryPath
            )
        }
    }
}

private fun FileChannel.writeFully(
    buffer: ByteBuffer,
    position: Long,
    deadlineMs: Long,
    elapsedRealtimeMs: () -> Long,
    shouldContinue: () -> Boolean
) {
    var writePosition = position
    var lastProgressAtMs = elapsedRealtimeMs()
    while (buffer.hasRemaining()) {
        if (!shouldContinue()) {
            throw IOException("Interrupted while writing spool probe data")
        }
        if (elapsedRealtimeMs() >= deadlineMs) {
            throw IOException("Timed out while writing spool probe data")
        }
        val written = write(buffer, writePosition)
        if (written <= 0) {
            if (elapsedRealtimeMs() - lastProgressAtMs >= 1_000L) {
                throw IOException("Unable to make progress while writing spool probe data")
            }
            Thread.yield()
            continue
        }
        writePosition += written
        lastProgressAtMs = elapsedRealtimeMs()
    }
}
