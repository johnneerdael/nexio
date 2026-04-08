package com.nexio.tv.ui.screens.player

import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe gate that tracks the most recent rebuffer event and exposes whether the
 * warm-ahead loop should pause while the player is recovering.
 *
 * [isPaused] returns true for [ttlMs] milliseconds after the last [notifyRebuffer] call.
 * [ageMs] returns [Long.MAX_VALUE] when no rebuffer has ever been recorded.
 *
 * The [nowMsProvider] is injectable for deterministic unit tests (defaults to
 * [android.os.SystemClock.elapsedRealtime]).
 */
internal class RebufferGate(
    private val ttlMs: Long = 2_000L,
    private val nowMsProvider: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) {
    // Sentinel: Long.MIN_VALUE means "never recorded". Distinct from any valid elapsedRealtime
    // value (which is always >= 0), so even a notifyRebuffer() at t=0 is handled correctly.
    private val lastRebufferMs = AtomicLong(Long.MIN_VALUE)

    /** Records the current time as the most-recent rebuffer timestamp (most-recent wins). */
    fun notifyRebuffer() {
        lastRebufferMs.set(nowMsProvider())
    }

    /**
     * Returns true if a rebuffer was recorded within the last [ttlMs] milliseconds.
     * Returns false if no rebuffer has ever been recorded.
     */
    fun isPaused(): Boolean {
        val last = lastRebufferMs.get()
        if (last == Long.MIN_VALUE) return false
        return (nowMsProvider() - last) < ttlMs
    }

    /**
     * Returns elapsed milliseconds since the last rebuffer, or [Long.MAX_VALUE] if none recorded.
     */
    fun ageMs(): Long {
        val last = lastRebufferMs.get()
        return if (last == Long.MIN_VALUE) Long.MAX_VALUE else nowMsProvider() - last
    }
}
