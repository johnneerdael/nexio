package com.nexio.tv.integrations.hyperhdr.network

import kotlin.math.min
import kotlin.random.Random

/** Truncated exponential backoff with jitter. */
class BackoffSchedule(
    private val initialMs: Long = 250,
    private val maxMs: Long = 5_000,
    private val multiplier: Double = 2.0,
    private val jitter: Double = 0.2,
    private val random: Random = Random.Default,
) {
    private var attempts = 0

    fun nextDelayMs(): Long {
        val base = (initialMs * Math.pow(multiplier, attempts.toDouble())).toLong()
        val capped = min(base, maxMs)
        val jitterRange = (capped * jitter).toLong()
        attempts++
        return capped + random.nextLong(-jitterRange, jitterRange + 1)
    }

    fun reset() { attempts = 0 }
}
