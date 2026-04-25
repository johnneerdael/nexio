package com.nexio.tv.core.player.auth

import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory ring buffer of auth-recovery attempts. Used for adb-visible
 * telemetry (`adb logcat -s AuthRecovery:*`) and exposed read-only via
 * [snapshot] for diagnostic UIs / playback ZIP exports.
 */
object AuthRecoveryTracker {
    private const val TAG = "AuthRecovery"
    private const val RING_SIZE = 16

    enum class Outcome { RECOVERED, GAVE_UP, RATE_LIMITED, NO_PROXY_KNOWN }

    data class Attempt(
        val proxyUrl: String,
        val statusCode: Int,
        val outcome: Outcome,
        val timestampMs: Long
    )

    private val lock = Any()
    private val ring = ArrayDeque<Attempt>(RING_SIZE)
    private val total = AtomicInteger(0)
    private val recovered = AtomicInteger(0)

    fun record(proxyUrl: String, statusCode: Int, outcome: Outcome) {
        val attempt = Attempt(proxyUrl, statusCode, outcome, System.currentTimeMillis())
        synchronized(lock) {
            if (ring.size >= RING_SIZE) ring.removeFirst()
            ring.addLast(attempt)
        }
        total.incrementAndGet()
        if (outcome == Outcome.RECOVERED) recovered.incrementAndGet()
        Log.i(
            TAG,
            "AUTH_RECOVERY status=$statusCode outcome=$outcome proxyHost=${safeHost(proxyUrl)}"
        )
    }

    fun snapshot(): List<Attempt> = synchronized(lock) { ring.toList() }
    fun totalAttempts(): Int = total.get()
    fun recoveredCount(): Int = recovered.get()

    internal fun resetForTesting() {
        synchronized(lock) { ring.clear() }
        total.set(0)
        recovered.set(0)
    }

    private fun safeHost(url: String): String =
        runCatching { java.net.URI(url).host ?: "unknown" }.getOrDefault("unknown")
}
