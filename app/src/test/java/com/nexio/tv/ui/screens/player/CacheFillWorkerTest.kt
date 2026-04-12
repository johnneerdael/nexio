package com.nexio.tv.ui.screens.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
    fun contentRange_mustCoverRequestedEnd() {
        val data = ByteArray(64) { it.toByte() }
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-999/1000")
                .setBody(BufferFactory.body(data))
        )
        val cache = provider.getOrCreateCache()
        val cacheKey = "movie-short-content-range"
        val worker = worker(cacheKey = cacheKey)

        try {
            worker.downloadChunkToCache(
                url = server.url("/movie").toString(),
                headers = emptyMap(),
                start = 0L,
                end = data.size.toLong()
            )
            throw AssertionError("Expected short Content-Range to fail")
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
    fun start_yieldsPlaybackOwnedRangeWithoutAdvancingFrontier() {
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

        val request = server.takeRequest(200, TimeUnit.MILLISECONDS)
        worker.stop()

        assertEquals(null, request)
        assertEquals(0L, worker.fillFrontierPosition)
    }

    @Test
    fun start_withInvalidContentLength_stopsExistingWorker() {
        val chunkBytes = 64L
        val firstRequestEntered = CountDownLatch(1)
        val requestCount = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestCount.incrementAndGet()
                firstRequestEntered.countDown()
                try {
                    while (true) {
                        Thread.sleep(10L)
                    }
                } catch (_: InterruptedException) {
                    throw IOException("interrupted")
                }
                throw IOException("unreachable")
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
            cacheKey = "movie-invalid-content-length",
            profile = profile,
            okHttpClient = client,
            safetyGapBytes = 0L
        )

        try {
            worker.start(
                url = server.url("/movie").toString(),
                headers = emptyMap(),
                contentLength = chunkBytes * 2L,
                startPosition = 0L
            )
            assertTrue(firstRequestEntered.await(1, TimeUnit.SECONDS))

            worker.start(
                url = server.url("/movie").toString(),
                headers = emptyMap(),
                contentLength = 0L,
                startPosition = 0L
            )

            assertFalse(worker.active)
            assertEquals(1, requestCount.get())
        } finally {
            worker.stop()
        }
    }

    @Test
    fun start_preservesMemoryPressureAcrossReplacementStart() {
        val chunkBytes = 64L
        val firstRequestEntered = CountDownLatch(1)
        val requestCount = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                if (requestCount.incrementAndGet() == 1) {
                    firstRequestEntered.countDown()
                    try {
                        while (true) {
                            Thread.sleep(10L)
                        }
                    } catch (_: InterruptedException) {
                        throw IOException("interrupted")
                    }
                    throw IOException("unreachable")
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
        val cache = provider.getOrCreateCache()
        val cacheKey = "movie-memory-pressure-replacement"
        val fillController = FillController(
            profile = profile,
            cache = cache,
            cacheKey = cacheKey,
            playbackByteProvider = { 0L }
        )
        val worker = worker(
            cacheKey = cacheKey,
            profile = profile,
            okHttpClient = client,
            fillController = fillController,
            safetyGapBytes = 0L
        )

        try {
            worker.start(
                url = server.url("/movie").toString(),
                headers = emptyMap(),
                contentLength = chunkBytes * 2L,
                startPosition = 0L
            )
            assertTrue(firstRequestEntered.await(1, TimeUnit.SECONDS))

            fillController.onMemoryWarning()
            worker.start(
                url = server.url("/movie").toString(),
                headers = emptyMap(),
                contentLength = chunkBytes * 2L,
                startPosition = 0L
            )

            assertEquals(FillController.State.MEMORY_PRESSURE, fillController.state)
            assertEquals(1, requestCount.get())
        } finally {
            worker.stop()
        }
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
    fun start_defersLatestReplacementUntilPreviousWorkerExits() {
        val chunkBytes = 64L
        val firstRequestEntered = CountDownLatch(1)
        val releaseFirstRequest = CountDownLatch(1)
        val requestCount = AtomicInteger(0)
        val replacementHeader = AtomicReference<String?>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                if (requestCount.incrementAndGet() == 1) {
                    firstRequestEntered.countDown()
                    while (releaseFirstRequest.count > 0L) {
                        try {
                            releaseFirstRequest.await(10L, TimeUnit.MILLISECONDS)
                        } catch (_: InterruptedException) {
                            // Keep the worker alive past the bounded join timeout.
                        }
                    }
                    throw IOException("slow to stop")
                }

                replacementHeader.set(chain.request().header("X-Replacement"))
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .header("Content-Range", "bytes 0-63/64")
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
            cacheKey = "movie-no-overlap-start",
            profile = profile,
            okHttpClient = client,
            safetyGapBytes = 0L
        )
        var firstWorkerThread: Thread? = null

        try {
            worker.start(
                url = server.url("/movie").toString(),
                headers = emptyMap(),
                contentLength = chunkBytes,
                startPosition = 0L
            )
            assertTrue(firstRequestEntered.await(1, TimeUnit.SECONDS))
            firstWorkerThread = liveCacheFillThreads().single()

            worker.start(
                url = server.url("/movie").toString(),
                headers = mapOf("X-Replacement" to "stale"),
                contentLength = chunkBytes,
                startPosition = 0L
            )
            worker.start(
                url = server.url("/movie").toString(),
                headers = mapOf("X-Replacement" to "latest"),
                contentLength = chunkBytes,
                startPosition = 0L
            )

            assertEquals(1, requestCount.get())
            assertTrue(firstWorkerThread.isAlive)
            assertEquals(listOf(firstWorkerThread), liveCacheFillThreads())

            releaseFirstRequest.countDown()

            assertTrue(waitUntil { requestCount.get() == 2 })
            assertEquals("latest", replacementHeader.get())
        } finally {
            releaseFirstRequest.countDown()
            worker.stop()
            firstWorkerThread?.let { thread ->
                assertTrue(waitUntil { !thread.isAlive })
            }
        }
    }

    @Test
    fun metrics_countFillChunkStarts() {
        StreamingMetrics.reset()
        val data = ByteArray(64) { it.toByte() }
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-63/64")
                .setBody(BufferFactory.body(data))
        )
        val worker = worker(cacheKey = "movie-fill-metrics")

        worker.downloadChunkToCache(
            url = server.url("/movie").toString(),
            headers = emptyMap(),
            start = 0L,
            end = data.size.toLong()
        )

        assertEquals(1L, StreamingMetrics.snapshot().getValue("fill_worker_chunk_starts"))
    }

    @Test
    fun pendingReplacementStart_survivesPauseResumeAndSeek() {
        val chunkBytes = 64L
        val firstRequestEntered = CountDownLatch(1)
        val releaseFirstRequest = CountDownLatch(1)
        val requestCount = AtomicInteger(0)
        val replacementHeader = AtomicReference<String?>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                if (requestCount.incrementAndGet() == 1) {
                    firstRequestEntered.countDown()
                    while (releaseFirstRequest.count > 0L) {
                        try {
                            releaseFirstRequest.await(10L, TimeUnit.MILLISECONDS)
                        } catch (_: InterruptedException) {
                            // Keep the worker alive while control commands churn.
                        }
                    }
                    throw IOException("slow to stop")
                }

                replacementHeader.set(chain.request().header("X-Replacement"))
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .header("Content-Range", "bytes 0-63/64")
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
            cacheKey = "movie-pending-start-survives-controls",
            profile = profile,
            okHttpClient = client,
            safetyGapBytes = 0L
        )

        try {
            worker.start(
                url = server.url("/movie").toString(),
                headers = emptyMap(),
                contentLength = chunkBytes,
                startPosition = 0L
            )
            assertTrue(firstRequestEntered.await(1, TimeUnit.SECONDS))

            worker.start(
                url = server.url("/movie").toString(),
                headers = mapOf("X-Replacement" to "pending"),
                contentLength = chunkBytes,
                startPosition = 0L
            )

            worker.pause()
            worker.resume()
            worker.seekTo(chunkBytes / 2L)

            releaseFirstRequest.countDown()

            assertTrue(waitUntil { requestCount.get() == 2 })
            assertEquals("pending", replacementHeader.get())
        } finally {
            releaseFirstRequest.countDown()
            worker.stop()
        }
    }

    @Test
    fun stopThenImmediateStart_doesNotReplaceStillRunningWorker() {
        val chunkBytes = 64L
        val firstRequestEntered = CountDownLatch(1)
        val releaseFirstRequest = CountDownLatch(1)
        val requestCount = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                if (requestCount.incrementAndGet() == 1) {
                    firstRequestEntered.countDown()
                    while (releaseFirstRequest.count > 0L) {
                        try {
                            releaseFirstRequest.await(10L, TimeUnit.MILLISECONDS)
                        } catch (_: InterruptedException) {
                            // Keep simulating cleanup that ignores interrupts until the test releases it.
                        }
                    }
                    throw IOException("stopped")
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
            cacheKey = "movie-stop-start-still-running",
            profile = profile,
            okHttpClient = client,
            safetyGapBytes = 0L
        )
        var firstWorkerThread: Thread? = null

        try {
            worker.start(
                url = server.url("/movie").toString(),
                headers = emptyMap(),
                contentLength = chunkBytes * 2L,
                startPosition = 0L
            )
            assertTrue(firstRequestEntered.await(1, TimeUnit.SECONDS))
            firstWorkerThread = liveCacheFillThreads().single()

            worker.stop()
            assertTrue(firstWorkerThread.isAlive)

            worker.start(
                url = server.url("/movie").toString(),
                headers = emptyMap(),
                contentLength = chunkBytes * 2L,
                startPosition = 0L
            )

            assertEquals(1, requestCount.get())
            assertEquals(listOf(firstWorkerThread), liveCacheFillThreads())
        } finally {
            releaseFirstRequest.countDown()
            worker.stop()
            firstWorkerThread?.let { thread ->
                assertTrue(waitUntil { !thread.isAlive })
            }
        }
    }

    @Test
    fun stopAndJoin_waitsForWorkerThreadToExit() {
        val chunkBytes = 64L
        val requestEntered = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val requestCount = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestCount.incrementAndGet()
                requestEntered.countDown()
                while (!releaseRequest.await(10L, TimeUnit.MILLISECONDS)) {
                    // Keep the worker active until the test releases the request.
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
            cacheKey = "movie-stop-and-join",
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
        assertTrue(requestEntered.await(1, TimeUnit.SECONDS))
        val workerThread = liveCacheFillThreads().single()

        releaseRequest.countDown()

        assertTrue(worker.stopAndJoin())
        assertFalse(workerThread.isAlive)
        assertFalse(worker.active)
        assertEquals(1, requestCount.get())
    }

    @Test
    fun concurrentStart_doesNotRunOverlappingWorkers() {
        val chunkBytes = 64L
        val requestEntered = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val startReady = CountDownLatch(2)
        val releaseStarts = CountDownLatch(1)
        val requestCount = AtomicInteger(0)
        val threadFailure = AtomicBoolean(false)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestCount.incrementAndGet()
                requestEntered.countDown()
                while (releaseRequest.count > 0L) {
                    try {
                        releaseRequest.await(10L, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        // Keep the first worker alive past a competing start's bounded join.
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
            cacheKey = "movie-concurrent-start",
            profile = profile,
            okHttpClient = client,
            safetyGapBytes = 0L
        )

        fun startThread(): Thread {
            return Thread {
                try {
                    startReady.countDown()
                    releaseStarts.await(1, TimeUnit.SECONDS)
                    worker.start(
                        url = server.url("/movie").toString(),
                        headers = emptyMap(),
                        contentLength = chunkBytes * 2L,
                        startPosition = 0L
                    )
                } catch (_: Throwable) {
                    threadFailure.set(true)
                }
            }.apply { start() }
        }

        val firstStart = startThread()
        val secondStart = startThread()

        try {
            assertTrue(startReady.await(1, TimeUnit.SECONDS))
            releaseStarts.countDown()
            assertTrue(requestEntered.await(1, TimeUnit.SECONDS))
            firstStart.join(2_000L)
            secondStart.join(2_000L)

            assertFalse(threadFailure.get())
            assertFalse(firstStart.isAlive)
            assertFalse(secondStart.isAlive)
            assertEquals(1, requestCount.get())
        } finally {
            releaseRequest.countDown()
            worker.stop()
            firstStart.join(2_000L)
            secondStart.join(2_000L)
            assertTrue(waitUntil { liveCacheFillThreads().isEmpty() })
        }
    }

    @Test
    fun prioritizeForPlaybackHole_fillsExactPositionWithoutSafetyGap() {
        val chunkBytes = 64L
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 64-127/128")
                .setBodyDelay(2L, TimeUnit.SECONDS)
                .setBody(BufferFactory.body(ByteArray(chunkBytes.toInt()) { it.toByte() }))
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 32-95/128")
                .setBody(BufferFactory.body(ByteArray(chunkBytes.toInt()) { it.toByte() }))
        )
        val cache = provider.getOrCreateCache()
        val cacheKey = "movie-urgent-priority"
        val worker = worker(
            cacheKey = cacheKey,
            profile = ProviderProfile(
                chunkBytes = chunkBytes,
                normalFragmentBytes = chunkBytes,
                fillHorizonBytes = chunkBytes * 4L,
                lowWaterBytes = chunkBytes,
                retainBehindBytes = 0L
            ),
            playbackByteProvider = { 0L },
            safetyGapBytes = 64L
        )

        worker.start(
            url = server.url("/movie").toString(),
            headers = emptyMap(),
            contentLength = 128L,
            startPosition = 0L
        )
        val initialRequest = server.takeRequest(1, TimeUnit.SECONDS)
        assertEquals("bytes=64-127", initialRequest?.getHeader("Range"))

        worker.prioritize(position = 32L)

        assertTrue(waitUntil { cache.isCached(cacheKey, 32L, chunkBytes) })
        val request = server.takeRequest(1, TimeUnit.SECONDS)
        assertEquals("bytes=32-95", request?.getHeader("Range"))
        worker.stopAndJoin()
    }

    @Test
    fun prioritize_retriesBlockedUrgentTargetAfterFallbackOwnershipClears() {
        val chunkBytes = 64L
        val contentLength = 128L
        val urgentStart = 32L
        val urgentEndExclusive = urgentStart + chunkBytes
        val coordinator = StreamingRangeCoordinator()
        val ownershipToken = coordinator.markFallbackOwned(
            start = urgentStart,
            endExclusive = urgentEndExclusive
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 32-95/128")
                .setBody(BufferFactory.body(ByteArray(chunkBytes.toInt()) { it.toByte() }))
        )
        val worker = worker(
            cacheKey = "movie-urgent-retry",
            profile = ProviderProfile(
                chunkBytes = chunkBytes,
                normalFragmentBytes = chunkBytes,
                fillHorizonBytes = chunkBytes * 4L,
                lowWaterBytes = chunkBytes,
                retainBehindBytes = 0L
            ),
            rangeCoordinator = coordinator,
            playbackByteProvider = { 0L },
            safetyGapBytes = chunkBytes
        )

        try {
            worker.start(
                url = server.url("/movie").toString(),
                headers = emptyMap(),
                contentLength = contentLength,
                startPosition = 0L
            )

            worker.prioritize(position = urgentStart)

            assertTrue(waitUntil { worker.fillFrontierPosition == urgentStart })
            assertEquals(null, server.takeRequest(200, TimeUnit.MILLISECONDS))

            coordinator.clearFallbackOwnership(ownershipToken)

            val request = server.takeRequest(1, TimeUnit.SECONDS)
            assertEquals("bytes=32-95", request?.getHeader("Range"))
        } finally {
            worker.stopAndJoin()
        }
    }

    @Test
    fun downloadChunkToCache_usesUrgentFragmentSizeWhenProvided() {
        val data = ByteArray((CacheFillWorker.URGENT_FRAGMENT_SIZE + 512L).toInt()) { it.toByte() }
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-${data.lastIndex}/${data.size}")
                .setBody(BufferFactory.body(data))
        )
        val cache = provider.getOrCreateCache()
        val cacheKey = "movie-urgent-fragment"
        val worker = worker(cacheKey = cacheKey)

        worker.downloadChunkToCache(
            url = server.url("/movie").toString(),
            headers = emptyMap(),
            start = 0L,
            end = data.size.toLong(),
            fragmentBytes = CacheFillWorker.URGENT_FRAGMENT_SIZE
        )

        assertTrue(cache.isCached(cacheKey, 0L, data.size.toLong()))
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
        playbackByteProvider: () -> Long = { 0L },
        fillController: FillController? = null,
        safetyGapBytes: Long = 0L
    ): CacheFillWorker {
        val cache = provider.getOrCreateCache()
        return CacheFillWorker(
            profile = profile,
            cache = cache,
            cacheKey = cacheKey,
            okHttpClient = okHttpClient,
            bandwidthMonitor = BandwidthMonitor(),
            fillController = fillController ?: FillController(
                profile = profile,
                cache = cache,
                cacheKey = cacheKey,
                playbackByteProvider = playbackByteProvider
            ),
            rangeCoordinator = rangeCoordinator,
            playbackByteProvider = playbackByteProvider,
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
