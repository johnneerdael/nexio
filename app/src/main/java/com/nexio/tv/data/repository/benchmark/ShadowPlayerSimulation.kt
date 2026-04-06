package com.nexio.tv.data.repository.benchmark

class ShadowPlayerSimulation(
    private val startupBufferMs: Long = 3_000L,
    private val maxBufferMs: Long = 50_000L,
    private val safetyFactor: Double = 0.85,
    private val searchToleranceMbps: Double = 0.1,
    private val minBitrateMbps: Double = 1.0,
    private val maxBitrateMbps: Double = 500.0
) {

    data class SimulationRun(
        val survived: Boolean,
        val rebufferCount: Int,
        val minBufferMs: Long
    )

    fun simulate(events: List<FrontierEvent>, candidateBitrateMbps: Double): SimulationRun {
        if (events.isEmpty()) return SimulationRun(survived = false, rebufferCount = 0, minBufferMs = 0)
        if (candidateBitrateMbps <= 0.0) return SimulationRun(survived = true, rebufferCount = 0, minBufferMs = Long.MAX_VALUE)

        val bytesPerMs = candidateBitrateMbps * 1_000_000.0 / 8.0 / 1_000.0
        val maxBufferBytes = maxBufferMs * bytesPerMs
        val startupBufferBytes = startupBufferMs * bytesPerMs

        var bufferBytes = 0.0
        var started = false
        var lastTMs = events.first().tMs
        var rebufferCount = 0
        var minBufferMs = Long.MAX_VALUE

        for (event in events) {
            val deltaTMs = event.tMs - lastTMs

            if (started && deltaTMs > 0) {
                val consumed = bytesPerMs * deltaTMs
                bufferBytes -= consumed
                if (bufferBytes < 0) {
                    rebufferCount++
                    bufferBytes = 0.0
                    started = false
                }
            }

            bufferBytes = minOf(bufferBytes + event.deltaContiguousBytes, maxBufferBytes)

            if (!started && bufferBytes >= startupBufferBytes) {
                started = true
            }

            if (started) {
                minBufferMs = minOf(minBufferMs, (bufferBytes / bytesPerMs).toLong())
            }

            lastTMs = event.tMs
        }

        return SimulationRun(
            survived = rebufferCount == 0,
            rebufferCount = rebufferCount,
            minBufferMs = minBufferMs
        )
    }

    fun findMaxSustainableBitrate(events: List<FrontierEvent>): ShadowPlayerSimulationResult {
        if (events.size < 2) {
            return ShadowPlayerSimulationResult(
                maxSustainableBitrateMbps = 0.0,
                safeBudgetMbps = 0.0,
                searchIterations = 0,
                startupBufferMs = startupBufferMs,
                maxBufferMs = maxBufferMs,
                simulatedRebufferCount = 0
            )
        }

        val sortedEvents = events.sortedBy { it.tMs }

        var lo = minBitrateMbps
        var hi = maxBitrateMbps
        var iterations = 0

        while ((hi - lo) > searchToleranceMbps) {
            val mid = (lo + hi) / 2.0
            if (simulate(sortedEvents, mid).survived) {
                lo = mid
            } else {
                hi = mid
            }
            iterations++
        }

        val maxSustainable = lo
        val safeBudget = maxSustainable * safetyFactor

        return ShadowPlayerSimulationResult(
            maxSustainableBitrateMbps = maxSustainable,
            safeBudgetMbps = safeBudget,
            searchIterations = iterations,
            startupBufferMs = startupBufferMs,
            maxBufferMs = maxBufferMs,
            simulatedRebufferCount = simulate(sortedEvents, safeBudget).rebufferCount
        )
    }
}
