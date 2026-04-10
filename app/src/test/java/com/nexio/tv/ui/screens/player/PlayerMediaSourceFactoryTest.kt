package com.nexio.tv.ui.screens.player

import androidx.media3.datasource.cache.CacheDataSource
import com.nexio.tv.instrumentation.ClientIdentitySnapshot
import com.nexio.tv.instrumentation.DeviceProvenance
import com.nexio.tv.instrumentation.FactoryArgs
import com.nexio.tv.instrumentation.PlaybackTracer
import com.nexio.tv.instrumentation.PolicySnapshot
import com.nexio.tv.instrumentation.SessionHeader
import com.nexio.tv.instrumentation.SessionWriter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import okhttp3.OkHttpClient
import java.io.StringWriter
import java.util.UUID
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import io.mockk.mockk

@RunWith(RobolectricTestRunner::class)
class PlayerMediaSourceFactoryTest {
    @After
    fun tearDown() {
        PlaybackTracer.enabled = false
        PlaybackTracer.currentInternal()?.let { PlaybackTracer.endSession(it.sessionId) }
        PlaybackTracer.installWriterFactory(null)
    }

    @Test
    fun usesHttpUpstream_returnsFalse_forAssetUris() {
        assertFalse(PlayerMediaSourceFactory.usesHttpUpstream("asset:///truehd.mkv"))
    }

    @Test
    fun usesHttpUpstream_returnsTrue_forHttpUris() {
        assertTrue(PlayerMediaSourceFactory.usesHttpUpstream("https://example.com/truehd.mkv"))
    }

    @Test
    fun cacheEventListener_emitsCacheEvents() {
        val sink = traceSink()
        PlaybackTracer.enabled = true
        val sid = PlaybackTracer.beginSession(fakeHeader())

        PlaybackTraceCacheEventListener("progressive").apply {
            onCachedBytesRead(1024L, 256L)
            onCacheIgnored(CacheDataSource.CACHE_IGNORED_REASON_ERROR)
        }

        PlaybackTracer.endSession(sid)
        val out = sink.toString()
        assertTrue(out.contains("\"ev\":\"cache_event\""))
        assertTrue(out.contains("\"kind\":\"cached_bytes_read\""))
        assertTrue(out.contains("\"kind\":\"cache_ignored\""))
    }

    @Test
    fun factoryTraceHelpers_emitWp7Events() {
        val sink = traceSink()
        PlaybackTracer.enabled = true
        val sid = PlaybackTracer.beginSession(fakeHeader())
        val factory = PlayerMediaSourceFactory(
            mockk(relaxed = true),
            OkHttpClient(),
            OkHttpClient()
        )

        factory.callPrivate("emitCacheActive", true, "test_source", 4096L)
        factory.callPrivate("emitWarmAheadStart", "https://example.com/video.mkv", 8192L)
        factory.callPrivate("emitWarmAheadStop", "test_stop")
        factory.callPrivate("emitWarmAheadLoopIteration", 42L, "cache_write", false, 1024L, 2048L)
        factory.callPrivate("emitCacheWriteLatency", 7L, 1024L, 2048L, false, RuntimeException("boom"))

        PlaybackTracer.endSession(sid)
        val out = sink.toString()
        assertTrue(out.contains("\"ev\":\"cache_active\""))
        assertTrue(out.contains("\"ev\":\"warm_ahead_start\""))
        assertTrue(out.contains("\"ev\":\"warm_ahead_stop\""))
        assertTrue(out.contains("\"ev\":\"warm_ahead_loop_iteration_ms\""))
        assertTrue(out.contains("\"ev\":\"cache_write_latency_ms\""))
    }

    private fun traceSink(): StringWriter {
        val sink = StringWriter()
        PlaybackTracer.installWriterFactory { header ->
            SessionWriter(
                header = header,
                baseFile = null,
                capacity = 4096,
                testSink = sink,
                rotationBytes = Long.MAX_VALUE,
                parkNanos = 1_000_000L,
                overflowReportIntervalNanos = Long.MAX_VALUE
            )
        }
        return sink
    }

    private fun PlayerMediaSourceFactory.callPrivate(name: String, vararg args: Any?) {
        val method = PlayerMediaSourceFactory::class.java.declaredMethods.first {
            it.name == name && it.parameterCount == args.size
        }
        method.isAccessible = true
        method.invoke(this, *args)
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
            branch = "cache",
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
