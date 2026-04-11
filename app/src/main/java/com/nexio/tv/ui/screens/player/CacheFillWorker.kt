package com.nexio.tv.ui.screens.player

import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import java.io.IOException
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

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

    @Volatile
    private var fillFrontier: Long = 0L

    @Volatile
    private var isActive: Boolean = false

    @Volatile
    private var isPaused: Boolean = false

    @Volatile
    private var restartRequested: Boolean = false

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

        fillFrontier = maxOf(startPosition, safeStart())
        isActive = true
        isPaused = false
        fillController.onStart()

        workerThread = Thread(
            {
                run(url = url, headers = headers, contentLength = contentLength)
            },
            THREAD_NAME
        ).apply {
            isDaemon = true
            start()
        }
    }

    fun seekTo(newPosition: Long) {
        fillController.onSeek()
        restartRequested = true
        cancelActiveCall()
        fillFrontier = maxOf(newPosition, safeStart())
        isPaused = false
    }

    fun pause() {
        isPaused = true
        cancelActiveCall()
    }

    fun resume() {
        isPaused = false
    }

    fun stop() {
        isActive = false
        isPaused = false
        restartRequested = false
        fillController.stop()
        cancelActiveCall()
        workerThread?.interrupt()
        workerThread = null
    }

    private fun run(url: String, headers: Map<String, String>, contentLength: Long) {
        try {
            while (isActive && fillFrontier < contentLength && !Thread.currentThread().isInterrupted) {
                if (isPaused) {
                    sleepBriefly()
                    continue
                }

                val safeStart = safeStart()
                if (fillFrontier < safeStart) {
                    fillFrontier = safeStart
                }

                if (fillController.shouldPause(fillFrontier)) {
                    sleepBriefly()
                    continue
                }

                val start = fillFrontier.coerceAtMost(contentLength)
                val end = (start + profile.chunkBytes).coerceAtMost(contentLength)
                if (end <= start) break

                if (rangeCoordinator.isOwnedByPlaybackFallback(start, end)) {
                    fillFrontier = end
                    continue
                }

                val bytesWritten = try {
                    downloadChunkToCache(url, headers, start, end)
                } catch (e: IOException) {
                    if (restartRequested || isPaused) {
                        restartRequested = false
                        continue
                    }
                    throw e
                }
                if (bytesWritten > 0L) {
                    fillFrontier = maxOf(fillFrontier, start + bytesWritten)
                } else if (restartRequested || isPaused) {
                    restartRequested = false
                    continue
                } else if (rangeCoordinator.isOwnedByPlaybackFallback(start, end)) {
                    fillFrontier = end
                } else {
                    break
                }
            }
        } catch (e: IOException) {
            if (isActive) {
                isActive = false
            }
        } finally {
            isActive = false
            activeCall = null
        }
    }

    @VisibleForTesting
    internal fun downloadChunkToCache(
        url: String,
        headers: Map<String, String>,
        start: Long,
        end: Long
    ): Long {
        if (end <= start) return 0L

        val call = okHttpClient.newCall(buildRequest(url, headers, start, end))
        activeCall = call

        call.execute().use { response ->
            val isValidResponse = response.code == 206 || (response.code == 200 && start == 0L)
            if (!isValidResponse) {
                throw IOException("Unexpected cache fill response ${response.code}")
            }

            val bodyStream = response.body?.byteStream() ?: return 0L
            val dataSpec = DataSpec.Builder()
                .setUri(Uri.parse(url))
                .setKey(cacheKey)
                .setPosition(start)
                .setLength(end - start)
                .build()
            val sink = CacheDataSink(cache, profile.normalFragmentBytes, READ_BUFFER_SIZE)
            val lockedSpan = reserveCacheSpan(start, end)
            var totalRead = 0L

            sink.open(dataSpec)
            try {
                while (!call.isCanceled()) {
                    if (rangeCoordinator.isOwnedByPlaybackFallback(start + totalRead, end)) {
                        call.cancel()
                        break
                    }

                    val read = bodyStream.read(readBuffer, 0, readBuffer.size)
                    if (read == -1) break

                    sink.write(readBuffer, 0, read)
                    totalRead += read.toLong()
                    bandwidthMonitor.onBytesTransferred(read.toLong())
                    StreamingMetrics.fillWorkerBytesWritten.addAndGet(read.toLong())
                }
            } finally {
                sink.close()
                releaseHoleSpan(lockedSpan)
                if (activeCall === call) {
                    activeCall = null
                }
            }

            return totalRead
        }
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
        activeCall?.cancel()
        activeCall = null
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
    }
}
