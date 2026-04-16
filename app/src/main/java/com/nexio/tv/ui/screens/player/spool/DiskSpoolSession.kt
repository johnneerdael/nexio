package com.nexio.tv.ui.screens.player.spool

import android.os.SystemClock
import androidx.media3.common.C
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

internal class DiskSpoolSession(
    spoolFile: File,
    private val capacityBytes: Long,
    private val waitTimeoutMs: Long = 10_000L
) : Closeable {
    private val lock = java.lang.Object()
    private val closed = AtomicBoolean(false)
    private val windowStart = AtomicLong(0L)
    private val frontier = AtomicLong(0L)
    private val readPosition = AtomicLong(0L)
    private val priorityPosition = AtomicLong(C.TIME_UNSET.toLong())
    private val contentLength = AtomicLong(C.LENGTH_UNSET.toLong())
    private val supportsRangesFlag = AtomicBoolean(false)

    private var writer: RandomAccessFile? = null
    private var reader: RandomAccessFile? = null
    private val spoolFile: File = spoolFile

    init {
        require(capacityBytes > 0L) { "capacityBytes must be positive" }

        spoolFile.parentFile?.mkdirs()
        if (!spoolFile.exists()) {
            spoolFile.createNewFile()
        }

        var openedWriter: RandomAccessFile? = null
        var openedReader: RandomAccessFile? = null
        var setupFailure: Throwable? = null
        try {
            openedWriter = RandomAccessFile(spoolFile, "rw")
            openedWriter.setLength(0L)
            openedReader = RandomAccessFile(spoolFile, "r")

            writer = openedWriter
            reader = openedReader
            openedWriter = null
            openedReader = null
        } catch (throwable: Throwable) {
            setupFailure = throwable
            throw throwable
        } finally {
            var cleanupFailure: Throwable? = null
            fun recordCleanupFailure(throwable: Throwable) {
                if (cleanupFailure == null) {
                    cleanupFailure = throwable
                } else {
                    cleanupFailure.addSuppressed(throwable)
                }
            }

            listOfNotNull(openedWriter, openedReader).forEach { handle ->
                try {
                    handle.close()
                } catch (throwable: IOException) {
                    recordCleanupFailure(throwable)
                }
            }
            if (setupFailure != null) {
                try {
                    if (spoolFile.exists() && !spoolFile.delete() && spoolFile.exists()) {
                        recordCleanupFailure(IOException("Unable to delete spool file: ${spoolFile.absolutePath}"))
                    }
                } catch (throwable: Throwable) {
                    recordCleanupFailure(throwable)
                }
            }
            cleanupFailure?.let { failure ->
                val setupThrowable = setupFailure
                if (setupThrowable == null) {
                    throw failure
                } else {
                    setupThrowable.addSuppressed(failure)
                }
            }
        }
    }

    fun isClosed(): Boolean = closed.get()

    fun contiguousFrontierBytes(): Long = frontier.get()

    fun windowStartBytes(): Long = windowStart.get()

    fun currentReadPositionBytes(): Long = readPosition.get()

    fun updateReadPosition(position: Long) {
        if (position < 0L) return
        synchronized(lock) {
            val previous = readPosition.get()
            if (position > previous) {
                readPosition.set(position)
                lock.notifyAll()
            }
        }
    }

    fun adaptiveTargetFrontierBytes(
        maxFrontierBytes: Long,
        startupPrebufferBytes: Long,
        headroomBytes: Long
    ): Long {
        val safeMax = maxFrontierBytes.coerceAtLeast(0L)
        val startupTarget = startupPrebufferBytes.coerceAtLeast(0L)
        val readTarget = currentReadPositionBytes() + headroomBytes.coerceAtLeast(0L)
        val contentTarget = contentLength.get().takeIf { it > 0L } ?: safeMax
        return minOf(maxOf(startupTarget, readTarget), safeMax, contentTarget)
    }

    fun consumePriorityPosition(): Long = priorityPosition.getAndSet(C.TIME_UNSET.toLong())

    fun contentLengthBytes(): Long = contentLength.get()

    fun supportsRanges(): Boolean = supportsRangesFlag.get()

    fun setSourceMetadata(contentLength: Long, supportsRanges: Boolean) {
        this.contentLength.set(contentLength)
        supportsRangesFlag.set(supportsRanges)
    }

    fun rebaseTo(position: Long) {
        synchronized(lock) {
            ensureOpenLocked()
            windowStart.set(position)
            frontier.set(position)
            readPosition.set(position)
            lock.notifyAll()
        }
    }

    fun writeRange(start: Long, bytes: ByteArray, length: Int) {
        require(start >= 0L) { "start must be non-negative" }
        require(length >= 0) { "length must be non-negative" }
        require(length <= bytes.size) { "length must be <= bytes.size" }
        if (length == 0) return

        synchronized(lock) {
            ensureOpenLocked()
            val currentWindowStart = windowStart.get()
            if (start < currentWindowStart) {
                return
            }
            val currentFrontier = frontier.get()
            if (start != currentFrontier) {
                return
            }

            val file = writer ?: throw IllegalStateException("Session closed")
            var remaining = length
            var logicalOffset = 0L
            while (remaining > 0) {
                val physicalOffset = (start + logicalOffset).mod(capacityBytes)
                val chunkLength = min(remaining.toLong(), capacityBytes - physicalOffset).toInt()
                file.seek(physicalOffset)
                file.write(bytes, logicalOffset.toInt(), chunkLength)
                remaining -= chunkLength
                logicalOffset += chunkLength.toLong()
            }

            val nextFrontier = start + length.toLong()
            frontier.set(nextFrontier)
            windowStart.set(maxOf(windowStart.get(), nextFrontier - capacityBytes))
            lock.notifyAll()
        }
    }

    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        require(position >= 0L) { "position must be non-negative" }
        require(offset >= 0) { "offset must be non-negative" }
        require(length >= 0) { "length must be non-negative" }
        require(offset <= buffer.size) { "offset must be <= buffer.size" }
        require(length <= buffer.size - offset) { "offset + length must fit in buffer" }
        if (length == 0) return 0

        synchronized(lock) {
            val startedAtMs = SystemClock.elapsedRealtime()
            var remainingMs = waitTimeoutMs
            while (true) {
                if (closed.get()) {
                    priorityPosition.set(position)
                    return -1
                }

                val currentWindowStart = windowStart.get()
                val currentFrontier = frontier.get()
                if (position < currentWindowStart) {
                    priorityPosition.set(position)
                    return -1
                }

                if (position < currentFrontier) {
                    val available = (currentFrontier - position).coerceAtMost(length.toLong()).toInt()
                    readFullyLocked(position, buffer, offset, available)
                    return available
                }

                val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
                if (elapsedMs >= waitTimeoutMs || remainingMs <= 0L) {
                    priorityPosition.set(position)
                    return -1
                }
                val waitSliceMs = minOf(remainingMs, 50L)
                val beforeWaitMs = SystemClock.elapsedRealtime()
                try {
                    lock.wait(waitSliceMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    priorityPosition.set(position)
                    return -1
                }
                val afterWaitMs = SystemClock.elapsedRealtime()
                remainingMs = if (afterWaitMs <= beforeWaitMs) {
                    (remainingMs - waitSliceMs).coerceAtLeast(0L)
                } else {
                    (waitTimeoutMs - (afterWaitMs - startedAtMs)).coerceAtLeast(0L)
                }
            }
        }
    }

    fun awaitFrontierAtLeast(targetFrontierBytes: Long, timeoutMs: Long): Boolean {
        if (targetFrontierBytes <= 0L) return true
        val safeTimeoutMs = timeoutMs.coerceAtLeast(0L)
        synchronized(lock) {
            val startedAtMs = SystemClock.elapsedRealtime()
            var remainingMs = safeTimeoutMs
            while (true) {
                if (closed.get()) return false
                val knownContentLength = contentLength.get()
                val effectiveTarget = if (knownContentLength > 0L) {
                    minOf(targetFrontierBytes, knownContentLength)
                } else {
                    targetFrontierBytes
                }
                if (frontier.get() >= effectiveTarget) return true
                val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
                if (elapsedMs >= safeTimeoutMs || remainingMs <= 0L) return false
                val waitSliceMs = minOf(remainingMs, 50L)
                val beforeWaitMs = SystemClock.elapsedRealtime()
                try {
                    lock.wait(waitSliceMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
                val afterWaitMs = SystemClock.elapsedRealtime()
                remainingMs = if (afterWaitMs <= beforeWaitMs) {
                    (remainingMs - waitSliceMs).coerceAtLeast(0L)
                } else {
                    (safeTimeoutMs - (afterWaitMs - startedAtMs)).coerceAtLeast(0L)
                }
            }
        }
    }

    override fun close() {
        val closeFailure = arrayListOf<IOException>()
        synchronized(lock) {
            if (closed.get()) {
                return
            }
            closed.set(true)
            lock.notifyAll()

            try {
                writer?.close()
            } catch (throwable: IOException) {
                closeFailure += throwable
            } finally {
                writer = null
            }
            try {
                reader?.close()
            } catch (throwable: IOException) {
                closeFailure += throwable
            } finally {
                reader = null
            }
        }

        if (spoolFile.exists() && !spoolFile.delete() && spoolFile.exists()) {
            closeFailure += IOException("Unable to delete spool file: ${spoolFile.absolutePath}")
        }

        if (closeFailure.isNotEmpty()) {
            val failure = closeFailure.first()
            closeFailure.drop(1).forEach(failure::addSuppressed)
            throw failure
        }
    }

    private fun readFullyLocked(position: Long, buffer: ByteArray, offset: Int, length: Int) {
        val file = reader ?: throw IllegalStateException("Session closed")
        var remaining = length
        var logicalOffset = 0L
        while (remaining > 0) {
            val physicalOffset = (position + logicalOffset).mod(capacityBytes)
            val chunkLength = min(remaining.toLong(), capacityBytes - physicalOffset).toInt()
            file.seek(physicalOffset)
            var bytesRead = 0
            while (bytesRead < chunkLength) {
                val read = file.read(buffer, offset + logicalOffset.toInt() + bytesRead, chunkLength - bytesRead)
                if (read < 0) {
                    throw IOException("Unexpected EOF while reading spool")
                }
                bytesRead += read
            }
            remaining -= chunkLength
            logicalOffset += chunkLength.toLong()
        }
    }

    private fun ensureOpenLocked() {
        if (closed.get()) {
            throw IllegalStateException("Session closed")
        }
    }
}

private fun Long.mod(divisor: Long): Long {
    val result = this % divisor
    return if (result >= 0L) result else result + divisor
}
