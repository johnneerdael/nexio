package com.nexio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.SimpleCache
import java.io.IOException

internal class CoverageAwareDataSource(
    private val cache: SimpleCache,
    private val cacheKeyFactory: CacheKeyFactory,
    private val cacheReadDataSourceFactory: DataSource.Factory,
    private val upstreamDataSourceFactory: DataSource.Factory,
    private val coordinator: StreamingCacheMissCoordinator,
    private val isStartupProvider: () -> Boolean
) : DataSource {
    private var activeSource: DataSource? = null
    private var pendingSpec: DataSpec? = null
    private var currentCacheKey: String? = null
    private var currentToken: String? = null
    private var currentUri: Uri? = null
    private var responseHeaders: Map<String, List<String>> = emptyMap()
    private val transferListeners = mutableListOf<TransferListener>()

    override fun open(dataSpec: DataSpec): Long {
        closeActiveSegment()
        currentUri = dataSpec.uri
        currentCacheKey = cacheKeyFactory.buildCacheKey(dataSpec)
        pendingSpec = dataSpec.takeIf { it.length != 0L }
        openNextSegment(allowUrgentFill = true)
        return dataSpec.length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        while (true) {
            val source = activeSource ?: return C.RESULT_END_OF_INPUT
            val read = source.read(buffer, offset, length)
            if (read != C.RESULT_END_OF_INPUT) return read
            closeActiveSegment()
            if (pendingSpec == null) return C.RESULT_END_OF_INPUT
            openNextSegment(allowUrgentFill = true)
        }
    }

    override fun getUri(): Uri? = activeSource?.uri ?: currentUri

    override fun getResponseHeaders(): Map<String, List<String>> {
        return activeSource?.responseHeaders ?: responseHeaders
    }

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
        activeSource?.addTransferListener(transferListener)
    }

    override fun close() {
        closeActiveSegment()
        pendingSpec = null
        currentCacheKey = null
    }

    private fun openNextSegment(allowUrgentFill: Boolean) {
        val spec = pendingSpec ?: return
        val cacheKey = currentCacheKey ?: cacheKeyFactory.buildCacheKey(spec).also { currentCacheKey = it }
        val queryLength = queryLength(spec)
        val cachedLength = cache.getCachedLength(cacheKey, spec.position, queryLength)

        when {
            isFullyCached(spec, cachedLength, queryLength) -> {
                StreamingMetrics.cacheHits.incrementAndGet()
                openSegment(cacheReadDataSourceFactory.createDataSource(), spec.withCacheKey(cacheKey))
                pendingSpec = null
            }
            cachedLength > 0L -> {
                StreamingMetrics.cacheHits.incrementAndGet()
                val segmentLength = cachedLength.coerceAtMost(queryLength)
                openSegment(cacheReadDataSourceFactory.createDataSource(), spec.withSegmentLength(segmentLength, cacheKey))
                pendingSpec = spec.afterSegment(segmentLength)
            }
            else -> openHoleSegment(
                spec = spec,
                cacheKey = cacheKey,
                cachedLength = cachedLength,
                queryLength = queryLength,
                allowUrgentFill = allowUrgentFill
            )
        }
    }

    private fun openHoleSegment(
        spec: DataSpec,
        cacheKey: String,
        cachedLength: Long,
        queryLength: Long,
        allowUrgentFill: Boolean
    ) {
        StreamingMetrics.cacheMisses.incrementAndGet()
        val holeLength = if (cachedLength < 0L) -cachedLength else queryLength
        val segmentLength = holeLength.coerceAtMost(queryLength).coerceAtLeast(1L)
        if (allowUrgentFill && !isStartupProvider()) {
            val urgentLength = segmentLength.coerceAtMost(CacheFillWorker.URGENT_FRAGMENT_SIZE)
            val timeoutMs = computeWaitTimeoutMs()
            if (coordinator.requestUrgentFill(cacheKey, spec.position, urgentLength, timeoutMs)) {
                openNextSegment(allowUrgentFill = false)
                return
            }
        }

        val segmentSpec = if (spec.length == C.LENGTH_UNSET.toLong()) {
            spec.withCacheKey(cacheKey)
        } else {
            spec.withSegmentLength(segmentLength, cacheKey)
        }
        val endExclusive = spec.position + segmentLength
        val token = coordinator.markFallbackOwned(spec.position, endExclusive)
        try {
            openSegment(upstreamDataSourceFactory.createDataSource(), segmentSpec)
            currentToken = token
            pendingSpec = if (spec.length == C.LENGTH_UNSET.toLong()) null else spec.afterSegment(segmentLength)
        } catch (error: Throwable) {
            coordinator.clearFallbackOwnership(token)
            throw error
        }
    }

    private fun openSegment(source: DataSource, spec: DataSpec) {
        transferListeners.forEach(source::addTransferListener)
        try {
            source.open(spec)
        } catch (error: Throwable) {
            try {
                source.close()
            } catch (closeError: IOException) {
                error.addSuppressed(closeError)
            }
            throw error
        }
        activeSource = source
        currentUri = source.uri ?: spec.uri
        responseHeaders = source.responseHeaders
    }

    private fun closeActiveSegment() {
        val token = currentToken
        currentToken = null
        try {
            activeSource?.close()
        } finally {
            activeSource = null
            if (token != null) {
                coordinator.clearFallbackOwnership(token)
            }
        }
    }

    private fun isFullyCached(spec: DataSpec, cachedLength: Long, queryLength: Long): Boolean {
        return spec.length != C.LENGTH_UNSET.toLong() && cachedLength >= queryLength
    }

    private fun queryLength(spec: DataSpec): Long {
        return if (spec.length == C.LENGTH_UNSET.toLong()) {
            OPEN_ENDED_SEGMENT_BYTES
        } else {
            spec.length.coerceAtMost(OPEN_ENDED_SEGMENT_BYTES).coerceAtLeast(1L)
        }
    }

    private fun computeWaitTimeoutMs(): Long {
        val bytesPerSecond = coordinator.estimatedBytesPerSecond()
        if (bytesPerSecond <= 0L) return DEFAULT_WAIT_TIMEOUT_MS
        val fillTimeMs = CacheFillWorker.URGENT_FRAGMENT_SIZE * 1_000L / bytesPerSecond
        return (fillTimeMs * 3L / 2L).coerceIn(MIN_WAIT_TIMEOUT_MS, MAX_WAIT_TIMEOUT_MS)
    }

    private fun DataSpec.withSegmentLength(segmentLength: Long, cacheKey: String): DataSpec {
        return buildUpon()
            .setPosition(position)
            .setLength(segmentLength)
            .setKey(cacheKey)
            .build()
    }

    private fun DataSpec.withCacheKey(cacheKey: String): DataSpec {
        return buildUpon()
            .setKey(cacheKey)
            .build()
    }

    private fun DataSpec.afterSegment(segmentLength: Long): DataSpec? {
        val nextPosition = position + segmentLength
        return if (length == C.LENGTH_UNSET.toLong()) {
            buildUpon()
                .setPosition(nextPosition)
                .setLength(C.LENGTH_UNSET.toLong())
                .build()
        } else {
            val remaining = length - segmentLength
            if (remaining <= 0L) {
                null
            } else {
                buildUpon()
                    .setPosition(nextPosition)
                    .setLength(remaining)
                    .build()
            }
        }
    }

    class Factory(
        private val cache: SimpleCache,
        private val cacheKeyFactory: CacheKeyFactory,
        private val cacheReadDataSourceFactory: DataSource.Factory,
        private val upstreamDataSourceFactory: DataSource.Factory,
        private val coordinator: StreamingCacheMissCoordinator,
        private val isStartupProvider: () -> Boolean
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return CoverageAwareDataSource(
                cache = cache,
                cacheKeyFactory = cacheKeyFactory,
                cacheReadDataSourceFactory = cacheReadDataSourceFactory,
                upstreamDataSourceFactory = upstreamDataSourceFactory,
                coordinator = coordinator,
                isStartupProvider = isStartupProvider
            )
        }
    }

    companion object {
        const val OPEN_ENDED_SEGMENT_BYTES = 4L * 1024L * 1024L
        const val DEFAULT_WAIT_TIMEOUT_MS = 3_000L
        const val MIN_WAIT_TIMEOUT_MS = 1_000L
        const val MAX_WAIT_TIMEOUT_MS = 5_000L
    }
}
