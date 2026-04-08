package com.nexio.tv.data.repository.benchmark

import com.nexio.tv.ui.screens.player.PlayerTransportTelemetry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies the okhttp.depth.benchmark telemetry site in [DirectDiscardBenchmarkTransport].
 *
 * The site mirrors okhttp.depth.playback in PlayerMediaSourceFactory: rate-limited to ≤1/sec
 * via [PlayerTransportTelemetry.logThrottled].
 *
 * We test the throttle contract directly on the same [PlayerTransportTelemetry] object that
 * the production code calls, using the injectable clock so we can control time.
 */
class BenchmarkOkHttpDepthTelemetryTest {

    private var fakeNow = 0L

    @Before
    fun setUp() {
        PlayerTransportTelemetry.nowMsProvider = { fakeNow }
        PlayerTransportTelemetry.resetForTest()
    }

    @After
    fun tearDown() {
        PlayerTransportTelemetry.resetForTest()
        PlayerTransportTelemetry.nowMsProvider = { System.currentTimeMillis() }
    }

    @Test
    fun `okhttp depth benchmark emits on first benchmark submission`() {
        fakeNow = 0L
        val emitted = PlayerTransportTelemetry.logThrottled(
            site = "okhttp.depth.benchmark",
            windowMs = 1000L,
            pairs = mapOf("maxRequestsPerHost" to 12, "queued" to 0, "running" to 1)
        )
        assertTrue("first benchmark submission must emit telemetry", emitted)
    }

    @Test
    fun `okhttp depth benchmark is throttled to at most 1 per second`() {
        fakeNow = 0L
        val first = PlayerTransportTelemetry.logThrottled(
            site = "okhttp.depth.benchmark",
            windowMs = 1000L,
            pairs = mapOf("maxRequestsPerHost" to 12, "queued" to 0, "running" to 1)
        )
        assertTrue("first call must emit", first)

        // Second call within the 1 s window must be suppressed.
        fakeNow = 500L
        val second = PlayerTransportTelemetry.logThrottled(
            site = "okhttp.depth.benchmark",
            windowMs = 1000L,
            pairs = mapOf("maxRequestsPerHost" to 12, "queued" to 1, "running" to 1)
        )
        assertFalse("second call within window must be suppressed", second)
    }

    @Test
    fun `okhttp depth benchmark emits again after 1 second window`() {
        fakeNow = 0L
        PlayerTransportTelemetry.logThrottled(
            site = "okhttp.depth.benchmark",
            windowMs = 1000L,
            pairs = mapOf("maxRequestsPerHost" to 12, "queued" to 0, "running" to 1)
        )

        // Advance past window
        fakeNow = 1001L
        val afterWindow = PlayerTransportTelemetry.logThrottled(
            site = "okhttp.depth.benchmark",
            windowMs = 1000L,
            pairs = mapOf("maxRequestsPerHost" to 12, "queued" to 0, "running" to 2)
        )
        assertTrue("call after window must emit", afterWindow)
    }

    @Test
    fun `okhttp depth benchmark throttle is independent from okhttp depth playback`() {
        fakeNow = 0L
        // Emit on playback site first
        PlayerTransportTelemetry.logThrottled(
            site = "okhttp.depth.playback",
            windowMs = 1000L,
            pairs = mapOf("maxRequestsPerHost" to 12, "queued" to 0, "running" to 0)
        )

        // Benchmark site must still emit (independent per-site throttle)
        val benchmarkEmitted = PlayerTransportTelemetry.logThrottled(
            site = "okhttp.depth.benchmark",
            windowMs = 1000L,
            pairs = mapOf("maxRequestsPerHost" to 12, "queued" to 0, "running" to 1)
        )
        assertTrue("benchmark site throttle is independent from playback site", benchmarkEmitted)
    }
}
