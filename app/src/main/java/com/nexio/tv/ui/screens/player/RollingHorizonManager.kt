package com.nexio.tv.ui.screens.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.SimpleCache
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@UnstableApi
internal class RollingHorizonManager(
    private val cache: SimpleCache,
    private val cacheKeyFactory: CacheKeyFactory,
    private val upstreamFactory: DataSource.Factory,
    private val capBytes: Long,
    private val activeReadBytePosition: AtomicLong,
    private val stop: AtomicBoolean
) {

    companion object {
        private const val TAG = "RollingHorizon"
        const val LOW_WATER_SECONDS = 30L
        const val HIGH_WATER_SECONDS = 120L
        const val MIN_EFFECTIVE_HORIZON_SECONDS = 15L
        const val HEALTH_NO_REBUFFER_MS = 10_000L
        const val HEALTH_NO_SEEK_MS = 5_000L
        const val HEALTH_MIN_BUFFER_AHEAD_SECONDS = 10L
        const val PREFETCH_BLOCK_BYTES = 4L * 1024L * 1024L  // 4MB blocks (smaller than old 16MB)
        const val IDLE_SLEEP_MS = 500L
        const val MAX_IDLE_CYCLES = 20
    }

    @Volatile var estimatedMediaBytesPerSecond: Double = 0.0
    @Volatile var lastRebufferTimeMs: Long = 0L
    @Volatile var lastSeekTimeMs: Long = 0L
    @Volatile var bufferAheadSeconds: Double = 0.0
    @Volatile var isFrontierAdvancing: Boolean = true
    private var activePrefetchWriter: CacheWriter? = null

    fun run() {
        if (capBytes <= 0L) return
        var idleCycles = 0

        while (!stop.get() && !Thread.currentThread().isInterrupted) {
            // Health gates
            if (!isHealthy()) {
                Thread.sleep(IDLE_SLEEP_MS)
                continue
            }

            val bytesPerSecond = estimatedMediaBytesPerSecond
            if (bytesPerSecond <= 0.0) {
                Thread.sleep(IDLE_SLEEP_MS)
                continue
            }

            // Calculate current cached-ahead seconds
            val readPos = activeReadBytePosition.get()
            val cacheKey = cacheKeyFactory.buildCacheKey(
                DataSpec.Builder().setUri(Uri.EMPTY).build()
            )  // Note: the actual URI will be provided when wired in Phase 5
            val cachedAheadBytes = contiguousCachedAheadBytes(cache, cacheKey, readPos, capBytes)
            val cachedAheadSecs = cachedAheadBytes / bytesPerSecond

            if (cachedAheadSecs >= HIGH_WATER_SECONDS) {
                // Enough cached ahead, idle
                idleCycles++
                if (idleCycles > MAX_IDLE_CYCLES) break
                Thread.sleep(IDLE_SLEEP_MS)
                continue
            }

            if (cachedAheadSecs >= LOW_WATER_SECONDS) {
                // Between low and high water, slow poll
                Thread.sleep(IDLE_SLEEP_MS)
                continue
            }

            idleCycles = 0

            // Need to fetch more — target HIGH_WATER_SECONDS
            val targetBytes = (HIGH_WATER_SECONDS * bytesPerSecond).toLong()
            val fetchStart = readPos + cachedAheadBytes
            val fetchLength = minOf(PREFETCH_BLOCK_BYTES, targetBytes - cachedAheadBytes, capBytes - fetchStart)

            if (fetchLength <= 0L) break

            val dataSpec = DataSpec.Builder()
                .setUri(Uri.EMPTY)  // Will be wired with actual URI in Phase 5
                .setPosition(fetchStart)
                .setLength(fetchLength)
                .build()

            // Use CacheDataSource for writing to cache
            val cacheDataSource = buildCacheDataSource()
            val writer = CacheWriter(cacheDataSource, dataSpec, null, null)
            activePrefetchWriter = writer

            runCatching { writer.cache() }.onFailure { error ->
                if (!stop.get()) {
                    Log.w(TAG, "Warm-ahead failed at offset=${fetchStart / 1024L / 1024L}MB", error)
                }
            }
            activePrefetchWriter = null
        }
    }

    fun cancel() {
        stop.set(true)
        activePrefetchWriter?.cancel()
    }

    fun onSeek() { lastSeekTimeMs = System.currentTimeMillis() }
    fun onRebuffer() { lastRebufferTimeMs = System.currentTimeMillis() }

    internal fun isHealthy(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastRebufferTimeMs < HEALTH_NO_REBUFFER_MS) return false
        if (now - lastSeekTimeMs < HEALTH_NO_SEEK_MS) return false
        if (!isFrontierAdvancing) return false
        if (bufferAheadSeconds < HEALTH_MIN_BUFFER_AHEAD_SECONDS) return false
        return true
    }

    private fun contiguousCachedAheadBytes(
        cache: SimpleCache,
        cacheKey: String,
        fromPosition: Long,
        limit: Long
    ): Long {
        var position = fromPosition
        val end = limit.coerceAtLeast(fromPosition)
        while (position < end) {
            val cachedLength = cache.getCachedLength(cacheKey, position, end - position)
            if (cachedLength <= 0L) break
            position += cachedLength
        }
        return position - fromPosition
    }

    private fun buildCacheDataSource(): CacheDataSource {
        val dataSinkFactory = CacheDataSink.Factory()
            .setCache(cache)
            .setFragmentSize(2L * 1024L * 1024L)
        return CacheDataSource.Factory()
            .setCache(cache)
            .setCacheKeyFactory(cacheKeyFactory)
            .setCacheWriteDataSinkFactory(dataSinkFactory)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()
    }
}
