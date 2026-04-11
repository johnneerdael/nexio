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
            upstream = FakeDataSource(byteArrayOf(3, 4, 5, 6), AtomicInteger(0)),
            startup = true
        )

        source.open(spec)
        val buffer = ByteArray(6)
        assertEquals(2, source.read(buffer, 0, 6))
        assertEquals(4, source.read(buffer, 2, 4))
        source.close()

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), buffer)
    }

    private fun dataSource(
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
            readPosition = 0
            openCount.incrementAndGet()
            onOpen()
            return dataSpec.length.takeIf { it != C.LENGTH_UNSET.toLong() } ?: bytes.size.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!opened) error("read before open")
            if (readPosition >= bytes.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, bytes.size - readPosition)
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
    }
}
