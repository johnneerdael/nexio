package com.nexio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.SimpleCache
import okhttp3.OkHttpClient

@androidx.annotation.OptIn(UnstableApi::class)
internal class StreamingCacheFillSession(
    private val cache: SimpleCache,
    private val cacheKeyFactory: CacheKeyFactory,
    private val okHttpClient: OkHttpClient,
    private val memoryBudget: MemoryBudget,
    private val rangeCoordinator: StreamingRangeCoordinator,
    private val bandwidthMonitor: BandwidthMonitor = BandwidthMonitor(),
    private val profile: ProviderProfile = ProviderProfile()
) {
    private var fillController: FillController? = null
    private var worker: CacheFillWorker? = null

    val isActive: Boolean
        get() = worker?.active == true

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

        if (!stop()) return

        val cacheKey = cacheKeyFactory.buildCacheKey(
            DataSpec.Builder()
                .setUri(Uri.parse(url))
                .setLength(C.LENGTH_UNSET.toLong())
                .build()
        )
        val controller = FillController(
            profile = profile,
            cache = cache,
            cacheKey = cacheKey,
            playbackByteProvider = playbackByteProvider
        )
        val nextWorker = CacheFillWorker(
            profile = profile,
            cache = cache,
            cacheKey = cacheKey,
            okHttpClient = okHttpClient,
            bandwidthMonitor = bandwidthMonitor,
            fillController = controller,
            rangeCoordinator = rangeCoordinator,
            playbackByteProvider = playbackByteProvider
        )

        fillController = controller
        worker = nextWorker
        nextWorker.start(
            url = url,
            headers = headers,
            contentLength = contentLength,
            startPosition = playbackByteProvider().coerceAtLeast(0L)
        )
    }

    fun seekTo(newPositionBytes: Long) {
        worker?.seekTo(newPositionBytes)
    }

    fun onMemoryWarning() {
        fillController?.onMemoryWarning()
    }

    fun stop(): Boolean {
        val stopped = worker?.stopAndJoin() ?: true
        if (stopped) {
            worker = null
            fillController = null
        }
        return stopped
    }
}
