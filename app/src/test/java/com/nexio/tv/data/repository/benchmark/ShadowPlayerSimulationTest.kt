package com.nexio.tv.data.repository.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadowPlayerSimulationTest {

    private val simulation = ShadowPlayerSimulation(
        startupBufferMs = 3_000L,
        maxBufferMs = 50_000L,
        safetyFactor = 0.85
    )

    @Test
    fun `constant rate frontier produces expected max sustainable bitrate`() {
        // Simulate 100 Mbps constant download over 60 seconds
        // 100 Mbps = 12.5 MB/s = 12500 bytes/ms
        val bytesPerMs = 100.0 * 1_000_000 / 8.0 / 1_000.0
        val events = (0 until 120).map { i ->
            val tMs = i * 500L // every 500ms
            val deltaBytes = (bytesPerMs * 500).toLong()
            FrontierEvent(
                tMs = tMs,
                contiguousFrontierBytes = (i + 1).toLong() * deltaBytes,
                deltaContiguousBytes = deltaBytes
            )
        }

        val result = simulation.findMaxSustainableBitrate(events)
        // Max sustainable should be close to or slightly above 100 Mbps
        // (startup buffer headroom allows slightly above the delivery rate)
        assertTrue(
            "Expected ~100-110 Mbps, got ${result.maxSustainableBitrateMbps}",
            result.maxSustainableBitrateMbps in 95.0..115.0
        )
        // Safe budget = maxSustainable * 0.85
        assertTrue(
            "Expected ~85-95 Mbps safe budget, got ${result.safeBudgetMbps}",
            result.safeBudgetMbps in 80.0..100.0
        )
        assertEquals(0, result.simulatedRebufferCount)
    }

    @Test
    fun `bursty frontier produces higher budget than worst-case throughput`() {
        // Simulate Wi-Fi bursts: 200 Mbps for 400ms, then stall for 600ms, repeat
        // Average throughput: 80 Mbps, but p10 bucket would be much lower
        val bytesPerMs200 = 200.0 * 1_000_000 / 8.0 / 1_000.0
        val events = mutableListOf<FrontierEvent>()
        var totalBytes = 0L
        var tMs = 0L

        for (cycle in 0 until 12) { // 12 seconds total
            // Burst phase: 400ms at 200 Mbps
            for (i in 0 until 4) {
                val delta = (bytesPerMs200 * 100).toLong() // 100ms increments
                totalBytes += delta
                tMs += 100
                events.add(FrontierEvent(tMs, totalBytes, delta))
            }
            // Stall phase: 600ms with no frontier advance (no events emitted)
            tMs += 600
        }

        val result = simulation.findMaxSustainableBitrate(events)
        // Should be able to sustain well above what a p10 bucket would say
        // Average delivery is 80 Mbps, buffer absorbs the gaps
        assertTrue(
            "Expected budget > 50 Mbps for bursty 80 Mbps average, got ${result.safeBudgetMbps}",
            result.safeBudgetMbps > 50.0
        )
        assertEquals(0, result.simulatedRebufferCount)
    }

    @Test
    fun `very slow frontier produces low budget`() {
        // 5 Mbps constant
        val bytesPerMs = 5.0 * 1_000_000 / 8.0 / 1_000.0
        val events = (0 until 60).map { i ->
            val tMs = i * 1000L
            val deltaBytes = (bytesPerMs * 1000).toLong()
            FrontierEvent(
                tMs = tMs,
                contiguousFrontierBytes = (i + 1).toLong() * deltaBytes,
                deltaContiguousBytes = deltaBytes
            )
        }

        val result = simulation.findMaxSustainableBitrate(events)
        assertTrue(
            "Expected ~5 Mbps max, got ${result.maxSustainableBitrateMbps}",
            result.maxSustainableBitrateMbps in 4.0..6.0
        )
    }

    @Test
    fun `empty events returns zero budget`() {
        val result = simulation.findMaxSustainableBitrate(emptyList())
        assertEquals(0.0, result.safeBudgetMbps, 0.01)
        assertEquals(0, result.searchIterations)
    }

    @Test
    fun `single event returns zero budget`() {
        val result = simulation.findMaxSustainableBitrate(
            listOf(FrontierEvent(tMs = 0, contiguousFrontierBytes = 1_000_000, deltaContiguousBytes = 1_000_000))
        )
        assertEquals(0.0, result.safeBudgetMbps, 0.01)
    }

    @Test
    fun `simulate with zero bitrate always survives`() {
        val events = listOf(
            FrontierEvent(0, 1000, 1000),
            FrontierEvent(1000, 2000, 1000)
        )
        val run = simulation.simulate(events, 0.0)
        assertTrue(run.survived)
        assertEquals(0, run.rebufferCount)
    }

    @Test
    fun `simulate fails when bitrate far exceeds frontier rate`() {
        // Frontier delivers 10 Mbps but we try to play at 500 Mbps
        // At 500 Mbps, startup needs 187.5 MB but frontier only delivers 12.5 MB total
        // Player never starts → survived = false
        val bytesPerMs10 = 10.0 * 1_000_000 / 8.0 / 1_000.0
        val events = (0 until 20).map { i ->
            val tMs = i * 500L
            val delta = (bytesPerMs10 * 500).toLong()
            FrontierEvent(tMs, (i + 1).toLong() * delta, delta)
        }

        val run = simulation.simulate(events, 500.0)
        assertFalse(run.survived)
    }

    @Test
    fun `simulate detects rebuffer when bitrate moderately exceeds frontier rate`() {
        // Frontier delivers 10 Mbps, try to play at 15 Mbps (starts but then drains)
        val bytesPerMs10 = 10.0 * 1_000_000 / 8.0 / 1_000.0
        val events = (0 until 60).map { i ->
            val tMs = i * 500L
            val delta = (bytesPerMs10 * 500).toLong()
            FrontierEvent(tMs, (i + 1).toLong() * delta, delta)
        }

        val run = simulation.simulate(events, 15.0)
        assertFalse(run.survived)
        assertTrue(run.rebufferCount > 0)
    }

    @Test
    fun `startup buffer requirement delays playback start`() {
        val sim = ShadowPlayerSimulation(
            startupBufferMs = 5_000L,
            maxBufferMs = 50_000L,
            safetyFactor = 0.85
        )

        // Deliver enough for 3s buffer at 10 Mbps, then stop
        // 10 Mbps for 3s = 3.75 MB
        val bytesPerMs = 10.0 * 1_000_000 / 8.0 / 1_000.0
        val events = (0 until 6).map { i ->
            val delta = (bytesPerMs * 500).toLong()
            FrontierEvent(i * 500L, (i + 1).toLong() * delta, delta)
        }

        // At 10 Mbps candidate, startup needs 5s * 10Mbps = 6.25 MB
        // We only delivered 3s worth = 3.75 MB → player never starts → survived=false technically
        // but rebufferCount=0 since player never started
        val run = sim.simulate(events, 10.0)
        // The player never started because we didn't reach startup buffer
        assertFalse(run.survived)
    }

    @Test
    fun `buffer caps at max buffer bytes`() {
        val sim = ShadowPlayerSimulation(
            startupBufferMs = 1_000L,
            maxBufferMs = 5_000L,
            safetyFactor = 0.85
        )

        // Deliver massive burst then slow trickle
        val events = mutableListOf<FrontierEvent>()
        // 500 Mbps burst for 10 seconds
        val burstBytesPerMs = 500.0 * 1_000_000 / 8.0 / 1_000.0
        var total = 0L
        for (i in 0 until 20) {
            val delta = (burstBytesPerMs * 500).toLong()
            total += delta
            events.add(FrontierEvent(i * 500L, total, delta))
        }
        // Then nothing for 20 seconds
        events.add(FrontierEvent(30_000, total, 0))

        // At 50 Mbps, 5s max buffer = 31.25 MB buffer cap
        // After burst, buffer is capped at max. Then 20s drain at 50 Mbps = 125 MB drain
        // But buffer was only 31.25 MB → rebuffer after ~5s of drain
        val run = sim.simulate(events, 50.0)
        assertFalse(run.survived)
    }

    @Test
    fun `safety factor is applied correctly`() {
        // 50 Mbps constant
        val bytesPerMs = 50.0 * 1_000_000 / 8.0 / 1_000.0
        val events = (0 until 120).map { i ->
            val delta = (bytesPerMs * 500).toLong()
            FrontierEvent(i * 500L, (i + 1).toLong() * delta, delta)
        }

        val result = simulation.findMaxSustainableBitrate(events)
        val expectedSafe = result.maxSustainableBitrateMbps * 0.85
        assertEquals(expectedSafe, result.safeBudgetMbps, 0.2)
    }

    @Test
    fun `tail drain helper appends zero delta event at run end`() {
        val events = listOf(
            FrontierEvent(tMs = 1_000L, contiguousFrontierBytes = 1_000_000L, deltaContiguousBytes = 1_000_000L),
            FrontierEvent(tMs = 5_000L, contiguousFrontierBytes = 5_000_000L, deltaContiguousBytes = 4_000_000L)
        )

        val adjusted = appendTailDrainFrontierEvent(events, runEndMs = 30_000L)

        assertEquals(3, adjusted.size)
        assertEquals(30_000L, adjusted.last().tMs)
        assertEquals(5_000_000L, adjusted.last().contiguousFrontierBytes)
        assertEquals(0L, adjusted.last().deltaContiguousBytes)
    }

    @Test
    fun `tail drain lowers safe budget when frontier stops long before run end`() {
        val bytesPerMs = 100.0 * 1_000_000 / 8.0 / 1_000.0
        val events = (1..10).map { i ->
            val tMs = i * 500L
            val deltaBytes = (bytesPerMs * 500).toLong()
            FrontierEvent(
                tMs = tMs,
                contiguousFrontierBytes = i.toLong() * deltaBytes,
                deltaContiguousBytes = deltaBytes
            )
        }

        val raw = simulation.findMaxSustainableBitrate(events)
        val adjusted = simulation.findMaxSustainableBitrate(
            appendTailDrainFrontierEvent(events, runEndMs = 30_000L)
        )

        assertTrue(adjusted.safeBudgetMbps < raw.safeBudgetMbps)
    }
}
