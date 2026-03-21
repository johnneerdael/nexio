package com.nexio.tv.debug.passthrough

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.analytics.PlaybackStats
import kotlinx.serialization.Serializable

@Serializable
enum class TransportValidationVerdict {
    PASS,
    FAIL,
    UNKNOWN,
}

@Serializable
enum class TransportValidationRuntimeVerdict {
    PASS,
    DEGRADED,
    FAIL,
    UNKNOWN,
}

@Serializable
enum class TransportValidationRuntimeFailureCode {
    STARTUP_TIMEOUT,
    READY_BUFFERING_OSCILLATION,
    AUDIO_UNDERRUN,
    AUDIO_WRITE_ZERO_STREAK,
    DROPPED_VIDEO_FRAMES_HIGH,
    POSITION_STALLED,
    POSITION_STALLED_WHILE_PLAYING,
    AUDIO_TIMESTAMP_DISCONTINUITY,
    PARTIAL_WRITE_REMAINDER_STUCK,
    AUDIO_UNDERRUN_REPEATED,
    OUTPUT_RESTART_CHURN,
    ROUTE_REPATCH_AFTER_START,
    ROUTE_REOPEN_AFTER_START,
    ROUTE_RECLASSIFIED_AFTER_START,
    WEAK_AVR_LOCK_REPORTED,
    CHOPPY_AUDIO_REPORTED,
    PLAYER_ERROR,
}

@Serializable
data class TransportValidationRuntimeThresholds(
    val startupTimeoutMs: Long,
    val observationWindowMs: Long,
    val stableStartGracePeriodMs: Long,
    val routeScoringDelayMs: Long,
    val lateNoiseExclusionWindowMs: Long,
    val positionStallWindowMs: Long,
    val minimumPositionAdvanceMs: Long,
    val zeroWriteStreakThreshold: Int,
    val stuckRemainderDurationMs: Long,
    val repeatedUnderrunThreshold: Int,
    val restartChurnThreshold: Int,
    val droppedVideoFramesHighThreshold: Int,
    val readyBufferingOscillationThreshold: Int,
)

@Serializable
enum class TransportValidationAvrLockQuality {
    GOOD,
    WEAK,
    NONE,
    UNKNOWN,
}

@Serializable
enum class TransportValidationAudioQuality {
    CLEAN,
    CHOPPY,
    DROPOUTS,
    UNKNOWN,
}

@Serializable
data class TransportValidationOperatorObservation(
    val avrLock: TransportValidationAvrLockQuality = TransportValidationAvrLockQuality.UNKNOWN,
    val audioQuality: TransportValidationAudioQuality = TransportValidationAudioQuality.UNKNOWN,
    val note: String? = null,
)

@Serializable
data class TransportValidationRuntimePlayerEvent(
    val elapsedRealtimeMs: Long,
    val type: String,
    val playbackState: Int? = null,
    val isPlaying: Boolean? = null,
    val positionMs: Long? = null,
    val detail: String? = null,
)

@Serializable
data class TransportValidationRuntimeAnalyticsEvent(
    val elapsedRealtimeMs: Long,
    val type: String,
    val positionMs: Long? = null,
    val value: Long? = null,
    val detail: String? = null,
)

@Serializable
data class TransportValidationPlaybackStatsSummary(
    val totalJoinTimeMs: Long,
    val totalPlayTimeMs: Long,
    val totalRebufferTimeMs: Long,
    val totalWaitTimeMs: Long,
    val totalElapsedTimeMs: Long,
    val totalRebufferCount: Int,
    val totalDroppedFrames: Long,
    val totalAudioUnderruns: Long,
    val fatalErrorCount: Int,
    val nonFatalErrorCount: Int,
)

@Serializable
data class TransportValidationRuntimeSummary(
    val enabled: Boolean,
    val thresholds: TransportValidationRuntimeThresholds,
    val startedAtElapsedRealtimeMs: Long,
    val prepareRequestedAtElapsedRealtimeMs: Long? = null,
    val stableStartAtElapsedRealtimeMs: Long? = null,
    val routeScoringStartAtElapsedRealtimeMs: Long? = null,
    val firstReadyAtElapsedRealtimeMs: Long? = null,
    val firstIsPlayingAtElapsedRealtimeMs: Long? = null,
    val firstRenderedFrameAtElapsedRealtimeMs: Long? = null,
    val firstAudioUnderrunAtElapsedRealtimeMs: Long? = null,
    val firstAudioTrackRestartAtElapsedRealtimeMs: Long? = null,
    val timeToReadyMs: Long? = null,
    val timeToIsPlayingMs: Long? = null,
    val timeToFirstFrameMs: Long? = null,
    val totalBufferingTimeMs: Long = 0L,
    val rebufferCount: Int = 0,
    val droppedVideoFrames: Long = 0L,
    val audioUnderrunCount: Long = 0L,
    val audioTrackRestartCount: Long = 0L,
    val playerErrorCount: Int = 0,
    val playbackStateTransitionCount: Int = 0,
    val readyBufferingOscillationCount: Int = 0,
    val continuousPlayingWindowSatisfied: Boolean = false,
    val positionStalled: Boolean = false,
    val positionStalledWhilePlaying: Boolean = false,
    val routeTupleChangedAfterStartup: Boolean = false,
    val routeChangeCount: Int = 0,
    val maxZeroWriteStreak: Int = 0,
    val longestZeroWriteStreakMs: Long = 0L,
    val longestPlaybackHeadStallMs: Long = 0L,
    val longestStuckRemainderMs: Long = 0L,
    val routeTupleChangeCountAfterStableStart: Int = 0,
    val routeReopenCountAfterStart: Int = 0,
    val playerStateVerdict: TransportValidationRuntimeVerdict =
        TransportValidationRuntimeVerdict.UNKNOWN,
    val sinkContinuityVerdict: TransportValidationRuntimeVerdict =
        TransportValidationRuntimeVerdict.UNKNOWN,
    val routeStabilityVerdict: TransportValidationRuntimeVerdict =
        TransportValidationRuntimeVerdict.UNKNOWN,
    val operatorObservationVerdict: TransportValidationRuntimeVerdict =
        TransportValidationRuntimeVerdict.UNKNOWN,
    val operatorObservation: TransportValidationOperatorObservation? = null,
    val verdict: TransportValidationRuntimeVerdict = TransportValidationRuntimeVerdict.UNKNOWN,
    val failureCodes: List<TransportValidationRuntimeFailureCode> = emptyList(),
)

@Serializable
data class TransportValidationSinkHealthSummary(
    val writeAttemptCount: Long = 0L,
    val successfulWriteCount: Long = 0L,
    val zeroWriteCount: Long = 0L,
    val partialWriteCount: Long = 0L,
    val maxZeroWriteStreak: Int = 0,
    val longestZeroWriteStreakMs: Long = 0L,
    val longestStuckRemainderMs: Long = 0L,
    val remainderRetryEventCount: Long = 0L,
    val maxRemainderRetryCount: Int = 0,
    val maxTimeSinceLastSuccessfulWriteMs: Long = 0L,
    val maxPlaybackHeadDeltaFramesBeforeRetry: Long = 0L,
    val maxBufferFitDeltaFramesBeforeRetry: Long = 0L,
    val retryReasonCounts: Map<String, Int> = emptyMap(),
    val audioUnderrunCount: Long = 0L,
    val audioTrackRestartCount: Long = 0L,
    val writeEvents: List<TransportValidationSinkWriteEventRecord> = emptyList(),
)

@Serializable
data class TransportValidationSinkWriteEventRecord(
    val elapsedRealtimeMs: Long,
    val type: String,
    val requestedBytes: Int? = null,
    val value: Long? = null,
    val detail: TransportValidationSinkWriteEventDetail = TransportValidationSinkWriteEventDetail(),
)

@Serializable
data class TransportValidationTimedRouteSnapshot(
    val elapsedRealtimeMs: Long,
    val snapshot: TransportValidationRouteSnapshot,
)

@Serializable
data class TransportValidationRouteHealthSummary(
    val stableStartAtElapsedRealtimeMs: Long? = null,
    val routeScoringStartAtElapsedRealtimeMs: Long? = null,
    val routeChangeCount: Int = 0,
    val routeChangeCountAfterStableStart: Int = 0,
    val routeTupleChangeCountAfterStableStart: Int = 0,
    val audioTrackStateChangeCountAfterStableStart: Int = 0,
    val routeReopenCountAfterStart: Int = 0,
    val initialRouteSnapshot: TransportValidationRouteSnapshot? = null,
    val finalRouteSnapshot: TransportValidationRouteSnapshot? = null,
    val samples: List<TransportValidationTimedRouteSnapshot> = emptyList(),
    val routeReopenCandidates: List<TransportValidationRouteReopenCandidate> = emptyList(),
)

@Serializable
data class TransportValidationRouteReopenCandidate(
    val elapsedRealtimeMs: Long,
    val reason: String? = null,
    val oldRouteTuple: String? = null,
    val newRouteTuple: String? = null,
    val stableStartAlreadyReached: Boolean = false,
    val externalRouteChangeDetected: Boolean = false,
    val outputStillConfigured: Boolean = false,
    val outputStillStarted: Boolean = false,
)

@Serializable
data class TransportValidationPlaybackHeadSample(
    val elapsedRealtimeMs: Long,
    val positionUs: Long,
)

@Serializable
data class TransportValidationPlaybackHeadHealthSummary(
    val sampleCount: Int = 0,
    val longestStallMs: Long = 0L,
    val backwardJumpCount: Int = 0,
    val monotonic: Boolean = true,
    val stalledWhilePlaying: Boolean = false,
    val samples: List<TransportValidationPlaybackHeadSample> = emptyList(),
)

data class TransportValidationRuntimeSnapshot(
    val summary: TransportValidationRuntimeSummary,
    val playbackStats: TransportValidationPlaybackStatsSummary? = null,
    val playerEvents: List<TransportValidationRuntimePlayerEvent> = emptyList(),
    val analyticsEvents: List<TransportValidationRuntimeAnalyticsEvent> = emptyList(),
    val sinkHealth: TransportValidationSinkHealthSummary? = null,
    val routeHealth: TransportValidationRouteHealthSummary? = null,
    val playbackHeadHealth: TransportValidationPlaybackHeadHealthSummary? = null,
)

object TransportValidationRuntimeDefaults {
    val thresholds =
        TransportValidationRuntimeThresholds(
            startupTimeoutMs = 5_000L,
            observationWindowMs = 30_000L,
            stableStartGracePeriodMs = 250L,
            routeScoringDelayMs = 1_000L,
            lateNoiseExclusionWindowMs = 2_000L,
            positionStallWindowMs = 4_000L,
            minimumPositionAdvanceMs = 250L,
            zeroWriteStreakThreshold = 3,
            stuckRemainderDurationMs = 500L,
            repeatedUnderrunThreshold = 1,
            restartChurnThreshold = 1,
            droppedVideoFramesHighThreshold = 24,
            readyBufferingOscillationThreshold = 2,
        )
}

object TransportValidationRuntimeVerdictCalculator {
    fun calculate(
        summary: TransportValidationRuntimeSummary,
        nowElapsedRealtimeMs: Long? = null,
        playbackStatsAvailable: Boolean = false,
    ): TransportValidationRuntimeSummary {
        if (!summary.enabled) {
            return summary.copy(verdict = TransportValidationRuntimeVerdict.UNKNOWN)
        }
        val failureCodes = linkedSetOf<TransportValidationRuntimeFailureCode>()
        val elapsedSincePrepareMs =
            summary.prepareRequestedAtElapsedRealtimeMs?.let { prepareAt ->
                nowElapsedRealtimeMs?.minus(prepareAt)
            }

        val startupTimedOut =
            summary.timeToIsPlayingMs == null &&
                summary.prepareRequestedAtElapsedRealtimeMs != null &&
                elapsedSincePrepareMs != null &&
                elapsedSincePrepareMs >= summary.thresholds.startupTimeoutMs

        if (startupTimedOut) {
            failureCodes += TransportValidationRuntimeFailureCode.STARTUP_TIMEOUT
        }
        if (summary.playerErrorCount > 0) {
            failureCodes += TransportValidationRuntimeFailureCode.PLAYER_ERROR
        }
        if (summary.positionStalled) {
            failureCodes += TransportValidationRuntimeFailureCode.POSITION_STALLED
        }
        if (summary.positionStalledWhilePlaying) {
            failureCodes += TransportValidationRuntimeFailureCode.POSITION_STALLED_WHILE_PLAYING
        }
        if (hasObservedZeroWriteContinuityFailure(summary)) {
            failureCodes += TransportValidationRuntimeFailureCode.AUDIO_WRITE_ZERO_STREAK
        }
        if (summary.longestStuckRemainderMs >= summary.thresholds.stuckRemainderDurationMs) {
            failureCodes += TransportValidationRuntimeFailureCode.PARTIAL_WRITE_REMAINDER_STUCK
        }
        if (hasObservedAudioDisruptionWithinRuntimeWindow(summary) &&
            summary.audioUnderrunCount >= summary.thresholds.repeatedUnderrunThreshold
        ) {
            failureCodes += TransportValidationRuntimeFailureCode.AUDIO_UNDERRUN_REPEATED
        }
        if (hasObservedAudioTrackRestartWithinRuntimeWindow(summary) &&
            summary.audioTrackRestartCount >= summary.thresholds.restartChurnThreshold
        ) {
            failureCodes += TransportValidationRuntimeFailureCode.OUTPUT_RESTART_CHURN
        }
        if (summary.routeTupleChangedAfterStartup) {
            failureCodes += TransportValidationRuntimeFailureCode.ROUTE_REPATCH_AFTER_START
        }
        if (summary.routeTupleChangeCountAfterStableStart > 0) {
            failureCodes += TransportValidationRuntimeFailureCode.ROUTE_RECLASSIFIED_AFTER_START
        }
        if (summary.routeReopenCountAfterStart > 0) {
            failureCodes += TransportValidationRuntimeFailureCode.ROUTE_REOPEN_AFTER_START
        }
        if (summary.readyBufferingOscillationCount >=
            summary.thresholds.readyBufferingOscillationThreshold
        ) {
            failureCodes += TransportValidationRuntimeFailureCode.READY_BUFFERING_OSCILLATION
        }
        if (hasObservedAudioDisruptionWithinRuntimeWindow(summary)) {
            failureCodes += TransportValidationRuntimeFailureCode.AUDIO_UNDERRUN
        }
        if (summary.droppedVideoFrames >= summary.thresholds.droppedVideoFramesHighThreshold) {
            failureCodes += TransportValidationRuntimeFailureCode.DROPPED_VIDEO_FRAMES_HIGH
        }
        when (summary.operatorObservation?.avrLock) {
            TransportValidationAvrLockQuality.WEAK,
            TransportValidationAvrLockQuality.NONE ->
                failureCodes += TransportValidationRuntimeFailureCode.WEAK_AVR_LOCK_REPORTED
            else -> Unit
        }
        when (summary.operatorObservation?.audioQuality) {
            TransportValidationAudioQuality.CHOPPY,
            TransportValidationAudioQuality.DROPOUTS ->
                failureCodes += TransportValidationRuntimeFailureCode.CHOPPY_AUDIO_REPORTED
            else -> Unit
        }

        val observationWindowElapsed =
            summary.prepareRequestedAtElapsedRealtimeMs != null &&
                elapsedSincePrepareMs != null &&
                elapsedSincePrepareMs >= summary.thresholds.observationWindowMs
        val playbackDidNotStabilize =
            playbackStatsAvailable &&
                summary.timeToIsPlayingMs != null &&
                observationWindowElapsed &&
                !summary.continuousPlayingWindowSatisfied
        if (playbackDidNotStabilize) {
            failureCodes += TransportValidationRuntimeFailureCode.POSITION_STALLED
        }

        val hasPassEvidence =
            summary.timeToIsPlayingMs != null &&
                summary.continuousPlayingWindowSatisfied &&
                playbackStatsAvailable

        val playerStateVerdict =
            when {
                failureCodes.any {
                    it == TransportValidationRuntimeFailureCode.STARTUP_TIMEOUT ||
                        it == TransportValidationRuntimeFailureCode.PLAYER_ERROR ||
                        it == TransportValidationRuntimeFailureCode.POSITION_STALLED ||
                        it == TransportValidationRuntimeFailureCode.READY_BUFFERING_OSCILLATION
                } -> TransportValidationRuntimeVerdict.FAIL
                hasPassEvidence -> TransportValidationRuntimeVerdict.PASS
                else -> TransportValidationRuntimeVerdict.UNKNOWN
            }

        val sinkContinuityVerdict =
            when {
                failureCodes.any {
                    it == TransportValidationRuntimeFailureCode.POSITION_STALLED_WHILE_PLAYING ||
                        it == TransportValidationRuntimeFailureCode.AUDIO_UNDERRUN ||
                        it == TransportValidationRuntimeFailureCode.AUDIO_WRITE_ZERO_STREAK ||
                        it == TransportValidationRuntimeFailureCode.PARTIAL_WRITE_REMAINDER_STUCK ||
                        it == TransportValidationRuntimeFailureCode.OUTPUT_RESTART_CHURN ||
                        it == TransportValidationRuntimeFailureCode.AUDIO_UNDERRUN_REPEATED
                } -> TransportValidationRuntimeVerdict.DEGRADED
                hasPassEvidence -> TransportValidationRuntimeVerdict.PASS
                else -> TransportValidationRuntimeVerdict.UNKNOWN
            }

        val routeStabilityVerdict =
            when {
                failureCodes.any {
                    it == TransportValidationRuntimeFailureCode.ROUTE_REPATCH_AFTER_START ||
                        it == TransportValidationRuntimeFailureCode.ROUTE_REOPEN_AFTER_START ||
                        it == TransportValidationRuntimeFailureCode.ROUTE_RECLASSIFIED_AFTER_START
                } -> TransportValidationRuntimeVerdict.DEGRADED
                hasPassEvidence -> TransportValidationRuntimeVerdict.PASS
                else -> TransportValidationRuntimeVerdict.UNKNOWN
            }

        val operatorObservationVerdict =
            when {
                failureCodes.any {
                    it == TransportValidationRuntimeFailureCode.WEAK_AVR_LOCK_REPORTED ||
                        it == TransportValidationRuntimeFailureCode.CHOPPY_AUDIO_REPORTED
                } -> TransportValidationRuntimeVerdict.DEGRADED
                summary.operatorObservation != null && hasPassEvidence -> TransportValidationRuntimeVerdict.PASS
                else -> TransportValidationRuntimeVerdict.UNKNOWN
            }

        val verdict =
            when {
                failureCodes.any {
                    it == TransportValidationRuntimeFailureCode.STARTUP_TIMEOUT ||
                        it == TransportValidationRuntimeFailureCode.PLAYER_ERROR ||
                        it == TransportValidationRuntimeFailureCode.POSITION_STALLED ||
                        it == TransportValidationRuntimeFailureCode.ROUTE_REPATCH_AFTER_START ||
                        it == TransportValidationRuntimeFailureCode.READY_BUFFERING_OSCILLATION
                } -> TransportValidationRuntimeVerdict.FAIL
                failureCodes.isNotEmpty() -> TransportValidationRuntimeVerdict.DEGRADED
                hasPassEvidence -> TransportValidationRuntimeVerdict.PASS
                else -> TransportValidationRuntimeVerdict.UNKNOWN
            }

        return summary.copy(
            playerStateVerdict = playerStateVerdict,
            sinkContinuityVerdict = sinkContinuityVerdict,
            routeStabilityVerdict = routeStabilityVerdict,
            operatorObservationVerdict = operatorObservationVerdict,
            verdict = verdict,
            failureCodes = failureCodes.toList(),
        )
    }

    fun transportVerdict(
        comparisonResults: List<TransportValidationComparisonResult>,
    ): TransportValidationVerdict =
        when {
            comparisonResults.isEmpty() -> TransportValidationVerdict.UNKNOWN
            comparisonResults.all { it.passed } -> TransportValidationVerdict.PASS
            else -> TransportValidationVerdict.FAIL
        }

    fun playbackStatsSummary(
        playbackStats: PlaybackStats?,
    ): TransportValidationPlaybackStatsSummary? {
        playbackStats ?: return null
        return TransportValidationPlaybackStatsSummary(
            totalJoinTimeMs = normalizeTime(playbackStats.getTotalJoinTimeMs()),
            totalPlayTimeMs = normalizeTime(playbackStats.getTotalPlayTimeMs()),
            totalRebufferTimeMs = normalizeTime(playbackStats.getTotalRebufferTimeMs()),
            totalWaitTimeMs = normalizeTime(playbackStats.getTotalWaitTimeMs()),
            totalElapsedTimeMs = normalizeTime(playbackStats.getTotalElapsedTimeMs()),
            totalRebufferCount = playbackStats.totalRebufferCount,
            totalDroppedFrames = playbackStats.totalDroppedFrames,
            totalAudioUnderruns = playbackStats.totalAudioUnderruns,
            fatalErrorCount = playbackStats.fatalErrorCount,
            nonFatalErrorCount = playbackStats.nonFatalErrorCount,
        )
    }

    private fun normalizeTime(value: Long): Long = if (value == C.TIME_UNSET) -1L else value

    private fun hasObservedAudioDisruptionWithinRuntimeWindow(
        summary: TransportValidationRuntimeSummary,
    ): Boolean {
        if (summary.audioUnderrunCount <= 0 && summary.audioTrackRestartCount <= 0) {
            return false
        }
        val prepareAt = summary.prepareRequestedAtElapsedRealtimeMs ?: return true
        val cutoff = prepareAt + summary.thresholds.observationWindowMs
        val firstRelevantAudioDisruptionAt =
            listOfNotNull(
                summary.firstAudioUnderrunAtElapsedRealtimeMs,
                summary.firstAudioTrackRestartAtElapsedRealtimeMs,
            ).minOrNull() ?: return true
        return firstRelevantAudioDisruptionAt <= cutoff
    }

    private fun hasObservedAudioTrackRestartWithinRuntimeWindow(
        summary: TransportValidationRuntimeSummary,
    ): Boolean {
        if (summary.audioTrackRestartCount <= 0) {
            return false
        }
        val prepareAt = summary.prepareRequestedAtElapsedRealtimeMs ?: return true
        val cutoff = prepareAt + summary.thresholds.observationWindowMs
        val firstRestartAt = summary.firstAudioTrackRestartAtElapsedRealtimeMs ?: return true
        return firstRestartAt <= cutoff
    }

    private fun hasObservedZeroWriteContinuityFailure(
        summary: TransportValidationRuntimeSummary,
    ): Boolean {
        if (summary.maxZeroWriteStreak < summary.thresholds.zeroWriteStreakThreshold) {
            return false
        }
        return summary.longestStuckRemainderMs >= summary.thresholds.stuckRemainderDurationMs ||
            summary.positionStalledWhilePlaying ||
            hasObservedAudioDisruptionWithinRuntimeWindow(summary) ||
            hasObservedAudioTrackRestartWithinRuntimeWindow(summary)
    }
}

internal fun playerStateName(playbackState: Int): String =
    when (playbackState) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN_$playbackState"
    }
