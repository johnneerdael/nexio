package com.nexio.tv.instrumentation

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * WP9 — offline classifier that consumes the JSONL trace produced by
 * [PlaybackTracer]/[SessionWriter] and labels each rebuffer window with one
 * of the 8 root-cause categories from spec §D.
 *
 * Fully self-contained: no Android dependencies, no instrumentation runtime
 * dependencies — just JSONL parsing + windowed metric aggregation. Designed
 * to be invoked from a unit test that ships a fixture JSONL alongside the
 * test class, and (later) from a tool that pulls a live session off-device.
 */
class StutterClassifier(private val session: ParsedSession) {

    enum class Cause {
        POLICY_MISMATCH,
        TRANSPORT,
        SCHEDULER,
        FRONTIER,
        FACADE,
        CACHE_INTERFERENCE,
        DECODE_RENDER,
        DEVICE,
        UNKNOWN,
    }

    fun classify(window: RebufferWindow): Cause = when {
        policyMismatch() -> Cause.POLICY_MISMATCH
        deviceUnderPressure(window) -> Cause.DEVICE
        cacheInterference(window) -> Cause.CACHE_INTERFERENCE
        schedulerStarved(window) -> Cause.SCHEDULER
        transportStarved(window) -> Cause.TRANSPORT
        frontierStalled(window) -> Cause.FRONTIER
        facadeStarved(window) -> Cause.FACADE
        decodeUnhealthy(window) -> Cause.DECODE_RENDER
        else -> Cause.UNKNOWN
    }

    /**
     * POLICY_MISMATCH (spec §D): wrong profile / envelope / specialization
     * actually applied. Reads only the immutable session header.
     */
    private fun policyMismatch(): Boolean {
        val h = session.header
        if (h.branch != "prds") return true
        if (h.envelopePresent != true) return true
        if (h.specializationState == "mismatch") return true
        if (h.policySource?.startsWith("fallback") == true) return true
        if (h.specializationMismatchReason != null) return true
        return false
    }

    /**
     * DEVICE: heap delta > 50 MiB OR thermal status ≥ MODERATE OR low_memory.
     */
    private fun deviceUnderPressure(window: RebufferWindow): Boolean {
        if (window.maxHeapDeltaKb >= 50L * 1024L) return true
        if (window.maxThermalStatus >= THERMAL_MODERATE) return true
        if (window.lowMemoryDuringWindow) return true
        return false
    }

    /**
     * CACHE_INTERFERENCE: warm-ahead active during rebuffer AND cache write
     * latency p99 > 50 ms AND frontier advance < 1 MiB/s.
     */
    private fun cacheInterference(window: RebufferWindow): Boolean {
        if (!window.warmAheadActive) return false
        if (window.cacheWriteLatencyP99Ms < 50L) return false
        if (window.frontierAdvanceBytesPerSec >= 1L * 1024L * 1024L) return false
        return true
    }

    /**
     * SCHEDULER: prefetch starvation rate high OR urgent submitted but
     * prefetch never started OR submitted prefetch chunk size diverges from
     * the policy snapshot's intended chunk size.
     */
    private fun schedulerStarved(window: RebufferWindow): Boolean {
        if (window.prefetchStarvedPerSec > 5.0) return true
        if (window.submitUrgentCount > 0 &&
            window.prefetchStartedCount == 0 &&
            window.windowDurationMs >= 3_000L
        ) {
            return true
        }
        val policyChunk = session.header.policyPrefetchChunkBytes
        if (policyChunk != null && window.observedPrefetchChunkBytes != null) {
            if (policyChunk != window.observedPrefetchChunkBytes) return true
        }
        return false
    }

    /**
     * TRANSPORT: network bytes/sec collapsed > 70% AND TTFB or body gap
     * spike AND read_wait p99 high. Only fires if SCHEDULER did not.
     */
    private fun transportStarved(window: RebufferWindow): Boolean {
        if (window.networkBytesPerSec5sCollapseFraction <= 0.70) return false
        val gapsHigh = window.rangeHttpBodyGapP99Ms > 500L ||
            window.rangeHttpTtfbP99Ms > 800L
        if (!gapsHigh) return false
        if (window.readWaitP99Ms <= 200L) return false
        return true
    }

    /**
     * FRONTIER: bytes arriving but readable frontier lags or store_lock_wait
     * p99 high. Distinguishes monitor-contention stalls from facade waits.
     */
    private fun frontierStalled(window: RebufferWindow): Boolean {
        val networkHealthy = window.networkBytesPerSec >= MIN_HEALTHY_BYTES_PER_SEC
        if (!networkHealthy) return false
        if (window.storeLockWaitP99Ms > 50L) return true
        if (window.networkBytesPerSec > 0L &&
            window.frontierAdvanceBytesPerSec * 4L < window.networkBytesPerSec
        ) {
            return true
        }
        return false
    }

    /**
     * FACADE: Media3 read() starvation despite healthy network + frontier.
     */
    private fun facadeStarved(window: RebufferWindow): Boolean {
        val networkHealthy = window.networkBytesPerSec >= MIN_HEALTHY_BYTES_PER_SEC
        if (!networkHealthy) return false
        val frontierHealthy = window.frontierAdvanceBytesPerSec >= MIN_HEALTHY_BYTES_PER_SEC / 2L
        if (!frontierHealthy) return false
        return window.readWaitP99Ms > 200L
    }

    /**
     * DECODE_RENDER: frontier + cache runway healthy but dropped frames or
     * audio underruns climb.
     */
    private fun decodeUnhealthy(window: RebufferWindow): Boolean {
        val networkHealthy = window.networkBytesPerSec >= MIN_HEALTHY_BYTES_PER_SEC
        val frontierHealthy = window.frontierAdvanceBytesPerSec >= MIN_HEALTHY_BYTES_PER_SEC / 2L
        if (!networkHealthy || !frontierHealthy) return false
        if (window.droppedFrames5s > 5) return true
        if (window.audioUnderruns5s > 0) return true
        return false
    }

    companion object {
        // PowerManager.THERMAL_STATUS_MODERATE == 2 (API 29+).
        const val THERMAL_MODERATE: Int = 2

        // 1 MiB/s lower bound on what we treat as "healthy" network throughput.
        private const val MIN_HEALTHY_BYTES_PER_SEC: Long = 1L * 1024L * 1024L

        /**
         * Parse a JSONL trace file (or any line-delimited JSON stream) into a
         * [ParsedSession]. Uses Gson which is already a project dependency
         * and works correctly in unit tests (the Android `org.json` stub is
         * unusable under `returnDefaultValues=true`).
         */
        fun parseJsonl(jsonlText: String): ParsedSession {
            val lines = jsonlText.lineSequence()
                .filter { it.isNotBlank() }
                .map { JsonParser.parseString(it).asJsonObject }
                .toList()
            val header = lines.firstOrNull { it.optStringField("ev") == "playback_session_started" }
                ?: throw IllegalStateException("No playback_session_started in trace")
            return ParsedSession(
                header = ParsedSessionHeader.fromJson(header),
                events = lines,
            )
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Gson convenience accessors. Gson's primitives are awkward — these reduce
// the call sites to the same shape org.json.JsonObject would offer.
// ────────────────────────────────────────────────────────────────────────────

internal fun JsonObject.optStringField(key: String, default: String = ""): String {
    val el = get(key) ?: return default
    if (el.isJsonNull) return default
    return runCatching { el.asString }.getOrDefault(default)
}

internal fun JsonObject.optStringOrNull(key: String): String? {
    val el = get(key) ?: return null
    if (el.isJsonNull) return null
    return runCatching { el.asString }.getOrNull()
}

internal fun JsonObject.optLongField(key: String, default: Long = 0L): Long {
    val el = get(key) ?: return default
    if (el.isJsonNull) return default
    return runCatching { el.asLong }.getOrDefault(default)
}

internal fun JsonObject.optIntField(key: String, default: Int = 0): Int {
    val el = get(key) ?: return default
    if (el.isJsonNull) return default
    return runCatching { el.asInt }.getOrDefault(default)
}

internal fun JsonObject.optBoolField(key: String, default: Boolean = false): Boolean {
    val el = get(key) ?: return default
    if (el.isJsonNull) return default
    return runCatching { el.asBoolean }.getOrDefault(default)
}

/** Parsed view of a single playback-trace JSONL session. */
data class ParsedSession(
    val header: ParsedSessionHeader,
    val events: List<JsonObject>,
) {
    /**
     * Return the rebuffer windows in this session by pairing each
     * `rebuffer_start` with the next `rebuffer_end`. Aggregates windowed
     * metrics from neighboring events for the [StutterClassifier] rules.
     */
    fun rebufferWindows(): List<RebufferWindow> {
        val windows = mutableListOf<RebufferWindow>()
        var startIdx: Int? = null
        for ((i, ev) in events.withIndex()) {
            val type = ev.optStringField("ev")
            if (type == "rebuffer_start") {
                startIdx = i
            } else if (type == "rebuffer_end" && startIdx != null) {
                windows += buildWindow(startIdx, i)
                startIdx = null
            }
        }
        return windows
    }

    private fun buildWindow(startIdx: Int, endIdx: Int): RebufferWindow {
        val startEv = events[startIdx]
        val endEv = events[endIdx]
        val startNs = startEv.optLongField("tNs", 0L)
        val endNs = endEv.optLongField("tNs", 0L)
        val durationMs = endEv.optLongField("durationMs", (endNs - startNs) / 1_000_000L)

        // Look back 5 seconds (5_000_000_000 nanos) for event aggregation.
        val windowFloorNs = endNs - 5_000_000_000L
        val inWindow = events.filter {
            val t = it.optLongField("tNs", 0L)
            t in windowFloorNs..endNs
        }

        return RebufferWindow(
            startTNanos = startNs,
            endTNanos = endNs,
            windowDurationMs = durationMs.coerceAtLeast(0L),
            networkBytesPerSec = aggregateNetworkBytesPerSec(inWindow),
            networkBytesPerSec5sCollapseFraction = networkCollapseFraction(inWindow),
            frontierAdvanceBytesPerSec = aggregateFrontierBytesPerSec(inWindow),
            readWaitP99Ms = aggregateReadWaitP99Ms(inWindow),
            storeLockWaitP99Ms = aggregateStoreLockWaitP99Ms(inWindow),
            rangeHttpTtfbP99Ms = aggregateHttpTtfbP99Ms(inWindow),
            rangeHttpBodyGapP99Ms = aggregateHttpBodyGapP99Ms(inWindow),
            droppedFrames5s = endEv.optIntField("droppedFrames5s", 0),
            audioUnderruns5s = endEv.optIntField("audioUnderruns5s", 0),
            warmAheadActive = warmAheadActiveDuringWindow(inWindow),
            cacheWriteLatencyP99Ms = aggregateCacheWriteLatencyP99Ms(inWindow),
            prefetchStarvedPerSec = aggregatePrefetchStarvedPerSec(inWindow, durationMs),
            submitUrgentCount = countEvents(inWindow, "submit_urgent"),
            prefetchStartedCount = countEvents(inWindow, "submit_prefetch") -
                countEvents(inWindow, "submit_prefetch_starved"),
            observedPrefetchChunkBytes = observedPrefetchChunkBytes(inWindow),
            maxHeapDeltaKb = maxHeapDeltaKb(inWindow),
            maxThermalStatus = maxThermalStatus(inWindow),
            lowMemoryDuringWindow = inWindow.any { it.optStringField("ev") == "low_memory" },
        )
    }

    private fun countEvents(events: List<JsonObject>, type: String): Int =
        events.count { it.optStringField("ev") == type }

    private fun aggregateNetworkBytesPerSec(events: List<JsonObject>): Long {
        val totals = events
            .filter { it.optStringField("ev") == "range_done" }
            .sumOf { it.optLongField("bytes", 0L) }
        return totals / 5L // 5-second window
    }

    private fun networkCollapseFraction(events: List<JsonObject>): Double {
        // Compare bytes/sec in the most recent 1-second sub-window vs the
        // prior 4-second sub-window.
        val byTime = events
            .filter { it.optStringField("ev") == "range_done" }
            .map { it.optLongField("tNs", 0L) to it.optLongField("bytes", 0L) }
        if (byTime.isEmpty()) return 0.0
        val maxNs = byTime.maxOf { it.first }
        val recentNs = maxNs - 1_000_000_000L
        val recent = byTime.filter { it.first >= recentNs }.sumOf { it.second }
        val prior = byTime.filter { it.first < recentNs }.sumOf { it.second } / 4L
        if (prior == 0L) return 0.0
        return ((prior - recent).toDouble() / prior.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun aggregateFrontierBytesPerSec(events: List<JsonObject>): Long {
        val totals = events
            .filter { it.optStringField("ev") == "frontier_advance" }
            .sumOf { it.optLongField("delta", 0L) }
        return totals / 5L
    }

    private fun aggregateReadWaitP99Ms(events: List<JsonObject>): Long =
        p99FromEvents(events, "read_wait_end", "ms")

    private fun aggregateStoreLockWaitP99Ms(events: List<JsonObject>): Long =
        p99FromEvents(events, "store_lock_wait_ms", "p99")

    private fun aggregateHttpTtfbP99Ms(events: List<JsonObject>): Long =
        p99FromEvents(events, "range_http_response_headers", "ms")

    private fun aggregateHttpBodyGapP99Ms(events: List<JsonObject>): Long =
        p99FromEvents(events, "range_http_body_end", "ms")

    private fun aggregateCacheWriteLatencyP99Ms(events: List<JsonObject>): Long =
        p99FromEvents(events, "cache_write_latency_ms", "p99")

    private fun warmAheadActiveDuringWindow(events: List<JsonObject>): Boolean {
        // Active if a warm_ahead_start appears without a matching warm_ahead_stop
        // before the window's end.
        var active = false
        for (ev in events) {
            when (ev.optStringField("ev")) {
                "warm_ahead_start" -> active = true
                "warm_ahead_stop" -> active = false
            }
        }
        return active
    }

    private fun aggregatePrefetchStarvedPerSec(events: List<JsonObject>, windowMs: Long): Double {
        if (windowMs <= 0L) return 0.0
        val starved = countEvents(events, "submit_prefetch_starved")
        return (starved.toDouble() * 1000.0) / windowMs.toDouble()
    }

    private fun observedPrefetchChunkBytes(events: List<JsonObject>): Long? {
        val sample = events.firstOrNull { it.optStringField("ev") == "submit_prefetch" }
            ?: return null
        val start = sample.optLongField("startByte", 0L)
        val end = sample.optLongField("endByte", 0L)
        return if (end > start) end - start + 1L else null
    }

    private fun maxHeapDeltaKb(events: List<JsonObject>): Long {
        val snapshots = events
            .filter { it.optStringField("ev") == "memory_snapshot" }
            .map { it.optLongField("javaHeapKb", 0L) }
        if (snapshots.size < 2) return 0L
        return snapshots.max() - snapshots.min()
    }

    private fun maxThermalStatus(events: List<JsonObject>): Int =
        events
            .filter { it.optStringField("ev") == "thermal_status_changed" }
            .maxOfOrNull { it.optIntField("status", 0) } ?: 0

    private fun p99FromEvents(events: List<JsonObject>, evType: String, key: String): Long {
        val values = events
            .filter { it.optStringField("ev") == evType }
            .map { it.optLongField(key, 0L) }
            .sorted()
        if (values.isEmpty()) return 0L
        val idx = ((values.size - 1) * 99) / 100
        return values[idx]
    }
}

/**
 * Subset of the playback-trace [SessionHeader] needed by the classifier rules.
 * Parsed from the `playback_session_started` event payload.
 */
data class ParsedSessionHeader(
    val sessionId: String,
    val branch: String,
    val envelopePresent: Boolean,
    val specializationState: String,
    val specializationMismatchReason: String?,
    val policySource: String?,
    val policyPrefetchChunkBytes: Long?,
) {
    companion object {
        fun fromJson(obj: JsonObject): ParsedSessionHeader = ParsedSessionHeader(
            sessionId = obj.optStringField("sid").ifEmpty { obj.optStringField("sessionId") },
            branch = obj.optStringField("branch"),
            envelopePresent = obj.optBoolField("envelopePresent", false),
            specializationState = obj.optStringField("specializationState"),
            specializationMismatchReason = obj.optStringOrNull("specializationMismatchReason")
                ?.takeUnless { it == "null" || it.isEmpty() },
            policySource = obj.optStringOrNull("policy_source")
                ?.takeUnless { it == "null" || it.isEmpty() },
            policyPrefetchChunkBytes = obj.optLongField("policy_prefetchChunkBytes", -1L)
                .takeIf { it > 0L },
        )
    }
}

/**
 * Aggregated metrics over the 5-second window leading up to a `rebuffer_end`
 * event. All fields are computed from the JSONL stream — none of them are
 * derived at runtime in the on-device tracer.
 */
data class RebufferWindow(
    val startTNanos: Long,
    val endTNanos: Long,
    val windowDurationMs: Long,
    val networkBytesPerSec: Long,
    val networkBytesPerSec5sCollapseFraction: Double,
    val frontierAdvanceBytesPerSec: Long,
    val readWaitP99Ms: Long,
    val storeLockWaitP99Ms: Long,
    val rangeHttpTtfbP99Ms: Long,
    val rangeHttpBodyGapP99Ms: Long,
    val droppedFrames5s: Int,
    val audioUnderruns5s: Int,
    val warmAheadActive: Boolean,
    val cacheWriteLatencyP99Ms: Long,
    val prefetchStarvedPerSec: Double,
    val submitUrgentCount: Int,
    val prefetchStartedCount: Int,
    val observedPrefetchChunkBytes: Long?,
    val maxHeapDeltaKb: Long,
    val maxThermalStatus: Int,
    val lowMemoryDuringWindow: Boolean,
)
