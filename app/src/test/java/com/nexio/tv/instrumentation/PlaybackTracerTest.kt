package com.nexio.tv.instrumentation

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.StringWriter
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class PlaybackTracerTest {

    private val sinks = mutableListOf<StringWriter>()
    private var lastWriter: AtomicReference<SessionWriter?> = AtomicReference(null)

    @Before
    fun setUp() {
        sinks.clear()
        lastWriter.set(null)
        PlaybackTracer.installWriterFactory { header ->
            val sw = StringWriter()
            sinks.add(sw)
            val w = SessionWriter(
                header = header,
                baseFile = null,
                capacity = 4096,
                testSink = sw,
                rotationBytes = Long.MAX_VALUE,
                parkNanos = 10_000_000L,
                overflowReportIntervalNanos = Long.MAX_VALUE
            )
            lastWriter.set(w)
            w
        }
        PlaybackTracer.enabled = false
    }

    @After
    fun tearDown() {
        PlaybackTracer.enabled = false
        PlaybackTracer.currentInternal()?.let { PlaybackTracer.endSession(it.sessionId) }
        PlaybackTracer.installWriterFactory(null)
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

    @Test
    fun toggleOffIsNoOp() {
        PlaybackTracer.enabled = false
        PlaybackTracer.beginSession(fakeHeader())
        PlaybackTracer.emit(EventFamily.RANGE, "submit_urgent") {
            putInt("chunk", 7)
        }
        // Drain
        val w = lastWriter.get()!!
        w.shutdown()
        w.awaitDrained()
        // The session writer is created so beginSession can write the header,
        // but the inline emit() must early-return before enqueuing.
        val out = sinks.last().toString()
        assertTrue("session_started should be present", out.contains("playback_session_started"))
        assertFalse("submit_urgent must not be present when disabled", out.contains("submit_urgent"))
    }

    @Test
    fun beginSessionWritesHeader() {
        PlaybackTracer.enabled = true
        val sid = PlaybackTracer.beginSession(fakeHeader("sid-12345"))
        assertEquals("sid-12345", sid)
        val w = lastWriter.get()!!
        // Force the writer to drain by ending the session.
        PlaybackTracer.endSession(sid)
        w.awaitDrained()
        val out = sinks.last().toString()
        assertTrue(out.contains("\"sid\":\"sid-12345\""))
        assertTrue(out.contains("playback_session_started"))
        assertTrue(out.contains("\"branch\":\"prds\""))
    }

    @Test
    fun endSessionFlushesAndClosesFile() {
        PlaybackTracer.enabled = true
        val sid = PlaybackTracer.beginSession(fakeHeader())
        PlaybackTracer.emit(EventFamily.PRDS, "prds_open_start") {
            putLong("pos", 42L)
        }
        PlaybackTracer.endSession(sid)
        val out = sinks.last().toString()
        assertTrue(out.contains("playback_session_started"))
        assertTrue(out.contains("playback_session_ended"))
        assertTrue(out.contains("prds_open_start"))
        assertTrue(out.contains("\"pos\":42"))
    }

    @Test
    fun emitMultipleEventsInSequence() {
        PlaybackTracer.enabled = true
        val sid = PlaybackTracer.beginSession(fakeHeader())
        val w = lastWriter.get()!!
        // Single-threaded push of a small batch; thread-safety of the
        // underlying ring + pool is covered by MpscRingStressTest.
        repeat(50) { j ->
            PlaybackTracer.emit(EventFamily.RANGE, "submit_urgent") {
                putInt("j", j)
            }
        }
        PlaybackTracer.endSession(sid)
        w.awaitDrained()
        val emitted = w.emittedSnapshot()
        val overflow = w.overflowSnapshot()
        assertEquals("no overflow expected at this load", 0L, overflow)
        // session_started + 50 emits + session_ended (+ optional tail summary)
        assertTrue("emitted=$emitted expected ≥ 51", emitted >= 51L)
    }
}
