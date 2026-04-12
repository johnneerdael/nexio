package com.nexio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CoverageAwareDataSourceTest {
    private lateinit var provider: StreamingCacheProvider
    private lateinit var cache: SimpleCache
    private val cacheKeyFactory = StableCacheKeyFactory()

    @Before
    fun setUp() {
        provider = StreamingCacheProvider(
            context = ApplicationProvider.getApplicationContext(),
            cacheDirectoryName = "coverage-aware-${System.nanoTime()}"
        )
        cache = provider.getOrCreateCache()
    }

    @After
    fun tearDown() {
        provider.release()
        provider.cacheDirectory.deleteRecursively()
    }

    @Test
    fun open_fullyCachedRange_readsCacheOnly() {
        val uri = Uri.parse("https://example.com/movie.mkv")
        val dataSpec = DataSpec.Builder().setUri(uri).setPosition(0L).setLength(4L).build()
        val cacheKey = cacheKeyFactory.buildCacheKey(dataSpec)
        writeCacheSpan(uri = uri, cacheKey = cacheKey, position = 0L, bytes = byteArrayOf(1, 2, 3, 4))
        val upstreamOpens = AtomicInteger(0)
        val source = dataSource(
            upstream = FakeDataSource(byteArrayOf(9, 9, 9, 9), upstreamOpens)
        )

        assertEquals(4L, source.open(dataSpec))
        val buffer = ByteArray(4)
        assertEquals(4, source.read(buffer, 0, 4))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), buffer)
        source.close()

        assertEquals(0, upstreamOpens.get())
    }

    @Test
    fun open_startupHole_marksFallbackBeforeUpstreamAndClearsOnClose() {
        val uri = Uri.parse("https://example.com/movie.mkv")
        val coordinator = StreamingCacheMissCoordinator(StreamingRangeCoordinator())
        val upstream = FakeDataSource(byteArrayOf(10, 11, 12, 13), AtomicInteger(0)) {
            assertTrue(coordinator.isOwnedByPlaybackFallback(0L, 4L))
        }
        val source = dataSource(
            coordinator = coordinator,
            upstream = upstream,
            startup = true
        )
        val spec = DataSpec.Builder().setUri(uri).setPosition(0L).setLength(4L).build()

        source.open(spec)
        val buffer = ByteArray(4)
        assertEquals(4, source.read(buffer, 0, 4))
        source.close()

        assertArrayEquals(byteArrayOf(10, 11, 12, 13), buffer)
        assertFalse(coordinator.isOwnedByPlaybackFallback(0L, 4L))
    }

    @Test
    fun open_steadyHole_waitsForUrgentFillBeforeFallingBack() {
        val uri = Uri.parse("https://example.com/movie.mkv")
        val spec = DataSpec.Builder().setUri(uri).setPosition(0L).setLength(4L).build()
        val cacheKey = cacheKeyFactory.buildCacheKey(spec)
        val coordinator = StreamingCacheMissCoordinator(StreamingRangeCoordinator())
        val urgentRequests = AtomicInteger(0)
        coordinator.attachUrgentFillHandler(object : StreamingCacheMissCoordinator.UrgentFillHandler {
            override fun prioritize(position: Long) {
                assertEquals(0L, position)
            }

            override fun awaitSpanCommitted(
                cacheKey: String,
                position: Long,
                minLength: Long,
                timeoutMs: Long
            ): Boolean {
                urgentRequests.incrementAndGet()
                writeCacheSpan(uri = uri, cacheKey = cacheKey, position = 0L, bytes = byteArrayOf(7, 8, 9, 10))
                return true
            }

            override fun estimatedBytesPerSecond(): Long = 8L * 1024L * 1024L
        })
        val upstreamOpens = AtomicInteger(0)
        val source = dataSource(
            coordinator = coordinator,
            upstream = FakeDataSource(byteArrayOf(1, 1, 1, 1), upstreamOpens),
            startup = false
        )

        source.open(spec)
        val buffer = ByteArray(4)
        assertEquals(4, source.read(buffer, 0, 4))
        source.close()

        assertArrayEquals(byteArrayOf(7, 8, 9, 10), buffer)
        assertEquals(1, urgentRequests.get())
        assertEquals(0, upstreamOpens.get())
    }

    @Test
    fun open_partialCacheReadsCachedPrefixThenFallbackSegment() {
        val uri = Uri.parse("https://example.com/movie.mkv")
        val spec = DataSpec.Builder().setUri(uri).setPosition(0L).setLength(6L).build()
        val cacheKey = cacheKeyFactory.buildCacheKey(spec)
        writeCacheSpan(uri = uri, cacheKey = cacheKey, position = 0L, bytes = byteArrayOf(1, 2))
        val source = dataSource(
            upstream = FakeDataSource(byteArrayOf(0, 0, 3, 4, 5, 6), AtomicInteger(0)),
            startup = true
        )

        source.open(spec)
        val buffer = ByteArray(6)
        assertEquals(2, source.read(buffer, 0, 6))
        assertEquals(4, source.read(buffer, 2, 4))
        source.close()

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), buffer)
    }

    @Test
    fun open_finiteLongRequestReadsCachedPrefixThenUpstreamSuffix() {
        val uri = Uri.parse("https://example.com/movie.mkv")
        val requestLength = CoverageAwareDataSource.OPEN_ENDED_SEGMENT_BYTES + 1L
        val spec = DataSpec.Builder().setUri(uri).setPosition(0L).setLength(requestLength).build()
        val cacheKey = cacheKeyFactory.buildCacheKey(spec)
        val cachedPrefix = ByteArray(CoverageAwareDataSource.OPEN_ENDED_SEGMENT_BYTES.toInt()) { 1 }
        val upstreamBytes = ByteArray((CoverageAwareDataSource.OPEN_ENDED_SEGMENT_BYTES + 1L).toInt()) { index ->
            if (index == CoverageAwareDataSource.OPEN_ENDED_SEGMENT_BYTES.toInt()) 2 else 0
        }
        val upstreamSuffix = byteArrayOf(2)
        writeCacheSpan(uri = uri, cacheKey = cacheKey, position = 0L, bytes = cachedPrefix)
        val upstreamOpens = AtomicInteger(0)
        val source = dataSource(
            upstream = FakeDataSource(upstreamBytes, upstreamOpens),
            startup = true
        )

        assertEquals(requestLength, source.open(spec))
        val prefixBuffer = ByteArray(cachedPrefix.size)
        assertEquals(cachedPrefix.size, source.read(prefixBuffer, 0, prefixBuffer.size))
        assertArrayEquals(cachedPrefix, prefixBuffer)
        val suffixBuffer = ByteArray(upstreamSuffix.size)
        assertEquals(upstreamSuffix.size, source.read(suffixBuffer, 0, suffixBuffer.size))
        assertArrayEquals(upstreamSuffix, suffixBuffer)
        assertEquals(C.RESULT_END_OF_INPUT, source.read(suffixBuffer, 0, suffixBuffer.size))
        source.close()

        assertEquals(1, upstreamOpens.get())
    }

    @Test
    fun open_partialCacheThenFallback_reusesOriginalCacheKeyForInternalSegments() {
        val uri = Uri.parse("https://example.com/movie.mkv")
        val originalLengthKeyFactory = object : CacheKeyFactory {
            override fun buildCacheKey(dataSpec: DataSpec): String {
                return dataSpec.key ?: "${dataSpec.uri}:${dataSpec.length}"
            }
        }
        val spec = DataSpec.Builder().setUri(uri).setPosition(0L).setLength(6L).build()
        val cacheKey = originalLengthKeyFactory.buildCacheKey(spec)
        writeCacheSpan(uri = uri, cacheKey = cacheKey, position = 0L, bytes = byteArrayOf(1, 2))
        val requestedUrgentKey = AtomicReference<String>()
        val coordinator = StreamingCacheMissCoordinator(StreamingRangeCoordinator())
        coordinator.attachUrgentFillHandler(object : StreamingCacheMissCoordinator.UrgentFillHandler {
            override fun prioritize(position: Long) = Unit

            override fun awaitSpanCommitted(
                cacheKey: String,
                position: Long,
                minLength: Long,
                timeoutMs: Long
            ): Boolean {
                requestedUrgentKey.set(cacheKey)
                return false
            }

            override fun estimatedBytesPerSecond(): Long = 8L * 1024L * 1024L
        })
        val source = dataSource(
            cacheKeyFactory = originalLengthKeyFactory,
            coordinator = coordinator,
            upstream = FakeDataSource(byteArrayOf(0, 0, 3, 4, 5, 6), AtomicInteger(0)),
            startup = false
        )

        source.open(spec)
        val buffer = ByteArray(6)
        assertEquals(2, source.read(buffer, 0, 6))
        assertEquals(4, source.read(buffer, 2, 4))
        source.close()

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), buffer)
        assertEquals(cacheKey, requestedUrgentKey.get())
    }

    @Test
    fun read_zeroLengthReturnsZeroEvenWithoutSourceOrAfterEof() {
        val uri = Uri.parse("https://example.com/movie.mkv")
        val spec = DataSpec.Builder().setUri(uri).setPosition(0L).setLength(3L).build()
        val source = dataSource(
            upstream = FakeDataSource(byteArrayOf(1, 2, 3), AtomicInteger(0)),
            startup = true
        )
        val buffer = ByteArray(3)

        assertEquals(0, source.read(buffer, 0, 0))

        source.open(spec)
        assertEquals(0, source.read(buffer, 0, 0))
        assertEquals(3, source.read(buffer, 0, buffer.size))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, buffer.size))
        assertEquals(0, source.read(buffer, 0, 0))
        source.close()
    }

    @Test
    fun open_unboundedShorterThanSyntheticSegment_readsOnceThenEnds() {
        val uri = Uri.parse("https://example.com/movie.mkv")
        val spec = DataSpec.Builder().setUri(uri).setPosition(0L).setLength(C.LENGTH_UNSET.toLong()).build()
        val upstreamOpens = AtomicInteger(0)
        val source = dataSource(
            upstream = FakeDataSource(byteArrayOf(1, 2, 3), upstreamOpens),
            startup = true
        )

        assertEquals(C.LENGTH_UNSET.toLong(), source.open(spec))
        val buffer = ByteArray(3)
        assertEquals(3, source.read(buffer, 0, 3))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, 3))
        source.close()

        assertArrayEquals(byteArrayOf(1, 2, 3), buffer)
        assertEquals(1, upstreamOpens.get())
    }

    @Test
    fun open_unboundedLongRead_ownsOnlyActiveFallbackSegmentAndContinuesWithNextSegment() {
        val uri = Uri.parse("https://example.com/movie.mkv")
        val spec = DataSpec.Builder().setUri(uri).setPosition(0L).setLength(C.LENGTH_UNSET.toLong()).build()
        val ownedProbeStart = CoverageAwareDataSource.OPEN_ENDED_SEGMENT_BYTES - 1L
        val ownedProbeEnd = CoverageAwareDataSource.OPEN_ENDED_SEGMENT_BYTES
        val unownedProbeStart = CoverageAwareDataSource.OPEN_ENDED_SEGMENT_BYTES + 1L
        val unownedProbeEnd = unownedProbeStart + 1L
        val upstreamBytes = ByteArray((CoverageAwareDataSource.OPEN_ENDED_SEGMENT_BYTES + 32L).toInt()) { index ->
            (index % 251).toByte()
        }
        val upstreamOpens = AtomicInteger(0)
        val coordinator = StreamingCacheMissCoordinator(StreamingRangeCoordinator())
        val source = dataSource(
            coordinator = coordinator,
            upstream = FakeDataSource(upstreamBytes, upstreamOpens),
            startup = true
        )

        assertEquals(C.LENGTH_UNSET.toLong(), source.open(spec))
        assertTrue(coordinator.isOwnedByPlaybackFallback(ownedProbeStart, ownedProbeEnd))
        assertFalse(coordinator.isOwnedByPlaybackFallback(unownedProbeStart, unownedProbeEnd))
        val buffer = ByteArray(64 * 1024)
        var totalRead = 0
        while (true) {
            val read = source.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            totalRead += read
        }
        source.close()

        assertEquals(upstreamBytes.size, totalRead)
        assertEquals(2, upstreamOpens.get())
        assertFalse(coordinator.isOwnedByPlaybackFallback(ownedProbeStart, ownedProbeEnd))
        assertFalse(coordinator.isOwnedByPlaybackFallback(unownedProbeStart, unownedProbeEnd))
    }

    @Test
    fun open_steadyHoleWithoutActualAwaitTimeout_doesNotIncrementCoordinatorWaitTimeouts() {
        StreamingMetrics.coordinatorWaitTimeouts.set(0L)
        val uri = Uri.parse("https://example.com/movie.mkv")
        val spec = DataSpec.Builder().setUri(uri).setPosition(0L).setLength(4L).build()
        val source = dataSource(
            upstream = FakeDataSource(byteArrayOf(1, 2, 3, 4), AtomicInteger(0)),
            startup = false
        )

        source.open(spec)
        source.close()

        assertEquals(0L, StreamingMetrics.coordinatorWaitTimeouts.get())
    }

    private fun dataSource(
        cacheKeyFactory: CacheKeyFactory = this.cacheKeyFactory,
        coordinator: StreamingCacheMissCoordinator = StreamingCacheMissCoordinator(StreamingRangeCoordinator()),
        upstream: DataSource,
        startup: Boolean = true
    ): CoverageAwareDataSource {
        return CoverageAwareDataSource(
            cache = cache,
            cacheKeyFactory = cacheKeyFactory,
            cacheReadDataSourceFactory = CacheDataSource.Factory()
                .setCache(cache)
                .setCacheKeyFactory(cacheKeyFactory)
                .setCacheWriteDataSinkFactory(null),
            upstreamDataSourceFactory = DataSource.Factory { upstream },
            coordinator = coordinator,
            isStartupProvider = { startup }
        )
    }

    private fun writeCacheSpan(uri: Uri, cacheKey: String, position: Long, bytes: ByteArray) {
        val mutations = ContentMetadataMutations()
        ContentMetadataMutations.setContentLength(mutations, position + bytes.size)
        cache.applyContentMetadataMutations(cacheKey, mutations)

        val lockedSpan = try {
            cache.startReadWrite(cacheKey, position, bytes.size.toLong())
        } catch (e: InterruptedException) {
            throw AssertionError("unexpected interruption while reserving cache span", e)
        }
        val sink = CacheDataSink(cache, 1024)
        try {
            sink.open(
                DataSpec.Builder()
                    .setUri(uri)
                    .setKey(cacheKey)
                    .setPosition(position)
                    .setLength(bytes.size.toLong())
                    .build()
            )
            try {
                sink.write(bytes, 0, bytes.size)
            } finally {
                sink.close()
            }
        } finally {
            if (lockedSpan.isHoleSpan) {
                cache.releaseHoleSpan(lockedSpan)
            }
        }
    }

    private class FakeDataSource(
        private val bytes: ByteArray,
        private val openCount: AtomicInteger,
        private val onOpen: () -> Unit = {}
    ) : DataSource {
        private var readPosition = 0
        private var opened = false

        override fun open(dataSpec: DataSpec): Long {
            opened = true
            readPosition = dataSpec.position.coerceAtMost(bytes.size.toLong()).toInt()
            readLimit = when (dataSpec.length) {
                C.LENGTH_UNSET.toLong() -> bytes.size
                else -> (readPosition + dataSpec.length).coerceAtMost(bytes.size.toLong()).toInt()
            }
            openCount.incrementAndGet()
            onOpen()
            return dataSpec.length.takeIf { it != C.LENGTH_UNSET.toLong() } ?: bytes.size.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!opened) error("read before open")
            if (readPosition >= readLimit) return C.RESULT_END_OF_INPUT
            val count = minOf(length, readLimit - readPosition)
            bytes.copyInto(buffer, offset, readPosition, readPosition + count)
            readPosition += count
            return count
        }

        override fun getUri(): Uri? = Uri.parse("https://example.com/movie.mkv")

        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

        override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) = Unit

        override fun close() {
            opened = false
        }

        private var readLimit = bytes.size
    }
}
