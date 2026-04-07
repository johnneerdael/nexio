package com.nexio.tv.ui.screens.player

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.SocketException
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the parallel-range transport engine that sits below the
 * [ParallelRangeDataSource] Media3 façade.
 *
 * The façade ([ParallelRangeDataSource]) is responsible for bootstrap reuse, fallback +
 * continuation pumps, and Media3 lifecycle callbacks; this class owns:
 *
 * - provider policy plumbing + runtime transport observations
 * - retries / backoff / transient-error classification
 * - connection budgeting
 * - range scheduling via a private [DualLaneScheduler]
 * - frontier promotion re-queue when a frontier-blocking chunk fails
 *
 * The façade calls [attachSession] once per `open()`, then calls
 * [scheduleForReaderPosition] from the reader thread as the cursor advances.
 * [detach] cancels everything and clears state so the manager can be reused.
 */
@UnstableApi
internal class SharedParallelTransportManager(
    private val upstreamFactory: OkHttpDataSource.Factory,
    private val parallelConnections: Int,
    private val transportSampleTimeMs: () -> Long,
    private val onTransportBytesDownloaded: (Long, Long) -> Unit,
    private val onChunkBytesDownloaded: (chunkIndex: Long, chunkSize: Long, offsetInChunk: Long, bytesRead: Int, sampleTimeMs: Long) -> Unit,
    private val onTransportObservation: (RuntimeTransportObservation) -> Unit,
    private val transportPolicyProvider: () -> TransportPolicy?,
    private val onTerminalError: (ChunkDownloadException) -> Unit,
    private val signalDataAvailable: () -> Unit
) {

    companion object {
        private const val TAG = "SharedParallelXport"
        private const val READ_BUFFER_SIZE = 512 * 1024
        private const val MAX_TRANSIENT_CHUNK_ATTEMPTS = 4
        private const val MAX_NON_TRANSIENT_CHUNK_ATTEMPTS = 2
        internal const val MAX_FRONTIER_PROMOTIONS = 2
    }

    private val closed = AtomicBoolean(true)
    private var scheduler: DualLaneScheduler? = null
    private var store: AbsoluteByteStore? = null
    private var resolvedUri: Uri? = null
    private var fallbackUri: Uri? = null
    private var totalFileLength: Long = C.LENGTH_UNSET.toLong()
    private var activeChunkSize: Long = 0L
    private var bootstrapCoverageEnd: Long = 0L

    private val scheduledRanges = ConcurrentHashMap<Long, Boolean>()
    private val frontierPromotionCounts = ConcurrentHashMap<Long, Int>()
    private val connectionOpenTimestamps = ArrayDeque<Long>()

    fun attachSession(
        store: AbsoluteByteStore,
        resolvedUri: Uri?,
        fallbackUri: Uri?,
        totalFileLength: Long,
        activeChunkSize: Long,
        bootstrapCoverageEnd: Long,
        preScheduledChunkIndexes: Collection<Long>
    ) {
        closed.set(false)
        this.store = store
        this.resolvedUri = resolvedUri
        this.fallbackUri = fallbackUri
        this.totalFileLength = totalFileLength
        this.activeChunkSize = activeChunkSize
        this.bootstrapCoverageEnd = bootstrapCoverageEnd
        scheduledRanges.clear()
        frontierPromotionCounts.clear()
        for (ci in preScheduledChunkIndexes) {
            scheduledRanges[ci] = true
        }
        scheduler?.cancelAll()
        scheduler = DualLaneScheduler(parallelConnections, 1)
    }

    fun detach() {
        closed.set(true)
        scheduler?.cancelAll()
        scheduler = null
        store = null
        resolvedUri = null
        fallbackUri = null
        totalFileLength = C.LENGTH_UNSET.toLong()
        activeChunkSize = 0L
        bootstrapCoverageEnd = 0L
        scheduledRanges.clear()
        frontierPromotionCounts.clear()
        synchronized(connectionOpenTimestamps) { connectionOpenTimestamps.clear() }
    }

    fun isAttached(): Boolean = scheduler != null && !closed.get()

    fun emitTransportObservation(responseHeaders: Map<String, List<String>>, uri: Uri?) {
        val host = uri?.host?.takeIf { it.isNotBlank() } ?: return
        val connectionHeader = responseHeaders.entries
            .firstOrNull { it.key.equals("Connection", ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.lowercase()
        val transportClass = if (connectionHeader?.contains("close") == true) {
            "connection_close"
        } else {
            "keep_alive"
        }
        val negotiatedProtocol = responseHeaders.entries
            .firstOrNull { it.key.equals("X-Android-Selected-Protocol", ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?: responseHeaders.entries
                .firstOrNull { it.key.equals("OkHttp-Selected-Protocol", ignoreCase = true) }
                ?.value
                ?.firstOrNull()
        onTransportObservation(
            RuntimeTransportObservation(
                hostScope = "host:$host",
                transportClass = transportClass,
                negotiatedProtocol = negotiatedProtocol,
                connectionHeader = connectionHeader
            )
        )
    }

    fun scheduleForReaderPosition(readerPosition: Long) {
        val sched = scheduler ?: return
        if (closed.get()) return
        val chunkSize = activeChunkSize
        if (chunkSize <= 0L) return
        promoteRanges(
            readerPosition = readerPosition,
            activeChunkSize = chunkSize,
            totalFileLength = totalFileLength,
            parallelConnections = parallelConnections,
            policy = transportPolicyProvider()
        ) { chunkIndex, start, end, urgent ->
            submitRange(sched, chunkIndex, start, end, urgent)
        }
    }

    /**
     * Translates "cursor is reading near byte N" into "promote the chunk covering N to
     * urgent, and queue a short read-ahead batch of prefetch chunks". Pure arithmetic
     * (no I/O, no state) — the caller's `submit` lambda hands ranges to the scheduler.
     */
    private fun promoteRanges(
        readerPosition: Long,
        activeChunkSize: Long,
        totalFileLength: Long,
        parallelConnections: Int,
        policy: TransportPolicy?,
        submit: (chunkIndex: Long, start: Long, end: Long, urgent: Boolean) -> Unit
    ) {
        val currentChunkIdx = readerPosition / activeChunkSize

        val urgentStart = currentChunkIdx * activeChunkSize
        val urgentEnd = if (totalFileLength != C.LENGTH_UNSET.toLong()) {
            minOf(urgentStart + activeChunkSize, totalFileLength)
        } else {
            urgentStart + activeChunkSize
        }
        if (urgentStart < urgentEnd) {
            submit(currentChunkIdx, urgentStart, urgentEnd, true)
        }

        // `PagedFrontierBuffer` only exposes bytes once the page covering them is fully
        // written, so if `chunkSize * (parallelConnections + 1)` < page size the reader
        // can deadlock: it can't advance past the current page until every chunk in that
        // page completes, and scheduling is position-driven. Always schedule enough chunks
        // to cover the current page plus one read-ahead chunk.
        val chunksPerPage = ((PagedFrontierBuffer.PAGE_SIZE + activeChunkSize - 1L) / activeChunkSize).toInt()
        val maxAhead = maxOf(parallelConnections + 1, chunksPerPage + 1)
        val urgentCount = policy?.urgentWorkers ?: maxAhead

        for (i in 1 until maxAhead) {
            val ci = currentChunkIdx + i
            val start = ci * activeChunkSize
            if (totalFileLength != C.LENGTH_UNSET.toLong() && start >= totalFileLength) break
            val end = if (totalFileLength != C.LENGTH_UNSET.toLong()) {
                minOf(start + activeChunkSize, totalFileLength)
            } else {
                start + activeChunkSize
            }
            submit(ci, start, end, i < urgentCount)
        }
    }

    private fun submitRange(
        sched: DualLaneScheduler,
        chunkIndex: Long,
        start: Long,
        end: Long,
        urgent: Boolean
    ) {
        if (scheduledRanges.putIfAbsent(chunkIndex, true) != null) return
        val s = store ?: return
        if (s.frontier >= end) return

        if (urgent) {
            sched.submitUrgent(start until end) { range ->
                downloadRange(range.first, range.last + 1)
            }
        } else {
            val submitted = sched.submitPrefetch(start until end) { range ->
                downloadRange(range.first, range.last + 1)
            }
            if (submitted == null) {
                scheduledRanges.remove(chunkIndex)
            }
        }
    }

    private fun downloadRange(start: Long, end: Long) {
        val s = store ?: return
        val chunkSize = activeChunkSize
        if (chunkSize <= 0L) return
        val chunkIndex = start / chunkSize
        val effectiveStart = maxOf(start, bootstrapCoverageEnd)
        if (effectiveStart >= end) return
        val expectedBytes = (end - effectiveStart).coerceAtLeast(0L).toInt()
        val readBuffer = ByteArray(READ_BUFFER_SIZE)
        var totalRead = 0
        var lastException: Exception? = null
        var transientAttemptNumber = 0
        var nonTransientAttemptNumber = 0

        while (!closed.get() && totalRead < expectedBytes) {
            val ds = upstreamFactory.createDataSource()
            try {
                awaitConnectionBudgetIfNeeded()
                val uri = resolvedUri ?: fallbackUri ?: throw IOException("No URI available")
                val spec = DataSpec.Builder()
                    .setUri(uri)
                    .setPosition(effectiveStart + totalRead)
                    .setLength((expectedBytes - totalRead).toLong())
                    .build()

                ds.open(spec)
                emitTransportObservation(ds.responseHeaders, ds.uri)
                while (!closed.get() && totalRead < expectedBytes) {
                    val maxRead = minOf(expectedBytes - totalRead, READ_BUFFER_SIZE)
                    if (maxRead <= 0) break
                    val read = ds.read(readBuffer, 0, maxRead)
                    if (read == C.RESULT_END_OF_INPUT) {
                        if (totalRead >= expectedBytes) break
                        throw EOFException("Unexpected end of range $chunkIndex after $totalRead / $expectedBytes bytes")
                    }
                    val offsetInChunk = (effectiveStart - start) + totalRead.toLong()
                    s.writeAt(effectiveStart + totalRead.toLong(), readBuffer, 0, read)
                    totalRead += read
                    val sampleTime = transportSampleTimeMs()
                    onTransportBytesDownloaded(read.toLong(), sampleTime)
                    onChunkBytesDownloaded(chunkIndex, chunkSize, offsetInChunk, read, sampleTime)
                    signalDataAvailable()
                }
                ds.close()
            } catch (e: Exception) {
                runCatching { ds.close() }
                if (closed.get()) return
                lastException = e
                val recoverable = e.isRecoverableChunkFailure()
                if (!recoverable) {
                    nonTransientAttemptNumber += 1
                    if (nonTransientAttemptNumber >= MAX_NON_TRANSIENT_CHUNK_ATTEMPTS) break
                } else {
                    transientAttemptNumber += 1
                    if (transientAttemptNumber >= MAX_TRANSIENT_CHUNK_ATTEMPTS) break
                }
                val allowedAttempts = if (recoverable) MAX_TRANSIENT_CHUNK_ATTEMPTS else MAX_NON_TRANSIENT_CHUNK_ATTEMPTS
                val attemptNumber = if (recoverable) transientAttemptNumber else nonTransientAttemptNumber
                val resumeOffset = totalRead
                if (e.isTransientInterruption()) {
                    Log.d(TAG, "Range $chunkIndex interrupted during prefetch at $resumeOffset bytes (attempt $attemptNumber), retrying")
                } else {
                    Log.w(
                        TAG,
                        "Range $chunkIndex download failed at $resumeOffset / $expectedBytes bytes (attempt $attemptNumber/$allowedAttempts), retrying: ${e.message}"
                    )
                }
                val delayMs = retryBackoffMs(attemptNumber, madeProgress = resumeOffset > 0)
                if (delayMs > 0L) {
                    try { Thread.sleep(delayMs) } catch (_: InterruptedException) { }
                }
                continue
            }
        }

        if (!closed.get() && totalRead < expectedBytes && lastException != null) {
            val chunkStart = chunkIndex * chunkSize
            val chunkEnd = chunkStart + chunkSize
            val currentFrontier = s.frontier
            val isFrontierBlocking = currentFrontier >= chunkStart && currentFrontier < chunkEnd
            val promotionCount = frontierPromotionCounts[chunkIndex] ?: 0

            if (isFrontierBlocking && promotionCount < MAX_FRONTIER_PROMOTIONS) {
                frontierPromotionCounts[chunkIndex] = promotionCount + 1
                scheduledRanges.remove(chunkIndex)
                Log.w(TAG, "Range $chunkIndex is frontier-blocking (frontier=$currentFrontier), " +
                    "re-promoting for urgent requeue (promotion ${promotionCount + 1}/$MAX_FRONTIER_PROMOTIONS)")
                signalDataAvailable()
                // Re-schedule using the current frontier as a proxy for the reader position;
                // the façade will re-drive us from the next read() anyway, but this keeps the
                // immediate requeue semantics of the pre-extraction code.
                scheduleForReaderPosition(currentFrontier)
            } else {
                val error = ChunkDownloadException(
                    chunkIndex = chunkIndex,
                    message = "Failed to download range $chunkIndex after retries",
                    cause = lastException
                )
                onTerminalError(error)
                signalDataAvailable()
                Log.e(TAG, "Range $chunkIndex failed to download completely after retries: ${lastException.message}")
            }
        }
        // The façade re-drives scheduleForReaderPosition() on every successful read(),
        // so the manager does NOT recurse here. The only self-reschedule that remains is
        // the frontier-promotion requeue above, which is required for correctness.
    }

    private fun Exception.isTransientInterruption(): Boolean {
        if (this is InterruptedIOException || this is InterruptedException) return true
        val cause = cause
        return cause is InterruptedIOException || cause is InterruptedException
    }

    private fun Exception.isRecoverableChunkFailure(): Boolean {
        if (isTransientInterruption()) return true
        if (this is SocketException || this is EOFException || this is ProtocolException) return true
        val messageText = message.orEmpty()
        if (messageText.contains("connection reset", ignoreCase = true) ||
            messageText.contains("connection closed", ignoreCase = true) ||
            messageText.contains("unexpected end of stream", ignoreCase = true) ||
            messageText.contains("broken pipe", ignoreCase = true)
        ) {
            return true
        }
        val cause = cause as? Exception ?: return false
        return cause.isRecoverableChunkFailure()
    }

    private fun retryBackoffMs(attemptNumber: Int, madeProgress: Boolean = false): Long {
        val connectionCloseMode = transportPolicyProvider()?.retryMode == RuntimeTransportRetryMode.CONNECTION_CLOSE
        if (connectionCloseMode) {
            return if (madeProgress) {
                when (attemptNumber) {
                    1 -> 250L
                    2 -> 500L
                    3 -> 1_000L
                    else -> 2_000L
                }
            } else {
                when (attemptNumber) {
                    1 -> 500L
                    2 -> 1_000L
                    3 -> 2_000L
                    else -> 4_000L
                }
            }
        }
        if (madeProgress) {
            return when (attemptNumber) {
                1 -> 0L
                2 -> 25L
                3 -> 50L
                else -> 100L
            }
        }
        return when (attemptNumber) {
            1 -> 50L
            2 -> 100L
            3 -> 200L
            else -> 250L
        }
    }

    private fun awaitConnectionBudgetIfNeeded() {
        val maxConnectionsPerSecond = transportPolicyProvider()?.connectionBudgetHint ?: return
        if (maxConnectionsPerSecond <= 0) return

        while (!closed.get()) {
            val now = SystemClock.elapsedRealtime()
            synchronized(connectionOpenTimestamps) {
                while (connectionOpenTimestamps.isNotEmpty() && now - connectionOpenTimestamps.first() >= 1_000L) {
                    connectionOpenTimestamps.removeFirst()
                }
                if (connectionOpenTimestamps.size < maxConnectionsPerSecond) {
                    connectionOpenTimestamps.addLast(now)
                    return
                }
            }
            try {
                Thread.sleep(25L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }
}
