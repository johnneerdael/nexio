package com.nexio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Strict, Media3-facing sequential read cursor. Wraps an [AbsoluteByteStore] and an
 * [OpenSession] and is the sole producer of Media3 `read()` bytes for
 * [ParallelRangeDataSource]: it copies contiguous bytes from the store, blocks on the
 * caller-supplied wait hook when the store is behind the reader, and surfaces typed
 * errors (`ChunkWaitTimeoutException`, wait errors) plus `C.RESULT_END_OF_INPUT`.
 */
@UnstableApi
internal interface SequentialReadCursor {
    val position: Long
    val bytesRemaining: Long
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun close()
}

internal enum class WaitOutcome { DATA_AVAILABLE, CLOSED, ERROR, TIMEOUT }

@UnstableApi
internal class DefaultSequentialReadCursor(
    private val session: OpenSession,
    private val store: AbsoluteByteStore,
    private val waitForBytes: (position: Long, deadlineNanos: Long) -> WaitOutcome,
    private val onPositionAdvanced: (Long) -> Unit,
    private val keepBehindBytes: Long,
    private val chunkWaitTimeoutMs: Long
) : SequentialReadCursor {
    companion object {
        private const val STAGED_READ_BUFFER_BYTES = 32 * 1024
    }

    @Volatile private var _position: Long = session.startPosition
    @Volatile private var _bytesRemaining: Long = session.openLength
    @Volatile private var closed: Boolean = false
    private val stagedBuffer = ByteArray(STAGED_READ_BUFFER_BYTES)
    private var stagedLength: Int = 0
    private var stagedOffset: Int = 0

    override val position: Long get() = _position
    override val bytesRemaining: Long get() = _bytesRemaining

    private fun consumeRead(count: Int) {
        _position += count
        if (_bytesRemaining != C.LENGTH_UNSET.toLong()) {
            _bytesRemaining -= count
        }
        onPositionAdvanced(_position)
        store.evictBefore(_position - keepBehindBytes)
    }

    private fun tryReadFromStage(buffer: ByteArray, offset: Int, length: Int): Int {
        if (stagedOffset >= stagedLength) return 0
        val toCopy = minOf(length, stagedLength - stagedOffset)
        System.arraycopy(stagedBuffer, stagedOffset, buffer, offset, toCopy)
        stagedOffset += toCopy
        consumeRead(toCopy)
        return toCopy
    }

    private fun refillStage(maxBytes: Int): Int {
        stagedOffset = 0
        stagedLength = store.read(_position, stagedBuffer, 0, minOf(stagedBuffer.size, maxBytes))
        return stagedLength
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (_bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val stagedRead = tryReadFromStage(buffer, offset, length)
        if (stagedRead > 0) return stagedRead

        val toRead = if (_bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), _bytesRemaining).toInt()
        }

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(chunkWaitTimeoutMs)
        while (!closed) {
            if (toRead <= stagedBuffer.size && refillStage(stagedBuffer.size) > 0) {
                return tryReadFromStage(buffer, offset, length)
            }
            val read = store.read(_position, buffer, offset, toRead)
            if (read > 0) {
                consumeRead(read)
                return read
            }
            when (waitForBytes(_position, deadline)) {
                WaitOutcome.DATA_AVAILABLE -> continue
                WaitOutcome.CLOSED -> return C.RESULT_END_OF_INPUT
                WaitOutcome.TIMEOUT -> throw ChunkWaitTimeoutException(
                    chunkIndex = _position,
                    timeoutMs = chunkWaitTimeoutMs
                )
                WaitOutcome.ERROR -> throw IOException("SequentialReadCursor wait error")
            }
        }
        return C.RESULT_END_OF_INPUT
    }

    override fun close() {
        closed = true
    }
}
