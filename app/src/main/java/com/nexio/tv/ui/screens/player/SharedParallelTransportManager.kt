package com.nexio.tv.ui.screens.player

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nexio.tv.data.repository.benchmark.CapabilityEnvelope
import com.nexio.tv.instrumentation.EventFamily
import com.nexio.tv.instrumentation.PayloadBuilder
import com.nexio.tv.instrumentation.PlaybackTracer
import com.nexio.tv.instrumentation.RangeContext
import com.nexio.tv.instrumentation.RangeContextHolder
import com.nexio.tv.instrumentation.putRangeContext
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
    private val envelope: CapabilityEnvelope,
    private val transportSampleTimeMs: () -> Long,
    private val onTransportBytesDownloaded: (Long, Long) -> Unit,
    private val onChunkBytesDownloaded: (chunkIndex: Long, chunkSize: Long, offsetInChunk: Long, bytesRead: Int, sampleTimeMs: Long) -> Unit,
    private val onTransportObservation: (RuntimeTransportObservation) -> Unit,
    private val transportPolicyProvider: () -> TransportPolicy?,
    private val onTerminalError: (ChunkDownloadException) -> Unit,
    private val signalDataAvailable: () -> Unit,
    private val onStoreProgress: (lane: String, absolutePosition: Long, bytesWritten: Int, frontierAfter: Long) -> Unit = { _, _, _, _ -> },
    private val provider: String? = null
) {
    init {
        if (provider != null) {
            val locked = CapabilityEnvelope.lockedFor(provider)
            if (locked != null && !locked.matchesLockedShape(envelope)) {
                throw IllegalStateException("CapabilityEnvelope shape drift for provider=$provider")
            }
        }
    }

    companion object {
        private const val TAG = "SharedParallelXport"
        private const val READ_BUFFER_SIZE = 512 * 1024
        private const val BODY_PROGRESS_STEP_BYTES = 256 * 1024
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

    internal data class DebugSnapshot(
        val attached: Boolean,
        val pendingUrgentCount: Int,
        val scheduledRanges: Int,
        val frontierPromotions: Int,
        val bootstrapCoverageEnd: Long,
        val activeChunkSize: Long,
        val totalFileLength: Long,
    )

    private inline fun emitRangeEvent(type: String, crossinline build: PayloadBuilder.() -> Unit = {}) {
        if (!PlaybackTracer.enabled) return
        PlaybackTracer.emit(EventFamily.RANGE, type) {
            build()
        }
    }

    private inline fun emitRangeContextEvent(type: String, context: RangeContext, crossinline build: PayloadBuilder.() -> Unit = {}) {
        if (!PlaybackTracer.enabled) return
        PlaybackTracer.emit(EventFamily.RANGE, type) {
            putRangeContext(context)
            build()
        }
    }

    private fun rangeContext(
        chunkIndex: Long,
        lane: String,
        attempt: Int,
        requestStart: Long,
        requestEndExclusive: Long,
        chunkStart: Long,
        chunkEndExclusive: Long,
        expectedBytes: Int,
        resumeOffsetBytes: Int
    ): RangeContext = RangeContext(
        chunkIndex = chunkIndex,
        lane = lane,
        attempt = attempt,
        requestStart = requestStart,
        requestEndExclusive = requestEndExclusive,
        chunkStart = chunkStart,
        chunkEndExclusive = chunkEndExclusive,
        expectedBytes = expectedBytes,
        resumeOffsetBytes = resumeOffsetBytes
    )

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
        scheduler = DualLaneScheduler(envelope.maxSafeUrgentWorkers, envelope.maxSafePrefetchWorkers)
        emitRangeEvent("range_attach_session") {
            putString("resolvedHost", resolvedUri?.host)
            putString("fallbackHost", fallbackUri?.host)
            putLong("totalFileLength", totalFileLength)
            putLong("activeChunkSize", activeChunkSize)
            putLong("bootstrapCoverageEnd", bootstrapCoverageEnd)
            putInt("preScheduledChunks", preScheduledChunkIndexes.size)
            putInt("urgentWorkers", envelope.maxSafeUrgentWorkers)
            putInt("prefetchWorkers", envelope.maxSafePrefetchWorkers)
        }
        PlayerTransportTelemetry.log("sptm.attach", mapOf(
            "prefetchChunk" to envelope.maxSafePrefetchChunkBytes,
            "prefetchWorkers" to envelope.maxSafePrefetchWorkers,
            "providerGuard" to (provider != null),
            "urgentChunk" to envelope.maxSafeUrgentChunkBytes,
            "urgentWorkers" to envelope.maxSafeUrgentWorkers
        ))
    }

    fun detach() {
        emitRangeEvent("range_detach_session") {
            putLong("totalFileLength", totalFileLength)
            putLong("activeChunkSize", activeChunkSize)
            putInt("scheduledRanges", scheduledRanges.size)
            putInt("frontierPromotions", frontierPromotionCounts.size)
        }
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

    fun debugSnapshot(): DebugSnapshot = DebugSnapshot(
        attached = isAttached(),
        pendingUrgentCount = scheduler?.pendingUrgentCount ?: -1,
        scheduledRanges = scheduledRanges.size,
        frontierPromotions = frontierPromotionCounts.size,
        bootstrapCoverageEnd = bootstrapCoverageEnd,
        activeChunkSize = activeChunkSize,
        totalFileLength = totalFileLength,
    )

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
        emitRangeEvent("range_schedule_reader_position") {
            putLong("readerPosition", readerPosition)
            putLong("activeChunkSize", chunkSize)
            putLong("totalFileLength", totalFileLength)
        }
        promoteRanges(
            readerPosition = readerPosition,
            activeChunkSize = chunkSize,
            totalFileLength = totalFileLength,
            envelope = envelope,
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
        envelope: CapabilityEnvelope,
        policy: TransportPolicy?,
        submit: (chunkIndex: Long, start: Long, end: Long, urgent: Boolean) -> Unit
    ) {
        val currentChunkIdx = readerPosition / activeChunkSize
        val frontierPageSize = frontierPageSizeBytes()
        val chunksPerPageForLog = ((frontierPageSize + activeChunkSize - 1L) / activeChunkSize).toInt()
        val connectionCloseBonusForLog = if (
            policy?.retryMode == RuntimeTransportRetryMode.CONNECTION_CLOSE ||
            activeChunkSize >= 16L * 1024L * 1024L
        ) 1 else 0
        val maxAheadForLog = maxOf(envelope.maxSafeUrgentWorkers + 1, chunksPerPageForLog + 1) + connectionCloseBonusForLog
        val urgentCountForLog = policy?.urgentWorkers ?: envelope.maxSafeUrgentWorkers
        PlayerTransportTelemetry.logThrottled("sptm.promote", 1000L, mapOf(
            "maxAhead" to maxAheadForLog,
            "policy" to (policy?.javaClass?.simpleName ?: "null"),
            "prefetchWorkers" to envelope.maxSafePrefetchWorkers,
            "urgentCount" to urgentCountForLog,
            "urgentWorkers" to envelope.maxSafeUrgentWorkers
        ))
        emitRangeEvent("range_promote") {
            putLong("readerPosition", readerPosition)
            putLong("currentChunkIndex", currentChunkIdx)
            putInt("maxAhead", maxAheadForLog)
            putInt("urgentCount", urgentCountForLog)
            putString("policy", policy?.javaClass?.simpleName)
        }

        // `PagedFrontierBuffer` only exposes bytes once the page covering them is fully
        // written, so if `chunkSize * (urgentWorkers + 1)` < page size the reader
        // can deadlock: it can't advance past the current page until every chunk in that
        // page completes, and scheduling is position-driven. Always schedule enough chunks
        // to cover the current page plus one read-ahead chunk.
        //
        // For connection-close providers (e.g. Real-Debrid) every chunk boundary requires a
        // new TCP+TLS handshake. Queue one extra chunk ahead so the prefetch executor can
        // start that handshake before the current chunk finishes, hiding the latency.
        // Large chunk size (≥ 16 MiB) is the reliable signal: it always comes from a locked
        // envelope (LOCKED_REAL_DEBRID = 32 MiB, LOCKED_PREMIUMIZE = 16 MiB), and those
        // providers are the ones with connection-close behaviour. Using retryMode alone would
        // miss the baseline path where runtime hints haven't been collected yet.
        val chunksPerPage = ((frontierPageSize + activeChunkSize - 1L) / activeChunkSize).toInt()
        val connectionCloseBonus = if (
            policy?.retryMode == RuntimeTransportRetryMode.CONNECTION_CLOSE ||
            activeChunkSize >= 16L * 1024L * 1024L
        ) 1 else 0
        val maxAhead = maxOf(envelope.maxSafeUrgentWorkers + 1, chunksPerPage + 1) + connectionCloseBonus
        val urgentCount = policy?.urgentWorkers ?: envelope.maxSafeUrgentWorkers
        val currentFrontier = store?.frontier ?: 0L
        var remainingUrgentBudget = urgentCount

        for (i in 0 until maxAhead) {
            val ci = currentChunkIdx + i
            val start = ci * activeChunkSize
            if (totalFileLength != C.LENGTH_UNSET.toLong() && start >= totalFileLength) break
            val end = if (totalFileLength != C.LENGTH_UNSET.toLong()) {
                minOf(start + activeChunkSize, totalFileLength)
            } else {
                start + activeChunkSize
            }
            if (start >= end) continue

            // Assign urgent slots to the first *uncovered* chunks rather than to the
            // current chunk index unconditionally. With RD's single urgent worker,
            // bootstrap can fully cover the "current" chunk; if that still consumes the
            // only urgent slot, the next uncovered chunk is demoted to prefetch and the
            // reader waits for an atomic prefetch publish at the frontier boundary.
            val uncovered = end > currentFrontier
            val urgent = uncovered && remainingUrgentBudget > 0
            submit(ci, start, end, urgent)
            if (urgent) remainingUrgentBudget--
        }
    }

    private fun frontierPageSizeBytes(): Long {
        return if (
            envelope.matchesLockedShape(CapabilityEnvelope.LOCKED_REAL_DEBRID) ||
            envelope.matchesLockedShape(CapabilityEnvelope.LOCKED_PREMIUMIZE)
        ) {
            64L * 1024L
        } else {
            PagedFrontierBuffer.PAGE_SIZE.toLong()
        }
    }

    private fun nextBodyProgressMilestone(
        totalRead: Int,
        expectedBytes: Int,
        lastEmittedMilestone: Int
    ): Int? {
        if (expectedBytes <= 0) return null
        val milestone = when {
            totalRead >= expectedBytes -> Int.MAX_VALUE
            totalRead < BODY_PROGRESS_STEP_BYTES -> return null
            else -> totalRead / BODY_PROGRESS_STEP_BYTES
        }
        return if (milestone > lastEmittedMilestone) milestone else null
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
            emitRangeEvent("submit_urgent") {
                putLong("chunkIndex", chunkIndex)
                putLong("start", start)
                putLong("endExclusive", end)
                putLong("frontier", s.frontier)
            }
            sched.submitUrgent(start until end) { range ->
                downloadRange(range.first, range.last + 1)
            }
        } else {
            val prefetchChunkSize = transportPolicyProvider()?.prefetchChunkBytes
                ?.takeIf { it > 0L }
                ?: activeChunkSize
            emitRangeEvent("submit_prefetch") {
                putLong("chunkIndex", chunkIndex)
                putLong("start", start)
                putLong("endExclusive", end)
                putLong("frontier", s.frontier)
                putLong("prefetchChunkSize", prefetchChunkSize)
            }
            sched.submitPrefetch(start until end, chunkSize = prefetchChunkSize) { handle ->
                downloadRangeIntoScratch(handle)
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
        var lastAttemptContext: RangeContext? = null
        var completionEmitted = false
        var transientAttemptNumber = 0
        var nonTransientAttemptNumber = 0
        var lastBodyProgressMilestone = -1
        // At 75% we proactively schedule the next chunk so its TCP+TLS handshake can complete
        // before this one finishes — hides connection-close per-chunk connection setup latency.
        val prewarmAtBytes = (expectedBytes * 3) / 4
        var prewarmScheduled = false

        while (!closed.get() && totalRead < expectedBytes) {
            val ds = upstreamFactory.createDataSource()
            var attemptContext: RangeContext? = null
            try {
                awaitConnectionBudgetIfNeeded()
                val uri = resolvedUri ?: fallbackUri ?: throw IOException("No URI available")
                val attemptNumber = transientAttemptNumber + nonTransientAttemptNumber + 1
                val requestStart = effectiveStart + totalRead
                attemptContext = rangeContext(
                    chunkIndex = chunkIndex,
                    lane = "urgent",
                    attempt = attemptNumber,
                    requestStart = requestStart,
                    requestEndExclusive = effectiveStart + expectedBytes,
                    chunkStart = start,
                    chunkEndExclusive = end,
                    expectedBytes = expectedBytes,
                    resumeOffsetBytes = totalRead
                )
                lastAttemptContext = attemptContext
                val spec = DataSpec.Builder()
                    .setUri(uri)
                    .setPosition(requestStart)
                    .setLength((expectedBytes - totalRead).toLong())
                    .build()

                emitRangeContextEvent("range_start", attemptContext)
                RangeContextHolder.withContext(attemptContext) {
                    ds.open(spec)
                }
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
                    onStoreProgress(
                        "urgent",
                        effectiveStart + totalRead.toLong(),
                        read,
                        s.frontier
                    )
                    totalRead += read
                    val sampleTime = transportSampleTimeMs()
                    onTransportBytesDownloaded(read.toLong(), sampleTime)
                    onChunkBytesDownloaded(chunkIndex, chunkSize, offsetInChunk, read, sampleTime)
                    emitRangeContextEvent("range_http_body", attemptContext) {
                        putInt("bytesRead", read)
                        putInt("totalRead", totalRead)
                        putLong("offsetInChunk", offsetInChunk)
                        putLong("sampleTimeMs", sampleTime)
                    }
                    val nextProgressMilestone = nextBodyProgressMilestone(
                        totalRead = totalRead,
                        expectedBytes = expectedBytes,
                        lastEmittedMilestone = lastBodyProgressMilestone
                    )
                    if (nextProgressMilestone != null) {
                        lastBodyProgressMilestone = nextProgressMilestone
                        emitRangeContextEvent("range_http_body_progress", attemptContext) {
                            putInt("bytesRead", read)
                            putInt("totalRead", totalRead)
                            putInt("expectedBytes", expectedBytes)
                            putLong("offsetInChunk", offsetInChunk)
                            putLong("sampleTimeMs", sampleTime)
                        }
                    }
                    // At 75% through this chunk, kick off the next chunk's scheduling so its
                    // TCP+TLS handshake starts while there is still ~25% of this chunk left to
                    // download. The scheduling is idempotent (scheduledRanges dedup prevents
                    // double-submission) so this is safe to call mid-stream.
                    if (!prewarmScheduled && totalRead >= prewarmAtBytes) {
                        prewarmScheduled = true
                        scheduleForReaderPosition(end)
                    }
                    signalDataAvailable()
                }
                ds.close()
                if (totalRead >= expectedBytes) {
                    emitRangeContextEvent("range_done", attemptContext) {
                        putInt("totalRead", totalRead)
                    }
                    emitRangeContextEvent("range_finish", attemptContext) {
                        putString("result", "success")
                        putInt("totalRead", totalRead)
                        putInt("expectedBytes", expectedBytes)
                    }
                    completionEmitted = true
                }
            } catch (e: Exception) {
                runCatching { ds.close() }
                if (closed.get()) {
                    if (!completionEmitted && attemptContext != null) {
                        emitRangeContextEvent("range_finish", attemptContext) {
                            putString("result", "cancelled")
                            putInt("totalRead", totalRead)
                            putInt("expectedBytes", expectedBytes)
                        }
                        completionEmitted = true
                    }
                    return
                }
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
                attemptContext?.let { context ->
                    emitRangeContextEvent("range_retry", context) {
                        putInt("resumeOffset", resumeOffset)
                        putInt("allowedAttempts", allowedAttempts)
                        putBool("recoverable", recoverable)
                        putString("errorClass", e::class.java.name)
                        putString("errorMessage", e.message)
                    }
                }
                val delayMs = retryBackoffMs(attemptNumber, madeProgress = resumeOffset > 0)
                if (delayMs > 0L) {
                    try { Thread.sleep(delayMs) } catch (_: InterruptedException) { }
                }
                continue
            }
        }

        if (closed.get() && !completionEmitted && lastAttemptContext != null) {
            emitRangeContextEvent("range_finish", lastAttemptContext) {
                putString("result", "cancelled")
                putInt("totalRead", totalRead)
                putInt("expectedBytes", expectedBytes)
            }
            completionEmitted = true
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
                emitRangeEvent("range_frontier_requeue") {
                    putLong("chunkIndex", chunkIndex)
                    putString("lane", "urgent")
                    putLong("frontier", currentFrontier)
                    putInt("promotionCount", promotionCount + 1)
                }
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
                emitRangeEvent("range_failed") {
                    putLong("chunkIndex", chunkIndex)
                    putString("lane", "urgent")
                    putLong("frontier", currentFrontier)
                    putString("errorClass", lastException::class.java.name)
                    putString("errorMessage", lastException.message)
                }
                emitRangeEvent("range_finish") {
                    putLong("chunkIndex", chunkIndex)
                    putString("lane", "urgent")
                    putString("result", "failed")
                    putLong("frontier", currentFrontier)
                    putInt("totalRead", totalRead)
                    putInt("expectedBytes", expectedBytes)
                    putString("errorClass", lastException::class.java.name)
                    putString("errorMessage", lastException.message)
                }
                completionEmitted = true
                Log.e(TAG, "Range $chunkIndex failed to download completely after retries: ${lastException.message}")
            }
        }
        // The façade re-drives scheduleForReaderPosition() on every successful read(),
        // so the manager does NOT recurse here. The only self-reschedule that remains is
        // the frontier-promotion requeue above, which is required for correctness.
    }

    /**
     * Prefetch download path: mirrors [downloadRange] but accumulates bytes into
     * [handle.scratch] instead of streaming them directly into the store.
     *
     * On preemption ([PrefetchTaskHandle.preempted] set by [DualLaneScheduler.submitUrgent]):
     * - Throws [PreemptedException] to exit the download loop immediately.
     * - Does NOT increment the retry counter — preemption is not an error.
     * - [handle.totalRead] is preserved so the scheduler can re-issue a Range request
     *   at effectiveStart + totalRead for a byte-identical resume.
     *
     * On chunk completion, calls [AbsoluteByteStore.publishCompleteChunk] so the entire
     * chunk becomes visible to readers atomically (PagedFrontierBuffer invariant).
     */
    private fun downloadRangeIntoScratch(handle: PrefetchTaskHandle) {
        val s = store ?: return
        val chunkSize = activeChunkSize
        if (chunkSize <= 0L) return
        val start = handle.range.first
        val end = handle.range.last + 1
        val chunkIndex = start / chunkSize
        val effectiveStart = maxOf(start, bootstrapCoverageEnd)
        if (effectiveStart >= end) return
        val expectedBytes = (end - effectiveStart).coerceAtLeast(0L).toInt()
        val readBuffer = ByteArray(READ_BUFFER_SIZE)
        var lastException: Exception? = null
        var lastAttemptContext: RangeContext? = null
        var completionEmitted = false
        var transientAttemptNumber = 0
        var nonTransientAttemptNumber = 0
        var lastBodyProgressMilestone = -1
        val prewarmAtBytes = (expectedBytes * 3) / 4
        var prewarmScheduled = false

        while (!closed.get() && handle.totalRead < expectedBytes) {
            if (handle.preempted.get()) throw PreemptedException()
            val ds = upstreamFactory.createDataSource()
            var attemptContext: RangeContext? = null
            try {
                awaitConnectionBudgetIfNeeded()
                if (handle.preempted.get()) throw PreemptedException()
                val uri = resolvedUri ?: fallbackUri ?: throw IOException("No URI available")
                val attemptNumber = transientAttemptNumber + nonTransientAttemptNumber + 1
                val requestStart = effectiveStart + handle.totalRead
                attemptContext = rangeContext(
                    chunkIndex = chunkIndex,
                    lane = "prefetch",
                    attempt = attemptNumber,
                    requestStart = requestStart,
                    requestEndExclusive = effectiveStart + expectedBytes,
                    chunkStart = start,
                    chunkEndExclusive = end,
                    expectedBytes = expectedBytes,
                    resumeOffsetBytes = handle.totalRead
                )
                lastAttemptContext = attemptContext
                val spec = DataSpec.Builder()
                    .setUri(uri)
                    .setPosition(requestStart)
                    .setLength((expectedBytes - handle.totalRead).toLong())
                    .build()

                emitRangeContextEvent("range_start", attemptContext)
                RangeContextHolder.withContext(attemptContext) {
                    ds.open(spec)
                }
                emitTransportObservation(ds.responseHeaders, ds.uri)
                while (!closed.get() && handle.totalRead < expectedBytes) {
                    if (handle.preempted.get()) throw PreemptedException()
                    val maxRead = minOf(expectedBytes - handle.totalRead, READ_BUFFER_SIZE)
                    if (maxRead <= 0) break
                    val read = ds.read(readBuffer, 0, maxRead)
                    if (read == C.RESULT_END_OF_INPUT) {
                        if (handle.totalRead >= expectedBytes) break
                        throw EOFException("Unexpected end of range $chunkIndex after ${handle.totalRead} / $expectedBytes bytes")
                    }
                    System.arraycopy(readBuffer, 0, handle.scratch, handle.totalRead, read)
                    val offsetInChunk = (effectiveStart - start) + handle.totalRead.toLong()
                    handle.totalRead += read
                    val sampleTime = transportSampleTimeMs()
                    onTransportBytesDownloaded(read.toLong(), sampleTime)
                    onChunkBytesDownloaded(chunkIndex, chunkSize, offsetInChunk, read, sampleTime)
                    emitRangeContextEvent("range_http_body", attemptContext) {
                        putInt("bytesRead", read)
                        putInt("totalRead", handle.totalRead)
                        putLong("offsetInChunk", offsetInChunk)
                        putLong("sampleTimeMs", sampleTime)
                    }
                    val nextProgressMilestone = nextBodyProgressMilestone(
                        totalRead = handle.totalRead,
                        expectedBytes = expectedBytes,
                        lastEmittedMilestone = lastBodyProgressMilestone
                    )
                    if (nextProgressMilestone != null) {
                        lastBodyProgressMilestone = nextProgressMilestone
                        emitRangeContextEvent("range_http_body_progress", attemptContext) {
                            putInt("bytesRead", read)
                            putInt("totalRead", handle.totalRead)
                            putInt("expectedBytes", expectedBytes)
                            putLong("offsetInChunk", offsetInChunk)
                            putLong("sampleTimeMs", sampleTime)
                        }
                    }
                    if (!prewarmScheduled && handle.totalRead >= prewarmAtBytes) {
                        prewarmScheduled = true
                        scheduleForReaderPosition(end)
                    }
                }
                ds.close()
                if (handle.totalRead >= expectedBytes) {
                    emitRangeContextEvent("range_done", attemptContext) {
                        putInt("totalRead", handle.totalRead)
                    }
                    emitRangeContextEvent("range_finish", attemptContext) {
                        putString("result", "success")
                        putInt("totalRead", handle.totalRead)
                        putInt("expectedBytes", expectedBytes)
                    }
                    completionEmitted = true
                }
            } catch (e: PreemptedException) {
                runCatching { ds.close() }
                attemptContext?.let { context ->
                    emitRangeContextEvent("range_preempted", context) {
                        putInt("totalRead", handle.totalRead)
                    }
                    emitRangeContextEvent("range_finish", context) {
                        putString("result", "preempted")
                        putInt("totalRead", handle.totalRead)
                        putInt("expectedBytes", expectedBytes)
                    }
                }
                completionEmitted = true
                throw e  // Propagate without incrementing retry counters.
            } catch (e: Exception) {
                runCatching { ds.close() }
                if (handle.preempted.get()) throw PreemptedException()
                if (closed.get()) {
                    if (!completionEmitted && attemptContext != null) {
                        emitRangeContextEvent("range_finish", attemptContext) {
                            putString("result", "cancelled")
                            putInt("totalRead", handle.totalRead)
                            putInt("expectedBytes", expectedBytes)
                        }
                        completionEmitted = true
                    }
                    return
                }
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
                val resumeOffset = handle.totalRead
                if (e.isTransientInterruption()) {
                    Log.d(TAG, "Prefetch range $chunkIndex interrupted at $resumeOffset bytes (attempt $attemptNumber), retrying")
                } else {
                    Log.w(
                        TAG,
                        "Prefetch range $chunkIndex download failed at $resumeOffset / $expectedBytes bytes (attempt $attemptNumber/$allowedAttempts), retrying: ${e.message}"
                    )
                }
                attemptContext?.let { context ->
                    emitRangeContextEvent("range_retry", context) {
                        putInt("resumeOffset", resumeOffset)
                        putInt("allowedAttempts", allowedAttempts)
                        putBool("recoverable", recoverable)
                        putString("errorClass", e::class.java.name)
                        putString("errorMessage", e.message)
                    }
                }
                val delayMs = retryBackoffMs(attemptNumber, madeProgress = resumeOffset > 0)
                if (delayMs > 0L) {
                    try { Thread.sleep(delayMs) } catch (_: InterruptedException) { }
                }
                continue
            }
        }

        if (closed.get() && !completionEmitted && lastAttemptContext != null) {
            emitRangeContextEvent("range_finish", lastAttemptContext) {
                putString("result", "cancelled")
                putInt("totalRead", handle.totalRead)
                putInt("expectedBytes", expectedBytes)
            }
            completionEmitted = true
        }

        if (!closed.get() && handle.totalRead >= expectedBytes) {
            // Chunk complete — publish atomically so no reader observes partial state.
            s.publishCompleteChunk(effectiveStart, handle.scratch, handle.totalRead)
            onStoreProgress("prefetch", effectiveStart, handle.totalRead, s.frontier)
            emitRangeEvent("scratch_publish") {
                putLong("chunkIndex", chunkIndex)
                putString("lane", "prefetch")
                putLong("effectiveStart", effectiveStart)
                putInt("totalRead", handle.totalRead)
            }
            signalDataAvailable()
        } else if (!closed.get() && handle.totalRead < expectedBytes && lastException != null) {
            val chunkStart = chunkIndex * chunkSize
            val chunkEnd = chunkStart + chunkSize
            val currentFrontier = s.frontier
            val isFrontierBlocking = currentFrontier >= chunkStart && currentFrontier < chunkEnd
            val promotionCount = frontierPromotionCounts[chunkIndex] ?: 0

            if (isFrontierBlocking && promotionCount < MAX_FRONTIER_PROMOTIONS) {
                frontierPromotionCounts[chunkIndex] = promotionCount + 1
                scheduledRanges.remove(chunkIndex)
                Log.w(TAG, "Prefetch range $chunkIndex is frontier-blocking (frontier=$currentFrontier), " +
                    "re-promoting for urgent requeue (promotion ${promotionCount + 1}/$MAX_FRONTIER_PROMOTIONS)")
                emitRangeEvent("range_frontier_requeue") {
                    putLong("chunkIndex", chunkIndex)
                    putString("lane", "prefetch")
                    putLong("frontier", currentFrontier)
                    putInt("promotionCount", promotionCount + 1)
                }
                signalDataAvailable()
                scheduleForReaderPosition(currentFrontier)
            } else {
                val error = ChunkDownloadException(
                    chunkIndex = chunkIndex,
                    message = "Failed to download prefetch range $chunkIndex after retries",
                    cause = lastException
                )
                onTerminalError(error)
                signalDataAvailable()
                emitRangeEvent("range_failed") {
                    putLong("chunkIndex", chunkIndex)
                    putString("lane", "prefetch")
                    putLong("frontier", currentFrontier)
                    putString("errorClass", lastException::class.java.name)
                    putString("errorMessage", lastException.message)
                }
                emitRangeEvent("range_finish") {
                    putLong("chunkIndex", chunkIndex)
                    putString("lane", "prefetch")
                    putString("result", "failed")
                    putLong("frontier", currentFrontier)
                    putInt("totalRead", handle.totalRead)
                    putInt("expectedBytes", expectedBytes)
                    putString("errorClass", lastException::class.java.name)
                    putString("errorMessage", lastException.message)
                }
                completionEmitted = true
                Log.e(TAG, "Prefetch range $chunkIndex failed to download completely after retries: ${lastException.message}")
            }
        }
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
            var waitingOpenCount = 0
            var acquiredOpenCount = 0
            synchronized(connectionOpenTimestamps) {
                while (connectionOpenTimestamps.isNotEmpty() && now - connectionOpenTimestamps.first() >= 1_000L) {
                    connectionOpenTimestamps.removeFirst()
                }
                if (connectionOpenTimestamps.size < maxConnectionsPerSecond) {
                    connectionOpenTimestamps.addLast(now)
                    acquiredOpenCount = connectionOpenTimestamps.size
                } else {
                    waitingOpenCount = connectionOpenTimestamps.size
                }
            }
            if (acquiredOpenCount > 0) {
                emitRangeEvent("connection_budget_acquired") {
                    putInt("maxConnectionsPerSecond", maxConnectionsPerSecond)
                    putInt("openCount", acquiredOpenCount)
                }
                return
            }
            emitRangeEvent("connection_budget_wait") {
                putInt("maxConnectionsPerSecond", maxConnectionsPerSecond)
                putInt("openCount", waitingOpenCount)
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
