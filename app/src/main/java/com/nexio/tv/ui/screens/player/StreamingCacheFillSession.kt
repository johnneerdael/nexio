package com.nexio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.SimpleCache
import okhttp3.OkHttpClient

@androidx.annotation.OptIn(UnstableApi::class)
internal class StreamingCacheFillSession(
    private val cache: SimpleCache,
    private val cacheKeyFactory: CacheKeyFactory,
    private val okHttpClient: OkHttpClient,
    private val memoryBudget: MemoryBudget,
    private val rangeCoordinator: PlaybackFallbackRangeChecker,
    private val missCoordinator: StreamingCacheMissCoordinator? = rangeCoordinator as? StreamingCacheMissCoordinator,
    private val bandwidthMonitor: BandwidthMonitor = BandwidthMonitor(),
    private val profile: ProviderProfile = ProviderProfile()
) : StreamingCacheMissCoordinator.UrgentFillHandler {
    private val sessionLock = Any()
    private var fillController: FillController? = null
    private var worker: CacheFillWorker? = null
    private var pendingStart: StartRequest? = null
    private var pendingStartWaiter: Thread? = null

    val isActive: Boolean
        get() = synchronized(sessionLock) { worker?.active == true }

    fun start(
        url: String,
        headers: Map<String, String>,
        contentLength: Long,
        playbackByteProvider: () -> Long
    ) {
        if (contentLength <= 0L || !memoryBudget.fillWorkerWithinBudget(profile.maxConnections)) {
            stop()
            return
        }

        val request = StartRequest(
            url = url,
            headers = headers.toMap(),
            contentLength = contentLength,
            playbackByteProvider = playbackByteProvider,
            cacheKey = cacheKey(url)
        )

        synchronized(sessionLock) {
            val existingWorker = worker
            if (existingWorker == null) {
                launchStartLocked(request)
                return
            }

            if (existingWorker.stopAndJoin()) {
                if (worker === existingWorker) {
                    worker = null
                    fillController = null
                }
                pendingStart = null
                launchStartLocked(request)
            } else {
                pendingStart = request
                ensurePendingStartWaiterLocked(existingWorker)
            }
        }
    }

    private fun launchStartLocked(request: StartRequest) {
        val mutations = ContentMetadataMutations()
        ContentMetadataMutations.setContentLength(mutations, request.contentLength)
        cache.applyContentMetadataMutations(request.cacheKey, mutations)

        val controller = FillController(
            profile = profile,
            cache = cache,
            cacheKey = request.cacheKey,
            playbackByteProvider = request.playbackByteProvider
        )
        val nextWorker = CacheFillWorker(
            profile = profile,
            cache = cache,
            cacheKey = request.cacheKey,
            okHttpClient = okHttpClient,
            bandwidthMonitor = bandwidthMonitor,
            fillController = controller,
            rangeCoordinator = rangeCoordinator,
            playbackByteProvider = request.playbackByteProvider
        )

        fillController = controller
        worker = nextWorker
        missCoordinator?.attachUrgentFillHandler(this)
        nextWorker.start(
            url = request.url,
            headers = request.headers,
            contentLength = request.contentLength,
            startPosition = request.playbackByteProvider().coerceAtLeast(0L)
        )
    }

    private fun ensurePendingStartWaiterLocked(workerToStop: CacheFillWorker) {
        if (pendingStartWaiter?.isAlive == true) return

        pendingStartWaiter = Thread(
            {
                awaitPendingStart(workerToStop)
            },
            PENDING_START_THREAD_NAME
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun awaitPendingStart(workerToStop: CacheFillWorker) {
        while (true) {
            val stopped = workerToStop.stopAndJoin()
            synchronized(sessionLock) {
                if (worker !== workerToStop) return
                if (stopped) {
                    worker = null
                    fillController = null
                    val request = pendingStart ?: return
                    pendingStart = null
                    launchStartLocked(request)
                    return
                }
                if (pendingStart == null) return
            }
        }
    }

    fun seekTo(newPositionBytes: Long) {
        synchronized(sessionLock) {
            worker?.seekTo(newPositionBytes)
        }
    }

    fun onMemoryWarning() {
        synchronized(sessionLock) {
            fillController?.onMemoryWarning()
        }
    }

    fun stop(): Boolean {
        synchronized(sessionLock) {
            pendingStart = null
            val stopped = worker?.stopAndJoin() ?: true
            if (stopped) {
                missCoordinator?.detachUrgentFillHandler(this)
                worker = null
                fillController = null
            }
            return stopped
        }
    }

    override fun prioritize(position: Long) {
        synchronized(sessionLock) {
            worker?.prioritize(position)
        }
    }

    override fun awaitSpanCommitted(
        cacheKey: String,
        position: Long,
        minLength: Long,
        timeoutMs: Long
    ): Boolean {
        val normalizedPosition = position.coerceAtLeast(0L)
        val normalizedLength = minLength.coerceAtLeast(1L)
        val deadlineMs = android.os.SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(0L)
        while (android.os.SystemClock.elapsedRealtime() <= deadlineMs) {
            val cachedLength = cache.getCachedLength(cacheKey, normalizedPosition, normalizedLength)
            if (cachedLength >= normalizedLength) return true
            Thread.sleep(25L)
        }
        return false
    }

    override fun estimatedBytesPerSecond(): Long {
        return bandwidthMonitor.estimatedBytesPerSecond()
    }

    private fun cacheKey(url: String): String {
        return cacheKeyFactory.buildCacheKey(
            DataSpec.Builder()
                .setUri(Uri.parse(url))
                .setLength(C.LENGTH_UNSET.toLong())
                .build()
        )
    }

    private data class StartRequest(
        val url: String,
        val headers: Map<String, String>,
        val contentLength: Long,
        val playbackByteProvider: () -> Long,
        val cacheKey: String
    )

    private companion object {
        const val PENDING_START_THREAD_NAME = "StreamingCacheFillSession-pending-start"
    }
}
