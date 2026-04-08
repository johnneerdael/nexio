package com.nexio.tv.instrumentation

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.StringWriter

/**
 * WP10 — microbenchmark for the [PlaybackTracer] hot path.
 *
 * Three variants per spec amendment C3:
 *
 *  - **noEmitBaseline**: the `emit` call is wrapped in `if (false)`, so
 *    Kotlin constant-folds the entire body away. Measures the empty loop
 *    cost — the floor for the other two variants.
 *
 *  - **toggleOff**: `PlaybackTracer.enabled = false`. Each iteration runs
 *    the inline `emit` which compiles to a single `@JvmField` volatile read
 *    + early return. Gate: p99 ≤ 20 ns AND |toggleOff − noEmitBaseline| /
 *    noEmitBaseline ≤ 1% (the price of "compiled in but not enabled").
 *
 *  - **toggleOn**: `PlaybackTracer.enabled = true`, a real [SessionWriter]
 *    backed by an in-memory sink is active, and the writer thread drains
 *    records concurrently. Measures the full hot path: `obtain` from the
 *    thread-local pool + `PayloadBuilder` mutation + `MpscArrayQueue.offer`.
 *    Gate: p99 ≤ 5000 ns (5 µs).
 *
 * androidx.benchmark auto-tunes warmup and iteration counts based on the
 * measured noise floor; the spec target of "1M iterations / 10K warmup" is
 * an upper bound the framework will not exceed — actual iterations may be
 * lower if convergence is reached earlier. Run with:
 *
 *     ./gradlew :app:connectedArm64DebugAndroidTest \
 *        -Pandroid.testInstrumentationRunnerArguments.class=\
 *        com.nexio.tv.instrumentation.PlaybackTracerBenchmarkTest
 *
 * The `androidx.benchmark` library writes `benchmarkData.json` to the
 * device's external storage on test exit; pull it with `adb pull` and
 * commit a transcribed summary to `docs/instrumentation/benchmark-results.md`.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackTracerBenchmarkTest {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val sink = StringWriter()

    @Before
    fun setUp() {
        // Drain anything left from a prior test in the same JVM.
        PlaybackTracer.endSession(currentSessionIdOrNull() ?: "")
        PlaybackTracer.enabled = false
        PlaybackTracer.installWriterFactory { header ->
            SessionWriter(
                header = header,
                baseFile = null,
                capacity = 8192,
                testSink = sink,
                rotationBytes = Long.MAX_VALUE,
                parkNanos = 10_000_000L,
                overflowReportIntervalNanos = Long.MAX_VALUE
            )
        }
    }

    @After
    fun tearDown() {
        PlaybackTracer.enabled = false
        currentSessionIdOrNull()?.let { PlaybackTracer.endSession(it) }
        PlaybackTracer.installWriterFactory(null)
    }

    /**
     * Variant A — no emit at all. The `if (false)` is constant-folded so
     * this is effectively an empty loop. Forms the baseline for the ≤ 1%
     * overhead gate on toggle-off.
     */
    @Test
    fun noEmitBaseline() {
        benchmarkRule.measureRepeated {
            @Suppress("KotlinConstantConditions")
            if (false) {
                PlaybackTracer.emit(EventFamily.FRONTIER, "frontier_advance") {
                    putLong("delta", 65_536L)
                }
            }
        }
    }

    /**
     * Variant B — tracer compiled in but disabled. Measures the cost of the
     * volatile flag check + early return. Should be effectively zero.
     */
    @Test
    fun emitToggleOff() {
        PlaybackTracer.enabled = false
        benchmarkRule.measureRepeated {
            PlaybackTracer.emit(EventFamily.FRONTIER, "frontier_advance") {
                putLong("delta", 65_536L)
            }
        }
    }

    /**
     * Variant C — tracer enabled, real writer draining. Measures the full
     * hot path: `obtain` + `PayloadBuilder.putLong` + `MpscArrayQueue.offer`.
     * Spec gate: p99 ≤ 5 µs.
     */
    @Test
    fun emitToggleOn() {
        PlaybackTracer.enabled = true
        val sid = PlaybackTracer.beginSession(benchmarkSessionHeader())
        try {
            benchmarkRule.measureRepeated {
                PlaybackTracer.emit(EventFamily.FRONTIER, "frontier_advance") {
                    putLong("delta", 65_536L)
                }
            }
        } finally {
            PlaybackTracer.endSession(sid)
        }
    }

    private fun currentSessionIdOrNull(): String? =
        PlaybackTracer.currentInternal()?.sessionId

    private fun benchmarkSessionHeader(): SessionHeader = SessionHeader(
        sessionId = "bench-session",
        startedAtNanos = 0L,
        assetKeyHash = "benchmark0000",
        serviceKey = null,
        provider = null,
        benchmarkResultId = null,
        benchmarkSource = null,
        envelopePresent = true,
        runtimeHintsPresent = false,
        specializationState = "confirmed",
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
        factoryArgs = FactoryArgs(
            activeChunkBytes = 8L * 1024 * 1024,
            parallelConnections = 4,
            keepBehindBytes = 32L * 1024 * 1024,
            bootstrapBytes = 4L * 1024 * 1024,
        ),
        initialPolicy = PolicySnapshot(
            urgentWorkers = 4,
            prefetchWorkers = 1,
            urgentChunkBytes = 8L * 1024 * 1024,
            prefetchChunkBytes = 8L * 1024 * 1024,
            source = "envelope",
        ),
        clientIdentity = ClientIdentitySnapshot(
            playbackClientHash = "bench",
            dispatcherMaxRequests = 64,
            dispatcherMaxRequestsPerHost = 12,
            dispatcherQueuedCalls = 0,
            dispatcherRunningCalls = 0,
            connectionPoolIdleCount = 0,
            connectionPoolTotalCount = 0,
            callTimeoutMs = 0L,
            readTimeoutMs = 30_000L,
            writeTimeoutMs = 30_000L,
            connectTimeoutMs = 10_000L,
        ),
        device = DeviceProvenance(
            deviceModel = "BENCH",
            deviceManufacturer = "BENCH",
            androidRelease = "14",
            androidSdkInt = 34,
            appVersionName = "bench",
            appVersionCode = 1L,
            gitSha = null,
            memoryClass = 256,
            largeMemoryClass = 512,
            isLowRamDevice = false,
            networkType = "wifi",
            networkTransportHash = null,
        ),
    )
}
