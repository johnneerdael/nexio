package com.nexio.tv.ui.screens.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CacheFillWorkerTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: StreamingCacheProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = StreamingCacheProvider(
            context = appContext(),
            cacheDirectoryName = "cache-fill-worker-${System.nanoTime()}"
        )
    }

    @After
    fun tearDown() {
        provider.release()
        provider.cacheDirectory.deleteRecursively()
        server.shutdown()
    }

    @Test
    fun downloadChunkToCache_sendsRangeAndWritesCache() {
        val data = ByteArray(64) { it.toByte() }
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-63/64")
                .setBody(BufferFactory.body(data))
        )
        val cache = provider.getOrCreateCache()
        val cacheKey = "movie-direct"
        val worker = worker(cacheKey = cacheKey)

        worker.downloadChunkToCache(
            url = server.url("/movie").toString(),
            headers = mapOf("Authorization" to "Bearer token", "Range" to "bytes=bad"),
            start = 0L,
            end = data.size.toLong()
        )

        val request = server.takeRequest(1, TimeUnit.SECONDS)
        assertEquals("bytes=0-63", request?.getHeader("Range"))
        assertEquals("Bearer token", request?.getHeader("Authorization"))
        assertTrue(cache.isCached(cacheKey, 0L, data.size.toLong()))
    }

    @Test
    fun downloadChunkToCache_rejectsMalformedContentRange() {
        val data = ByteArray(64) { it.toByte() }
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 10-73/128")
                .setBody(BufferFactory.body(data))
        )
        val cache = provider.getOrCreateCache()
        val cacheKey = "movie-bad-content-range"
        val worker = worker(cacheKey = cacheKey)

        try {
            worker.downloadChunkToCache(
                url = server.url("/movie").toString(),
                headers = emptyMap(),
                start = 0L,
                end = data.size.toLong()
            )
            throw AssertionError("Expected malformed Content-Range to fail")
        } catch (_: IOException) {
            assertFalse(cache.isCached(cacheKey, 0L, data.size.toLong()))
        }
    }

    @Test
    fun downloadChunkToCache_ignoresExtraBytesWhenOriginReturnsFullBody() {
        val requestedBytes = 64L
        val fullBody = ByteArray(128) { it.toByte() }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(BufferFactory.body(fullBody))
        )
        val cache = provider.getOrCreateCache()
        val cacheKey = "movie-range-ignored"
        val worker = worker(cacheKey = cacheKey)

        val bytesWritten = worker.downloadChunkToCache(
            url = server.url("/movie").toString(),
            headers = emptyMap(),
            start = 0L,
            end = requestedBytes
        )

        val request = server.takeRequest(1, TimeUnit.SECONDS)
        assertEquals("bytes=0-63", request?.getHeader("Range"))
        assertEquals(requestedBytes, bytesWritten)
        assertTrue(cache.isCached(cacheKey, 0L, requestedBytes))
        assertFalse(cache.isCached(cacheKey, requestedBytes, fullBody.size - requestedBytes))
    }

    @Test
    fun start_skipsPlaybackOwnedRange() {
        val chunkBytes = 64L
        val profile = ProviderProfile(
            chunkBytes = chunkBytes,
            normalFragmentBytes = chunkBytes,
            fillHorizonBytes = chunkBytes * 4L,
            lowWaterBytes = chunkBytes,
            retainBehindBytes = 0L
        )
        val coordinator = StreamingRangeCoordinator()
        coordinator.markFallbackOwned(start = 0L, endExclusive = chunkBytes)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 64-127/128")
                .setBody(BufferFactory.body(ByteArray(chunkBytes.toInt()) { 1 }))
        )
        val worker = worker(
            cacheKey = "movie-skip",
            profile = profile,
            rangeCoordinator = coordinator,
            safetyGapBytes = 0L
        )

        worker.start(
            url = server.url("/movie").toString(),
            headers = emptyMap(),
            contentLength = chunkBytes * 2L,
            startPosition = 0L
        )

        val request = server.takeRequest(1, TimeUnit.SECONDS)
        worker.stop()

        assertEquals("bytes=64-127", request?.getHeader("Range"))
        assertTrue(worker.fillFrontierPosition >= chunkBytes)
    }

    @Test
    fun readBuffer_staysAt512Kb() {
        assertEquals(512 * 1024, CacheFillWorker.READ_BUFFER_SIZE)
    }

    private fun appContext(): Context {
        return ApplicationProvider.getApplicationContext()
    }

    private fun worker(
        cacheKey: String,
        profile: ProviderProfile = ProviderProfile(),
        rangeCoordinator: StreamingRangeCoordinator = StreamingRangeCoordinator(),
        safetyGapBytes: Long = 0L
    ): CacheFillWorker {
        val cache = provider.getOrCreateCache()
        return CacheFillWorker(
            profile = profile,
            cache = cache,
            cacheKey = cacheKey,
            okHttpClient = OkHttpClient(),
            bandwidthMonitor = BandwidthMonitor(),
            fillController = FillController(
                profile = profile,
                cache = cache,
                cacheKey = cacheKey,
                playbackByteProvider = { 0L }
            ),
            rangeCoordinator = rangeCoordinator,
            playbackByteProvider = { 0L },
            safetyGapBytes = safetyGapBytes
        )
    }

    private object BufferFactory {
        fun body(data: ByteArray): okio.Buffer {
            return okio.Buffer().write(data)
        }
    }
}
