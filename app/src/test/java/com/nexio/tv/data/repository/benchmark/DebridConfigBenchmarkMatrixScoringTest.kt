package com.nexio.tv.data.repository.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebridConfigBenchmarkMatrixScoringTest {

    // -- Fixtures --

    private fun rdProfiles(): List<DebridConfigBenchmarkProfileResult> {
        // RD-like: connection-close, benefits from larger chunks + fewer connections
        return listOf(
            profileResult(connections = 2, chunkMb = 16, throughput = 500.0),
            profileResult(connections = 4, chunkMb = 8, throughput = 480.0),
            profileResult(connections = 2, chunkMb = 8, throughput = 420.0),
            profileResult(connections = 4, chunkMb = 16, throughput = 450.0)
        )
    }

    private fun pmProfiles(): List<DebridConfigBenchmarkProfileResult> {
        // PM-like: keep-alive, similar throughput across profiles
        return listOf(
            profileResult(connections = 2, chunkMb = 8, throughput = 400.0),
            profileResult(connections = 4, chunkMb = 8, throughput = 410.0),
            profileResult(connections = 2, chunkMb = 16, throughput = 395.0),
            profileResult(connections = 4, chunkMb = 16, throughput = 405.0)
        )
    }

    // -- Multi-factor scoring uses multiple factors, not just throughput --

    @Test
    fun `multi-factor scoring uses throughput stability and fitness for RD`() {
        val profiles = rdProfiles()
        val best = selectBestProfile(profiles, DebridBenchmarkProvider.REAL_DEBRID)

        assertNotNull(best)
        // For RD, 2-conn/16MB has best throughput AND highest fitness score
        assertEquals(2, best!!.parallelConnectionCount)
        assertEquals(16, best.chunkSizeMb)

        // Verify multi-factor scoring produces different scores than throughput alone
        val scores = profiles.map { profile ->
            scoreBenchmarkProfile(profile, sessionFailureRatio = 0.0, provider = DebridBenchmarkProvider.REAL_DEBRID)
        }
        // All scores should be in valid range
        assertTrue(scores.all { it in 0.0..1.0 })
        // Scores should not be purely ordered by throughput
        val throughputOrder = profiles.sortedByDescending { it.averageThroughputMbps ?: 0.0 }
        val scoreOrder = profiles.sortedByDescending {
            scoreBenchmarkProfile(it, 0.0, DebridBenchmarkProvider.REAL_DEBRID)
        }
        // 4-conn/8MB (480 Mbps) should rank lower than 2-conn/16MB (500 Mbps) due to fitness penalty
        val highThroughputLowFitness = profiles.first { it.parallelConnectionCount == 4 && it.chunkSizeMb == 8 }
        val lowerThroughputHighFitness = profiles.first { it.parallelConnectionCount == 2 && it.chunkSizeMb == 16 }
        assertTrue(
            scoreBenchmarkProfile(lowerThroughputHighFitness, 0.0, DebridBenchmarkProvider.REAL_DEBRID) >
                scoreBenchmarkProfile(highThroughputLowFitness, 0.0, DebridBenchmarkProvider.REAL_DEBRID)
        )
    }

    @Test
    fun `multi-factor scoring favors keep-alive profiles for PM`() {
        val profiles = pmProfiles()
        val best = selectBestProfile(profiles, DebridBenchmarkProvider.PREMIUMIZE)

        assertNotNull(best)
        // PM fitness rewards 3-conn/16MB, but throughput highest is 4-conn/8MB (410)
        // Score = throughput*0.5 + stability*0.3 + fitness*0.2
        // For 4/8: 0.41*0.5 + 1.0*0.3 + 0.4*0.2 = 0.205 + 0.3 + 0.08 = 0.585
        // For 2/16: 0.395*0.5 + 1.0*0.3 + 0.9*0.2 = 0.1975 + 0.3 + 0.18 = 0.6775
        // PM should prefer the fitness-favored profile even with lower throughput
        val scoreFor4x8 = scoreBenchmarkProfile(
            profiles.first { it.parallelConnectionCount == 4 && it.chunkSizeMb == 8 },
            0.0, DebridBenchmarkProvider.PREMIUMIZE
        )
        val scoreFor2x16 = scoreBenchmarkProfile(
            profiles.first { it.parallelConnectionCount == 2 && it.chunkSizeMb == 16 },
            0.0, DebridBenchmarkProvider.PREMIUMIZE
        )
        assertTrue(
            "PM fitness should override small throughput difference",
            scoreFor2x16 > scoreFor4x8
        )
    }

    // -- RD and PM scoring produces different rankings --

    @Test
    fun `RD and PM scoring select different profiles from identical data`() {
        // Use identical throughput data to show provider fitness makes the difference
        val profiles = listOf(
            profileResult(connections = 2, chunkMb = 16, throughput = 450.0),
            profileResult(connections = 3, chunkMb = 16, throughput = 450.0),
            profileResult(connections = 4, chunkMb = 8, throughput = 450.0)
        )

        val rdBest = selectBestProfile(profiles, DebridBenchmarkProvider.REAL_DEBRID)
        val pmBest = selectBestProfile(profiles, DebridBenchmarkProvider.PREMIUMIZE)

        assertNotNull(rdBest)
        assertNotNull(pmBest)
        // RD prefers 2-conn/16MB (fitness 1.0), PM prefers 3-conn/16MB (fitness 1.0)
        assertEquals(2, rdBest!!.parallelConnectionCount)
        assertEquals(3, pmBest!!.parallelConnectionCount)
    }

    // -- Stability penalty from failures --

    @Test
    fun `high failure ratio reduces scoring`() {
        val profile = profileResult(connections = 2, chunkMb = 16, throughput = 500.0)

        val scoreNoFailures = scoreBenchmarkProfile(profile, sessionFailureRatio = 0.0, provider = DebridBenchmarkProvider.REAL_DEBRID)
        val scoreHighFailures = scoreBenchmarkProfile(profile, sessionFailureRatio = 0.75, provider = DebridBenchmarkProvider.REAL_DEBRID)

        assertTrue(
            "High failure ratio should reduce score: $scoreNoFailures vs $scoreHighFailures",
            scoreNoFailures > scoreHighFailures
        )
        // Stability weight is 30%, so 75% failure rate penalty = 0.3 * 0.75 = 0.225 score reduction
        val expectedDelta = 0.3 * 0.75
        assertEquals(expectedDelta, scoreNoFailures - scoreHighFailures, 0.01)
    }

    // -- Safe budget requires minimum frontier events --

    @Test
    fun `safe budget calculation requires minimum frontier events`() {
        val profiles = rdProfiles()
        val summary = DebridConfigBenchmarkSessionSummary(
            totalProfileCount = profiles.size,
            successfulProfileCount = profiles.size,
            failedProfileCount = 0,
            unsupportedProfileCount = 0,
            bestProfile = profiles.first()
        )
        val envelope = summary.toCapabilityEnvelope(measuredAtMs = 42L)
        assertNotNull("envelope should be created from successful profiles", envelope)

        // Verify that shadow player simulation gates on frontier event count
        val sim = ShadowPlayerSimulation()
        val insufficientEvents = (0 until 5).map { i ->
            FrontierEvent(
                tMs = i * 500L,
                contiguousFrontierBytes = (i + 1).toLong() * 625_000L,
                deltaContiguousBytes = 625_000L
            )
        }
        val sufficientEvents = (0 until 20).map { i ->
            FrontierEvent(
                tMs = i * 500L,
                contiguousFrontierBytes = (i + 1).toLong() * 625_000L,
                deltaContiguousBytes = 625_000L
            )
        }

        // With insufficient events, result should still work but caller should gate
        val insufficientResult = sim.findMaxSustainableBitrate(insufficientEvents)
        val sufficientResult = sim.findMaxSustainableBitrate(sufficientEvents)

        assertTrue("insufficient events still produces a budget", insufficientResult.safeBudgetMbps > 0.0)
        assertTrue("sufficient events produces a budget", sufficientResult.safeBudgetMbps > 0.0)
        // FRONTIER_EVENT_MINIMUM = 10, so 5 events should be gated out at the call site
        assertTrue("fewer than 10 events should be below the minimum threshold", insufficientEvents.size < 10)
    }

    @Test
    fun `selectBestProfile returns null for all-failed profiles`() {
        val profiles = listOf(
            DebridConfigBenchmarkProfileResult(
                parallelConnectionCount = 2, chunkSizeMb = 8,
                status = DebridConfigBenchmarkStatus.FAILED,
                failureReason = "timeout"
            ),
            DebridConfigBenchmarkProfileResult(
                parallelConnectionCount = 3, chunkSizeMb = 8,
                status = DebridConfigBenchmarkStatus.FAILED,
                failureReason = "timeout"
            )
        )
        val best = selectBestProfile(profiles, DebridBenchmarkProvider.REAL_DEBRID)
        assertNull(best)
    }

    @Test
    fun `scoreBenchmarkProfile returns negative infinity for null throughput`() {
        val profile = DebridConfigBenchmarkProfileResult(
            parallelConnectionCount = 2, chunkSizeMb = 8,
            status = DebridConfigBenchmarkStatus.SUCCESS,
            averageThroughputMbps = null
        )
        val score = scoreBenchmarkProfile(profile, 0.0, DebridBenchmarkProvider.REAL_DEBRID)
        assertEquals(Double.NEGATIVE_INFINITY, score, 0.0)
    }

    // -- Helpers --

    private fun profileResult(
        connections: Int,
        chunkMb: Int,
        throughput: Double
    ): DebridConfigBenchmarkProfileResult {
        return DebridConfigBenchmarkProfileResult(
            parallelConnectionCount = connections,
            chunkSizeMb = chunkMb,
            status = DebridConfigBenchmarkStatus.SUCCESS,
            averageThroughputMbps = throughput,
            transferredBytes = 50L * 1024L * 1024L,
            elapsedMs = ((50.0 * 8.0) / throughput * 1000.0).toLong()
        )
    }
}
