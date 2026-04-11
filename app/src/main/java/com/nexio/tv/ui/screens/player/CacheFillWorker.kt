package com.nexio.tv.ui.screens.player

import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

@androidx.annotation.OptIn(UnstableApi::class)
internal class CacheFillWorker(
    private val profile: ProviderProfile,
    private val cache: SimpleCache,
    private val cacheKey: String,
    private val okHttpClient: OkHttpClient,
    private val bandwidthMonitor: BandwidthMonitor,
    private val fillController: FillController,
    private val rangeCoordinator: StreamingRangeCoordinator,
    private val playbackByteProvider: () -> Long,
    private val safetyGapBytes: Long = 8L * 1024L * 1024L
) {
    private val readBuffer = ByteArray(READ_BUFFER_SIZE)
    private val generation = AtomicLong(0L)
    private val commandSerial = AtomicLong(0L)
    private val pendingSeekTarget = AtomicLong(NO_PENDING_SEEK)
    private val pauseRequested = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val callLock = Any()

    @Volatile
    private var fillFrontier: Long = 0L

    @Volatile
    private var isActive: Boolean = false

    @Volatile
    private var workerThread: Thread? = null

    @Volatile
    private var activeCall: Call? = null

    val fillFrontierPosition: Long
        get() = fillFrontier

    val active: Boolean
        get() = isActive

    fun start(url: String, headers: Map<String, String>, contentLength: Long, startPosition: Long) {
        if (contentLength <= 0L) return

        stop()

        val workerGeneration = generation.incrementAndGet()
        commandSerial.incrementAndGet()
        pendingSeekTarget.set(NO_PENDING_SEEK)
        pauseRequested.set(false)
        stopRequested.set(false)
        fillFrontier = maxOf(startPosition, safeStart()).coerceAtMost(contentLength)
        isActive = true
        fillController.onStart()

        workerThread = Thread(
            {
                run(url = url, headers = headers, contentLength = contentLength, workerGeneration = workerGeneration)
            },
            THREAD_NAME
        ).apply {
            isDaemon = true
            start()
        }
    }

    fun seekTo(newPosition: Long) {
        pendingSeekTarget.set(newPosition.coerceAtLeast(0L))
        commandSerial.incrementAndGet()
        pauseRequested.set(false)
        fillController.onSeek()
        cancelActiveCall()
    }

    fun pause() {
        pauseRequested.set(true)
        commandSerial.incrementAndGet()
        cancelActiveCall()
    }

    fun resume() {
        pauseRequested.set(false)
        commandSerial.incrementAndGet()
    }

    fun stop() {
        generation.incrementAndGet()
        commandSerial.incrementAndGet()
        stopRequested.set(true)
        isActive = false
        pauseRequested.set(false)
        pendingSeekTarget.set(NO_PENDING_SEEK)
        fillController.stop()
        cancelActiveCall()
        workerThread?.interrupt()
        workerThread = null
    }

    private fun run(url: String, headers: Map<String, String>, contentLength: Long, workerGeneration: Long) {
        try {
            while (
                isCurrentGeneration(workerGeneration) &&
                isActive &&
                !stopRequested.get() &&
                fillFrontier < contentLength &&
                !Thread.currentThread().isInterrupted
            ) {
                val seekTarget = pendingSeekTarget.getAndSet(NO_PENDING_SEEK)
                if (seekTarget != NO_PENDING_SEEK) {
                    fillFrontier = maxOf(seekTarget, safeStart()).coerceAtMost(contentLength)
                    continue
                }

                if (pauseRequested.get()) {
                    sleepBriefly()
                    continue
                }

                val safeStart = safeStart()
                if (fillFrontier < safeStart) {
                    fillFrontier = safeStart.coerceAtMost(contentLength)
                }

                if (fillController.shouldPause(fillFrontier)) {
                    sleepBriefly()
                    continue
                }

                val start = fillFrontier.coerceAtMost(contentLength)
                val end = (start + profile.chunkBytes).coerceAtMost(contentLength)
                if (end <= start) break

                val resultCommandSerial = commandSerial.get()
                if (rangeCoordinator.isOwnedByPlaybackFallback(start, end)) {
                    applyChunkResult(
                        ChunkResult(
                            generation = workerGeneration,
                            commandSerial = resultCommandSerial,
                            start = start,
                            end = end,
                            bytesWritten = end - start
                        )
                    )
                    continue
                }

                val result = try {
                    downloadChunkToCache(
                        url = url,
                        headers = headers,
                        start = start,
                        end = end,
                        workerGeneration = workerGeneration,
                        resultCommandSerial = resultCommandSerial
                    )
                } catch (e: IOException) {
                    if (shouldYieldChunk(workerGeneration, resultCommandSerial)) {
                        continue
                    }
                    throw e
                }

                if (!applyChunkResult(result)) {
                    continue
                } else if (result.bytesWritten <= 0L) {
                    if (rangeCoordinator.isOwnedByPlaybackFallback(start, end)) {
                        continue
                    }
                    break
                }
            }
        } catch (_: IOException) {
        } finally {
            if (isCurrentGeneration(workerGeneration)) {
                isActive = false
                synchronized(callLock) {
                    activeCall = null
                }
            }
        }
    }

    @VisibleForTesting
    internal fun downloadChunkToCache(
        url: String,
        headers: Map<String, String>,
        start: Long,
        end: Long,
        workerGeneration: Long = generation.get(),
        resultCommandSerial: Long = commandSerial.get()
    ): ChunkResult {
        if (end <= start) {
            return ChunkResult(workerGeneration, resultCommandSerial, start, end, 0L)
        }

        val requestedLength = end - start
        if (cache.isCached(cacheKey, start, requestedLength)) {
            return ChunkResult(workerGeneration, resultCommandSerial, start, end, requestedLength)
        }

        val cachedPrefixLength = cache.getCachedLength(cacheKey, start, requestedLength)
        if (cachedPrefixLength > 0L) {
            return ChunkResult(
                generation = workerGeneration,
                commandSerial = resultCommandSerial,
                start = start,
                end = start + cachedPrefixLength.coerceAtMost(requestedLength),
                bytesWritten = cachedPrefixLength.coerceAtMost(requestedLength)
            )
        }

        var chunkEnd = end
        if (cachedPrefixLength < 0L) {
            val holeLength = -cachedPrefixLength
            if (holeLength <= 0L) {
                return ChunkResult(workerGeneration, resultCommandSerial, start, end, 0L)
            }
            chunkEnd = start + holeLength.coerceAtMost(requestedLength)
        }

        if (!canContinueChunk(workerGeneration, resultCommandSerial)) {
            return ChunkResult(workerGeneration, resultCommandSerial, start, chunkEnd, 0L)
        }

        val call = okHttpClient.newCall(buildRequest(url, headers, start, chunkEnd))
        synchronized(callLock) {
            if (!canContinueChunk(workerGeneration, resultCommandSerial)) {
                call.cancel()
                return ChunkResult(workerGeneration, resultCommandSerial, start, chunkEnd, 0L)
            }
            activeCall = call
        }

        try {
            call.execute().use { response ->
                validateResponse(response, start)

                val downloadLength = chunkEnd - start
                val bodyStream = response.body?.byteStream()
                    ?: return ChunkResult(workerGeneration, resultCommandSerial, start, chunkEnd, 0L)
                val lockedSpan = reserveCacheSpan(start, chunkEnd)
                if (!lockedSpan.isHoleSpan) {
                    return ChunkResult(
                        generation = workerGeneration,
                        commandSerial = resultCommandSerial,
                        start = start,
                        end = (start + lockedSpan.length.coerceAtMost(downloadLength)).coerceAtMost(chunkEnd),
                        bytesWritten = lockedSpan.length.coerceAtMost(downloadLength)
                    )
                }
                val dataSpec = DataSpec.Builder()
                    .setUri(Uri.parse(url))
                    .setKey(cacheKey)
                    .setPosition(start)
                    .setLength(downloadLength)
                    .build()
                val sink = CacheDataSink(cache, profile.normalFragmentBytes)
                var totalRead = 0L

                var failure: Throwable? = null
                try {
                    if (!canContinueChunk(workerGeneration, resultCommandSerial)) {
                        call.cancel()
                        return ChunkResult(workerGeneration, resultCommandSerial, start, chunkEnd, 0L)
                    }
                    sink.open(dataSpec)
                    while (!call.isCanceled()) {
                        val remainingBytes = downloadLength - totalRead
                        if (remainingBytes <= 0L) break

                        val currentPosition = start + totalRead
                        if (
                            !canContinueChunk(workerGeneration, resultCommandSerial) ||
                            rangeCoordinator.isOwnedByPlaybackFallback(currentPosition, chunkEnd)
                        ) {
                            call.cancel()
                            break
                        }

                        val read = bodyStream.read(
                            readBuffer,
                            0,
                            minOf(readBuffer.size.toLong(), remainingBytes).toInt()
                        )
                        if (read == -1) break

                        if (
                            !canContinueChunk(workerGeneration, resultCommandSerial) ||
                            rangeCoordinator.isOwnedByPlaybackFallback(currentPosition, chunkEnd)
                        ) {
                            call.cancel()
                            break
                        }

                        sink.write(readBuffer, 0, read)
                        totalRead += read.toLong()
                        bandwidthMonitor.onBytesTransferred(read.toLong())
                        StreamingMetrics.fillWorkerBytesWritten.addAndGet(read.toLong())
                    }
                } catch (t: Throwable) {
                    failure = t
                    throw t
                } finally {
                    var closeFailureToThrow: Throwable? = null
                    try {
                        sink.close()
                    } catch (closeFailure: Throwable) {
                        if (failure == null) {
                            closeFailureToThrow = closeFailure
                        } else {
                            failure.addSuppressed(closeFailure)
                        }
                    }

                    try {
                        releaseHoleSpan(lockedSpan)
                    } catch (releaseFailure: Throwable) {
                        when {
                            failure != null -> failure.addSuppressed(releaseFailure)
                            closeFailureToThrow != null -> closeFailureToThrow.addSuppressed(releaseFailure)
                            else -> throw releaseFailure
                        }
                    }

                    closeFailureToThrow?.let { throw it }
                }

                return ChunkResult(workerGeneration, resultCommandSerial, start, chunkEnd, totalRead)
            }
        } finally {
            synchronized(callLock) {
                if (activeCall === call) {
                    activeCall = null
                }
            }
        }
    }

    private fun isCurrentGeneration(workerGeneration: Long): Boolean {
        return generation.get() == workerGeneration
    }

    private fun canContinueChunk(workerGeneration: Long, resultCommandSerial: Long): Boolean {
        return isCurrentGeneration(workerGeneration) &&
            commandSerial.get() == resultCommandSerial &&
            !stopRequested.get() &&
            !pauseRequested.get() &&
            pendingSeekTarget.get() == NO_PENDING_SEEK
    }

    private fun shouldYieldChunk(workerGeneration: Long, resultCommandSerial: Long): Boolean {
        return !isCurrentGeneration(workerGeneration) ||
            commandSerial.get() != resultCommandSerial ||
            stopRequested.get() ||
            pauseRequested.get() ||
            pendingSeekTarget.get() != NO_PENDING_SEEK
    }

    private fun applyChunkResult(result: ChunkResult): Boolean {
        if (!canContinueChunk(result.generation, result.commandSerial)) return false
        if (result.bytesWritten > 0L) {
            val resultFrontier = (result.start + result.bytesWritten).coerceAtMost(result.end)
            fillFrontier = maxOf(fillFrontier, resultFrontier)
        }
        return true
    }

    private fun validateResponse(response: Response, requestedStart: Long) {
        if (response.code == 200 && requestedStart == 0L) return
        if (response.code == 206 && isValidContentRange(response.header("Content-Range"), requestedStart)) return
        if (response.code == 206) {
            throw IOException("Malformed cache fill Content-Range ${response.header("Content-Range")}")
        }
        throw IOException("Unexpected cache fill response ${response.code}")
    }

    private fun isValidContentRange(contentRange: String?, requestedStart: Long): Boolean {
        val match = CONTENT_RANGE_PATTERN.matchEntire(contentRange ?: return false) ?: return false
        val responseStart = match.groupValues[1].toLongOrNull() ?: return false
        val responseEnd = match.groupValues[2].toLongOrNull() ?: return false
        val total = match.groupValues[3]
        if (responseStart != requestedStart || responseEnd < responseStart) return false
        if (total == "*") return true
        val numericTotal = total.toLongOrNull() ?: return false
        return numericTotal >= responseEnd + 1L
    }

    private fun reserveCacheSpan(start: Long, end: Long): CacheSpan {
        try {
            return cache.startReadWrite(cacheKey, start, end - start)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while reserving cache fill span", e)
        }
    }

    private fun releaseHoleSpan(span: CacheSpan) {
        if (span.isHoleSpan) {
            cache.releaseHoleSpan(span)
        }
    }

    private fun buildRequest(
        url: String,
        headers: Map<String, String>,
        start: Long,
        end: Long
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .header("Range", "bytes=$start-${end - 1L}")

        headers.forEach { (name, value) ->
            if (!name.equals("Range", ignoreCase = true)) {
                builder.header(name, value)
            }
        }

        return builder.build()
    }

    private fun cancelActiveCall() {
        synchronized(callLock) {
            activeCall?.cancel()
            activeCall = null
        }
    }

    private fun safeStart(): Long {
        return (playbackByteProvider() + safetyGapBytes).coerceAtLeast(0L)
    }

    private fun sleepBriefly() {
        try {
            Thread.sleep(PAUSE_SLEEP_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        const val READ_BUFFER_SIZE = MemoryBudget.FILL_WORKER_READ_BUFFER_BYTES
        const val THREAD_NAME = "CacheFill-0"
        private const val PAUSE_SLEEP_MS = 20L
        private const val NO_PENDING_SEEK = Long.MIN_VALUE
        private val CONTENT_RANGE_PATTERN = Regex("""bytes\s+(\d+)-(\d+)/(\*|\d+)""")
    }

    @VisibleForTesting
    internal fun commandSerialForTesting(): Long {
        return commandSerial.get()
    }

    @VisibleForTesting
    internal fun applyChunkResultForTesting(
        resultStart: Long,
        resultEnd: Long,
        bytesWritten: Long,
        resultCommandSerial: Long
    ): Boolean {
        return applyChunkResult(
            ChunkResult(
                generation = generation.get(),
                commandSerial = resultCommandSerial,
                start = resultStart,
                end = resultEnd,
                bytesWritten = bytesWritten
            )
        )
    }

    internal data class ChunkResult(
        val generation: Long,
        val commandSerial: Long,
        val start: Long,
        val end: Long,
        val bytesWritten: Long
    )
}
