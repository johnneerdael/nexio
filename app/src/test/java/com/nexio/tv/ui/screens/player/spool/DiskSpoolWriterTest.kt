package com.nexio.tv.ui.screens.player.spool

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import com.nexio.tv.data.integration.playback.transport.OkHttpDiskSpoolHttpTransport
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class DiskSpoolWriterTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `probe discovers content length from content range`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Accept-Ranges", "bytes")
                .setHeader("Content-Range", "bytes 0-0/1048576")
                .setHeader("Content-Length", 1)
                .setBody(Buffer().writeByte(0x2A))
        )
        server.start()

        try {
            val writer = DiskSpoolWriter(OkHttpDiskSpoolHttpTransport(OkHttpClient()))

            val metadata = writer.probe(server.url("/movie.bin").toString())

            assertEquals(1_048_576L, metadata.contentLength)
            assertTrue(metadata.supportsRanges)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `probe and range requests include playback headers`() {
        val content = ByteArray(1024) { (it % 251).toByte() }
        val requests = mutableListOf<RecordedRequest>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) {
                    requests += request
                }
                return when (request.getHeader("Range")) {
                    "bytes=0-0" -> MockResponse()
                        .setResponseCode(206)
                        .setHeader("Accept-Ranges", "bytes")
                        .setHeader("Content-Range", "bytes 0-0/${content.size}")
                        .setHeader("Content-Length", 1)
                        .setBody(Buffer().writeByte(0x2A))

                    "bytes=0-1023" -> MockResponse()
                        .setResponseCode(206)
                        .setHeader("Accept-Ranges", "bytes")
                        .setHeader("Content-Range", "bytes 0-1023/${content.size}")
                        .setHeader("Content-Length", content.size)
                        .setBody(Buffer().write(content))

                    else -> MockResponse().setResponseCode(400)
                }
            }
        }
        server.start()

        val session = DiskSpoolSession(
            File(temp.root, "headers.spool"),
            capacityBytes = 2 * 1024L,
            waitTimeoutMs = 1_000L
        )

        try {
            val writer = DiskSpoolWriter(
                OkHttpDiskSpoolHttpTransport(OkHttpClient()),
                requestHeaders = mapOf(
                    "Authorization" to "Bearer token",
                    "X-Playback-Token" to "custom-token",
                    "Range" to "bytes=999-1000"
                ),
                chunkBytes = content.size,
                ioBufferBytes = 256
            )

            writer.downloadUntil(
                url = server.url("/movie.bin").toString(),
                session = session,
                targetFrontierBytes = content.size.toLong()
            )

            val recorded = synchronized(requests) { requests.toList() }
            assertEquals(2, recorded.size)
            assertEquals("Bearer token", recorded[0].getHeader("Authorization"))
            assertEquals("custom-token", recorded[0].getHeader("X-Playback-Token"))
            assertEquals("bytes=0-0", recorded[0].getHeader("Range"))
            assertEquals("Bearer token", recorded[1].getHeader("Authorization"))
            assertEquals("custom-token", recorded[1].getHeader("X-Playback-Token"))
            assertEquals("bytes=0-1023", recorded[1].getHeader("Range"))
        } finally {
            session.close()
            server.shutdown()
        }
    }

    @Test
    fun `writer streams ranges into session and advances frontier`() {
        val content = ByteArray(256 * 1024) { (it % 251).toByte() }
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val rangeHeader = request.getHeader("Range")
                return if (rangeHeader == null) {
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Accept-Ranges", "bytes")
                        .setHeader("Content-Length", content.size)
                        .setBody(Buffer().write(content))
                } else {
                    rangedResponse(content, rangeHeader)
                }
            }
        }
        server.start()

        val session = DiskSpoolSession(
            File(temp.root, "movie.spool"),
            capacityBytes = 512 * 1024L,
            waitTimeoutMs = 1_000L
        )

        try {
            val writer = DiskSpoolWriter(
                OkHttpDiskSpoolHttpTransport(OkHttpClient()),
                chunkBytes = 64 * 1024,
                ioBufferBytes = 16 * 1024
            )

            writer.downloadUntil(
                url = server.url("/movie.bin").toString(),
                session = session,
                targetFrontierBytes = content.size.toLong()
            )

            val buffer = ByteArray(content.size)
            val read = session.read(0L, buffer, 0, buffer.size)

            assertEquals(content.size, read)
            assertArrayEquals(content, buffer)
            assertEquals(content.size.toLong(), session.contiguousFrontierBytes())
        } finally {
            session.close()
            server.shutdown()
        }
    }

    @Test
    fun `writer rebases when session reports priority seek`() {
        val content = ByteArray(256 * 1024) { (it % 251).toByte() }
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val rangeHeader = request.getHeader("Range")
                return if (rangeHeader == null) {
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Accept-Ranges", "bytes")
                        .setHeader("Content-Length", content.size)
                        .setBody(Buffer().write(content))
                } else {
                    rangedResponse(content, rangeHeader)
                }
            }
        }
        server.start()

        val session = DiskSpoolSession(
            File(temp.root, "movie.spool"),
            capacityBytes = 128 * 1024L,
            waitTimeoutMs = 0L
        )

        try {
            assertEquals(-1, session.read(192 * 1024L, ByteArray(8), 0, 8))
            val writer = DiskSpoolWriter(
                OkHttpDiskSpoolHttpTransport(OkHttpClient()),
                chunkBytes = 32 * 1024,
                ioBufferBytes = 8 * 1024
            )

            writer.downloadUntil(
                url = server.url("/movie.bin").toString(),
                session = session,
                targetFrontierBytes = 224 * 1024L
            )

            assertEquals(224 * 1024L, session.contiguousFrontierBytes())
            assertEquals(192 * 1024L, session.windowStartBytes())
        } finally {
            session.close()
            server.shutdown()
        }
    }

    @Test
    fun `downloadSequentially returns immediately after top-of-loop priority rebase`() {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Accept-Ranges", "bytes")
                    .setHeader("Content-Range", "bytes 0-0/524288")
                    .setHeader("Content-Length", 1)
                    .setBody(Buffer().writeByte(0x2A))
            }
        }
        server.start()

        try {
            val writer = DiskSpoolWriter(
                OkHttpDiskSpoolHttpTransport(OkHttpClient()),
                chunkBytes = 32 * 1024,
                ioBufferBytes = 8 * 1024
            )
            val bridge = PriorityFirstSessionBridge(
                frontierBytes = 64 * 1024L,
                priorityBytes = 256 * 1024L
            )

            writer.downloadSequentially(
                url = server.url("/movie.bin").toString(),
                bridge = bridge,
                targetFrontierBytes = 128 * 1024L,
                contentLength = 512 * 1024L,
                ioBuffer = ByteArray(8 * 1024)
            )

            assertEquals(listOf(256 * 1024L), bridge.rebases)
            assertTrue(bridge.writeRanges.isEmpty())
            assertTrue(requests.isEmpty())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `writer allocates one io buffer for multiple ranges in one run`() {
        val content = ByteArray(96 * 1024) { (it % 251).toByte() }
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (val range = request.getHeader("Range")) {
                    "bytes=0-0" -> MockResponse()
                        .setResponseCode(206)
                        .setHeader("Accept-Ranges", "bytes")
                        .setHeader("Content-Range", "bytes 0-0/${content.size}")
                        .setHeader("Content-Length", 1)
                        .setBody(Buffer().writeByte(0x2A))
                    else -> rangedResponse(content, range ?: error("Missing range"))
                }
            }
        }
        server.start()
        val session = DiskSpoolSession(File(temp.root, "single-buffer.spool"), capacityBytes = 128 * 1024L)
        val allocationCount = AtomicInteger(0)

        try {
            DiskSpoolWriter(
                transport = OkHttpDiskSpoolHttpTransport(OkHttpClient()),
                chunkBytes = 32 * 1024,
                ioBufferBytes = 4 * 1024,
                startupPriorityBytes = 0L,
                ioBufferFactory = { size ->
                    allocationCount.incrementAndGet()
                    ByteArray(size)
                }
            ).downloadUntil(server.url("/movie.bin").toString(), session, content.size.toLong())

            val buffer = ByteArray(content.size)
            assertEquals(content.size, session.read(0L, buffer, 0, buffer.size))
            assertArrayEquals(content, buffer)
            assertEquals(1, allocationCount.get())
        } finally {
            session.close()
            server.shutdown()
        }
    }

    @Test
    fun `writer pauses after adaptive headroom target until reader advances`() {
        val content = ByteArray(512 * 1024) { (it % 251).toByte() }
        val rangeRequests = AtomicInteger(0)
        val requestLatch = CountDownLatch(2)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (val range = request.getHeader("Range")) {
                    "bytes=0-0" -> MockResponse()
                        .setResponseCode(206)
                        .setHeader("Accept-Ranges", "bytes")
                        .setHeader("Content-Range", "bytes 0-0/${content.size}")
                        .setHeader("Content-Length", 1)
                        .setBody(Buffer().writeByte(0x2A))
                    else -> {
                        rangeRequests.incrementAndGet()
                        requestLatch.countDown()
                        rangedResponse(content, range ?: error("Missing range"))
                    }
                }
            }
        }
        server.start()
        val session = DiskSpoolSession(File(temp.root, "adaptive.spool"), capacityBytes = 512 * 1024L)
        val writerFailure = AtomicReference<Throwable?>(null)
        val writerThread = Thread {
            try {
                DiskSpoolWriter(
                    transport = OkHttpDiskSpoolHttpTransport(OkHttpClient()),
                    chunkBytes = 64 * 1024,
                    ioBufferBytes = 8 * 1024,
                    startupPriorityBytes = 64 * 1024L,
                    adaptiveHeadroomBytes = 128 * 1024L,
                    idlePollMs = 10L
                ).downloadUntil(server.url("/movie.bin").toString(), session, content.size.toLong())
            } catch (throwable: Throwable) {
                writerFailure.set(throwable)
            }
        }

        try {
            writerThread.start()
            assertTrue(requestLatch.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(session.awaitFrontierAtLeast(128 * 1024L, timeoutMs = 2_000L))
            assertEquals(2, rangeRequests.get())
            assertEquals(null, writerFailure.get())
            assertTrue(writerThread.isAlive)

            session.updateReadPosition(256 * 1024L)

            assertTrue(session.awaitFrontierAtLeast(384 * 1024L, timeoutMs = 2_000L))
            session.close()
            writerThread.join(2_000L)
            assertFalse(writerThread.isAlive)
            assertEquals(null, writerFailure.get())
        } finally {
            session.close()
            writerThread.join(2_000L)
            server.shutdown()
        }
    }

    @Test
    fun `writer never requests invalid range after priority jumps beyond adaptive target`() {
        val content = ByteArray(512 * 1024) { (it % 251).toByte() }
        val requestedRanges = CopyOnWriteArrayList<String>()
        val secondDataRangeSeen = CountDownLatch(1)
        val dataRangeCount = AtomicInteger(0)
        val priorityPublished = AtomicBoolean(false)
        val invalidRangeSeen = AtomicBoolean(false)
        val session = DiskSpoolSession(
            File(temp.root, "priority-target.spool"),
            capacityBytes = 512 * 1024L,
            waitTimeoutMs = 0L
        )
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                if (range != null) {
                    requestedRanges += range
                }

                return when (range) {
                    "bytes=0-0" -> MockResponse()
                        .setResponseCode(206)
                        .setHeader("Accept-Ranges", "bytes")
                        .setHeader("Content-Range", "bytes 0-0/${content.size}")
                        .setHeader("Content-Length", 1)
                        .setBody(Buffer().writeByte(0x2A))

                    null -> MockResponse().setResponseCode(400)

                    else -> {
                        if (dataRangeCount.incrementAndGet() == 2) {
                            secondDataRangeSeen.countDown()
                            if (priorityPublished.compareAndSet(false, true)) {
                                session.updateReadPosition(256 * 1024L)
                                session.read(256 * 1024L, ByteArray(1), 0, 1)
                            }
                        }

                        val parsedRange = parseRangeHeader(range)
                        if (parsedRange != null && parsedRange.first > parsedRange.second) {
                            invalidRangeSeen.set(true)
                            return MockResponse().setResponseCode(500)
                        }

                        rangedResponse(content, range).throttleBody(8 * 1024, 25, TimeUnit.MILLISECONDS)
                    }
                }
            }
        }
        server.start()

        val writerFailure = AtomicReference<Throwable?>(null)
        val writerThread = Thread {
            try {
                DiskSpoolWriter(
                    transport = OkHttpDiskSpoolHttpTransport(OkHttpClient()),
                    chunkBytes = 64 * 1024,
                    ioBufferBytes = 8 * 1024,
                    startupPriorityBytes = 0L,
                    adaptiveHeadroomBytes = 128 * 1024L,
                    idlePollMs = 10L
                ).downloadUntil(server.url("/movie.bin").toString(), session, content.size.toLong())
            } catch (throwable: Throwable) {
                writerFailure.set(throwable)
            }
        }

        try {
            writerThread.start()

            assertTrue(secondDataRangeSeen.await(2, TimeUnit.SECONDS))
            assertTrue(session.awaitFrontierAtLeast(256 * 1024L + 64 * 1024L, timeoutMs = 5_000L))
            assertEquals(
                listOf(
                    "bytes=0-0",
                    "bytes=0-65535",
                    "bytes=65536-131071",
                    "bytes=262144-327679"
                ),
                requestedRanges.take(4)
            )
            assertTrue(requestedRanges.contains("bytes=262144-327679"))
            assertFalse(invalidRangeSeen.get())
            assertTrue(requestedRanges.none { range ->
                val parsedRange = parseRangeHeader(range) ?: return@none true
                parsedRange.first > parsedRange.second
            })

            session.close()
            writerThread.join(5_000L)

            assertFalse(writerThread.isAlive)
            assertEquals(null, writerFailure.get())
        } finally {
            session.close()
            writerThread.join(1_000L)
            server.shutdown()
        }
    }

    @Test
    fun `writer consumes idle priority seek before sleeping`() {
        val content = ByteArray(384 * 1024) { (it % 251).toByte() }
        val rangeRequests = AtomicInteger(0)
        val requestLatch = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (val range = request.getHeader("Range")) {
                    "bytes=0-0" -> MockResponse()
                        .setResponseCode(206)
                        .setHeader("Accept-Ranges", "bytes")
                        .setHeader("Content-Range", "bytes 0-0/${content.size}")
                        .setHeader("Content-Length", 1)
                        .setBody(Buffer().writeByte(0x2A))
                    else -> {
                        rangeRequests.incrementAndGet()
                        requestLatch.countDown()
                        rangedResponse(content, range ?: error("Missing range"))
                    }
                }
            }
        }
        server.start()

        val session = DiskSpoolSession(
            File(temp.root, "priority-idle.spool"),
            capacityBytes = 384 * 1024L,
            waitTimeoutMs = 50L
        )
        val writerFailure = AtomicReference<Throwable?>(null)
        val writerThread = Thread {
            try {
                DiskSpoolWriter(
                    transport = OkHttpDiskSpoolHttpTransport(OkHttpClient()),
                    chunkBytes = 64 * 1024,
                    ioBufferBytes = 8 * 1024,
                    startupPriorityBytes = 64 * 1024L,
                    adaptiveHeadroomBytes = 64 * 1024L,
                    idlePollMs = 250L
                ).downloadUntil(server.url("/movie.bin").toString(), session, content.size.toLong())
            } catch (throwable: Throwable) {
                writerFailure.set(throwable)
            }
        }

        try {
            writerThread.start()
            assertTrue(requestLatch.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(session.awaitFrontierAtLeast(64 * 1024L, timeoutMs = 2_000L))
            assertEquals(1, rangeRequests.get())

            assertEquals(-1, session.read(256 * 1024L, ByteArray(8), 0, 8))

            assertTrue(session.awaitFrontierAtLeast(256 * 1024L, timeoutMs = 2_000L))
            assertTrue(session.windowStartBytes() >= 256 * 1024L)
            session.close()
            writerThread.join(2_000L)
            assertFalse(writerThread.isAlive)
            assertEquals(null, writerFailure.get())
        } finally {
            session.close()
            writerThread.join(2_000L)
            server.shutdown()
        }
    }

    @Test
    fun `writer retries a failed range request`() {
        val content = ByteArray(64 * 1024) { (it % 251).toByte() }
        val rangeAttempts = AtomicInteger(0)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val rangeHeader = request.getHeader("Range")
                return if (rangeHeader == "bytes=0-0") {
                    MockResponse()
                        .setResponseCode(206)
                        .setHeader("Accept-Ranges", "bytes")
                        .setHeader("Content-Range", "bytes 0-0/${content.size}")
                        .setHeader("Content-Length", 1)
                        .setBody(Buffer().writeByte(0x2A))
                } else if (rangeHeader != null) {
                    val attempt = rangeAttempts.incrementAndGet()
                    if (attempt == 1) {
                        MockResponse().setResponseCode(503)
                    } else {
                        rangedResponse(content, rangeHeader)
                    }
                } else {
                    MockResponse().setResponseCode(200)
                }
            }
        }
        server.start()

        val session = DiskSpoolSession(
            File(temp.root, "movie.spool"),
            capacityBytes = 128 * 1024L,
            waitTimeoutMs = 10L
        )

        try {
            val writer = DiskSpoolWriter(
                OkHttpDiskSpoolHttpTransport(OkHttpClient()),
                chunkBytes = 64 * 1024,
                ioBufferBytes = 8 * 1024
            )

            writer.downloadUntil(
                url = server.url("/movie.bin").toString(),
                session = session,
                targetFrontierBytes = content.size.toLong()
            )

            assertTrue(rangeAttempts.get() >= 2)
            assertEquals(content.size.toLong(), session.contiguousFrontierBytes())
        } finally {
            session.close()
            server.shutdown()
        }
    }

    @Test
    fun `writer keeps new priority seek when another priority arrives mid chunk`() {
        val firstPriority = 192 * 1024L
        val secondPriority = 223 * 1024L
        val source = Buffer().write(ByteArray(32 * 1024) { (it % 251).toByte() })
        val session = ScriptedSessionBridge(priorityToInject = secondPriority)
        val writer = DiskSpoolWriter(
            OkHttpDiskSpoolHttpTransport(OkHttpClient()),
            chunkBytes = 32 * 1024,
            ioBufferBytes = 8 * 1024
        )

        val nextCursor = writer.downloadRangeIntoSession(
            source = source,
            start = firstPriority,
            endInclusive = firstPriority + 32 * 1024L - 1L,
            session = session,
            buffer = ByteArray(8 * 1024)
        )

        assertEquals(secondPriority, nextCursor)
        assertEquals(secondPriority, session.contiguousFrontierBytes())
        assertEquals(listOf(secondPriority), session.rebases)
    }

    @Test
    fun `writer exits cleanly when session closes during download`() {
        val content = ByteArray(256 * 1024) { (it % 251).toByte() }
        val firstDataRangeStarted = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val rangeHeader = request.getHeader("Range")
                return when {
                    rangeHeader == null -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Accept-Ranges", "bytes")
                        .setHeader("Content-Length", content.size)
                        .setBody(Buffer().write(content))

                    rangeHeader == "bytes=0-32767" -> MockResponse()
                        .setResponseCode(206)
                        .setHeader("Accept-Ranges", "bytes")
                        .setHeader("Content-Range", "bytes 0-32767/${content.size}")
                        .setHeader("Content-Length", 32 * 1024)
                        .setBody(Buffer().write(content, 0, 32 * 1024))
                        .throttleBody(8 * 1024, 150, TimeUnit.MILLISECONDS)
                        .also { firstDataRangeStarted.countDown() }

                    else -> rangedResponse(content, rangeHeader)
                }
            }
        }
        server.start()

        val session = DiskSpoolSession(
            File(temp.root, "movie.spool"),
            capacityBytes = 128 * 1024L,
            waitTimeoutMs = 1_000L
        )
        val failure = AtomicReference<Throwable?>(null)
        val writer = DiskSpoolWriter(
            OkHttpDiskSpoolHttpTransport(OkHttpClient()),
            chunkBytes = 32 * 1024,
            ioBufferBytes = 8 * 1024
        )
        val writerThread = Thread {
            try {
                writer.downloadUntil(
                    url = server.url("/movie.bin").toString(),
                    session = session,
                    targetFrontierBytes = content.size.toLong()
                )
            } catch (throwable: Throwable) {
                failure.set(throwable)
            }
        }

        try {
            writerThread.start()
            assertTrue(firstDataRangeStarted.await(5, TimeUnit.SECONDS))

            session.close()
            writerThread.join(5_000L)

            assertTrue(!writerThread.isAlive)
            assertEquals(null, failure.get())
        } finally {
            session.close()
            writerThread.join(1_000L)
            server.shutdown()
        }
    }

    @Test
    fun `writer cancels pending metadata probe when session closes`() {
        val probeStarted = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Range") == "bytes=0-0") {
                    probeStarted.countDown()
                    return MockResponse()
                        .setSocketPolicy(SocketPolicy.NO_RESPONSE)
                }

                return MockResponse().setResponseCode(500)
            }
        }
        server.start()

        val session = DiskSpoolSession(
            File(temp.root, "movie.spool"),
            capacityBytes = 128 * 1024L,
            waitTimeoutMs = 10L
        )
        val failure = AtomicReference<Throwable?>(null)
        val writer = DiskSpoolWriter(OkHttpDiskSpoolHttpTransport(OkHttpClient()))
        val writerThread = Thread {
            try {
                writer.downloadUntil(
                    url = server.url("/movie.bin").toString(),
                    session = session,
                    targetFrontierBytes = 64 * 1024L
                )
            } catch (throwable: Throwable) {
                failure.set(throwable)
            }
        }

        try {
            writerThread.start()
            assertTrue(probeStarted.await(5, TimeUnit.SECONDS))

            session.close()
            writerThread.join(2_000L)

            assertTrue(!writerThread.isAlive)
            assertEquals(null, failure.get())
        } finally {
            session.close()
            writerThread.join(1_000L)
            server.shutdown()
        }
    }

    @Test
    fun `writer cancels pending range request when session closes`() {
        val contentLength = 64 * 1024
        val rangeStarted = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.getHeader("Range")) {
                    "bytes=0-0" -> MockResponse()
                        .setResponseCode(206)
                        .setHeader("Accept-Ranges", "bytes")
                        .setHeader("Content-Range", "bytes 0-0/$contentLength")
                        .setHeader("Content-Length", 1)
                        .setBody(Buffer().writeByte(0x2A))

                    null -> MockResponse().setResponseCode(200)

                    else -> {
                        rangeStarted.countDown()
                        MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    }
                }
            }
        }
        server.start()

        val session = DiskSpoolSession(
            File(temp.root, "movie.spool"),
            capacityBytes = 128 * 1024L,
            waitTimeoutMs = 10L
        )
        val failure = AtomicReference<Throwable?>(null)
        val writer = DiskSpoolWriter(
            OkHttpDiskSpoolHttpTransport(OkHttpClient()),
            chunkBytes = contentLength,
            ioBufferBytes = 8 * 1024
        )
        val writerThread = Thread {
            try {
                writer.downloadUntil(
                    url = server.url("/movie.bin").toString(),
                    session = session,
                    targetFrontierBytes = contentLength.toLong()
                )
            } catch (throwable: Throwable) {
                failure.set(throwable)
            }
        }

        try {
            writerThread.start()
            assertTrue(rangeStarted.await(5, TimeUnit.SECONDS))

            session.close()
            writerThread.join(2_000L)

            assertTrue(!writerThread.isAlive)
            assertEquals(null, failure.get())
        } finally {
            session.close()
            writerThread.join(1_000L)
            server.shutdown()
        }
    }

    @Test
    fun `writer skips metadata probe when session is already closed`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Accept-Ranges", "bytes")
                .setHeader("Content-Range", "bytes 0-0/1048576")
                .setHeader("Content-Length", 1)
                .setBody(Buffer().writeByte(0x2A))
        )
        server.start()

        val session = DiskSpoolSession(
            File(temp.root, "closed.spool"),
            capacityBytes = 128 * 1024L,
            waitTimeoutMs = 10L
        )

        try {
            session.close()

            DiskSpoolWriter(OkHttpDiskSpoolHttpTransport(OkHttpClient())).downloadUntil(
                url = server.url("/movie.bin").toString(),
                session = session,
                targetFrontierBytes = 64 * 1024L
            )

            assertEquals(0, server.requestCount)
        } finally {
            session.close()
            server.shutdown()
        }
    }

    @Test
    fun `disk spool writer ignores parallel connection requests`() {
        val content = ByteArray(96 * 1024) { (it % 251).toByte() }
        val requestedRanges = java.util.concurrent.CopyOnWriteArrayList<String>()
        val activeRangeRequests = AtomicInteger(0)
        val maxActiveRangeRequests = AtomicInteger(0)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                if (range != null) requestedRanges += range
                return when (range) {
                    "bytes=0-0" -> MockResponse()
                        .setResponseCode(206)
                        .setHeader("Accept-Ranges", "bytes")
                        .setHeader("Content-Range", "bytes 0-0/${content.size}")
                        .setHeader("Content-Length", 1)
                        .setBody(Buffer().writeByte(0x2A))
                    else -> {
                        val active = activeRangeRequests.incrementAndGet()
                        maxActiveRangeRequests.updateAndGet { previous -> maxOf(previous, active) }
                        try {
                            Thread.sleep(100L)
                            rangedResponse(content, range ?: error("Missing range"))
                        } finally {
                            activeRangeRequests.decrementAndGet()
                        }
                    }
                }
            }
        }
        server.start()
        val session = DiskSpoolSession(File(temp.root, "single-writer.spool"), capacityBytes = 128 * 1024L)

        try {
            DiskSpoolWriter(
                transport = OkHttpDiskSpoolHttpTransport(OkHttpClient()),
                chunkBytes = 32 * 1024,
                ioBufferBytes = 4 * 1024,
                parallelConnections = 3,
                startupPriorityBytes = 0L
            ).downloadUntil(server.url("/movie.bin").toString(), session, content.size.toLong())

            val buffer = ByteArray(content.size)
            assertEquals(content.size, session.read(0L, buffer, 0, buffer.size))
            assertArrayEquals(content, buffer)
            assertEquals(
                listOf("bytes=0-0", "bytes=0-32767", "bytes=32768-65535", "bytes=65536-98303"),
                requestedRanges.toList()
            )
            assertEquals(1, maxActiveRangeRequests.get())
        } finally {
            session.close()
            server.shutdown()
        }
    }

    private fun rangedResponse(content: ByteArray, rangeHeader: String): MockResponse {
        val match = Regex("""bytes=(\d+)-(\d+)""").matchEntire(rangeHeader)
            ?: error("Unexpected Range header: $rangeHeader")
        val start = match.groupValues[1].toInt()
        val requestedEnd = match.groupValues[2].toInt()
        val endExclusive = minOf(requestedEnd + 1, content.size)
        val length = endExclusive - start

        return MockResponse()
            .setResponseCode(206)
            .setHeader("Accept-Ranges", "bytes")
            .setHeader("Content-Range", "bytes $start-${endExclusive - 1}/${content.size}")
            .setHeader("Content-Length", length)
            .setBody(Buffer().write(content, start, length))
    }

    private fun parseRangeHeader(rangeHeader: String): Pair<Long, Long>? {
        val match = Regex("""bytes=(\d+)-(\d+)""").matchEntire(rangeHeader) ?: return null
        return match.groupValues[1].toLong() to match.groupValues[2].toLong()
    }

    private class ScriptedSessionBridge(
        private val priorityToInject: Long
    ) : DiskSpoolWriter.SessionBridge {
        val rebases = mutableListOf<Long>()
        private var frontier = 0L
        private var priority = -1L
        private var writeCount = 0

        override fun isClosed(): Boolean = false

        override fun contiguousFrontierBytes(): Long = frontier

        override fun consumePriorityPosition(): Long = priority.also {
            priority = -1L
        }

        override fun rebaseTo(position: Long) {
            rebases += position
            frontier = position
        }

        override fun writeRange(start: Long, bytes: ByteArray, length: Int) {
            frontier = maxOf(frontier, start + length.toLong())
            writeCount++
            if (writeCount == 1) {
                priority = priorityToInject
            }
        }
    }

    private class PriorityFirstSessionBridge(
        frontierBytes: Long,
        private val priorityBytes: Long
    ) : DiskSpoolWriter.SessionBridge {
        val rebases = mutableListOf<Long>()
        val writeRanges = mutableListOf<Long>()
        private var frontier = frontierBytes
        private var priorityPending = true

        override fun isClosed(): Boolean = false

        override fun contiguousFrontierBytes(): Long = frontier

        override fun consumePriorityPosition(): Long = if (priorityPending) {
            priorityPending = false
            priorityBytes
        } else {
            -1L
        }

        override fun rebaseTo(position: Long) {
            rebases += position
            frontier = position
        }

        override fun writeRange(start: Long, bytes: ByteArray, length: Int) {
            writeRanges += start
            frontier = maxOf(frontier, start + length.toLong())
        }
    }
}
