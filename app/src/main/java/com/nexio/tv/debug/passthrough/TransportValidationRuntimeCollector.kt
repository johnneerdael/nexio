package com.nexio.tv.debug.passthrough

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Singleton
class TransportValidationRuntimeCollector @Inject constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var activeSession: ActiveRuntimeSession? = null

    @Volatile
    private var pendingOperatorObservation: TransportValidationOperatorObservation? = null

    fun beginSession(
        sample: TransportValidationSample,
        settings: TransportValidationSettings,
    ) {
        clearSession()
        activeSession =
            ActiveRuntimeSession(
                sampleId = sample.id,
                sourceAssetPath = sample.sourceAssetPath,
                enabled = settings.runtimeValidationEnabled,
                thresholds =
                    TransportValidationRuntimeDefaults.thresholds.copy(
                        startupTimeoutMs = settings.runtimeStartupTimeoutMs.toLong(),
                        observationWindowMs = settings.runtimeObservationWindowMs.toLong(),
                    ),
                startedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                operatorObservation = pendingOperatorObservation,
            )
    }

    fun onPrepareRequested(streamUrl: String) {
        val session = activeSession ?: return
        if (!session.matches(streamUrl)) {
            return
        }
        session.prepareRequestedAtElapsedRealtimeMs = SystemClock.elapsedRealtime()
    }

    fun attachPlayer(player: ExoPlayer, streamUrl: String) {
        val session = activeSession ?: return
        if (!session.enabled || !session.matches(streamUrl) || session.attachedPlayer === player) {
            return
        }
        session.detach()
        val playbackStatsListener = PlaybackStatsListener(/* keepHistory= */ true, /* callback= */ null)
        val playerListener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    val now = SystemClock.elapsedRealtime()
                    val previousState = session.lastPlaybackState
                    session.lastPlaybackState = playbackState
                    session.playbackStateTransitionCount += 1
                    session.playerEvents +=
                        TransportValidationRuntimePlayerEvent(
                            elapsedRealtimeMs = now,
                            type = "playback_state_changed",
                            playbackState = playbackState,
                            positionMs = player.currentPosition,
                            detail = playerStateName(playbackState),
                        )
                    if (playbackState == Player.STATE_READY &&
                        session.firstReadyAtElapsedRealtimeMs == null
                    ) {
                        session.firstReadyAtElapsedRealtimeMs = now
                    }
                    if (previousState == Player.STATE_READY &&
                        playbackState == Player.STATE_BUFFERING &&
                        session.firstReadyAtElapsedRealtimeMs != null
                    ) {
                        session.readyBufferingOscillationCount += 1
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    val now = SystemClock.elapsedRealtime()
                    session.playerEvents +=
                        TransportValidationRuntimePlayerEvent(
                            elapsedRealtimeMs = now,
                            type = "is_playing_changed",
                            isPlaying = isPlaying,
                            positionMs = player.currentPosition,
                        )
                    if (isPlaying && session.firstIsPlayingAtElapsedRealtimeMs == null) {
                        session.firstIsPlayingAtElapsedRealtimeMs = now
                    }
                }

                override fun onRenderedFirstFrame() {
                    val now = SystemClock.elapsedRealtime()
                    session.analyticsEvents +=
                        TransportValidationRuntimeAnalyticsEvent(
                            elapsedRealtimeMs = now,
                            type = "rendered_first_frame",
                            positionMs = player.currentPosition,
                        )
                    if (session.firstRenderedFrameAtElapsedRealtimeMs == null) {
                        session.firstRenderedFrameAtElapsedRealtimeMs = now
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    session.playerEvents +=
                        TransportValidationRuntimePlayerEvent(
                            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
                            type = "player_error",
                            positionMs = player.currentPosition,
                            detail = "${error.errorCodeName}:${error.message ?: "unknown"}",
                        )
                }
            }
        val analyticsListener =
            object : AnalyticsListener {
                override fun onDroppedVideoFrames(
                    eventTime: AnalyticsListener.EventTime,
                    droppedFrames: Int,
                    elapsedMs: Long,
                ) {
                    session.analyticsEvents +=
                        TransportValidationRuntimeAnalyticsEvent(
                            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
                            type = "dropped_video_frames",
                            positionMs = eventTime.currentPlaybackPositionMs,
                            value = droppedFrames.toLong(),
                            detail = "elapsedMs=$elapsedMs",
                        )
                }

                override fun onAudioUnderrun(
                    eventTime: AnalyticsListener.EventTime,
                    bufferSize: Int,
                    bufferSizeMs: Long,
                    elapsedSinceLastFeedMs: Long,
                ) {
                    val now = SystemClock.elapsedRealtime()
                    session.analyticsEvents +=
                        TransportValidationRuntimeAnalyticsEvent(
                            elapsedRealtimeMs = now,
                            type = "audio_underrun",
                            positionMs = eventTime.currentPlaybackPositionMs,
                            value = elapsedSinceLastFeedMs,
                            detail = "bufferSize=$bufferSize bufferSizeMs=$bufferSizeMs",
                        )
                    if (session.firstAudioUnderrunAtElapsedRealtimeMs == null) {
                        session.firstAudioUnderrunAtElapsedRealtimeMs = now
                    }
                }
            }

        player.addListener(playerListener)
        player.addAnalyticsListener(analyticsListener)
        player.addAnalyticsListener(playbackStatsListener)
        session.attachedPlayer = player
        session.playerListener = playerListener
        session.analyticsListener = analyticsListener
        session.playbackStatsListener = playbackStatsListener
        session.positionPollJob =
            scope.launch {
                while (activeSession === session && session.attachedPlayer === player) {
                    session.positionSamples +=
                        PositionSample(
                            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
                            positionMs = player.currentPosition,
                            isPlaying = player.isPlaying,
                            playbackState = player.playbackState,
                        )
                    delay(POSITION_SAMPLE_INTERVAL_MS)
                }
            }
    }

    fun detachPlayer(player: ExoPlayer?) {
        val session = activeSession ?: return
        if (player == null || session.attachedPlayer !== player) {
            return
        }
        session.detach()
    }

    fun recordRouteSnapshot(snapshot: TransportValidationRouteSnapshot) {
        recordRouteSnapshot(snapshot, SystemClock.elapsedRealtime())
    }

    fun recordRouteSnapshot(
        snapshot: TransportValidationRouteSnapshot,
        elapsedRealtimeMs: Long,
    ) {
        val session = activeSession ?: return
        val last = session.routeSnapshots.lastOrNull()
        if (last != null &&
            last.elapsedRealtimeMs == elapsedRealtimeMs &&
            last.snapshot == snapshot
        ) {
            return
        }
        session.routeSnapshots += RouteSnapshotSample(elapsedRealtimeMs, snapshot)
    }

    fun recordOperatorObservation(observation: TransportValidationOperatorObservation) {
        pendingOperatorObservation = observation
        val session = activeSession ?: return
        session.operatorObservation = observation
    }

    fun snapshot(): TransportValidationRuntimeSnapshot? {
        val session = activeSession ?: return null
        val nowElapsedRealtimeMs = SystemClock.elapsedRealtime()
        val playbackStatsSummary = session.capturePlaybackStatsSummary()
        val continuousPlayingWindowSatisfied =
            session.positionSamples.maxContinuousPlayingWindowMs(
                thresholds = session.thresholds,
                nowElapsedRealtimeMs = nowElapsedRealtimeMs,
            ) >= session.thresholds.observationWindowMs
        val positionStalled =
            session.positionSamples.isPositionStalled(
                thresholds = session.thresholds,
                nowElapsedRealtimeMs = nowElapsedRealtimeMs,
            )
        val stableStartAtElapsedRealtimeMs =
            session.firstIsPlayingAtElapsedRealtimeMs?.plus(session.thresholds.stableStartGracePeriodMs)
        val routeScoringStartAtElapsedRealtimeMs =
            stableStartAtElapsedRealtimeMs?.plus(session.thresholds.routeScoringDelayMs)
        val routeHealth =
            session.routeSnapshots
                .map { sample ->
                    TransportValidationTimedRouteSnapshot(
                        elapsedRealtimeMs = sample.elapsedRealtimeMs,
                        snapshot = sample.snapshot,
                    )
                }.toRouteHealthSummary(
                    stableStartAtElapsedRealtimeMs = stableStartAtElapsedRealtimeMs,
                    routeScoringStartAtElapsedRealtimeMs = routeScoringStartAtElapsedRealtimeMs,
                    analyticsEvents = session.analyticsEvents,
                )
        val summary =
            TransportValidationRuntimeVerdictCalculator.calculate(
                TransportValidationRuntimeSummary(
                    enabled = session.enabled,
                    thresholds = session.thresholds,
                    startedAtElapsedRealtimeMs = session.startedAtElapsedRealtimeMs,
                    prepareRequestedAtElapsedRealtimeMs = session.prepareRequestedAtElapsedRealtimeMs,
                    stableStartAtElapsedRealtimeMs = stableStartAtElapsedRealtimeMs,
                    routeScoringStartAtElapsedRealtimeMs = routeScoringStartAtElapsedRealtimeMs,
                    firstReadyAtElapsedRealtimeMs = session.firstReadyAtElapsedRealtimeMs,
                    firstIsPlayingAtElapsedRealtimeMs = session.firstIsPlayingAtElapsedRealtimeMs,
                    firstRenderedFrameAtElapsedRealtimeMs = session.firstRenderedFrameAtElapsedRealtimeMs,
                    firstAudioUnderrunAtElapsedRealtimeMs = session.firstAudioUnderrunAtElapsedRealtimeMs,
                    timeToReadyMs =
                        session.prepareRequestedAtElapsedRealtimeMs?.let { prepareAt ->
                            session.firstReadyAtElapsedRealtimeMs?.minus(prepareAt)
                        },
                    timeToIsPlayingMs =
                        session.prepareRequestedAtElapsedRealtimeMs?.let { prepareAt ->
                            session.firstIsPlayingAtElapsedRealtimeMs?.minus(prepareAt)
                        },
                    timeToFirstFrameMs =
                        session.prepareRequestedAtElapsedRealtimeMs?.let { prepareAt ->
                            session.firstRenderedFrameAtElapsedRealtimeMs?.minus(prepareAt)
                        },
                    totalBufferingTimeMs = playbackStatsSummary?.totalRebufferTimeMs ?: 0L,
                    rebufferCount = playbackStatsSummary?.totalRebufferCount ?: 0,
                    droppedVideoFrames = playbackStatsSummary?.totalDroppedFrames ?: 0L,
                    audioUnderrunCount = playbackStatsSummary?.totalAudioUnderruns ?: 0L,
                    playerErrorCount = session.playerEvents.count { it.type == "player_error" },
                    playbackStateTransitionCount = session.playbackStateTransitionCount,
                    readyBufferingOscillationCount = session.readyBufferingOscillationCount,
                    continuousPlayingWindowSatisfied = continuousPlayingWindowSatisfied,
                    positionStalled = positionStalled,
                    routeTupleChangedAfterStartup = routeHealth.routeTupleChangeCountAfterStableStart > 0,
                    routeChangeCount = maxOf(session.routeSnapshots.size - 1, 0),
                    routeTupleChangeCountAfterStableStart = routeHealth.routeTupleChangeCountAfterStableStart,
                    routeReopenCountAfterStart = routeHealth.audioTrackStateChangeCountAfterStableStart,
                    operatorObservation = session.operatorObservation,
                ),
                nowElapsedRealtimeMs = nowElapsedRealtimeMs,
                playbackStatsAvailable = playbackStatsSummary != null,
            )
        return TransportValidationRuntimeSnapshot(
            summary = summary,
            playbackStats = playbackStatsSummary,
            playerEvents = session.playerEvents.toList(),
            analyticsEvents = session.analyticsEvents.toList(),
            routeHealth = routeHealth,
        )
    }

    fun clearSession() {
        val session = activeSession ?: return
        session.detach()
        activeSession = null
        pendingOperatorObservation = null
    }

    private fun ActiveRuntimeSession.detach() {
        capturePlaybackStatsSummary()
        positionPollJob?.cancel()
        val player = attachedPlayer
        val playerListenerToRemove = playerListener
        val analyticsListenerToRemove = analyticsListener
        val playbackStatsListenerToRemove = playbackStatsListener
        positionPollJob = null
        attachedPlayer = null
        playerListener = null
        analyticsListener = null
        playbackStatsListener = null
        player?.let { attached ->
            val removeListeners: () -> Unit = {
                playerListenerToRemove?.let(attached::removeListener)
                analyticsListenerToRemove?.let(attached::removeAnalyticsListener)
                playbackStatsListenerToRemove?.let(attached::removeAnalyticsListener)
                Unit
            }
            if (Looper.myLooper() == attached.applicationLooper) {
                removeListeners()
            } else {
                Handler(attached.applicationLooper).post(removeListeners)
            }
        }
    }

    private data class ActiveRuntimeSession(
        val sampleId: String,
        val sourceAssetPath: String,
        val enabled: Boolean,
        val thresholds: TransportValidationRuntimeThresholds,
        val startedAtElapsedRealtimeMs: Long,
        var prepareRequestedAtElapsedRealtimeMs: Long? = null,
        var firstReadyAtElapsedRealtimeMs: Long? = null,
        var firstIsPlayingAtElapsedRealtimeMs: Long? = null,
        var firstRenderedFrameAtElapsedRealtimeMs: Long? = null,
        var firstAudioUnderrunAtElapsedRealtimeMs: Long? = null,
        var lastPlaybackState: Int? = null,
        var playbackStateTransitionCount: Int = 0,
        var readyBufferingOscillationCount: Int = 0,
        val playerEvents: MutableList<TransportValidationRuntimePlayerEvent> = mutableListOf(),
        val analyticsEvents: MutableList<TransportValidationRuntimeAnalyticsEvent> = mutableListOf(),
        val positionSamples: MutableList<PositionSample> = mutableListOf(),
        val routeSnapshots: MutableList<RouteSnapshotSample> = mutableListOf(),
        var operatorObservation: TransportValidationOperatorObservation? = null,
        var attachedPlayer: ExoPlayer? = null,
        var playerListener: Player.Listener? = null,
        var analyticsListener: AnalyticsListener? = null,
        var playbackStatsListener: PlaybackStatsListener? = null,
        var lastPlaybackStatsSummary: TransportValidationPlaybackStatsSummary? = null,
        var positionPollJob: Job? = null,
    ) {
        fun matches(streamUrl: String): Boolean =
            streamUrl.endsWith(sourceAssetPath) || streamUrl.endsWith("/$sourceAssetPath")

        fun capturePlaybackStatsSummary(): TransportValidationPlaybackStatsSummary? {
            val liveSummary =
                TransportValidationRuntimeVerdictCalculator.playbackStatsSummary(
                    playbackStatsListener?.getCombinedPlaybackStats()
                )
            if (liveSummary != null) {
                lastPlaybackStatsSummary = liveSummary
            }
            return liveSummary ?: lastPlaybackStatsSummary
        }
    }

    private companion object {
        const val POSITION_SAMPLE_INTERVAL_MS = 500L
    }
}

internal data class PositionSample(
    val elapsedRealtimeMs: Long,
    val positionMs: Long,
    val isPlaying: Boolean,
    val playbackState: Int,
)

private data class RouteSnapshotSample(
    val elapsedRealtimeMs: Long,
    val snapshot: TransportValidationRouteSnapshot,
)

fun List<TransportValidationTimedRouteSnapshot>.toRouteHealthSummary(
    stableStartAtElapsedRealtimeMs: Long?,
    routeScoringStartAtElapsedRealtimeMs: Long?,
    analyticsEvents: List<TransportValidationRuntimeAnalyticsEvent> = emptyList(),
): TransportValidationRouteHealthSummary {
    val routeReopenCandidates =
        analyticsEvents
            .asSequence()
            .filter { it.type == "route_reopen_candidate" }
            .mapNotNull { event ->
                parseRouteReopenCandidate(
                    elapsedRealtimeMs = event.elapsedRealtimeMs,
                    detail = event.detail,
                )
            }.toList()
    if (isEmpty()) {
        return TransportValidationRouteHealthSummary(
            stableStartAtElapsedRealtimeMs = stableStartAtElapsedRealtimeMs,
            routeScoringStartAtElapsedRealtimeMs = routeScoringStartAtElapsedRealtimeMs,
            routeChangeCount = 0,
            routeReopenCandidates = routeReopenCandidates,
        )
    }
    val allSamples = this
    var totalRouteChangeCount = 0
    var totalPrevious: TransportValidationTimedRouteSnapshot? = null
    allSamples.forEach { sample ->
        val prior = totalPrevious
        if (prior != null && prior.snapshot != sample.snapshot) {
            totalRouteChangeCount += 1
        }
        totalPrevious = sample
    }
    val scoringSamples =
        routeScoringStartAtElapsedRealtimeMs?.let { routeStart ->
            allSamples.filter { it.elapsedRealtimeMs >= routeStart }
        } ?: allSamples
    var routeChangeCount = 0
    var routeTupleChangeCount = 0
    var audioTrackStateChangeCount = 0
    var previous: TransportValidationTimedRouteSnapshot? = null
    scoringSamples.forEach { sample ->
        val prior = previous
        if (prior != null && prior.snapshot != sample.snapshot) {
            routeChangeCount += 1
            if (prior.snapshot.toTuple() != sample.snapshot.toTuple()) {
                routeTupleChangeCount += 1
            }
            if (prior.snapshot.audioTrackState != sample.snapshot.audioTrackState) {
                audioTrackStateChangeCount += 1
            }
        }
        previous = sample
    }
    return TransportValidationRouteHealthSummary(
        stableStartAtElapsedRealtimeMs = stableStartAtElapsedRealtimeMs,
        routeScoringStartAtElapsedRealtimeMs = routeScoringStartAtElapsedRealtimeMs,
        routeChangeCount = totalRouteChangeCount,
        routeChangeCountAfterStableStart = routeChangeCount,
        routeTupleChangeCountAfterStableStart = routeTupleChangeCount,
        audioTrackStateChangeCountAfterStableStart = audioTrackStateChangeCount,
        routeReopenCountAfterStart = audioTrackStateChangeCount,
        initialRouteSnapshot = scoringSamples.firstOrNull()?.snapshot ?: first().snapshot,
        finalRouteSnapshot = last().snapshot,
        samples = allSamples,
        routeReopenCandidates = routeReopenCandidates,
    )
}

private fun parseRouteReopenCandidate(
    elapsedRealtimeMs: Long,
    detail: String?,
): TransportValidationRouteReopenCandidate? {
    if (detail.isNullOrBlank()) {
        return null
    }
    val parts =
        detail
            .split(' ')
            .mapNotNull { token ->
                val separatorIndex = token.indexOf('=')
                if (separatorIndex <= 0 || separatorIndex == token.lastIndex) {
                    null
                } else {
                    token.substring(0, separatorIndex) to token.substring(separatorIndex + 1)
                }
            }.toMap()
    return TransportValidationRouteReopenCandidate(
        elapsedRealtimeMs = elapsedRealtimeMs,
        reason = parts["reason"],
        oldRouteTuple = parts["oldRouteTuple"],
        newRouteTuple = parts["newRouteTuple"],
        stableStartAlreadyReached = parts["stableStartAlreadyReached"]?.toBooleanStrictOrNull() ?: false,
        externalRouteChangeDetected =
            parts["externalRouteChangeDetected"]?.toBooleanStrictOrNull() ?: false,
        outputStillConfigured = parts["outputStillConfigured"]?.toBooleanStrictOrNull() ?: false,
        outputStillStarted = parts["outputStillStarted"]?.toBooleanStrictOrNull() ?: false,
    )
}

private fun TransportValidationRouteSnapshot.toTuple(): Triple<String?, Int?, String?> =
    Triple(encoding, sampleRate, channelMask)

internal fun List<PositionSample>.maxContinuousPlayingWindowMs(
    thresholds: TransportValidationRuntimeThresholds,
    nowElapsedRealtimeMs: Long,
): Long {
    val scoringSamples =
        scoringSamplesForRuntimeWindow(
            thresholds = thresholds,
            nowElapsedRealtimeMs = nowElapsedRealtimeMs,
        )
    if (scoringSamples.size < 2) return 0L
    var best = 0L
    var streakStart: PositionSample? = null
    for (sample in scoringSamples) {
        if (sample.isPlaying) {
            if (streakStart == null) {
                streakStart = sample
            }
            best = maxOf(best, sample.elapsedRealtimeMs - streakStart.elapsedRealtimeMs)
        } else {
            streakStart = null
        }
    }
    return best
}

internal fun List<PositionSample>.isPositionStalled(
    thresholds: TransportValidationRuntimeThresholds,
    nowElapsedRealtimeMs: Long,
): Boolean {
    val scoringSamples =
        scoringSamplesForRuntimeWindow(
            thresholds = thresholds,
            nowElapsedRealtimeMs = nowElapsedRealtimeMs,
        )
    if (scoringSamples.size < 2) return false
    var windowStartIndex = 0
    for (index in scoringSamples.indices) {
        while (scoringSamples[index].elapsedRealtimeMs -
            scoringSamples[windowStartIndex].elapsedRealtimeMs >
            thresholds.positionStallWindowMs
        ) {
            windowStartIndex += 1
        }
        val start = scoringSamples[windowStartIndex]
        val end = scoringSamples[index]
        if (start.isPlaying &&
            end.isPlaying &&
            end.playbackState == Player.STATE_READY &&
            end.elapsedRealtimeMs - start.elapsedRealtimeMs >= thresholds.positionStallWindowMs &&
            end.positionMs - start.positionMs < thresholds.minimumPositionAdvanceMs
        ) {
            return true
        }
    }
    return false
}

private fun List<PositionSample>.scoringSamplesForRuntimeWindow(
    thresholds: TransportValidationRuntimeThresholds,
    nowElapsedRealtimeMs: Long,
): List<PositionSample> {
    val scoringEndExclusive = nowElapsedRealtimeMs - thresholds.lateNoiseExclusionWindowMs
    if (scoringEndExclusive <= 0L) {
        return this
    }
    return filter { it.elapsedRealtimeMs < scoringEndExclusive }
}
