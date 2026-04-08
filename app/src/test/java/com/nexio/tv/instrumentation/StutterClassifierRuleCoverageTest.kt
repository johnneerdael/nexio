package com.nexio.tv.instrumentation

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T7 — coverage for every [StutterClassifier.Cause] rule.
 *
 * Each test method builds an in-memory list of JSON events that *only* the
 * targeted rule should fire on, runs the parser + classifier, and asserts the
 * verdict. Together with [StutterClassifierTest] (which exercises the
 * synthetic transport-stutter fixture), this guarantees the spec §D rule
 * order is correct and that each rule's discriminating fields are honored.
 *
 * The synthetic events are deliberately minimal — just enough to trip a
 * single rule each, with care taken not to accidentally fire higher-priority
 * rules in the chain (POLICY_MISMATCH → DEVICE → CACHE_INTERFERENCE →
 * SCHEDULER → TRANSPORT → FRONTIER → FACADE → DECODE_RENDER → UNKNOWN).
 */
class StutterClassifierRuleCoverageTest {

    @Test
    fun policyMismatchOnFallbackPolicySource() {
        // Header has policy_source starting with "fallback" → POLICY_MISMATCH
        // fires before any window data is even examined.
        val session = sessionWithHeader(headerOverrides = mapOf(
            "policy_source" to "fallback(2,16MiB)",
        ))
        assertEquals(StutterClassifier.Cause.POLICY_MISMATCH, classify(session))
    }

    @Test
    fun policyMismatchOnNonPrdsBranch() {
        val session = sessionWithHeader(headerOverrides = mapOf(
            "branch" to "okHttp",
        ))
        assertEquals(StutterClassifier.Cause.POLICY_MISMATCH, classify(session))
    }

    @Test
    fun deviceUnderPressureOnLowMemory() {
        val session = healthyHeaderSession(extraEvents = listOf(
            event("DEVICE", "low_memory", "tNs" to T_END - 1_000_000_000L),
        ))
        assertEquals(StutterClassifier.Cause.DEVICE, classify(session))
    }

    @Test
    fun deviceUnderPressureOnThermalModerate() {
        val session = healthyHeaderSession(extraEvents = listOf(
            event(
                "DEVICE", "thermal_status_changed",
                "tNs" to T_END - 1_000_000_000L,
                "status" to StutterClassifier.THERMAL_MODERATE,
            ),
        ))
        assertEquals(StutterClassifier.Cause.DEVICE, classify(session))
    }

    @Test
    fun cacheInterferenceWhileWarmAheadActive() {
        // Warm-ahead active during the window + slow cache writes + frontier
        // < 1 MiB/s. Network bytes deliberately low so transport rule can
        // also not fire above this rule (which has higher priority anyway).
        val session = healthyHeaderSession(extraEvents = listOf(
            event("CACHE", "warm_ahead_start", "tNs" to T_END - 4_000_000_000L),
            // Slow cache writes — p99 > 50 ms.
            event("CACHE", "cache_write_latency_ms", "tNs" to T_END - 3_000_000_000L, "p99" to 120L),
            event("CACHE", "cache_write_latency_ms", "tNs" to T_END - 2_500_000_000L, "p99" to 130L),
            event("CACHE", "cache_write_latency_ms", "tNs" to T_END - 2_000_000_000L, "p99" to 150L),
            // No frontier_advance events → 0 MiB/s frontier rate.
        ))
        assertEquals(StutterClassifier.Cause.CACHE_INTERFERENCE, classify(session))
    }

    @Test
    fun schedulerStarvedOnHighStarvationRate() {
        val starvedEvents = (0 until 50).map { i ->
            event(
                "RANGE", "submit_prefetch_starved",
                "tNs" to T_END - (4_000_000_000L - i * 80_000_000L),
                "reason" to "urgent_pending",
            )
        }
        val session = healthyHeaderSession(extraEvents = starvedEvents)
        assertEquals(StutterClassifier.Cause.SCHEDULER, classify(session))
    }

    @Test
    fun transportStarvedOnNetworkCollapsePlusHighGapsAndReadWaits() {
        // Synthetic transport-stutter shape: high prior throughput, then a
        // collapse, plus HTTP TTFB / body gaps and read-wait spikes.
        val events = mutableListOf<JsonObject>()
        // Prior 4 s of healthy throughput (4 × 4 MiB).
        for (i in 0 until 4) {
            events += event(
                "RANGE", "range_done",
                "tNs" to T_END - 5_000_000_000L + i * 1_000_000_000L,
                "bytes" to 4L * 1024 * 1024,
            )
        }
        // Last 1 s collapses to 64 KiB.
        events += event(
            "RANGE", "range_done",
            "tNs" to T_END - 500_000_000L,
            "bytes" to 65_536L,
        )
        // High response-headers TTFB samples (> 800 ms p99).
        events += event("RANGE", "range_http_response_headers", "tNs" to T_END - 4_500_000_000L, "ms" to 850L)
        events += event("RANGE", "range_http_response_headers", "tNs" to T_END - 3_500_000_000L, "ms" to 900L)
        // Body-end timestamps with > 500 ms inter-arrival gap.
        events += event("RANGE", "range_http_body_end", "tNs" to T_END - 4_400_000_000L, "ms" to 200L)
        events += event("RANGE", "range_http_body_end", "tNs" to T_END - 3_300_000_000L, "ms" to 250L)
        events += event("RANGE", "range_http_body_end", "tNs" to T_END - 1_500_000_000L, "ms" to 250L)
        // Read-wait spikes.
        events += event("READ_WAIT", "read_wait_end", "tNs" to T_END - 1_200_000_000L, "ms" to 350L, "reason" to "no_bytes_at_cursor")
        events += event("READ_WAIT", "read_wait_end", "tNs" to T_END - 1_100_000_000L, "ms" to 400L, "reason" to "no_bytes_at_cursor")

        val session = healthyHeaderSession(extraEvents = events)
        assertEquals(StutterClassifier.Cause.TRANSPORT, classify(session))
    }

    @Test
    fun frontierStalledOnHighStoreLockWait() {
        // Network healthy, but store_lock_wait p99 > 50 ms — frontier rule
        // catches monitor contention.
        val events = mutableListOf<JsonObject>()
        // Healthy network throughput across the window (8 × 1 MiB).
        for (i in 0 until 5) {
            events += event(
                "RANGE", "range_done",
                "tNs" to T_END - 5_000_000_000L + i * 1_000_000_000L,
                "bytes" to 2L * 1024 * 1024,
            )
        }
        // Frontier advances at the same rate so the network/frontier ratio
        // stays healthy and only the lock-wait rule fires.
        for (i in 0 until 5) {
            events += event(
                "FRONTIER", "frontier_advance",
                "tNs" to T_END - 5_000_000_000L + i * 1_000_000_000L,
                "delta" to 2L * 1024 * 1024,
            )
        }
        // store_lock_wait_ms samples > 50 ms p99.
        events += event("FRONTIER", "store_lock_wait_ms", "tNs" to T_END - 1_000_000_000L, "p99" to 75L)
        events += event("FRONTIER", "store_lock_wait_ms", "tNs" to T_END - 500_000_000L, "p99" to 95L)
        val session = healthyHeaderSession(extraEvents = events)
        assertEquals(StutterClassifier.Cause.FRONTIER, classify(session))
    }

    @Test
    fun facadeStarvedDespiteHealthyNetworkAndFrontier() {
        val events = mutableListOf<JsonObject>()
        // Healthy network + frontier (each ≥ 1 MiB/s).
        for (i in 0 until 5) {
            events += event(
                "RANGE", "range_done",
                "tNs" to T_END - 5_000_000_000L + i * 1_000_000_000L,
                "bytes" to 2L * 1024 * 1024,
            )
            events += event(
                "FRONTIER", "frontier_advance",
                "tNs" to T_END - 5_000_000_000L + i * 1_000_000_000L,
                "delta" to 2L * 1024 * 1024,
            )
        }
        // No store_lock_wait → frontier rule does not fire.
        // High read_wait p99 → facade rule fires.
        events += event("READ_WAIT", "read_wait_end", "tNs" to T_END - 800_000_000L, "ms" to 250L, "reason" to "no_bytes_at_cursor")
        events += event("READ_WAIT", "read_wait_end", "tNs" to T_END - 400_000_000L, "ms" to 300L, "reason" to "no_bytes_at_cursor")
        val session = healthyHeaderSession(extraEvents = events)
        assertEquals(StutterClassifier.Cause.FACADE, classify(session))
    }

    @Test
    fun decodeRenderOnDroppedFramesWithHealthyTransport() {
        val events = mutableListOf<JsonObject>()
        // Healthy network + frontier so transport / frontier / facade rules
        // do not fire.
        for (i in 0 until 5) {
            events += event(
                "RANGE", "range_done",
                "tNs" to T_END - 5_000_000_000L + i * 1_000_000_000L,
                "bytes" to 2L * 1024 * 1024,
            )
            events += event(
                "FRONTIER", "frontier_advance",
                "tNs" to T_END - 5_000_000_000L + i * 1_000_000_000L,
                "delta" to 2L * 1024 * 1024,
            )
        }
        val session = healthyHeaderSession(
            extraEvents = events,
            droppedFrames5s = 12,
        )
        assertEquals(StutterClassifier.Cause.DECODE_RENDER, classify(session))
    }

    @Test
    fun unknownWhenNoRuleFires() {
        // Healthy header, healthy network/frontier, no read waits, no
        // dropped frames → no rule should fire and the verdict is UNKNOWN.
        val events = mutableListOf<JsonObject>()
        for (i in 0 until 5) {
            events += event(
                "RANGE", "range_done",
                "tNs" to T_END - 5_000_000_000L + i * 1_000_000_000L,
                "bytes" to 2L * 1024 * 1024,
            )
            events += event(
                "FRONTIER", "frontier_advance",
                "tNs" to T_END - 5_000_000_000L + i * 1_000_000_000L,
                "delta" to 2L * 1024 * 1024,
            )
        }
        val session = healthyHeaderSession(extraEvents = events)
        assertEquals(StutterClassifier.Cause.UNKNOWN, classify(session))
    }

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    private fun classify(session: ParsedSession): StutterClassifier.Cause {
        val window = session.rebufferWindows().single()
        return StutterClassifier(session).classify(window)
    }

    /**
     * Build a [ParsedSession] with a single rebuffer window and a healthy
     * (non-mismatch) header. Caller can splice in extra events to trip a
     * specific rule.
     */
    private fun healthyHeaderSession(
        extraEvents: List<JsonObject> = emptyList(),
        droppedFrames5s: Int = 0,
        audioUnderruns5s: Int = 0,
    ): ParsedSession = sessionWithHeader(
        extraEvents = extraEvents,
        droppedFrames5s = droppedFrames5s,
        audioUnderruns5s = audioUnderruns5s,
    )

    private fun sessionWithHeader(
        headerOverrides: Map<String, Any?> = emptyMap(),
        extraEvents: List<JsonObject> = emptyList(),
        droppedFrames5s: Int = 0,
        audioUnderruns5s: Int = 0,
    ): ParsedSession {
        val headerEvent = JsonObject().apply {
            addProperty("sid", SESSION_ID)
            addProperty("tNs", 0L)
            addProperty("th", "main")
            addProperty("fam", "SESSION")
            addProperty("ev", "playback_session_started")
            // Healthy defaults — overridden per-test.
            addProperty(SessionHeaderKeys.SESSION_ID, SESSION_ID)
            addProperty(SessionHeaderKeys.BRANCH, "prds")
            addProperty(SessionHeaderKeys.ENVELOPE_PRESENT, true)
            addProperty(SessionHeaderKeys.SPECIALIZATION_STATE, "confirmed")
            addProperty(SessionHeaderKeys.POLICY_SOURCE, "envelope")
            addProperty(SessionHeaderKeys.POLICY_PREFETCH_CHUNK_BYTES, 8L * 1024 * 1024)
            for ((k, v) in headerOverrides) {
                when (v) {
                    null -> add(k, com.google.gson.JsonNull.INSTANCE)
                    is Number -> addProperty(k, v)
                    is Boolean -> addProperty(k, v)
                    is String -> addProperty(k, v)
                    else -> addProperty(k, v.toString())
                }
            }
        }
        val rebufferStart = event(
            "REBUFFER", "rebuffer_start",
            "tNs" to (T_END - 2_000_000_000L),
            "posMs" to 12_000L,
        )
        val rebufferEnd = event(
            "REBUFFER", "rebuffer_end",
            "tNs" to T_END,
            "durationMs" to 2_000L,
            "droppedFrames5s" to droppedFrames5s,
            "audioUnderruns5s" to audioUnderruns5s,
            "posMs" to 12_000L,
        )
        val all = mutableListOf(headerEvent).apply {
            addAll(extraEvents)
            add(rebufferStart)
            add(rebufferEnd)
        }
        // sort by tNs so the parser sees a chronologically-correct stream
        all.sortBy { it.get("tNs")?.asLong ?: 0L }
        return ParsedSession(
            header = ParsedSessionHeader.fromJson(headerEvent),
            events = all.toList(),
        )
    }

    private fun event(family: String, type: String, vararg fields: Pair<String, Any?>): JsonObject =
        JsonObject().apply {
            addProperty("sid", SESSION_ID)
            addProperty("th", "test")
            addProperty("fam", family)
            addProperty("ev", type)
            for ((k, v) in fields) {
                when (v) {
                    null -> add(k, com.google.gson.JsonNull.INSTANCE)
                    is Number -> addProperty(k, v)
                    is Boolean -> addProperty(k, v)
                    is String -> addProperty(k, v)
                    else -> addProperty(k, v.toString())
                }
            }
        }

    companion object {
        private const val SESSION_ID = "rule-coverage"
        // End-of-window timestamp picked far enough out that
        // (T_END - 5 000 000 000) stays positive.
        private const val T_END: Long = 30_000_000_000L
    }
}
