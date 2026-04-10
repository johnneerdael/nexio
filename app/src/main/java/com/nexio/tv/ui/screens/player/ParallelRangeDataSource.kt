package com.nexio.tv.ui.screens.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.IOException
import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.repository.benchmark.CapabilityEnvelope
import com.nexio.tv.instrumentation.EventFamily
import com.nexio.tv.instrumentation.PlaybackTracer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import android.os.SystemClock
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
    private val envelope: CapabilityEnvelope = CapabilityEnvelope.DEFAULT,
    private val parallelConnections: Int = envelope.maxSafeUrgentWorkers,
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
    private val transportPolicyProvider: () -> TransportPolicy? = { null },
    private val onTransportSessionAttachedForTest: () -> Unit = {},
    private val onStoreResetForTest: () -> Unit = {}
) : BaseDataSource(/* isNetwork = */ true) {

    companion object {
        private const val TAG = "ParallelRangeDS"
        private const val READ_BUFFER_SIZE = 512 * 1024 // 512KB read buffer for chunk downloads
        internal const val DEFAULT_CHUNK_WAIT_TIMEOUT_MS = 60_000L
        private const val STARTUP_WATCHDOG_DELAY_MS = 5_000L
        private const val CONNECTION_CLOSE_FRONTIER_PAGE_SIZE = 64 * 1024
        private const val READ_RETURN_TRACE_MIN_CALLS = 32
        private const val READ_RETURN_TRACE_MIN_INTERVAL_MS = 500L
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

    private enum class ReopenCause(val traceValue: String) {
        COLD_OPEN("cold_open"),
        CONTINUATION_REOPEN("continuation_reopen"),
        SEEK_LIKE_REOPEN("seek_like_reopen"),
        EOF_PROBE("eof_probe")
    }

    private var resolvedUri: Uri? = null
    private var originalDataSpec: DataSpec? = null
    private var totalFileLength: Long = C.LENGTH_UNSET.toLong()
    private var position: Long = 0
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private val closed = AtomicBoolean(false)
    private val readerClosed = AtomicBoolean(false)
    private var activeChunkSize: Long = chunkSize

    // Page-level frontier buffer wrapped behind an AbsoluteByteStore façade. All byte
    // producers (bootstrap reuse, continuation pump, fallback pump, parallel range workers)
    // publish through `store`; the sequential read cursor is the sole reader.
    private var store: AbsoluteByteStore = PagedFrontierByteStore(
        PagedFrontierBuffer(frontierPageSizeBytes())
    )
    private var openSession: OpenSession? = null
    private var cursor: SequentialReadCursor? = null

    // Condition for readers waiting on data to arrive
    private val readLock = ReentrantLock()
    private val dataAvailable = readLock.newCondition()

    // Keep ~2 chunks behind the current position for backward seeks within the same open
    private val keepBehindBytes: Long
        get() = 2L * activeChunkSize

    // Propagate download failures to the reader thread
    @Volatile private var lastDownloadError: Exception? = null

    // Parallel transport engine (scheduling / retries / backoff / connection budget /
    // frontier promotion) lives here. PRDS keeps only the Media3 façade + bootstrap/pumps.
    private val transportManager = SharedParallelTransportManager(
        upstreamFactory = upstreamFactory,
        envelope = envelope,
        transportSampleTimeMs = transportSampleTimeMs,
        onTransportBytesDownloaded = onTransportBytesDownloaded,
        onChunkBytesDownloaded = onChunkBytesDownloaded,
        onTransportObservation = onTransportObservation,
        transportPolicyProvider = transportPolicyProvider,
        onTerminalError = { err ->
            // Producer errors land in `lastDownloadError` so `readInternal()` can surface
            // them to Media3 as the typed `ChunkDownloadException` instead of the cursor's
            // generic wait-error wrapper.
            lastDownloadError = err
        },
        signalDataAvailable = {
            readLock.lock()
            try { dataAvailable.signalAll() } finally { readLock.unlock() }
        },
        onStoreProgress = { lane, absolutePosition, bytesWritten, frontierAfter ->
            if (firstStoreProgressLogged.compareAndSet(false, true)) {
                logStartupStage(
                    "first_store_progress",
                    mapOf(
                        "absolutePosition" to absolutePosition,
                        "bytesWritten" to bytesWritten,
                        "frontierAfter" to frontierAfter,
                        "lane" to lane
                    )
                )
            }
            maybeLogFirstFrontierAdvance(lane, frontierAfter)
        }
    )

    private fun logStartupStage(stage: String, pairs: Map<String, Any?> = emptyMap()) {
        val openStarted = openStartedAtMs.get()
        val elapsedMs = if (openStarted > 0L) {
            (SystemClock.elapsedRealtime() - openStarted).coerceAtLeast(0L)
        } else null
        PlayerTransportTelemetry.log(
            "prds.startup.$stage",
            linkedMapOf(
                "activeChunk" to activeChunkSize,
                "bytesRemaining" to bytesRemaining,
                "elapsedMs" to elapsedMs,
                "frontier" to store.frontier,
                "position" to position
            ) + pairs
        )
    }

    private fun maybeLogFirstFrontierAdvance(source: String, frontierAfter: Long) {
        val startPosition = openSession?.startPosition ?: position
        if (frontierAfter <= startPosition) return
        if (!firstFrontierAdvanceLogged.compareAndSet(false, true)) return
        logStartupStage(
            "first_frontier_advance",
            mapOf("frontierAfter" to frontierAfter, "source" to source, "startPosition" to startPosition)
        )
    }

    private fun maybeLogFirstReadableBytes(source: String) {
        val readable = store.readableContiguousBytesFrom(position)
        if (readable <= 0L) return
        if (!firstReadableBytesLogged.compareAndSet(false, true)) return
        logStartupStage(
            "first_readable_bytes",
            mapOf("readableBytes" to readable, "source" to source)
        )
    }

    private fun maybeLogFirstReadReturn(read: Int) {
        if (read <= 0) return
        if (!firstReadReturnLogged.compareAndSet(false, true)) return
        logStartupStage("first_read_return", mapOf("read" to read))
    }

    private fun startStartupWatchdog() {
        startupWatchdogThread?.interrupt()
        val thread = Thread({
            try {
                Thread.sleep(STARTUP_WATCHDOG_DELAY_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (closed.get()) return@Thread
            if (firstReadReturnLogged.get()) return@Thread
            val snapshot = transportManager.debugSnapshot()
            logStartupStage(
                "watchdog",
                mapOf(
                    "continuationActive" to (continuationSource != null),
                    "fallbackActive" to (fallbackSource != null),
                    "lastDownloadError" to lastDownloadError?.javaClass?.name,
                    "lastDownloadErrorMessage" to lastDownloadError?.message,
                    "readableBytes" to store.readableContiguousBytesFrom(position),
                    "transportActiveChunk" to snapshot.activeChunkSize,
                    "transportAttached" to snapshot.attached,
                    "transportBootstrapCoverageEnd" to snapshot.bootstrapCoverageEnd,
                    "transportFrontierPromotions" to snapshot.frontierPromotions,
                    "transportPendingUrgent" to snapshot.pendingUrgentCount,
                    "transportScheduledRanges" to snapshot.scheduledRanges,
                    "transportTotalFileLength" to snapshot.totalFileLength
                )
            )
        }, "PRDS-StartupWatchdog")
        thread.isDaemon = true
        startupWatchdogThread = thread
        thread.start()
    }

    /**
     * Translates the reader's current position into a transport scheduling hint.
     * The scheduler must always see the real reader position; continuation-window
     * visibility is emitted separately so observability never mutates scheduling
     * input.
     */
    private fun scheduleFromCursor() {
        if (!transportManager.isAttached()) return
        val chunkSizeForSchedule = activeChunkSize.takeIf { it > 0L } ?: return
        val readerChunkIndex = position / chunkSizeForSchedule
        val currentFrontier = store.frontier
        val frontierChunkIndex = if (currentFrontier <= 0L) {
            0L
        } else {
            (currentFrontier - 1L) / chunkSizeForSchedule
        }
        val continuationWindowEnd = if (continuationSource != null) {
            continuationEndPositionExclusive
        } else {
            C.TIME_UNSET
        }
        if (
            readerChunkIndex == lastScheduledReaderChunkIndex &&
            frontierChunkIndex == lastScheduledFrontierChunkIndex &&
            continuationWindowEnd == lastScheduledContinuationWindowEndExclusive
        ) {
            return
        }
        transportManager.scheduleForReaderPosition(position)
        lastScheduledReaderChunkIndex = readerChunkIndex
        lastScheduledFrontierChunkIndex = frontierChunkIndex
        lastScheduledContinuationWindowEndExclusive = continuationWindowEnd
        emitContinuationWindowObservation()
    }

    private fun emitContinuationWindowObservation() {
        val endPositionExclusive = continuationEndPositionExclusive
        if (continuationSource == null || endPositionExclusive == C.TIME_UNSET) return
        if (!PlaybackTracer.enabled) return
        PlaybackTracer.emit(EventFamily.PRDS, "continuation_window") {
            putLong("readerPosition", position)
            putLong("continuationEndPositionExclusive", endPositionExclusive)
        }
    }

    /**
     * Reports a contiguous run of `[startPosition, startPosition + size)` bytes to
     * `onChunkBytesDownloaded`, splitting the report on chunk boundaries so the
     * benchmark frontier tracker sees one event per chunk segment.
     */
    private fun reportBootstrapChunkBytes(startPosition: Long, size: Int) {
        if (size <= 0) return
        val sampleTime = transportSampleTimeMs()
        var offset = 0
        while (offset < size) {
            val ci = (startPosition + offset) / activeChunkSize
            val chunkStart = ci * activeChunkSize
            val offsetInChunk = (startPosition + offset) - chunkStart
            val chunkEnd = chunkStart + activeChunkSize
            val reportSize = minOf(
                size - offset,
                (chunkEnd - (startPosition + offset)).toInt()
            )
            onChunkBytesDownloaded(ci, activeChunkSize, offsetInChunk, reportSize, sampleTime)
            offset += reportSize
        }
    }

    private fun bootstrapReadBytes(clampedOpenLength: Long): Int {
        val providerBootstrapBytes = when {
            envelope.matchesLockedShape(CapabilityEnvelope.LOCKED_REAL_DEBRID) -> 32L * 1024L * 1024L
            envelope.matchesLockedShape(CapabilityEnvelope.LOCKED_PREMIUMIZE) -> 16L * 1024L * 1024L
            else -> 24L * 1024L * 1024L
        }
        // Keep the bootstrap window bounded by the active chunk size and the clamped open
        // length so the first read never exceeds the current transport window.
        return minOf(activeChunkSize, providerBootstrapBytes, clampedOpenLength).toInt()
    }

    private fun frontierPageSizeBytes(): Int {
        return if (
            envelope.matchesLockedShape(CapabilityEnvelope.LOCKED_REAL_DEBRID) ||
            envelope.matchesLockedShape(CapabilityEnvelope.LOCKED_PREMIUMIZE)
        ) {
            CONNECTION_CLOSE_FRONTIER_PAGE_SIZE
        } else {
            PagedFrontierBuffer.PAGE_SIZE
        }
    }

    // Bootstrap coverage: downloads must start AFTER this to avoid overlapping writes
    // from different CDN connections that may return slightly different bytes
    @Volatile private var bootstrapCoverageEnd: Long = 0L

    private var bootstrapPrefetchDeferred: Boolean = false
    private var bootstrapStartPosition: Long = C.TIME_UNSET
    private var continuationSource: OkHttpDataSource? = null
    private var continuationEndPositionExclusive: Long = C.TIME_UNSET

    // Fallback: if parallel mode fails, use a single upstream DataSource
    private var fallbackSource: OkHttpDataSource? = null

    // Background producer pumps that translate sequential upstream bytes into store writes.
    private var continuationPumpThread: Thread? = null
    private var fallbackPumpThread: Thread? = null
    private var startupWatchdogThread: Thread? = null

    private val firstFrontierAdvanceLogged = AtomicBoolean(false)
    private val firstReadableBytesLogged = AtomicBoolean(false)
    private val firstReadReturnLogged = AtomicBoolean(false)
    private val firstStoreProgressLogged = AtomicBoolean(false)
    private val openStartedAtMs = AtomicLong(0L)
    private var currentReopenCause: ReopenCause = ReopenCause.COLD_OPEN
    private var lastScheduledReaderChunkIndex: Long = Long.MIN_VALUE
    private var lastScheduledFrontierChunkIndex: Long = Long.MIN_VALUE
    private var lastScheduledContinuationWindowEndExclusive: Long = Long.MIN_VALUE
    private var aggregatedReadBytes: Long = 0L
    private var aggregatedReadCalls: Int = 0
    private var lastReadTraceAtMs: Long = 0L

    // BaseDataSource.transferStarted() fans out to all registered TransferListeners on
    // every call with no internal dedup, so calling it twice would double-fire listeners.
    // open() has several successful return paths (bootstrap reuse, 416 zero-length,
    // fallback-single-connection, parallel-range happy path), and we want at-most-once
    // start/end semantics per open() regardless of which path ran.
    private var transferStartedFired: Boolean = false

    private fun fireTransferStarted(dataSpec: DataSpec) {
        if (transferStartedFired) return
        transferStartedFired = true
        transferStarted(dataSpec)
    }

    private fun resetScheduleMemo() {
        lastScheduledReaderChunkIndex = Long.MIN_VALUE
        lastScheduledFrontierChunkIndex = Long.MIN_VALUE
        lastScheduledContinuationWindowEndExclusive = Long.MIN_VALUE
    }

    private fun parseContentRangeTotal(headers: Map<String, List<String>>): Long? {
        val contentRange = headers.entries
            .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?: return null
        // Accepts both `bytes N-M/<total>` and the unsatisfied-range `bytes */<total>` form
        // (the latter is what OkHttpDataSource hands us when it transparently turns a 416
        // with `position == documentSize` into a zero-length successful open).
        val match = Regex("""bytes\s+(?:\d+-\d+|\*)/(\d+|\*)""", RegexOption.IGNORE_CASE).find(contentRange)
            ?: return null
        val totalText = match.groupValues[1]
        return if (totalText == "*") null else totalText.toLongOrNull()
    }

    private fun classifyReopenCause(
        position: Long,
        requestedLength: Long,
        clampedOpenLength: Long,
        continuationBranch: Boolean
    ): ReopenCause {
        return when {
            position == 0L -> ReopenCause.COLD_OPEN
            clampedOpenLength == 0L || requestedLength == 0L -> ReopenCause.EOF_PROBE
            position > 0L && requestedLength == C.LENGTH_UNSET.toLong() -> ReopenCause.SEEK_LIKE_REOPEN
            continuationBranch -> ReopenCause.CONTINUATION_REOPEN
            else -> ReopenCause.SEEK_LIKE_REOPEN
        }
    }

    private fun beginPrdsOpenTrace(dataSpec: DataSpec, reopenCause: ReopenCause) {
        currentReopenCause = reopenCause
        emitPrdsOpenStart(dataSpec)
    }

    private fun emitPrdsOpenStart(dataSpec: DataSpec) {
        if (!PlaybackTracer.enabled) return
        PlaybackTracer.emit(EventFamily.PRDS, "prds_open_start") {
            putLong("position", dataSpec.position)
            putLong("length", dataSpec.length)
            putString("reopenCause", currentReopenCause.traceValue)
            putString("uriScheme", dataSpec.uri.scheme)
            putString("uriHost", dataSpec.uri.host)
            putLong("chunkSize", activeChunkSize)
            putInt("parallelConnections", parallelConnections)
        }
    }

    private fun emitPrdsOpenMode(mode: String, openLength: Long, acceptsRanges: String) {
        if (!PlaybackTracer.enabled) return
        PlaybackTracer.emit(EventFamily.PRDS, "prds_open_mode") {
            putString("mode", mode)
            putString("reopenCause", currentReopenCause.traceValue)
            putLong("openLength", openLength)
            putString("acceptsRanges", acceptsRanges)
            putLong("activeChunkSize", activeChunkSize)
            putLong("bootstrapCoverageEnd", bootstrapCoverageEnd)
        }
    }

    private fun emitPrdsOpenResolved(mode: String, openLength: Long, acceptsRanges: String) {
        if (!PlaybackTracer.enabled) return
        PlaybackTracer.emit(EventFamily.PRDS, "prds_open_resolved") {
            putString("mode", mode)
            putString("reopenCause", currentReopenCause.traceValue)
            putString("resolvedScheme", resolvedUri?.scheme)
            putString("resolvedHost", resolvedUri?.host)
            putLong("startPosition", position)
            putLong("openLength", openLength)
            putLong("totalFileLength", totalFileLength)
            putLong("bytesRemaining", bytesRemaining)
            putString("acceptsRanges", acceptsRanges)
            putLong("activeChunkSize", activeChunkSize)
            putLong("bootstrapCoverageEnd", bootstrapCoverageEnd)
        }
    }

    private fun emitPrdsOpenSuccess(mode: String, openLength: Long, acceptsRanges: String) {
        if (!PlaybackTracer.enabled) return
        emitPrdsOpenMode(mode, openLength, acceptsRanges)
        emitPrdsOpenResolved(mode, openLength, acceptsRanges)
    }

    private fun emitPrdsOpenError(dataSpec: DataSpec, error: Throwable) {
        if (!PlaybackTracer.enabled) return
        PlaybackTracer.emit(EventFamily.PRDS, "prds_open_error") {
            putLong("position", dataSpec.position)
            putLong("length", dataSpec.length)
            putString("reopenCause", currentReopenCause.traceValue)
            putString("errorClass", error::class.java.name)
            putString("errorMessage", error.message)
        }
    }

    private fun emitReadWaitStart(waitPosition: Long, deadlineNanos: Long) {
        if (!PlaybackTracer.enabled) return
        PlaybackTracer.emit(EventFamily.READ_WAIT, "read_wait_start") {
            putLong("position", waitPosition)
            putLong("deadlineNanos", deadlineNanos)
        }
    }

    private fun emitReadWaitOutcome(waitPosition: Long, outcome: WaitOutcome, startedNanos: Long) {
        if (!PlaybackTracer.enabled) return
        val waitedNanos = System.nanoTime() - startedNanos
        PlaybackTracer.emit(EventFamily.READ_WAIT, "read_wait_end") {
            putLong("position", waitPosition)
            putString("outcome", outcome.name)
            putLong("waitedNanos", waitedNanos)
        }
        val event = when (outcome) {
            WaitOutcome.TIMEOUT -> "read_wait_timeout"
            WaitOutcome.ERROR -> "read_wait_error"
            else -> "read_wait_return"
        }
        PlaybackTracer.emit(EventFamily.READ_WAIT, event) {
            putLong("position", waitPosition)
            putString("outcome", outcome.name)
            putLong("waitedNanos", waitedNanos)
        }
    }

    private fun emitPrdsReadReturn(read: Int, requestedLength: Int) {
        if (!PlaybackTracer.enabled) return
        PlaybackTracer.emit(EventFamily.PRDS, "read_return") {
            putInt("read", read)
            putInt("requestedLength", requestedLength)
            putLong("position", position)
            putLong("bytesRemaining", bytesRemaining)
        }
    }

    private fun resetAggregatedReadTrace() {
        aggregatedReadBytes = 0L
        aggregatedReadCalls = 0
        lastReadTraceAtMs = 0L
    }

    private fun flushAggregatedReadReturn(nowMs: Long = SystemClock.elapsedRealtime()) {
        if (!PlaybackTracer.enabled) {
            resetAggregatedReadTrace()
            return
        }
        if (aggregatedReadCalls <= 0 || aggregatedReadBytes <= 0L) return
        PlaybackTracer.emit(EventFamily.PRDS, "read_return_agg") {
            putLong("bytesRead", aggregatedReadBytes)
            putInt("readCalls", aggregatedReadCalls)
            putLong("position", position)
            putLong("bytesRemaining", bytesRemaining)
        }
        aggregatedReadBytes = 0L
        aggregatedReadCalls = 0
        lastReadTraceAtMs = nowMs
    }

    private fun maybeEmitAggregatedReadReturn(read: Int) {
        if (read <= 0) return
        aggregatedReadBytes += read.toLong()
        aggregatedReadCalls += 1
        val nowMs = SystemClock.elapsedRealtime()
        if (
            aggregatedReadCalls < READ_RETURN_TRACE_MIN_CALLS &&
            nowMs - lastReadTraceAtMs < READ_RETURN_TRACE_MIN_INTERVAL_MS
        ) {
            return
        }
        flushAggregatedReadReturn(nowMs)
    }

    private fun emitPrdsReadError(error: Throwable, requestedLength: Int) {
        if (!PlaybackTracer.enabled) return
        PlaybackTracer.emit(EventFamily.PRDS, "read_error") {
            putInt("requestedLength", requestedLength)
            putLong("position", position)
            putLong("bytesRemaining", bytesRemaining)
            putString("errorClass", error::class.java.name)
            putString("errorMessage", error.message)
        }
    }

    private fun emitPrdsClose(wasStarted: Boolean) {
        if (!PlaybackTracer.enabled) return
        PlaybackTracer.emit(EventFamily.PRDS, "prds_close") {
            putBool("wasStarted", wasStarted)
            putString("reopenCause", currentReopenCause.traceValue)
            putLong("position", position)
            putLong("bytesRemaining", bytesRemaining)
            putLong("totalFileLength", totalFileLength)
            putBool("hadOpenSession", openSession != null)
            putString("resolvedScheme", resolvedUri?.scheme)
            putString("resolvedHost", resolvedUri?.host)
        }
    }

    private fun resetStoreForNewOpen(reportForTest: Boolean = true) {
        store.reset()
        if (reportForTest) {
            onStoreResetForTest()
        }
    }

    private fun remainingOpenLengthFor(session: OpenSession, requestedPosition: Long): Long {
        return when {
            session.totalFileLength != C.LENGTH_UNSET.toLong() ->
                (session.totalFileLength - requestedPosition).coerceAtLeast(0L)
            session.openLength != C.LENGTH_UNSET.toLong() ->
                (session.openLength - (requestedPosition - session.startPosition)).coerceAtLeast(0L)
            else -> C.LENGTH_UNSET.toLong()
        }
    }

    private fun canReuseOpenSession(dataSpec: DataSpec): Boolean {
        if (!readerClosed.get()) return false
        val session = openSession ?: return false
        if (continuationSource != null || fallbackSource != null) return false
        if (!transportManager.isAttached()) return false
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) return false
        if (session.requestSpec.uri != dataSpec.uri) return false
        if (dataSpec.position < session.startPosition) return false
        return store.readableContiguousBytesFrom(dataSpec.position) > 0L
    }

    private fun reopenFromRetainedCoverage(dataSpec: DataSpec): Long {
        val previousSession = openSession ?: error("openSession required for retained reopen")
        closed.set(false)
        readerClosed.set(false)
        position = dataSpec.position
        resolvedUri = previousSession.resolvedUri ?: resolvedUri ?: dataSpec.uri
        totalFileLength = previousSession.totalFileLength
        bytesRemaining = remainingOpenLengthFor(previousSession, dataSpec.position)
        val reopenedSession = previousSession.copy(
            requestSpec = dataSpec,
            startPosition = dataSpec.position,
            openLength = bytesRemaining
        )
        openSession = reopenedSession
        cursor?.close()
        cursor = DefaultSequentialReadCursor(
            session = reopenedSession,
            store = store,
            waitForBytes = ::waitForBytesAt,
            onPositionAdvanced = onReadPositionAdvanced,
            keepBehindBytes = keepBehindBytes,
            chunkWaitTimeoutMs = chunkWaitTimeoutMs
        )
        val reopenCause = classifyReopenCause(
            position = dataSpec.position,
            requestedLength = dataSpec.length,
            clampedOpenLength = bytesRemaining,
            continuationBranch = false
        )
        beginPrdsOpenTrace(dataSpec, reopenCause)
        maybeLogFirstReadableBytes("retained_reopen")
        fireTransferStarted(dataSpec)
        emitPrdsOpenSuccess("parallel_range_reuse", bytesRemaining, reopenedSession.acceptsRanges.toString())
        startStartupWatchdog()
        return bytesRemaining
    }

    private fun canPreserveOpenStateOnClose(): Boolean {
        if (continuationSource != null || fallbackSource != null) return false
        if (!transportManager.isAttached()) return false
        if (openSession == null) return false
        return store.readableContiguousBytesFrom(position) > 0L
    }

    override fun open(dataSpec: DataSpec): Long {
        // Reset listener-fire latch first: open() may throw before close() runs, and the
        // caller is allowed to retry. If the latch stayed `true` from a prior open(),
        // fireTransferStarted() would no-op on the retry and listeners would never see
        // a start.
        transferStartedFired = false
        resetScheduleMemo()
        resetAggregatedReadTrace()
        openStartedAtMs.set(SystemClock.elapsedRealtime())
        firstFrontierAdvanceLogged.set(false)
        firstReadableBytesLogged.set(false)
        firstReadReturnLogged.set(false)
        firstStoreProgressLogged.set(false)
        originalDataSpec = dataSpec
        activeChunkSize = chunkSize
        bootstrapPrefetchDeferred = false
        bootstrapStartPosition = C.TIME_UNSET
        lastDownloadError = null

        // Media3 transfer-listener contract: notify lifecycle for our DataSource (not just upstream).
        transferInitializing(dataSpec)
        if (canReuseOpenSession(dataSpec)) {
            return reopenFromRetainedCoverage(dataSpec)
        }

        readerClosed.set(false)
        closed.set(false)
        position = dataSpec.position
        // Defensive: ensure getUri() returns the requested URI on any throw before
        // a success path overwrites this with the resolved (post-redirect) URI.
        resolvedUri = dataSpec.uri
        totalFileLength = C.LENGTH_UNSET.toLong()
        bytesRemaining = C.LENGTH_UNSET.toLong()
        stopPumps()
        startupWatchdogThread?.interrupt()
        startupWatchdogThread = null
        fallbackSource?.close()
        fallbackSource = null
        continuationSource?.close()
        continuationSource = null
        continuationEndPositionExclusive = C.TIME_UNSET

        // Cancel any in-flight work from a previous open (e.g., after seek)
        transportManager.detach()
        cursor?.close()
        cursor = null
        openSession = null
        resetStoreForNewOpen(reportForTest = openSession != null || transportManager.isAttached())
        bootstrapCoverageEnd = 0L

        // Signal any thread that might be blocked in read() so it can observe closed state
        readLock.lock()
        try { dataAvailable.signalAll() } finally { readLock.unlock() }

        val cachedBootstrap = consumeBootstrapCache(dataSpec)
        cachedBootstrap?.let { cached ->
            logStartupStage("bootstrap_reuse_start")
            resolvedUri = cached.resolvedUri
            onResolvedUri(resolvedUri)
            totalFileLength = cached.totalFileLength
            bytesRemaining = cached.openLength
            bootstrapStartPosition = cached.startPosition
            val reopenCause = classifyReopenCause(
                position = cached.startPosition,
                requestedLength = dataSpec.length,
                clampedOpenLength = cached.openLength,
                continuationBranch = false
            )
            beginPrdsOpenTrace(dataSpec, reopenCause)

            // Write bootstrap data into frontier buffer immediately
            store = PagedFrontierByteStore()
            if (cached.startPosition > 0L) store.setBasePosition(cached.startPosition)
            store.setTotalLength(totalFileLength)
            store.publishStartupWindow(cached.startPosition, cached.bootstrapData, 0, cached.bootstrapSize)
            bootstrapCoverageEnd = cached.startPosition + cached.bootstrapSize
            maybeLogFirstFrontierAdvance("bootstrap_reuse", store.frontier)
            maybeLogFirstReadableBytes("bootstrap_reuse")

            // Fire onChunkBytesDownloaded for cached bootstrap data
            reportBootstrapChunkBytes(cached.startPosition, cached.bootstrapSize)

            // Mark bootstrap chunks as scheduled ONLY if fully covered
            val bootstrapEndPos = cached.startPosition + cached.bootstrapSize
            val preScheduled = mutableListOf<Long>()
            var ci = cached.startPosition / activeChunkSize
            while (ci * activeChunkSize < bootstrapEndPos) {
                val chunkEnd = (ci + 1) * activeChunkSize
                if (chunkEnd <= bootstrapEndPos) {
                    preScheduled.add(ci)
                }
                ci++
            }

            bootstrapPrefetchDeferred = true
            transportManager.attachSession(
                store = store,
                resolvedUri = resolvedUri,
                fallbackUri = dataSpec.uri,
                totalFileLength = totalFileLength,
                activeChunkSize = activeChunkSize,
                bootstrapCoverageEnd = bootstrapCoverageEnd,
                preScheduledChunkIndexes = preScheduled
            )
            onTransportSessionAttachedForTest()
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
            fireTransferStarted(dataSpec)
            emitPrdsOpenSuccess("bootstrap_reuse", cached.openLength, "true")
            startStartupWatchdog()
            return cached.openLength
        }

        // Open first connection to determine total length and capture the resolved (redirected) URL
        val probeSource: OkHttpDataSource = upstreamFactory.createDataSource()
        logStartupStage("probe_open_start")

        val rawOpenLength: Long
        try {
            rawOpenLength = probeSource.open(dataSpec)
            logStartupStage("probe_open_end", mapOf("rawOpenLength" to rawOpenLength))
            resolvedUri = probeSource.uri // Final URL after redirects (CDN URL)
            onResolvedUri(resolvedUri)
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            probeSource.close()
            // 416 Range Not Satisfiable: position is at or past EOF. Per Media3
            // DataSource contract, position == length is a valid open that immediately
            // reads EOF, while position > length must throw POSITION_OUT_OF_RANGE.
            // Follow up with a zero-position probe to learn the actual file length.
            if (e.responseCode == 416) {
                val sizingProbe = upstreamFactory.createDataSource()
                val actualLength = try {
                    sizingProbe.open(
                        dataSpec.buildUpon()
                            .setPosition(0L)
                            .setLength(C.LENGTH_UNSET.toLong())
                            .build()
                    )
                } catch (sizingError: Exception) {
                    Log.w(TAG, "416 sizing-probe failed; surfacing original 416: ${sizingError.message}")
                    sizingProbe.close()
                    throw e
                }
                sizingProbe.close()
                if (dataSpec.position > actualLength) {
                    beginPrdsOpenTrace(
                        dataSpec,
                        classifyReopenCause(
                            position = dataSpec.position,
                            requestedLength = dataSpec.length,
                            clampedOpenLength = 0L,
                            continuationBranch = false
                        )
                    )
                    emitPrdsOpenError(dataSpec, e)
                    @Suppress("DEPRECATION")
                    throw androidx.media3.datasource.DataSourceException(
                        e,
                        androidx.media3.datasource.DataSourceException.POSITION_OUT_OF_RANGE
                    )
                }
                resolvedUri = dataSpec.uri
                onResolvedUri(resolvedUri)
                totalFileLength = actualLength
                bytesRemaining = 0L
                val reopenCause = classifyReopenCause(
                    position = dataSpec.position,
                    requestedLength = dataSpec.length,
                    clampedOpenLength = 0L,
                    continuationBranch = false
                )
                beginPrdsOpenTrace(dataSpec, reopenCause)
                openSession = OpenSession(
                    requestSpec = dataSpec,
                    resolvedUri = resolvedUri,
                    startPosition = dataSpec.position,
                    openLength = 0L,
                    totalFileLength = totalFileLength,
                    acceptsRanges = true,
                    responseHeaders = emptyMap()
                )
                store = PagedFrontierByteStore()
                if (dataSpec.position > 0L) store.setBasePosition(dataSpec.position)
                if (totalFileLength != C.LENGTH_UNSET.toLong()) store.setTotalLength(totalFileLength)
                cursor = DefaultSequentialReadCursor(
                    session = openSession!!,
                    store = store,
                    waitForBytes = ::waitForBytesAt,
                    onPositionAdvanced = onReadPositionAdvanced,
                    keepBehindBytes = keepBehindBytes,
                    chunkWaitTimeoutMs = chunkWaitTimeoutMs
                )
                fireTransferStarted(dataSpec)
                val returnedOpenLength = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else 0L
                emitPrdsOpenSuccess("range_416_eof", returnedOpenLength, "true")
                return returnedOpenLength
            }
            // 404 / other error: contract requires getUri() to surface the requested URI.
            resolvedUri = dataSpec.uri
            beginPrdsOpenTrace(
                dataSpec,
                classifyReopenCause(
                    position = dataSpec.position,
                    requestedLength = dataSpec.length,
                    clampedOpenLength = C.LENGTH_UNSET.toLong(),
                    continuationBranch = false
                )
            )
            emitPrdsOpenError(dataSpec, e)
            throw e
        } catch (e: Exception) {
            probeSource.close()
            // Contract: getUri() after a failed open must return the requested URI.
            resolvedUri = dataSpec.uri
            beginPrdsOpenTrace(
                dataSpec,
                classifyReopenCause(
                    position = dataSpec.position,
                    requestedLength = dataSpec.length,
                    clampedOpenLength = C.LENGTH_UNSET.toLong(),
                    continuationBranch = false
                )
            )
            emitPrdsOpenError(dataSpec, e)
            throw e
        }

        // Check if we can do parallel range requests
        val responseHeaders = probeSource.responseHeaders
        transportManager.emitTransportObservation(responseHeaders, resolvedUri)
        activeChunkSize = transportPolicyProvider()?.urgentChunkBytes ?: chunkSize
        val acceptsRanges = responseHeaders["Accept-Ranges"]?.any { it.contains("bytes") } == true ||
                responseHeaders["Content-Range"]?.isNotEmpty() == true

        // Parse Content-Range to recover the authoritative total file size. OkHttpDataSource
        // can return openLength == requested dataSpec.length even when the server clamped the
        // response (e.g. requested length past EOF), which would otherwise cause chunk workers
        // to request bytes past EOF and receive 416. When Content-Range is absent or has an
        // unknown total (`bytes N-M/*`), `parseContentRangeTotal` returns null and the clamp
        // below becomes a no-op — `clampedOpenLength` falls back to `rawOpenLength`.
        val totalFromContentRange = parseContentRangeTotal(responseHeaders)
        val authoritativeTotal = totalFromContentRange ?: C.LENGTH_UNSET.toLong()
        val clampedOpenLength = if (authoritativeTotal != C.LENGTH_UNSET.toLong() && rawOpenLength != C.LENGTH_UNSET.toLong()) {
            minOf(rawOpenLength, (authoritativeTotal - position).coerceAtLeast(0L))
        } else {
            rawOpenLength
        }

        if (clampedOpenLength == C.LENGTH_UNSET.toLong() || !acceptsRanges) {
            // Can't determine length or server doesn't support ranges — reuse probe as single
            // connection. Pump its bytes into the store so the cursor remains the sole reader.
            Log.w(TAG, "Falling back to single connection (length=${clampedOpenLength}, acceptsRanges=$acceptsRanges)")
            fallbackSource = probeSource
            totalFileLength = if (clampedOpenLength != C.LENGTH_UNSET.toLong()) {
                position + clampedOpenLength
            } else {
                C.LENGTH_UNSET.toLong()
            }
            bytesRemaining = clampedOpenLength
            fireTransferStarted(dataSpec)

            store = PagedFrontierByteStore()
            if (position > 0L) store.setBasePosition(position)
            if (totalFileLength != C.LENGTH_UNSET.toLong()) store.setTotalLength(totalFileLength)

            openSession = OpenSession(
                requestSpec = dataSpec,
                resolvedUri = resolvedUri,
                startPosition = position,
                openLength = clampedOpenLength,
                totalFileLength = totalFileLength,
                acceptsRanges = false,
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
            startFallbackPump(probeSource, position)
            val reopenCause = classifyReopenCause(
                position = position,
                requestedLength = dataSpec.length,
                clampedOpenLength = clampedOpenLength,
                continuationBranch = false
            )
            beginPrdsOpenTrace(dataSpec, reopenCause)
            val returnedOpenLength = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else clampedOpenLength
            emitPrdsOpenSuccess("fallback_single_connection", returnedOpenLength, "false")
            return returnedOpenLength
        }

        totalFileLength = if (authoritativeTotal != C.LENGTH_UNSET.toLong()) authoritativeTotal else position + clampedOpenLength
        bytesRemaining = clampedOpenLength

        // OkHttpDataSource quietly turns a 416 with `position == documentSize` into a
        // zero-length successful open (returning `dataSpec.length`). Detect that here so
        // we don't attach a transport session and schedule a chunk worker that would
        // immediately EOF past the end of the file.
        if (clampedOpenLength == 0L) {
            probeSource.close()
            store = PagedFrontierByteStore()
            if (position > 0L) store.setBasePosition(position)
            if (totalFileLength != C.LENGTH_UNSET.toLong()) store.setTotalLength(totalFileLength)
            val reopenCause = classifyReopenCause(
                position = position,
                requestedLength = dataSpec.length,
                clampedOpenLength = 0L,
                continuationBranch = false
            )
            beginPrdsOpenTrace(dataSpec, reopenCause)
            openSession = OpenSession(
                requestSpec = dataSpec,
                resolvedUri = resolvedUri,
                startPosition = position,
                openLength = 0L,
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
            fireTransferStarted(dataSpec)
            val returnedOpenLength = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else 0L
            emitPrdsOpenSuccess("zero_length_eof", returnedOpenLength, acceptsRanges.toString())
            return returnedOpenLength
        }

        fireTransferStarted(dataSpec)

        store = PagedFrontierByteStore()
        if (position > 0L) store.setBasePosition(position)
        store.setTotalLength(totalFileLength)
        val preScheduledFromBootstrap = mutableListOf<Long>()

        PlayerTransportTelemetry.log("prds.open", mapOf(
            "activeChunk" to activeChunkSize,
            "locked" to "n/a",
            "prefetchChunkBytes" to envelope.maxSafePrefetchChunkBytes,
            "prefetchWorkers" to envelope.maxSafePrefetchWorkers,
            "supportsRangeRequests" to envelope.supportsRangeRequests,
            "urgentChunkBytes" to envelope.maxSafeUrgentChunkBytes,
            "urgentWorkers" to envelope.maxSafeUrgentWorkers
        ))

        // Reuse a small probe window immediately for both startup and large seek reopens.
        val firstChunkIndex = position / activeChunkSize
        if (clampedOpenLength > 0L) {
            val bootstrapBytes = bootstrapReadBytes(clampedOpenLength)
            val bootstrapStartedAt = SystemClock.elapsedRealtime()
            val (bootstrapData, bootstrapSize) = readBootstrapChunk(probeSource, bootstrapBytes)
            if (PlaybackTracer.enabled) {
                PlaybackTracer.emit(EventFamily.PRDS, "prds_bootstrap_read_end") {
                    putLong("bootstrapBytesRequested", bootstrapBytes.toLong())
                    putLong("bootstrapBytesRead", bootstrapSize.toLong())
                    putLong("bootstrapDurationMs", SystemClock.elapsedRealtime() - bootstrapStartedAt)
                }
            }
            logStartupStage(
                "bootstrap_read_end",
                mapOf(
                    "bootstrapBytesRequested" to bootstrapBytes,
                    "bootstrapBytesRead" to bootstrapSize,
                    "bootstrapDurationMs" to (SystemClock.elapsedRealtime() - bootstrapStartedAt)
                )
            )
            bootstrapStartPosition = position

            // Write bootstrap data into frontier buffer
            store.publishStartupWindow(position, bootstrapData, 0, bootstrapSize)
            bootstrapCoverageEnd = position + bootstrapSize
            maybeLogFirstFrontierAdvance("bootstrap", store.frontier)
            maybeLogFirstReadableBytes("bootstrap")

            // Fire onChunkBytesDownloaded for bootstrap data so FrontierTracker (benchmark)
            // receives events for all downloaded bytes, not just background-fetched chunks
            reportBootstrapChunkBytes(position, bootstrapSize)

            // Mark bootstrap chunks as already scheduled ONLY if fully covered by bootstrap.
            // If bootstrap only partially covers a chunk (e.g., 1MB bootstrap in an 8MB chunk),
            // the chunk must still be scheduled so downloadRange fetches the remaining bytes.
            val bootstrapEndPos = position + bootstrapSize
            var bci = position / activeChunkSize
            while (bci * activeChunkSize < bootstrapEndPos) {
                val chunkEnd = (bci + 1) * activeChunkSize
                if (chunkEnd <= bootstrapEndPos) {
                    preScheduledFromBootstrap.add(bci)
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
                        openLength = clampedOpenLength,
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

        transportManager.attachSession(
            store = store,
            resolvedUri = resolvedUri,
            fallbackUri = dataSpec.uri,
            totalFileLength = totalFileLength,
            activeChunkSize = activeChunkSize,
            bootstrapCoverageEnd = bootstrapCoverageEnd,
            preScheduledChunkIndexes = preScheduledFromBootstrap
        )
        onTransportSessionAttachedForTest()
        logStartupStage(
            "transport_attach",
            mapOf(
                "acceptsRanges" to acceptsRanges,
                "bootstrapCoverageEnd" to bootstrapCoverageEnd,
                "preScheduledChunks" to preScheduledFromBootstrap.size,
                "resolvedHost" to resolvedUri?.host
            )
        )

        openSession = OpenSession(
            requestSpec = dataSpec,
            resolvedUri = resolvedUri,
            startPosition = position,
            openLength = clampedOpenLength,
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
        continuationSource?.let { src ->
            startContinuationPump(src, bootstrapCoverageEnd, continuationEndPositionExclusive)
        }
        val reopenCause = classifyReopenCause(
            position = position,
            requestedLength = dataSpec.length,
            clampedOpenLength = clampedOpenLength,
            continuationBranch = continuationSource != null
        )
        beginPrdsOpenTrace(dataSpec, reopenCause)
        // Contract: open() must return dataSpec.length when it's set, even if the implementation
        // knows fewer bytes will actually be read.
        val returnedOpenLength = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else clampedOpenLength
        emitPrdsOpenSuccess("parallel_range", returnedOpenLength, acceptsRanges.toString())
        startStartupWatchdog()
        return returnedOpenLength
    }

    /**
     * Cursor wait hook: blocks the reader thread on the shared [dataAvailable] condition
     * until a producer signals new bytes, the source is closed, a producer reports a
     * terminal error, or the caller's deadline elapses. The return value tells the
     * [DefaultSequentialReadCursor] how to resume: retry the store read, EOF, throw a
     * typed wait error, or throw a timeout.
     */
    private fun waitForBytesAt(position: Long, deadlineNanos: Long): WaitOutcome {
        val waitStartedNanos = System.nanoTime()
        var outcome = WaitOutcome.ERROR
        emitReadWaitStart(position, deadlineNanos)
        readLock.lock()
        try {
            if (closed.get()) {
                outcome = WaitOutcome.CLOSED
                return outcome
            }
            if (readerClosed.get()) {
                outcome = WaitOutcome.CLOSED
                return outcome
            }
            lastDownloadError?.let {
                outcome = WaitOutcome.ERROR
                return outcome
            }
            // Re-check the store *while holding the lock* to close the missed-wakeup race
            // between the cursor's last `store.read()` and our await. A producer that wrote
            // bytes + signaled in that window would otherwise be lost: the signal fires
            // when no thread is waiting, then we enter awaitNanos and never wake. Worker
            // signals also acquire `readLock` before calling signalAll, so any write that
            // races with us is either visible here or arrives as a signal after we await.
            if (store.readableContiguousBytesFrom(position) > 0L) {
                outcome = WaitOutcome.DATA_AVAILABLE
                return outcome
            }
            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0L) {
                outcome = WaitOutcome.TIMEOUT
                return outcome
            }
            dataAvailable.awaitNanos(remaining)
            if (closed.get()) {
                outcome = WaitOutcome.CLOSED
                return outcome
            }
            if (readerClosed.get()) {
                outcome = WaitOutcome.CLOSED
                return outcome
            }
            if (lastDownloadError != null) {
                outcome = WaitOutcome.ERROR
                return outcome
            }
            outcome = WaitOutcome.DATA_AVAILABLE
            return outcome
        } finally {
            readLock.unlock()
            emitReadWaitOutcome(position, outcome, waitStartedNanos)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val result = readInternal(buffer, offset, length)
        if (result > 0) {
            bytesTransferred(result)
        }
        return result
    }

    private fun readInternal(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) {
            flushAggregatedReadReturn()
            emitPrdsReadReturn(0, length)
            return 0
        }

        // Trigger deferred bootstrap prefetch on the first post-open read.
        if (bootstrapPrefetchDeferred && shouldAllowBackgroundPrefetch()) {
            bootstrapPrefetchDeferred = false
            scheduleFromCursor()
        }

        // Ensure work is scheduled (no-op when fallback or fully covered).
        scheduleFromCursor()
        maybeLogFirstReadableBytes("read_entry")

        // Surface any producer error to the reader thread.
        lastDownloadError?.let { error ->
            lastDownloadError = null
            flushAggregatedReadReturn()
            emitPrdsReadError(error, length)
            throw error
        }

        // All bytes flow through the cursor, which reads from AbsoluteByteStore.
        // Producers (bootstrap reuse, continuation pump, fallback pump, range workers)
        // write into the store underneath; the cursor is the sole reader.
        val cursor = this.cursor ?: run {
            flushAggregatedReadReturn()
            emitPrdsReadReturn(C.RESULT_END_OF_INPUT, length)
            return C.RESULT_END_OF_INPUT
        }
        val read = try {
            cursor.read(buffer, offset, length)
        } catch (e: IOException) {
            // If a producer failed mid-wait, surface its typed error (e.g. ChunkDownloadException)
            // instead of the cursor's generic wait-error wrapper.
            val producerError = lastDownloadError
            if (producerError != null) {
                lastDownloadError = null
                flushAggregatedReadReturn()
                emitPrdsReadError(producerError, length)
                throw producerError
            }
            flushAggregatedReadReturn()
            emitPrdsReadError(e, length)
            throw e
        }
        if (read > 0) {
            position = cursor.position
            bytesRemaining = cursor.bytesRemaining
            scheduleFromCursor()
            maybeEmitAggregatedReadReturn(read)
        } else {
            flushAggregatedReadReturn()
        }
        maybeLogFirstReadReturn(read)
        if (read <= 0) {
            emitPrdsReadReturn(read, length)
        }
        return read
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

    private fun startFallbackPump(source: OkHttpDataSource, startPosition: Long) {
        val thread = Thread({
            val buf = ByteArray(READ_BUFFER_SIZE)
            var pumpPos = startPosition
            try {
                while (!closed.get()) {
                    val read = try {
                        source.read(buf, 0, buf.size)
                    } catch (e: Exception) {
                        if (closed.get()) return@Thread
                        lastDownloadError = e
                        readLock.lock()
                        try { dataAvailable.signalAll() } finally { readLock.unlock() }
                        return@Thread
                    }
                    if (read == C.RESULT_END_OF_INPUT) {
                        // Mark EOF by tightening totalLength so the cursor terminates.
                        if (totalFileLength == C.LENGTH_UNSET.toLong()) {
                            totalFileLength = pumpPos
                            store.setTotalLength(pumpPos)
                        }
                        readLock.lock()
                        try { dataAvailable.signalAll() } finally { readLock.unlock() }
                        return@Thread
                    }
                    if (read > 0) {
                        store.writeAt(pumpPos, buf, 0, read)
                        pumpPos += read
                        maybeLogFirstFrontierAdvance("fallback_pump", store.frontier)
                        maybeLogFirstReadableBytes("fallback_pump")
                        onTransportBytesDownloaded(read.toLong(), transportSampleTimeMs())
                        readLock.lock()
                        try { dataAvailable.signalAll() } finally { readLock.unlock() }
                    }
                }
            } finally {
                runCatching { source.close() }
            }
        }, "PRDS-FallbackPump")
        thread.isDaemon = true
        fallbackPumpThread = thread
        thread.start()
    }

    private fun startContinuationPump(
        source: OkHttpDataSource,
        startPosition: Long,
        endPositionExclusive: Long
    ) {
        if (startPosition >= endPositionExclusive) {
            runCatching { source.close() }
            continuationSource = null
            continuationEndPositionExclusive = C.TIME_UNSET
            return
        }
        val thread = Thread({
            val buf = ByteArray(READ_BUFFER_SIZE)
            var pumpPos = startPosition
            try {
                while (!closed.get() && pumpPos < endPositionExclusive) {
                    val maxRead = minOf(buf.size.toLong(), endPositionExclusive - pumpPos).toInt()
                    val read = try {
                        source.read(buf, 0, maxRead)
                    } catch (e: Exception) {
                        if (closed.get()) return@Thread
                        // Continuation failure is non-fatal; range workers will fill the gap.
                        Log.w(TAG, "Continuation pump aborted at $pumpPos: ${e.message}")
                        return@Thread
                    }
                    if (read == C.RESULT_END_OF_INPUT) return@Thread
                    if (read > 0) {
                        store.writeAt(pumpPos, buf, 0, read)
                        pumpPos += read
                        maybeLogFirstFrontierAdvance("continuation_pump", store.frontier)
                        maybeLogFirstReadableBytes("continuation_pump")
                        onTransportBytesDownloaded(read.toLong(), transportSampleTimeMs())
                        readLock.lock()
                        try { dataAvailable.signalAll() } finally { readLock.unlock() }
                    }
                }
            } finally {
                runCatching { source.close() }
                synchronized(this@ParallelRangeDataSource) {
                    if (continuationSource === source) {
                        continuationSource = null
                        continuationEndPositionExclusive = C.TIME_UNSET
                    }
                }
                resetScheduleMemo()
                if (!closed.get()) scheduleFromCursor()
            }
        }, "PRDS-ContinuationPump")
        thread.isDaemon = true
        continuationPumpThread = thread
        thread.start()
    }

    private fun stopPumps() {
        continuationPumpThread?.interrupt()
        continuationPumpThread = null
        fallbackPumpThread?.interrupt()
        fallbackPumpThread = null
        startupWatchdogThread?.interrupt()
        startupWatchdogThread = null
    }

    override fun close() {
        val wasStarted = transferStartedFired
        val preserveOpenState = canPreserveOpenStateOnClose()
        flushAggregatedReadReturn()
        emitPrdsClose(wasStarted)
        transferStartedFired = false
        readerClosed.set(true)
        if (!preserveOpenState) {
            closed.set(true)
        }
        stopPumps()
        cursor?.close()
        cursor = null
        fallbackSource?.close()
        fallbackSource = null
        continuationSource?.close()
        continuationSource = null
        continuationEndPositionExclusive = C.TIME_UNSET
        resetScheduleMemo()
        lastDownloadError = null
        bootstrapPrefetchDeferred = false

        if (!preserveOpenState) {
            openSession = null
            resolvedUri = null
            transportManager.detach()
            resetStoreForNewOpen()
            bootstrapCoverageEnd = 0L
            totalFileLength = C.LENGTH_UNSET.toLong()
            bytesRemaining = C.LENGTH_UNSET.toLong()
        }

        // Signal any waiting readers
        readLock.lock()
        try { dataAvailable.signalAll() } finally { readLock.unlock() }

        if (wasStarted) {
            transferEnded()
        }
        originalDataSpec = null
        resetAggregatedReadTrace()
        firstStoreProgressLogged.set(false)
    }

    override fun getUri(): Uri? =
        openSession?.resolvedUri ?: resolvedUri ?: fallbackSource?.uri

    override fun getResponseHeaders(): Map<String, List<String>> {
        val sessionHeaders = openSession?.responseHeaders
        if (sessionHeaders != null && sessionHeaders.isNotEmpty()) return sessionHeaders
        return fallbackSource?.responseHeaders ?: emptyMap()
    }

    /**
     * Factory for creating ParallelRangeDataSource instances.
     */
    class Factory(
        private val upstreamFactory: OkHttpDataSource.Factory,
        private val envelope: CapabilityEnvelope = CapabilityEnvelope.DEFAULT,
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
                envelope = envelope,
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
