package com.nexio.tv.ui.screens.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
                .setHeader("Content-Range", "bytes 0-63/32")
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

        val result = worker.downloadChunkToCache(
            url = server.url("/movie").toString(),
            headers = emptyMap(),
            start = 0L,
            end = requestedBytes
        )

        val request = server.takeRequest(1, TimeUnit.SECONDS)
        assertEquals("bytes=0-63", request?.getHeader("Range"))
        assertEquals(requestedBytes, result.bytesWritten)
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
    fun staleChunkProgressCannotOverwriteProcessedSeek() {
        val chunkBytes = 64L
        val seekTarget = chunkBytes * 4L
        val profile = ProviderProfile(
            chunkBytes = chunkBytes,
            normalFragmentBytes = chunkBytes,
            fillHorizonBytes = chunkBytes * 16L,
            lowWaterBytes = chunkBytes,
            retainBehindBytes = 0L
        )
        val worker = worker(
            cacheKey = "movie-command-seek",
            profile = profile,
            safetyGapBytes = 0L
        )

        worker.start(
            url = server.url("/movie").toString(),
            headers = emptyMap(),
            contentLength = seekTarget + chunkBytes,
            startPosition = 0L
        )
        val staleCommandSerial = worker.commandSerialForTesting()

        worker.seekTo(seekTarget)
        assertTrue(
            waitUntil {
                worker.fillFrontierPosition == seekTarget
            }
        )

        val applied = worker.applyChunkResultForTesting(
            resultStart = 0L,
            resultEnd = chunkBytes,
            bytesWritten = chunkBytes,
            resultCommandSerial = staleCommandSerial
        )
        worker.stop()

        assertFalse(applied)
        assertEquals(seekTarget, worker.fillFrontierPosition)
    }

    @Test
    fun start_waitsForPreviousWorkerThreadToExitBeforeStartingAgain() {
        val chunkBytes = 64L
        val firstRequestEntered = CountDownLatch(1)
        val isFirstRequest = AtomicBoolean(true)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                if (isFirstRequest.getAndSet(false)) {
                    firstRequestEntered.countDown()
                    try {
                        while (true) {
                            Thread.sleep(10L)
                        }
                    } catch (_: InterruptedException) {
                        Thread.sleep(150L)
                        throw IOException("interrupted")
                    }
                }

                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .header("Content-Range", "bytes 0-63/128")
                    .body(ByteArray(chunkBytes.toInt()) { 1 }.toResponseBody())
                    .build()
            }
            .build()
        val profile = ProviderProfile(
            chunkBytes = chunkBytes,
            normalFragmentBytes = chunkBytes,
            fillHorizonBytes = chunkBytes * 16L,
            lowWaterBytes = chunkBytes,
            retainBehindBytes = 0L
        )
        val worker = worker(
            cacheKey = "movie-serialized-start",
            profile = profile,
            okHttpClient = client,
            safetyGapBytes = 0L
        )

        worker.start(
            url = server.url("/movie").toString(),
            headers = emptyMap(),
            contentLength = chunkBytes * 2L,
            startPosition = 0L
        )
        assertTrue(firstRequestEntered.await(1, TimeUnit.SECONDS))
        val firstWorkerThread = liveCacheFillThreads().single()

        worker.start(
            url = server.url("/movie").toString(),
            headers = emptyMap(),
            contentLength = chunkBytes * 2L,
            startPosition = 0L
        )
        worker.stop()

        assertFalse(firstWorkerThread.isAlive)
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
        okHttpClient: OkHttpClient = OkHttpClient(),
        safetyGapBytes: Long = 0L
    ): CacheFillWorker {
        val cache = provider.getOrCreateCache()
        return CacheFillWorker(
            profile = profile,
            cache = cache,
            cacheKey = cacheKey,
            okHttpClient = okHttpClient,
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

    private fun waitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10L)
        }
        return condition()
    }

    private fun liveCacheFillThreads(): List<Thread> {
        return Thread.getAllStackTraces().keys
            .filter { thread -> thread.name == CacheFillWorker.THREAD_NAME && thread.isAlive }
    }

    private object BufferFactory {
        fun body(data: ByteArray): okio.Buffer {
            return okio.Buffer().write(data)
        }
    }
}
