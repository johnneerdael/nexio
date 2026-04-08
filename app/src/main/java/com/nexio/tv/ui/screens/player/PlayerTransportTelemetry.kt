package com.nexio.tv.ui.screens.player

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Structured telemetry helper for the parallel transport stack.
 *
 * All logs are emitted under the single tag "nexio.transport" so they can be
 * captured from logcat with: `adb logcat -s nexio.transport:I`
 *
 * Format: `site=<site> key1=val1 key2=val2 ...` — keys are sorted alphabetically
 * for deterministic output regardless of map insertion order.
 *
 * [logThrottled] is rate-limited per site: at most one emission per [windowMs].
 * The internal clock is injectable via [nowMsProvider] for deterministic unit tests.
 */
internal object PlayerTransportTelemetry {
    internal const val TAG = "nexio.transport"

    // Per-site last-emission timestamp for the rate limiter.
    private val lastLogMsBysite = ConcurrentHashMap<String, AtomicLong>()

    // Clock provider; injectable for tests.
    internal var nowMsProvider: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    /**
     * Emits one log line unconditionally.
     *
     * Keys are sorted alphabetically; null values render as "null".
     */
    fun log(site: String, pairs: Map<String, Any?>) {
        Log.i(TAG, buildLine(site, pairs))
    }

    /**
     * Emits a log line for [site] at most once per [windowMs].
     *
     * Returns true when a line was emitted, false when suppressed by the rate limiter.
     * The [pairs] map is evaluated by the caller before calling this function; the
     * rate-limiter short-circuit must be done at the call site to avoid map allocation
     * on the hot path (the caller can use a lambda or check [wouldEmit] first).
     */
    fun logThrottled(site: String, windowMs: Long, pairs: Map<String, Any?>): Boolean {
        val now = nowMsProvider()
        val stamp = lastLogMsBysite.getOrPut(site) { AtomicLong(Long.MIN_VALUE / 2) }
        val last = stamp.get()
        if (now - last < windowMs) return false
        if (!stamp.compareAndSet(last, now)) return false
        Log.i(TAG, buildLine(site, pairs))
        return true
    }

    private fun buildLine(site: String, pairs: Map<String, Any?>): String {
        val sb = StringBuilder("site=").append(site)
        pairs.entries
            .sortedBy { it.key }
            .forEach { (k, v) ->
                sb.append(' ').append(k).append('=').append(v ?: "null")
            }
        return sb.toString()
    }

    /** Resets all rate-limiter state. Intended for tests only. */
    internal fun resetForTest() {
        lastLogMsBysite.clear()
    }
}
