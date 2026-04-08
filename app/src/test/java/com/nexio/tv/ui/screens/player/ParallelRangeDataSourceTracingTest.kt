package com.nexio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nexio.tv.instrumentation.ClientIdentitySnapshot
import com.nexio.tv.instrumentation.DeviceProvenance
import com.nexio.tv.instrumentation.FactoryArgs
import com.nexio.tv.instrumentation.PlaybackOkHttpEventListener
import com.nexio.tv.instrumentation.PlaybackRangeContextCallFactory
import com.nexio.tv.instrumentation.PlaybackTracer
import com.nexio.tv.instrumentation.PolicySnapshot
import com.nexio.tv.instrumentation.SessionHeader
import com.nexio.tv.instrumentation.SessionWriter
import java.io.StringWriter
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ParallelRangeDataSourceTracingTest {

    private val sinks = mutableListOf<StringWriter>()
    private val lastWriter = AtomicReference<SessionWriter?>()

    @Before
    fun setUp() {
        sinks.clear()
        lastWriter.set(null)
        PlaybackTracer.installWriterFactory { header ->
            val sink = StringWriter()
            sinks.add(sink)
            SessionWriter(
                header = header,
                baseFile = null,
                capacity = 4096,
                testSink = sink,
                rotationBytes = Long.MAX_VALUE,
                parkNanos = 1_000_000L,
                overflowReportIntervalNanos = Long.MAX_VALUE
            ).also { lastWriter.set(it) }
        }
        PlaybackTracer.enabled = true
    }

    @After
    fun tearDown() {
        PlaybackTracer.enabled = false
        PlaybackTracer.currentInternal()?.let { PlaybackTracer.endSession(it.sessionId) }
        PlaybackTracer.installWriterFactory(null)
    }

    @Test(timeout = 10_000L)
    fun `parallel range datasource emits WP4 and WP5 trace events`() {
        val content = ByteArray(256 * 1024) { (it % 251).toByte() }
        val chunkSize = 64 * 1024L
        val server = startSlowRangeServer(content)
        val sid = PlaybackTracer.beginSession(fakeHeader())
        try {
            val dataSource = ParallelRangeDataSource(
                upstreamFactory = OkHttpDataSource.Factory(
                    PlaybackRangeContextCallFactory(
                        OkHttpClient.Builder()
                            .connectTimeout(5, TimeUnit.SECONDS)
                            .readTimeout(5, TimeUnit.SECONDS)
                            .writeTimeout(5, TimeUnit.SECONDS)
                            .eventListenerFactory(PlaybackOkHttpEventListener.FACTORY)
                            .build()
                    )
                ),
                parallelConnections = 2,
                chunkSize = chunkSize,
                chunkWaitTimeoutMs = 5_000L
            )
            val bytes = readAll(
                dataSource,
                DataSpec.Builder()
                    .setUri(server.url("/media.bin").toString())
                    .setPosition(0L)
                    .setLength(C.LENGTH_UNSET.toLong())
                    .build()
            )

            assertArrayEquals(content, bytes)
        } finally {
            server.shutdown()
            PlaybackTracer.endSession(sid)
        }

        assertTrue(lastWriter.get()?.awaitDrained() == true)
        val out = sinks.last().toString()
        assertTrue(out.contains("\"ev\":\"prds_open_start\""))
        assertTrue(out.contains("\"ev\":\"prds_open_mode\""))
        assertTrue(out.contains("\"ev\":\"prds_open_resolved\""))
        assertTrue(out.contains("\"ev\":\"prds_close\""))
        assertTrue(out.contains("\"ev\":\"read_return\""))
        assertTrue(out.contains("\"ev\":\"read_wait_start\""))
        assertTrue(out.contains("\"ev\":\"read_wait_end\""))
        assertTrue(out.contains("\"ev\":\"read_wait_return\""))
        assertTrue(out.contains("\"ev\":\"submit_urgent\""))
        assertTrue(out.contains("\"ev\":\"range_start\""))
        assertTrue(out.contains("\"ev\":\"range_done\""))
        assertTrue(out.contains("\"ev\":\"range_http_body\""))
        assertTrue(out.contains("\"ev\":\"range_http_call_start\""))
        assertTrue(out.contains("\"ev\":\"range_http_response_body_end\""))
    }

    private fun readAll(
        dataSource: ParallelRangeDataSource,
        dataSpec: DataSpec
    ): ByteArray {
        val sink = Buffer()
        val buffer = ByteArray(32 * 1024)
        dataSource.open(dataSpec)
        try {
            while (true) {
                val read = dataSource.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) break
                if (read > 0) sink.write(buffer, 0, read)
            }
        } finally {
            dataSource.close()
        }
        return sink.readByteArray()
    }

    private fun startSlowRangeServer(content: ByteArray): MockWebServer {
        return MockWebServer().apply {
            dispatcher = object : Dispatcher() {
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
                            .setBodyDelay(50, TimeUnit.MILLISECONDS)
                    }
                }
            }
            start()
        }
    }

    private fun rangedResponse(content: ByteArray, rangeHeader: String): MockResponse {
        val match = Regex("""bytes=(\d+)-(\d+)?""").matchEntire(rangeHeader)
            ?: error("Unexpected Range header: $rangeHeader")
        val start = match.groupValues[1].toLong()
        val requestedEnd = match.groupValues[2].takeIf { it.isNotBlank() }?.toLong()
        val endExclusive = minOf((requestedEnd?.plus(1L)) ?: content.size.toLong(), content.size.toLong())
        val length = (endExclusive - start).toInt()
        if (length <= 0 || start >= content.size) {
            return MockResponse()
                .setResponseCode(416)
                .setHeader("Content-Range", "bytes */${content.size}")
        }
        return MockResponse()
            .setResponseCode(206)
            .setHeader("Accept-Ranges", "bytes")
            .setHeader("Content-Range", "bytes $start-${endExclusive - 1}/${content.size}")
            .setHeader("Content-Length", length)
            .setBody(Buffer().write(content, start.toInt(), length))
    }

    private fun fakeHeader(sid: String = UUID.randomUUID().toString()): SessionHeader {
        return SessionHeader(
            sessionId = sid,
            startedAtNanos = 1L,
            assetKeyHash = "deadbeef0000",
            serviceKey = "real-debrid",
            provider = "addon-x",
            benchmarkResultId = null,
            benchmarkSource = null,
            envelopePresent = false,
            runtimeHintsPresent = false,
            specializationState = "baseline",
            hintServiceKey = null,
            hintHostScope = null,
            hintTransportClass = null,
            hintAgeMs = null,
            hintFreshnessBand = null,
            specializationMismatchReason = null,
            observedHostScope = null,
            observedTransportClass = null,
            branch = "prds",
            cacheActive = false,
            warmAheadFactory = null,
            factoryArgs = FactoryArgs(8L * 1024 * 1024, 4, 32L * 1024 * 1024, 4L * 1024 * 1024),
            initialPolicy = PolicySnapshot(2, 16, 4L * 1024 * 1024, 16L * 1024 * 1024, "fallback"),
            clientIdentity = ClientIdentitySnapshot(
                playbackClientHash = "abc123",
                dispatcherMaxRequests = 64,
                dispatcherMaxRequestsPerHost = 12,
                dispatcherQueuedCalls = 0,
                dispatcherRunningCalls = 0,
                connectionPoolIdleCount = 0,
                connectionPoolTotalCount = 0,
                callTimeoutMs = 0L,
                readTimeoutMs = 30_000L,
                writeTimeoutMs = 30_000L,
                connectTimeoutMs = 10_000L
            ),
            device = DeviceProvenance(
                deviceModel = "TEST",
                deviceManufacturer = "TEST",
                androidRelease = "14",
                androidSdkInt = 34,
                appVersionName = "test",
                appVersionCode = 1L,
                gitSha = null,
                memoryClass = 256,
                largeMemoryClass = 512,
                isLowRamDevice = false,
                networkType = "wifi",
                networkTransportHash = null
            )
        )
    }
}
