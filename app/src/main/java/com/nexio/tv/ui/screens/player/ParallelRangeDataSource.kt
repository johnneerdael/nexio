package com.nexio.tv.ui.screens.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.InterruptedIOException
import java.io.IOException
import java.net.ProtocolException
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import com.nexio.tv.data.local.PlayerSettings
import java.util.concurrent.atomic.AtomicBoolean
import android.os.SystemClock
import java.io.EOFException
import java.net.SocketException
import java.util.concurrent.locks.ReentrantLock

/**
 * A DataSource that downloads progressive files using multiple parallel HTTP range requests.
 *
 * Each individual TCP connection may be limited to ~100 Mbps (due to CDN per-connection limits
 * or Java/Okio networking overhead). By downloading different byte ranges in parallel across
 * multiple connections, we can multiply the effective throughput (e.g., 3 connections ≈ 300 Mbps).
 *
 * Uses a PagedFrontierBuffer for page-level streaming reads (no head-of-line blocking on full
 * chunk boundaries) and a DualLaneScheduler to prioritize urgent playback ranges over prefetch.
 *
 * Only used for progressive downloads (MKV, MP4). HLS/DASH already handle chunked parallel downloads.
 */
@UnstableApi
internal class ParallelRangeDataSource(
    private val upstreamFactory: OkHttpDataSource.Factory,
    private val parallelConnections: Int = PlayerSettings.DEFAULT_PARALLEL_CONNECTION_COUNT,
    private val chunkSize: Long = PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_MB.toLong() * 1024 * 1024,
    private val chunkWaitTimeoutMs: Long = DEFAULT_CHUNK_WAIT_TIMEOUT_MS,
    private val shouldAllowBackgroundPrefetch: () -> Boolean = { true },
    private val transportSampleTimeMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val onTransportBytesDownloaded: (Long, Long) -> Unit = { _, _ -> },
    private val onChunkBytesDownloaded: (chunkIndex: Long, chunkSize: Long, offsetInChunk: Long, bytesRead: Int, sampleTimeMs: Long) -> Unit = { _, _, _, _, _ -> },
    private val onResolvedUri: (Uri?) -> Unit = {},
    private val onTransportObservation: (RuntimeTransportObservation) -> Unit = {},
    private val onReadPositionAdvanced: (Long) -> Unit = {},
    private val consumeBootstrapCache: (DataSpec) -> BootstrapCacheEntry? = { null },
    private val updateBootstrapCache: (BootstrapCacheEntry?) -> Unit = {},
    private val transportPolicyProvider: () -> TransportPolicy? = { null }
) : DataSource {

    internal open class ChunkTransferException(
        val chunkIndex: Long,
        message: String,
        cause: Throwable? = null
    ) : IOException(message, cause)

    internal class ChunkWaitTimeoutException(
        chunkIndex: Long,
        timeoutMs: Long,
        cause: Throwable? = null
    ) : ChunkTransferException(
        chunkIndex = chunkIndex,
        message = "Timed out waiting ${timeoutMs}ms for chunk $chunkIndex",
        cause = cause
    )

    internal class ChunkDownloadException(
        chunkIndex: Long,
        message: String,
        cause: Throwable? = null
    ) : ChunkTransferException(
        chunkIndex = chunkIndex,
        message = message,
        cause = cause
    )

    companion object {
        private const val TAG = "ParallelRangeDS"
        private const val READ_BUFFER_SIZE = 512 * 1024 // 512KB read buffer for chunk downloads
        private const val BOOTSTRAP_READ_BYTES = 1L * 1024L * 1024L
        internal const val DEFAULT_CHUNK_WAIT_TIMEOUT_MS = 60_000L
        private const val MAX_TRANSIENT_CHUNK_ATTEMPTS = 4
        private const val MAX_NON_TRANSIENT_CHUNK_ATTEMPTS = 2
        internal const val USE_PAGED_TRANSPORT = true // Feature flag for rollback
        internal const val MAX_FRONTIER_PROMOTIONS = 2
    }

    internal data class BootstrapCacheEntry(
        val requestUri: Uri,
        val startPosition: Long,
        val resolvedUri: Uri?,
        val openLength: Long,
        val totalFileLength: Long,
        val bootstrapData: ByteArray,
        val bootstrapSize: Int,
        val createdAtUptimeMs: Long
    )

    private var resolvedUri: Uri? = null
    private var originalDataSpec: DataSpec? = null
    private var totalFileLength: Long = C.LENGTH_UNSET.toLong()
    private var position: Long = 0
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private val closed = AtomicBoolean(false)
    private var activeChunkSize: Long = chunkSize

    // Page-level frontier buffer — wrapped behind an AbsoluteByteStore façade (Phase 2).
    // The underlying PagedFrontierBuffer is unchanged; `store` is the seam that Phase 3
    // will route producers through.
    private var store: AbsoluteByteStore = PagedFrontierByteStore()
    private var openSession: OpenSession? = null
    private var cursor: SequentialReadCursor? = null
    private var scheduler: DualLaneScheduler? = null

    // Condition for readers waiting on data to arrive
    private val readLock = ReentrantLock()
    private val dataAvailable = readLock.newCondition()

    // Keep ~2 chunks behind the current position for backward seeks within the same open
    private val keepBehindBytes: Long
        get() = 2L * activeChunkSize

    // Tracks which chunk ranges have already been submitted (keyed by chunkIndex = start / chunkSize)
    private val scheduledRanges = ConcurrentHashMap<Long, Boolean>()

    // Tracks how many times a frontier-blocking chunk has been re-promoted after terminal failure
    private val frontierPromotionCounts = ConcurrentHashMap<Long, Int>()

    // Propagate download failures to the reader thread
    @Volatile private var lastDownloadError: Exception? = null

    // Bootstrap coverage: downloads must start AFTER this to avoid overlapping writes
    // from different CDN connections that may return slightly different bytes
    @Volatile private var bootstrapCoverageEnd: Long = 0L

    private var bootstrapPrefetchDeferred: Boolean = false
    private var bootstrapStartPosition: Long = C.TIME_UNSET
    private var continuationSource: OkHttpDataSource? = null
    private var continuationEndPositionExclusive: Long = C.TIME_UNSET

    private val transferListeners = mutableListOf<TransferListener>()
    private val connectionOpenTimestamps = ArrayDeque<Long>()

    // Fallback: if parallel mode fails, use a single upstream DataSource
    private var fallbackSource: OkHttpDataSource? = null

    override fun open(dataSpec: DataSpec): Long {
        closed.set(false)
        originalDataSpec = dataSpec
        position = dataSpec.position
        activeChunkSize = chunkSize
        bootstrapPrefetchDeferred = false
        bootstrapStartPosition = C.TIME_UNSET
        continuationSource?.close()
        continuationSource = null
        continuationEndPositionExclusive = C.TIME_UNSET

        // Cancel any in-flight work from a previous open (e.g., after seek)
        scheduler?.cancelAll()
        scheduler = null
        cursor?.close()
        cursor = null
        openSession = null
        store.reset()
        scheduledRanges.clear()
        frontierPromotionCounts.clear()
        lastDownloadError = null
        bootstrapCoverageEnd = 0L

        // Signal any thread that might be blocked in read() so it can observe closed state
        readLock.lock()
        try { dataAvailable.signalAll() } finally { readLock.unlock() }

        consumeBootstrapCache(dataSpec)?.let { cached ->
            resolvedUri = cached.resolvedUri
            onResolvedUri(resolvedUri)
            totalFileLength = cached.totalFileLength
            bytesRemaining = cached.openLength
            bootstrapStartPosition = cached.startPosition

            // Write bootstrap data into frontier buffer immediately
            store = PagedFrontierByteStore()
            if (cached.startPosition > 0L) store.setBasePosition(cached.startPosition)
            store.setTotalLength(totalFileLength)
            store.writeAt(cached.startPosition, cached.bootstrapData, 0, cached.bootstrapSize)
            bootstrapCoverageEnd = cached.startPosition + cached.bootstrapSize

            // Fire onChunkBytesDownloaded for cached bootstrap data
            val cachedSampleTime = transportSampleTimeMs()
            var cachedReportOffset = 0
            while (cachedReportOffset < cached.bootstrapSize) {
                val bci = (cached.startPosition + cachedReportOffset) / activeChunkSize
                val chunkStart = bci * activeChunkSize
                val offsetInChunk = (cached.startPosition + cachedReportOffset) - chunkStart
                val chunkEnd = chunkStart + activeChunkSize
                val reportSize = minOf(
                    cached.bootstrapSize - cachedReportOffset,
                    (chunkEnd - (cached.startPosition + cachedReportOffset)).toInt()
                )
                onChunkBytesDownloaded(bci, activeChunkSize, offsetInChunk, reportSize, cachedSampleTime)
                cachedReportOffset += reportSize
            }

            // Mark bootstrap chunks as scheduled ONLY if fully covered
            val bootstrapEndPos = cached.startPosition + cached.bootstrapSize
            var ci = cached.startPosition / activeChunkSize
            while (ci * activeChunkSize < bootstrapEndPos) {
                val chunkEnd = (ci + 1) * activeChunkSize
                if (chunkEnd <= bootstrapEndPos) {
                    scheduledRanges[ci] = true
                }
                ci++
            }

            bootstrapPrefetchDeferred = true
            scheduler = DualLaneScheduler(parallelConnections, 1)
            openSession = OpenSession(
                requestSpec = dataSpec,
                resolvedUri = resolvedUri,
                startPosition = cached.startPosition,
                openLength = cached.openLength,
                totalFileLength = totalFileLength,
                acceptsRanges = true,
                responseHeaders = emptyMap()
            )
            cursor = DefaultSequentialReadCursor(
                session = openSession!!,
                store = store,
                waitForBytes = ::waitForBytesAt,
                onPositionAdvanced = onReadPositionAdvanced,
                keepBehindBytes = keepBehindBytes,
                chunkWaitTimeoutMs = chunkWaitTimeoutMs
            )
            Log.d(
                TAG,
                "Reusing bootstrap window for immediate reopen at ${cached.startPosition}, " +
                    "file=${totalFileLength / 1024 / 1024}MB, resolved=${resolvedUri?.host}"
            )
            return cached.openLength
        }

        // Open first connection to determine total length and capture the resolved (redirected) URL
        val probeSource: OkHttpDataSource = upstreamFactory.createDataSource()
        transferListeners.forEach { probeSource.addTransferListener(it) }

        val openLength: Long
        try {
            openLength = probeSource.open(dataSpec)
            resolvedUri = probeSource.uri // Final URL after redirects (CDN URL)
            onResolvedUri(resolvedUri)
        } catch (e: Exception) {
            probeSource.close()
            throw e
        }

        // Check if we can do parallel range requests
        val responseHeaders = probeSource.responseHeaders
        emitTransportObservation(responseHeaders, resolvedUri)
        activeChunkSize = transportPolicyProvider()?.urgentChunkBytes ?: chunkSize
        val acceptsRanges = responseHeaders["Accept-Ranges"]?.any { it.contains("bytes") } == true ||
                responseHeaders["Content-Range"]?.isNotEmpty() == true

        if (openLength == C.LENGTH_UNSET.toLong() || !acceptsRanges) {
            // Can't determine length or server doesn't support ranges — reuse probe as single connection
            Log.w(TAG, "Falling back to single connection (length=${openLength}, acceptsRanges=$acceptsRanges)")
            fallbackSource = probeSource
            return openLength
        }

        totalFileLength = position + openLength
        bytesRemaining = openLength

        store = PagedFrontierByteStore()
        if (position > 0L) store.setBasePosition(position)
        store.setTotalLength(totalFileLength)
        scheduler = DualLaneScheduler(parallelConnections, 1)

        System.err.println("DIAG open() ${parallelConnections}conn ${activeChunkSize / 1024 / 1024}MB chunks " +
                "file=${totalFileLength / 1024 / 1024}MB pos=${position / 1024 / 1024}MB " +
                "bootstrap=${minOf(minOf(activeChunkSize, BOOTSTRAP_READ_BYTES), bytesRemaining).toInt() / 1024}KB " +
                "host=${resolvedUri?.host}")

        // Reuse a small probe window immediately for both startup and large seek reopens.
        val firstChunkIndex = position / activeChunkSize
        if (openLength > 0L) {
            val bootstrapBytes = minOf(minOf(activeChunkSize, BOOTSTRAP_READ_BYTES), openLength).toInt()
            val (bootstrapData, bootstrapSize) = readBootstrapChunk(probeSource, bootstrapBytes)
            bootstrapStartPosition = position

            // Write bootstrap data into frontier buffer
            store.writeAt(position, bootstrapData, 0, bootstrapSize)
            bootstrapCoverageEnd = position + bootstrapSize

            // Fire onChunkBytesDownloaded for bootstrap data so FrontierTracker (benchmark)
            // receives events for all downloaded bytes, not just background-fetched chunks
            val bootstrapSampleTime = transportSampleTimeMs()
            var bootstrapReportOffset = 0
            while (bootstrapReportOffset < bootstrapSize) {
                val ci = (position + bootstrapReportOffset) / activeChunkSize
                val chunkStart = ci * activeChunkSize
                val offsetInChunk = (position + bootstrapReportOffset) - chunkStart
                val chunkEnd = chunkStart + activeChunkSize
                val reportSize = minOf(
                    bootstrapSize - bootstrapReportOffset,
                    (chunkEnd - (position + bootstrapReportOffset)).toInt()
                )
                onChunkBytesDownloaded(ci, activeChunkSize, offsetInChunk, reportSize, bootstrapSampleTime)
                bootstrapReportOffset += reportSize
            }

            // Mark bootstrap chunks as already scheduled ONLY if fully covered by bootstrap.
            // If bootstrap only partially covers a chunk (e.g., 1MB bootstrap in an 8MB chunk),
            // the chunk must still be scheduled so downloadRange fetches the remaining bytes.
            val bootstrapEndPos = position + bootstrapSize
            var bci = position / activeChunkSize
            while (bci * activeChunkSize < bootstrapEndPos) {
                val chunkEnd = (bci + 1) * activeChunkSize
                if (chunkEnd <= bootstrapEndPos) {
                    scheduledRanges[bci] = true
                }
                bci++
            }

            // Avoid startup churn from immediate background fetches during repeated startup opens,
            // but do not redownload the active seek chunk from its start.
            bootstrapPrefetchDeferred = true
            if (position == 0L) {
                updateBootstrapCache(
                    BootstrapCacheEntry(
                        requestUri = dataSpec.uri,
                        startPosition = dataSpec.position,
                        resolvedUri = resolvedUri,
                        openLength = openLength,
                        totalFileLength = totalFileLength,
                        bootstrapData = bootstrapData,
                        bootstrapSize = bootstrapSize,
                        createdAtUptimeMs = SystemClock.uptimeMillis()
                    )
                )
                probeSource.close()
            } else {
                continuationSource = probeSource
                continuationEndPositionExclusive = minOf((firstChunkIndex + 1L) * activeChunkSize, totalFileLength)
            }
        } else {
            probeSource.close()
        }

        openSession = OpenSession(
            requestSpec = dataSpec,
            resolvedUri = resolvedUri,
            startPosition = position,
            openLength = openLength,
            totalFileLength = totalFileLength,
            acceptsRanges = acceptsRanges,
            responseHeaders = responseHeaders
        )
        cursor = DefaultSequentialReadCursor(
            session = openSession!!,
            store = store,
            waitForBytes = ::waitForBytesAt,
            onPositionAdvanced = onReadPositionAdvanced,
            keepBehindBytes = keepBehindBytes,
            chunkWaitTimeoutMs = chunkWaitTimeoutMs
        )
        return openLength
    }

    /**
     * Phase 2 stub: cursor's wait-for-bytes hook. Not routed through `read()` yet — installed
     * only so [DefaultSequentialReadCursor] can be constructed. The real sequential-read path
     * is still the inline loop inside [read]. Phase 3 will consolidate them.
     */
    private fun waitForBytesAt(position: Long, deadlineNanos: Long): WaitOutcome {
        readLock.lock()
        try {
            if (closed.get()) return WaitOutcome.CLOSED
            lastDownloadError?.let { return WaitOutcome.ERROR }
            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0L) return WaitOutcome.TIMEOUT
            dataAvailable.awaitNanos(remaining)
            if (closed.get()) return WaitOutcome.CLOSED
            if (lastDownloadError != null) return WaitOutcome.ERROR
            return WaitOutcome.DATA_AVAILABLE
        } finally {
            readLock.unlock()
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        // Fallback mode: delegate to single upstream
        fallbackSource?.let {
            val read = it.read(buffer, offset, length)
            if (read > 0) {
                onTransportBytesDownloaded(read.toLong(), transportSampleTimeMs())
                position += read
                bytesRemaining = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
                    C.LENGTH_UNSET.toLong()
                } else {
                    bytesRemaining - read
                }
                onReadPositionAdvanced(position)
            }
            return read
        }

        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val toRead = minOf(length.toLong(), bytesRemaining).toInt()

        if (bootstrapPrefetchDeferred && shouldAllowBackgroundPrefetch()) {
            bootstrapPrefetchDeferred = false
            scheduleChunks()
        }

        continuationSource?.let { source ->
            val bootstrapEnd = if (bootstrapStartPosition != C.TIME_UNSET) {
                // frontier already includes bootstrap bytes; use frontier as the boundary
                store.frontier
            } else {
                0L
            }
            if (position < continuationEndPositionExclusive &&
                bytesRemaining > 0L &&
                position >= bootstrapEnd
            ) {
                val read = source.read(buffer, offset, toRead)
                if (read > 0) {
                    onTransportBytesDownloaded(read.toLong(), transportSampleTimeMs())
                    // Also write into frontier buffer so frontier advances for later reads
                    store.writeAt(position, buffer, offset, read)
                    readLock.lock()
                    try { dataAvailable.signalAll() } finally { readLock.unlock() }
                    position += read
                    bytesRemaining -= read
                    onReadPositionAdvanced(position)
                    if (position >= continuationEndPositionExclusive) {
                        source.close()
                        continuationSource = null
                        continuationEndPositionExclusive = C.TIME_UNSET
                        scheduleChunks()
                    }
                    return read
                }
                if (read == C.RESULT_END_OF_INPUT || position >= continuationEndPositionExclusive) {
                    source.close()
                    continuationSource = null
                    continuationEndPositionExclusive = C.TIME_UNSET
                    scheduleChunks()
                }
            } else if (position >= continuationEndPositionExclusive || bytesRemaining <= 0L) {
                source.close()
                continuationSource = null
                continuationEndPositionExclusive = C.TIME_UNSET
            }
        }

        // Try to read from frontier buffer
        System.err.println("DIAG pre-read pos=$position frontier=${store.frontier} remaining=$bytesRemaining cont=${continuationSource != null} contEnd=$continuationEndPositionExclusive bsDeferred=$bootstrapPrefetchDeferred prefetchOk=${shouldAllowBackgroundPrefetch()}")
        val bytesRead = store.read(position, buffer, offset, toRead)
        if (bytesRead > 0) {
            if (position < 2L * 1024L * 1024L || (totalFileLength > 0 && position > totalFileLength - 2L * 1024L * 1024L)) {
                val preview = ByteArray(minOf(16, bytesRead))
                System.arraycopy(buffer, offset, preview, 0, preview.size)
                System.err.println("DIAG read() pos=$position len=$bytesRead frontier=${store.frontier} first16=${preview.joinToString(",") { (it.toInt() and 0xFF).toString(16) }}")
            }
            position += bytesRead
            bytesRemaining -= bytesRead
            onReadPositionAdvanced(position)
            store.evictBefore(position - keepBehindBytes)
            scheduleChunks()
            return bytesRead
        }

        // No data available — ensure downloads are scheduled and wait
        scheduleChunks()
        readLock.lock()
        try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(chunkWaitTimeoutMs)
            while (!closed.get()) {
                // Check for download errors
                lastDownloadError?.let { error ->
                    lastDownloadError = null
                    throw error
                }
                val available = store.read(position, buffer, offset, toRead)
                if (available > 0) {
                    position += available
                    bytesRemaining -= available
                    onReadPositionAdvanced(position)
                    store.evictBefore(position - keepBehindBytes)
                    return available
                }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    throw ChunkWaitTimeoutException(
                        chunkIndex = position / activeChunkSize,
                        timeoutMs = chunkWaitTimeoutMs
                    )
                }
                // Re-schedule: prefetch may have been rejected by admission control earlier
                readLock.unlock()
                try { scheduleChunks() } finally { readLock.lock() }
                dataAvailable.awaitNanos(remaining)
            }
        } finally {
            readLock.unlock()
        }
        return C.RESULT_END_OF_INPUT
    }

    private fun scheduleChunks() {
        val sched = scheduler ?: return

        val currentPos =
            if (continuationSource != null && continuationEndPositionExclusive != C.TIME_UNSET && position < continuationEndPositionExclusive) {
                continuationEndPositionExclusive
            } else {
                position
            }

        val currentChunkIdx = currentPos / activeChunkSize

        // ALWAYS schedule the chunk at the current read position (urgent).
        // This ensures read() never blocks waiting for data that was never requested.
        // The old code called ensureChunkScheduled() for the current chunk unconditionally.
        val urgentStart = currentChunkIdx * activeChunkSize
        val urgentEnd = if (totalFileLength != C.LENGTH_UNSET.toLong()) {
            minOf(urgentStart + activeChunkSize, totalFileLength)
        } else {
            urgentStart + activeChunkSize
        }
        if (urgentStart < urgentEnd) {
            submitRange(sched, currentChunkIdx, urgentStart, urgentEnd, urgent = true)
        }

        // No gate on ahead chunks — first frame can't render until ExoPlayer parses
        // header + Cues, which requires reading from this DataSource. Gating ahead chunks
        // on first frame creates a deadlock.

        val maxAhead = parallelConnections + 1
        val policy = transportPolicyProvider()
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
            submitRange(sched, ci, start, end, urgent = i < urgentCount)
        }
    }

    private fun submitRange(
        sched: DualLaneScheduler,
        chunkIndex: Long,
        start: Long,
        end: Long,
        urgent: Boolean
    ) {
        // Skip if this range has already been scheduled
        if (scheduledRanges.putIfAbsent(chunkIndex, true) != null) return
        // Skip if the frontier has already passed this range
        if (store.frontier >= end) return

        if (urgent) {
            sched.submitUrgent(start until end) { range ->
                downloadRange(range.first, range.last + 1)
            }
        } else {
            val submitted = sched.submitPrefetch(start until end) { range ->
                downloadRange(range.first, range.last + 1)
            }
            if (submitted == null) {
                // Prefetch was back-pressure dropped; allow re-scheduling later
                scheduledRanges.remove(chunkIndex)
            }
        }
    }

    private fun downloadRange(start: Long, end: Long) {
        val chunkIndex = start / activeChunkSize
        // Skip bootstrap-covered bytes to avoid overlapping writes from different CDN
        // connections that may return slightly different bytes for the same range.
        // bootstrapCoverageEnd is set once in open() and never changes — no race condition.
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
                val uri = resolvedUri ?: originalDataSpec?.uri ?: throw IOException("No URI available")
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
                    // Write into frontier buffer as bytes arrive (streaming)
                    store.writeAt(effectiveStart + totalRead.toLong(), readBuffer, 0, read)
                    totalRead += read
                    val sampleTime = transportSampleTimeMs()
                    onTransportBytesDownloaded(read.toLong(), sampleTime)
                    onChunkBytesDownloaded(chunkIndex, activeChunkSize, offsetInChunk, read, sampleTime)
                    // Signal any waiting readers
                    readLock.lock()
                    try { dataAvailable.signalAll() } finally { readLock.unlock() }
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
            // Check if this failed chunk is blocking the contiguous frontier.
            // If so, re-promote it for urgent requeue instead of immediately failing playback.
            val chunkStart = chunkIndex * activeChunkSize
            val chunkEnd = chunkStart + activeChunkSize
            val currentFrontier = store.frontier
            // A chunk is frontier-blocking if the contiguous frontier sits inside this
            // chunk's range — meaning this chunk must complete to advance the frontier.
            val isFrontierBlocking = currentFrontier >= chunkStart && currentFrontier < chunkEnd
            val promotionCount = frontierPromotionCounts[chunkIndex] ?: 0

            if (isFrontierBlocking && promotionCount < MAX_FRONTIER_PROMOTIONS) {
                frontierPromotionCounts[chunkIndex] = promotionCount + 1
                scheduledRanges.remove(chunkIndex)
                Log.w(TAG, "Range $chunkIndex is frontier-blocking (frontier=$currentFrontier), " +
                    "re-promoting for urgent requeue (promotion ${promotionCount + 1}/$MAX_FRONTIER_PROMOTIONS)")
                // Signal waiting readers and re-schedule so the chunk gets re-submitted as urgent
                readLock.lock()
                try { dataAvailable.signalAll() } finally { readLock.unlock() }
                scheduleChunks()
            } else {
                // Propagate error to waiting reader
                lastDownloadError = ChunkDownloadException(
                    chunkIndex = chunkIndex,
                    message = "Failed to download range $chunkIndex after retries",
                    cause = lastException
                )
                readLock.lock()
                try { dataAvailable.signalAll() } finally { readLock.unlock() }
                Log.e(TAG, "Range $chunkIndex failed to download completely after retries: ${lastException.message}")
            }
        }

        // After completing a range, schedule more work (prefetch may have been rejected earlier
        // by admission control while urgent was pending — now that this range is done, retry)
        if (!closed.get()) {
            scheduleChunks()
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
                return
            }
        }
    }

    /** Read only a small startup window from an already-opened DataSource. Returns data + size. */
    private fun readBootstrapChunk(ds: DataSource, maxBytes: Int): Pair<ByteArray, Int> {
        val buf = ByteArray(maxBytes)
        var totalRead = 0
        try {
            while (!closed.get() && totalRead < buf.size) {
                val maxRead = minOf(buf.size - totalRead, READ_BUFFER_SIZE)
                if (maxRead <= 0) break
                val read = ds.read(buf, totalRead, maxRead)
                if (read == C.RESULT_END_OF_INPUT) break
                totalRead += read
                onTransportBytesDownloaded(read.toLong(), transportSampleTimeMs())
            }
        } catch (e: Exception) {
            if (closed.get()) throw IOException("DataSource closed")
            throw e
        }
        if (closed.get()) throw IOException("DataSource closed")
        return Pair(buf, totalRead)
    }

    override fun close() {
        closed.set(true)
        cursor?.close()
        cursor = null
        openSession = null
        fallbackSource?.close()
        fallbackSource = null
        continuationSource?.close()
        continuationSource = null
        continuationEndPositionExclusive = C.TIME_UNSET

        scheduler?.cancelAll()
        scheduler = null
        store.reset()
        scheduledRanges.clear()
        frontierPromotionCounts.clear()

        // Signal any waiting readers
        readLock.lock()
        try { dataAvailable.signalAll() } finally { readLock.unlock() }
    }

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners.add(transferListener)
    }

    override fun getUri(): Uri? =
        openSession?.resolvedUri ?: resolvedUri ?: fallbackSource?.uri

    override fun getResponseHeaders(): Map<String, List<String>> {
        val sessionHeaders = openSession?.responseHeaders
        if (sessionHeaders != null && sessionHeaders.isNotEmpty()) return sessionHeaders
        return fallbackSource?.responseHeaders ?: emptyMap()
    }

    private fun emitTransportObservation(responseHeaders: Map<String, List<String>>, uri: Uri?) {
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

    /**
     * Factory for creating ParallelRangeDataSource instances.
     */
    class Factory(
        private val upstreamFactory: OkHttpDataSource.Factory,
        private val parallelConnections: Int = PlayerSettings.DEFAULT_PARALLEL_CONNECTION_COUNT,
        private val chunkSize: Long = PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_MB.toLong() * 1024 * 1024,
        private val chunkWaitTimeoutMs: Long = DEFAULT_CHUNK_WAIT_TIMEOUT_MS,
        private val shouldAllowBackgroundPrefetch: () -> Boolean = { true },
        private val transportSampleTimeMs: () -> Long = { SystemClock.elapsedRealtime() },
        private val onTransportBytesDownloaded: (Long, Long) -> Unit = { _, _ -> },
        private val onChunkBytesDownloaded: (chunkIndex: Long, chunkSize: Long, offsetInChunk: Long, bytesRead: Int, sampleTimeMs: Long) -> Unit = { _, _, _, _, _ -> },
        private val onResolvedUri: (Uri?) -> Unit = {},
        private val onTransportObservation: (RuntimeTransportObservation) -> Unit = {},
        private val onReadPositionAdvanced: (Long) -> Unit = {},
        private val allowStartupBootstrapReuse: Boolean = true,
        private val transportPolicyProvider: () -> TransportPolicy? = { null }
    ) : DataSource.Factory {
        @Volatile
        private var startupBootstrapCache: BootstrapCacheEntry? = null

        override fun createDataSource(): DataSource {
            return ParallelRangeDataSource(
                upstreamFactory = upstreamFactory,
                parallelConnections = parallelConnections,
                chunkSize = chunkSize,
                chunkWaitTimeoutMs = chunkWaitTimeoutMs,
                shouldAllowBackgroundPrefetch = shouldAllowBackgroundPrefetch,
                transportSampleTimeMs = transportSampleTimeMs,
                onTransportBytesDownloaded = onTransportBytesDownloaded,
                onChunkBytesDownloaded = onChunkBytesDownloaded,
                onResolvedUri = onResolvedUri,
                onTransportObservation = onTransportObservation,
                onReadPositionAdvanced = onReadPositionAdvanced,
                transportPolicyProvider = transportPolicyProvider,
                consumeBootstrapCache = { dataSpec ->
                    if (!allowStartupBootstrapReuse) {
                        return@ParallelRangeDataSource null
                    }
                    val cached = startupBootstrapCache ?: return@ParallelRangeDataSource null
                    val isFresh = SystemClock.uptimeMillis() - cached.createdAtUptimeMs <= 15_000L
                    if (!isFresh) {
                        startupBootstrapCache = null
                        return@ParallelRangeDataSource null
                    }
                    if (cached.startPosition != 0L || dataSpec.position != 0L) return@ParallelRangeDataSource null
                    if (dataSpec.position != cached.startPosition) return@ParallelRangeDataSource null
                    if (dataSpec.uri != cached.requestUri) return@ParallelRangeDataSource null
                    cached
                },
                updateBootstrapCache = { entry ->
                    startupBootstrapCache = entry
                }
            )
        }
    }
}
