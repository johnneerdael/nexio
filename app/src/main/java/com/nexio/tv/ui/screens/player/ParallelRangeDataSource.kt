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
import java.util.concurrent.atomic.AtomicBoolean
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
    private val transportPolicyProvider: () -> TransportPolicy? = { null }
) : BaseDataSource(/* isNetwork = */ true) {

    companion object {
        private const val TAG = "ParallelRangeDS"
        private const val READ_BUFFER_SIZE = 512 * 1024 // 512KB read buffer for chunk downloads
        private const val BOOTSTRAP_READ_BYTES = 1L * 1024L * 1024L
        internal const val DEFAULT_CHUNK_WAIT_TIMEOUT_MS = 60_000L
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

    // Page-level frontier buffer wrapped behind an AbsoluteByteStore façade. All byte
    // producers (bootstrap reuse, continuation pump, fallback pump, parallel range workers)
    // publish through `store`; the sequential read cursor is the sole reader.
    private var store: AbsoluteByteStore = PagedFrontierByteStore()
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
        }
    )

    /**
     * Translates the reader's current position into a transport scheduling hint.
     * Skips while the continuation pump is active by reporting the pump's leading
     * edge instead of the reader position — that way, urgent ranges queue past the
     * pump's tail, not on top of it.
     */
    private fun scheduleFromCursor() {
        if (!transportManager.isAttached()) return
        val currentPos = if (continuationSource != null &&
            continuationEndPositionExclusive != C.TIME_UNSET &&
            position < continuationEndPositionExclusive) {
            continuationEndPositionExclusive
        } else {
            position
        }
        transportManager.scheduleForReaderPosition(currentPos)
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

    override fun open(dataSpec: DataSpec): Long {
        // Reset listener-fire latch first: open() may throw before close() runs, and the
        // caller is allowed to retry. If the latch stayed `true` from a prior open(),
        // fireTransferStarted() would no-op on the retry and listeners would never see
        // a start.
        transferStartedFired = false
        closed.set(false)
        originalDataSpec = dataSpec
        position = dataSpec.position
        activeChunkSize = chunkSize
        bootstrapPrefetchDeferred = false
        bootstrapStartPosition = C.TIME_UNSET
        // Defensive: ensure getUri() returns the requested URI on any throw before
        // a success path overwrites this with the resolved (post-redirect) URI.
        resolvedUri = dataSpec.uri
        totalFileLength = C.LENGTH_UNSET.toLong()
        bytesRemaining = C.LENGTH_UNSET.toLong()
        stopPumps()
        fallbackSource?.close()
        fallbackSource = null
        continuationSource?.close()
        continuationSource = null
        continuationEndPositionExclusive = C.TIME_UNSET

        // Media3 transfer-listener contract: notify lifecycle for our DataSource (not just upstream).
        transferInitializing(dataSpec)

        // Cancel any in-flight work from a previous open (e.g., after seek)
        transportManager.detach()
        cursor?.close()
        cursor = null
        openSession = null
        store.reset()
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
            return cached.openLength
        }

        // Open first connection to determine total length and capture the resolved (redirected) URL
        val probeSource: OkHttpDataSource = upstreamFactory.createDataSource()

        val rawOpenLength: Long
        try {
            rawOpenLength = probeSource.open(dataSpec)
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
                    throw androidx.media3.datasource.DataSourceException(
                        e,
                        androidx.media3.datasource.DataSourceException.POSITION_OUT_OF_RANGE
                    )
                }
                resolvedUri = dataSpec.uri
                onResolvedUri(resolvedUri)
                totalFileLength = actualLength
                bytesRemaining = 0L
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
                return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else 0L
            }
            // 404 / other error: contract requires getUri() to surface the requested URI.
            resolvedUri = dataSpec.uri
            throw e
        } catch (e: Exception) {
            probeSource.close()
            // Contract: getUri() after a failed open must return the requested URI.
            resolvedUri = dataSpec.uri
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
            return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else clampedOpenLength
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
            return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else 0L
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
            val bootstrapBytes = minOf(minOf(activeChunkSize, BOOTSTRAP_READ_BYTES), clampedOpenLength).toInt()
            val (bootstrapData, bootstrapSize) = readBootstrapChunk(probeSource, bootstrapBytes)
            bootstrapStartPosition = position

            // Write bootstrap data into frontier buffer
            store.writeAt(position, bootstrapData, 0, bootstrapSize)
            bootstrapCoverageEnd = position + bootstrapSize

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
        // Contract: open() must return dataSpec.length when it's set, even if the implementation
        // knows fewer bytes will actually be read.
        return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else clampedOpenLength
    }

    /**
     * Cursor wait hook: blocks the reader thread on the shared [dataAvailable] condition
     * until a producer signals new bytes, the source is closed, a producer reports a
     * terminal error, or the caller's deadline elapses. The return value tells the
     * [DefaultSequentialReadCursor] how to resume: retry the store read, EOF, throw a
     * typed wait error, or throw a timeout.
     */
    private fun waitForBytesAt(position: Long, deadlineNanos: Long): WaitOutcome {
        readLock.lock()
        try {
            if (closed.get()) return WaitOutcome.CLOSED
            lastDownloadError?.let { return WaitOutcome.ERROR }
            // Re-check the store *while holding the lock* to close the missed-wakeup race
            // between the cursor's last `store.read()` and our await. A producer that wrote
            // bytes + signaled in that window would otherwise be lost: the signal fires
            // when no thread is waiting, then we enter awaitNanos and never wake. Worker
            // signals also acquire `readLock` before calling signalAll, so any write that
            // races with us is either visible here or arrives as a signal after we await.
            if (store.readableContiguousBytesFrom(position) > 0L) return WaitOutcome.DATA_AVAILABLE
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
        val result = readInternal(buffer, offset, length)
        if (result > 0) {
            bytesTransferred(result)
        }
        return result
    }

    private fun readInternal(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0

        // Trigger deferred bootstrap prefetch on the first post-open read.
        if (bootstrapPrefetchDeferred && shouldAllowBackgroundPrefetch()) {
            bootstrapPrefetchDeferred = false
            scheduleFromCursor()
        }

        // Ensure work is scheduled (no-op when fallback or fully covered).
        scheduleFromCursor()

        // Surface any producer error to the reader thread.
        lastDownloadError?.let { error ->
            lastDownloadError = null
            throw error
        }

        // All bytes flow through the cursor, which reads from AbsoluteByteStore.
        // Producers (bootstrap reuse, continuation pump, fallback pump, range workers)
        // write into the store underneath; the cursor is the sole reader.
        val cursor = this.cursor ?: return C.RESULT_END_OF_INPUT
        val read = try {
            cursor.read(buffer, offset, length)
        } catch (e: IOException) {
            // If a producer failed mid-wait, surface its typed error (e.g. ChunkDownloadException)
            // instead of the cursor's generic wait-error wrapper.
            val producerError = lastDownloadError
            if (producerError != null) {
                lastDownloadError = null
                throw producerError
            }
            throw e
        }
        if (read > 0) {
            position = cursor.position
            bytesRemaining = cursor.bytesRemaining
            scheduleFromCursor()
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
    }

    override fun close() {
        val wasStarted = transferStartedFired
        transferStartedFired = false
        closed.set(true)
        stopPumps()
        cursor?.close()
        cursor = null
        openSession = null
        resolvedUri = null
        fallbackSource?.close()
        fallbackSource = null
        continuationSource?.close()
        continuationSource = null
        continuationEndPositionExclusive = C.TIME_UNSET

        transportManager.detach()
        store.reset()

        // Signal any waiting readers
        readLock.lock()
        try { dataAvailable.signalAll() } finally { readLock.unlock() }

        if (wasStarted) {
            transferEnded()
        }
        originalDataSpec = null
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
