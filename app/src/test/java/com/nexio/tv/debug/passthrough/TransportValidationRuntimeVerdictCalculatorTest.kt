package com.nexio.tv.debug.passthrough

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportValidationRuntimeVerdictCalculatorTest {

    @Test
    fun `startup timeout yields runtime fail`() {
        val summary =
            TransportValidationRuntimeSummary(
                enabled = true,
                thresholds = TransportValidationRuntimeDefaults.thresholds.copy(startupTimeoutMs = 1L),
                startedAtElapsedRealtimeMs = 0L,
                prepareRequestedAtElapsedRealtimeMs = 0L,
            )

        val verdict =
            TransportValidationRuntimeVerdictCalculator.calculate(
                summary,
                nowElapsedRealtimeMs = 10L,
                playbackStatsAvailable = false,
            )

        assertEquals(TransportValidationRuntimeVerdict.FAIL, verdict.verdict)
        assertTrue(
            verdict.failureCodes.contains(TransportValidationRuntimeFailureCode.STARTUP_TIMEOUT)
        )
    }

    @Test
    fun `audio underrun yields degraded runtime verdict`() {
        val summary =
            TransportValidationRuntimeSummary(
                enabled = true,
                thresholds = TransportValidationRuntimeDefaults.thresholds,
                startedAtElapsedRealtimeMs = 0L,
                prepareRequestedAtElapsedRealtimeMs = 10L,
                timeToIsPlayingMs = 50L,
                audioUnderrunCount = 1L,
            )

        val verdict =
            TransportValidationRuntimeVerdictCalculator.calculate(
                summary,
                nowElapsedRealtimeMs = 100L,
                playbackStatsAvailable = true,
            )

        assertEquals(TransportValidationRuntimeVerdict.DEGRADED, verdict.verdict)
        assertTrue(
            verdict.failureCodes.contains(TransportValidationRuntimeFailureCode.AUDIO_UNDERRUN)
        )
    }

    @Test
    fun `audiotrack restart yields degraded runtime verdict`() {
        val summary =
            TransportValidationRuntimeSummary(
                enabled = true,
                thresholds = TransportValidationRuntimeDefaults.thresholds,
                startedAtElapsedRealtimeMs = 0L,
                prepareRequestedAtElapsedRealtimeMs = 10L,
                timeToIsPlayingMs = 50L,
                audioTrackRestartCount = 1L,
            )

        val verdict =
            TransportValidationRuntimeVerdictCalculator.calculate(
                summary,
                nowElapsedRealtimeMs = 100L,
                playbackStatsAvailable = true,
            )

        assertEquals(TransportValidationRuntimeVerdict.DEGRADED, verdict.verdict)
        assertTrue(
            verdict.failureCodes.contains(TransportValidationRuntimeFailureCode.AUDIO_UNDERRUN)
        )
    }

    @Test
    fun `audio underrun after observation window does not degrade runtime verdict`() {
        val thresholds =
            TransportValidationRuntimeDefaults.thresholds.copy(
                observationWindowMs = 45_000L,
            )
        val summary =
            TransportValidationRuntimeSummary(
                enabled = true,
                thresholds = thresholds,
                startedAtElapsedRealtimeMs = 0L,
                prepareRequestedAtElapsedRealtimeMs = 1_000L,
                firstAudioUnderrunAtElapsedRealtimeMs = 60_000L,
                timeToReadyMs = 100L,
                timeToIsPlayingMs = 120L,
                timeToFirstFrameMs = 150L,
                audioUnderrunCount = 1L,
                continuousPlayingWindowSatisfied = true,
            )

        val verdict =
            TransportValidationRuntimeVerdictCalculator.calculate(
                summary,
                nowElapsedRealtimeMs = 70_000L,
                playbackStatsAvailable = true,
            )

        assertEquals(TransportValidationRuntimeVerdict.PASS, verdict.verdict)
        assertTrue(
            verdict.failureCodes.none {
                it == TransportValidationRuntimeFailureCode.AUDIO_UNDERRUN
            }
        )
    }

    @Test
    fun `clean runtime signals yield pass`() {
        val summary =
            TransportValidationRuntimeSummary(
                enabled = true,
                thresholds = TransportValidationRuntimeDefaults.thresholds,
                startedAtElapsedRealtimeMs = 0L,
                prepareRequestedAtElapsedRealtimeMs = 10L,
                timeToReadyMs = 100L,
                timeToIsPlayingMs = 120L,
                timeToFirstFrameMs = 150L,
                continuousPlayingWindowSatisfied = true,
            )

        val verdict =
            TransportValidationRuntimeVerdictCalculator.calculate(
                summary,
                nowElapsedRealtimeMs = 1000L,
                playbackStatsAvailable = true,
            )

        assertEquals(TransportValidationRuntimeVerdict.PASS, verdict.verdict)
        assertTrue(verdict.failureCodes.isEmpty())
    }

    @Test
    fun `missing sustained playback evidence does not yield pass`() {
        val summary =
            TransportValidationRuntimeSummary(
                enabled = true,
                thresholds = TransportValidationRuntimeDefaults.thresholds,
                startedAtElapsedRealtimeMs = 0L,
                prepareRequestedAtElapsedRealtimeMs = 10L,
                timeToReadyMs = 100L,
                timeToIsPlayingMs = 120L,
                timeToFirstFrameMs = 150L,
                continuousPlayingWindowSatisfied = false,
            )

        val verdict =
            TransportValidationRuntimeVerdictCalculator.calculate(
                summary,
                nowElapsedRealtimeMs = 1000L,
                playbackStatsAvailable = false,
            )

        assertEquals(TransportValidationRuntimeVerdict.UNKNOWN, verdict.verdict)
        assertTrue(verdict.failureCodes.isEmpty())
    }

    @Test
    fun `playback that starts but does not stabilize yields runtime fail`() {
        val thresholds =
            TransportValidationRuntimeDefaults.thresholds.copy(
                observationWindowMs = 1_000L,
            )
        val summary =
            TransportValidationRuntimeSummary(
                enabled = true,
                thresholds = thresholds,
                startedAtElapsedRealtimeMs = 0L,
                prepareRequestedAtElapsedRealtimeMs = 10L,
                timeToReadyMs = 100L,
                timeToIsPlayingMs = 120L,
                timeToFirstFrameMs = 150L,
                continuousPlayingWindowSatisfied = false,
            )

        val verdict =
            TransportValidationRuntimeVerdictCalculator.calculate(
                summary,
                nowElapsedRealtimeMs = 2_000L,
                playbackStatsAvailable = true,
            )

        assertEquals(TransportValidationRuntimeVerdict.FAIL, verdict.verdict)
        assertTrue(
            verdict.failureCodes.contains(TransportValidationRuntimeFailureCode.POSITION_STALLED)
        )
    }

    @Test
    fun `shared runtime config exports explicit steady state scoring windows`() {
        val thresholds = TransportValidationRuntimeDefaults.thresholds

        assertEquals(5_000L, thresholds.startupTimeoutMs)
        assertEquals(30_000L, thresholds.observationWindowMs)
        assertEquals(250L, thresholds.stableStartGracePeriodMs)
        assertEquals(1_000L, thresholds.routeScoringDelayMs)
        assertEquals(2_000L, thresholds.lateNoiseExclusionWindowMs)
    }

    @Test
    fun `position stalled while playing degrades sink continuity and runtime`() {
        val summary =
            TransportValidationRuntimeSummary(
                enabled = true,
                thresholds = TransportValidationRuntimeDefaults.thresholds,
                startedAtElapsedRealtimeMs = 0L,
                prepareRequestedAtElapsedRealtimeMs = 10L,
                timeToReadyMs = 100L,
                timeToIsPlayingMs = 120L,
                timeToFirstFrameMs = 150L,
                continuousPlayingWindowSatisfied = true,
                sinkContinuityVerdict = TransportValidationRuntimeVerdict.DEGRADED,
                positionStalledWhilePlaying = true,
            )

        val verdict =
            TransportValidationRuntimeVerdictCalculator.calculate(
                summary,
                nowElapsedRealtimeMs = 10_000L,
                playbackStatsAvailable = true,
            )

        assertEquals(TransportValidationRuntimeVerdict.DEGRADED, verdict.verdict)
        assertEquals(TransportValidationRuntimeVerdict.DEGRADED, verdict.sinkContinuityVerdict)
        assertTrue(
            verdict.failureCodes.contains(
                TransportValidationRuntimeFailureCode.POSITION_STALLED_WHILE_PLAYING
            )
        )
    }

    @Test
    fun `weak operator observation downgrades otherwise healthy runtime`() {
        val summary =
            TransportValidationRuntimeSummary(
                enabled = true,
                thresholds = TransportValidationRuntimeDefaults.thresholds,
                startedAtElapsedRealtimeMs = 0L,
                prepareRequestedAtElapsedRealtimeMs = 10L,
                timeToReadyMs = 100L,
                timeToIsPlayingMs = 120L,
                timeToFirstFrameMs = 150L,
                continuousPlayingWindowSatisfied = true,
                operatorObservation = TransportValidationOperatorObservation(
                    avrLock = TransportValidationAvrLockQuality.WEAK,
                    audioQuality = TransportValidationAudioQuality.CLEAN,
                ),
            )

        val verdict =
            TransportValidationRuntimeVerdictCalculator.calculate(
                summary,
                nowElapsedRealtimeMs = 10_000L,
                playbackStatsAvailable = true,
            )

        assertEquals(TransportValidationRuntimeVerdict.DEGRADED, verdict.verdict)
        assertEquals(
            TransportValidationRuntimeVerdict.DEGRADED,
            verdict.operatorObservationVerdict,
        )
        assertTrue(
            verdict.failureCodes.contains(
                TransportValidationRuntimeFailureCode.WEAK_AVR_LOCK_REPORTED
            )
        )
    }

    @Test
    fun `zero write streak degrades sink continuity verdict`() {
        val summary =
            TransportValidationRuntimeSummary(
                enabled = true,
                thresholds = TransportValidationRuntimeDefaults.thresholds.copy(
                    zeroWriteStreakThreshold = 2,
                ),
                startedAtElapsedRealtimeMs = 0L,
                prepareRequestedAtElapsedRealtimeMs = 10L,
                timeToReadyMs = 100L,
                timeToIsPlayingMs = 120L,
                timeToFirstFrameMs = 150L,
                continuousPlayingWindowSatisfied = true,
                maxZeroWriteStreak = 2,
                audioUnderrunCount = 1L,
            )

        val verdict =
            TransportValidationRuntimeVerdictCalculator.calculate(
                summary,
                nowElapsedRealtimeMs = 10_000L,
                playbackStatsAvailable = true,
            )

        assertEquals(TransportValidationRuntimeVerdict.DEGRADED, verdict.verdict)
        assertEquals(TransportValidationRuntimeVerdict.DEGRADED, verdict.sinkContinuityVerdict)
        assertTrue(
            verdict.failureCodes.contains(
                TransportValidationRuntimeFailureCode.AUDIO_WRITE_ZERO_STREAK
            )
        )
    }

    @Test
    fun `zero write streak alone does not degrade sink continuity verdict`() {
        val summary =
            TransportValidationRuntimeSummary(
                enabled = true,
                thresholds = TransportValidationRuntimeDefaults.thresholds.copy(
                    zeroWriteStreakThreshold = 2,
                ),
                startedAtElapsedRealtimeMs = 0L,
                prepareRequestedAtElapsedRealtimeMs = 10L,
                timeToReadyMs = 100L,
                timeToIsPlayingMs = 120L,
                timeToFirstFrameMs = 150L,
                continuousPlayingWindowSatisfied = true,
                maxZeroWriteStreak = 2,
            )

        val verdict =
            TransportValidationRuntimeVerdictCalculator.calculate(
                summary,
                nowElapsedRealtimeMs = 10_000L,
                playbackStatsAvailable = true,
            )

        assertEquals(TransportValidationRuntimeVerdict.PASS, verdict.verdict)
        assertEquals(TransportValidationRuntimeVerdict.PASS, verdict.sinkContinuityVerdict)
        assertTrue(
            verdict.failureCodes.none {
                it == TransportValidationRuntimeFailureCode.AUDIO_WRITE_ZERO_STREAK
            }
        )
    }

    @Test
    fun `route reclassification degrades route stability verdict`() {
        val summary =
            TransportValidationRuntimeSummary(
                enabled = true,
                thresholds = TransportValidationRuntimeDefaults.thresholds,
                startedAtElapsedRealtimeMs = 0L,
                prepareRequestedAtElapsedRealtimeMs = 10L,
                timeToReadyMs = 100L,
                timeToIsPlayingMs = 120L,
                timeToFirstFrameMs = 150L,
                continuousPlayingWindowSatisfied = true,
                routeTupleChangeCountAfterStableStart = 1,
            )

        val verdict =
            TransportValidationRuntimeVerdictCalculator.calculate(
                summary,
                nowElapsedRealtimeMs = 10_000L,
                playbackStatsAvailable = true,
            )

        assertEquals(TransportValidationRuntimeVerdict.DEGRADED, verdict.verdict)
        assertEquals(TransportValidationRuntimeVerdict.DEGRADED, verdict.routeStabilityVerdict)
        assertTrue(
            verdict.failureCodes.contains(
                TransportValidationRuntimeFailureCode.ROUTE_RECLASSIFIED_AFTER_START
            )
        )
    }

    @Test
    fun `route health ignores pre window changes when scoring window has no samples`() {
        val routeHealth =
            listOf(
                TransportValidationTimedRouteSnapshot(
                    elapsedRealtimeMs = 100L,
                    snapshot = TransportValidationRouteSnapshot(
                        deviceName = "",
                        encoding = "2",
                        channelMask = "0",
                    ),
                ),
                TransportValidationTimedRouteSnapshot(
                    elapsedRealtimeMs = 200L,
                    snapshot = TransportValidationRouteSnapshot(
                        deviceName = "",
                        encoding = "IEC61937",
                        sampleRate = 192000,
                        channelMask = "7.1",
                        directPlaybackSupported = true,
                        audioTrackState = 1,
                    ),
                ),
            ).toRouteHealthSummary(
                stableStartAtElapsedRealtimeMs = 500L,
                routeScoringStartAtElapsedRealtimeMs = 1000L,
            )

        assertEquals(0, routeHealth.routeChangeCountAfterStableStart)
        assertEquals(0, routeHealth.routeTupleChangeCountAfterStableStart)
        assertEquals(0, routeHealth.audioTrackStateChangeCountAfterStableStart)
        assertEquals("2", routeHealth.initialRouteSnapshot?.encoding)
        assertEquals("IEC61937", routeHealth.finalRouteSnapshot?.encoding)
    }
}
