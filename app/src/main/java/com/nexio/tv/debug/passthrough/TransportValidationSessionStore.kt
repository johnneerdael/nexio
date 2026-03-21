package com.nexio.tv.debug.passthrough

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.exoplayer.audio.kodi.validation.TransportValidationRuntimeEvent
import androidx.media3.exoplayer.audio.kodi.validation.TransportValidationRuntimeEventType
import androidx.media3.exoplayer.audio.kodi.validation.TransportValidationRuntime
import androidx.media3.exoplayer.audio.kodi.validation.TransportValidationRuntimeBurst
import androidx.media3.exoplayer.audio.kodi.validation.TransportValidationRuntimeRouteSnapshot
import androidx.media3.exoplayer.audio.kodi.validation.TransportValidationRuntimeRouteSample
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@Singleton
class TransportValidationSessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hostedAssetStore: TransportValidationHostedAssetStore,
    private val settingsStore: TransportValidationSettingsStore,
    private val runtimeCollector: TransportValidationRuntimeCollector,
) {
    @Volatile
    private var currentSession: TransportValidationSessionSnapshot? = null

    suspend fun startSession(sampleId: String): TransportValidationSessionSnapshot? {
        val preparedSample = runCatching { hostedAssetStore.prepareSample(sampleId) }.getOrNull() ?: run {
            Log.w(TAG, "Failed to prepare hosted transport validation sample sampleId=$sampleId")
            return null
        }
        return startSession(preparedSample)
    }

    suspend fun startSession(preparedSample: PreparedTransportValidationSample): TransportValidationSessionSnapshot? {
        val sampleId = preparedSample.sample.id
        Log.i(TAG, "startSession begin sampleId=$sampleId")
        val manifest = preparedSample.manifest
        val sample = preparedSample.sample
        Log.i(TAG, "startSession manifest loaded version=${manifest.version} sampleCount=${manifest.samples.size}")
        Log.i(
            TAG,
            "startSession sample resolved sampleId=${sample.id} codec=${sample.codecFamily} referenceAsset=${sample.referenceAssetPath}"
        )
        val settings = settingsStore.transportValidationSettings.first()
        Log.i(
            TAG,
            "startSession settings captureMode=${settings.captureMode} comparisonMode=${settings.comparisonMode} captureBurstCount=${settings.captureBurstCount} binaryDumps=${settings.binaryDumpsEnabled}"
        )
        val session =
            runCatching {
                Log.i(
                    TAG,
                    "startSession parsing reference sampleId=${sample.id} referenceAsset=${sample.referenceAssetPath} referenceBurstLimit=${referenceBurstLimit(settings)}"
                )
                preparedSample.referenceFile.inputStream().use { inputStream ->
                    TransportValidationSessionFactory.createSession(
                        preparedSample = preparedSample,
                        referenceInputStream = inputStream,
                        referenceBurstLimit = referenceBurstLimit(settings),
                    )
                }
            }.getOrNull() ?: run {
                Log.w(
                    TAG,
                    "Failed to create transport validation session sampleId=${sample.id} referenceAsset=${sample.referenceAssetPath}"
                )
                return null
            }
        Log.i(TAG, "startSession parsed reference sampleId=${sample.id} referenceBurstCount=${session.referenceBursts.size}")
        TransportValidationRuntime.beginSession(
            sample.id,
            sample.codecFamily.name,
            runtimeBurstLimit(sample, settings, session.referenceBursts.size)
        )
        runtimeCollector.beginSession(sample, settings)
        Log.i(
            TAG,
            "Started transport validation session sampleId=${sample.id} referenceBurstCount=${session.referenceBursts.size} runtimeBurstLimit=${runtimeBurstLimit(sample, settings, session.referenceBursts.size)}"
        )
        currentSession = session
        return session
    }

    fun snapshot(): TransportValidationSessionSnapshot? {
        val session = currentSession ?: return null
        val settings = runBlocking { settingsStore.transportValidationSettings.first() }
        val runtime = TransportValidationRuntime.snapshot()
        if (runtime == null || runtime.sampleId != session.sample.id) {
            return session
        }
        val packerInputBursts =
            runtime.packerInputBursts.mapNotNull { mapRuntimeBurst(session.sample, it) }
        val packedBursts =
            runtime.packedBursts.mapNotNull { mapRuntimeBurst(session.sample, it) }
        val audioTrackWriteBursts =
            runtime.audioTrackWriteBursts.mapNotNull { mapRuntimeBurst(session.sample, it) }
        val comparisonResults = mutableListOf<TransportValidationComparisonResult>()
        var referenceFailureBurstIndex: Int? = null
        val packedCount = minOf(session.referenceBursts.size, packedBursts.size)
        for (index in 0 until packedCount) {
            val result =
                TransportValidationComparator.compareReferenceBurst(
                    sample = session.sample,
                    reference = session.referenceBursts[index],
                    live = packedBursts[index],
                    comparisonMode = settings.comparisonMode,
                )
            comparisonResults += result
            if (settings.captureMode == TransportValidationCaptureMode.UNTIL_FAILURE && !result.passed) {
                referenceFailureBurstIndex = index
                break
            }
        }
        var audioTrackFailureBurstIndex: Int? = null
        val audioTrackCount = minOf(packedBursts.size, audioTrackWriteBursts.size)
        for (index in 0 until audioTrackCount) {
            if (referenceFailureBurstIndex != null && index > referenceFailureBurstIndex) {
                break
            }
            val result =
                TransportValidationComparator.comparePackedToAudioTrack(
                    packed = packedBursts[index],
                    audioTrack = audioTrackWriteBursts[index],
                    comparisonMode = settings.comparisonMode,
                )
            comparisonResults += result
            if (settings.captureMode == TransportValidationCaptureMode.UNTIL_FAILURE && !result.passed) {
                audioTrackFailureBurstIndex = index
                break
            }
        }
        val finalPackerInputBursts =
            trimToFailureIfNeeded(
                settings = settings,
                bursts = packerInputBursts,
                failureBurstIndex = referenceFailureBurstIndex
            )
        val finalPackedBursts =
            trimToFailureIfNeeded(
                settings = settings,
                bursts = packedBursts,
                failureBurstIndex = maxIndex(referenceFailureBurstIndex, audioTrackFailureBurstIndex)
            )
        val finalAudioTrackWriteBursts =
            trimToFailureIfNeeded(
                settings = settings,
                bursts = audioTrackWriteBursts,
                failureBurstIndex = audioTrackFailureBurstIndex
            )
        runtime.routeSnapshots.forEach { sample ->
            runtimeCollector.recordRouteSnapshot(
                snapshot = sample.snapshot.toAppSnapshot(),
                elapsedRealtimeMs = sample.elapsedRealtimeMs,
            )
        }
        val routeSnapshot =
            runtime.routeSnapshots.lastOrNull()?.snapshot?.toAppSnapshot()
                ?: runtime.routeSnapshot?.toAppSnapshot()
        val runtimeSnapshot =
            mergeRuntimeSnapshot(
                runtimeCollector.snapshot(),
                runtime.runtimeEvents.map(::mapRuntimeEvent),
            )
        return session.copy(
            routeSnapshot = routeSnapshot,
            packerInputBursts = finalPackerInputBursts,
            packedBursts = finalPackedBursts,
            audioTrackWriteBursts = finalAudioTrackWriteBursts,
            comparisonResults = comparisonResults,
            transportVerdict =
                TransportValidationRuntimeVerdictCalculator.transportVerdict(comparisonResults),
            runtimeSnapshot = runtimeSnapshot,
        )
    }

    fun clearSession() {
        Log.i(TAG, "Clearing transport validation session")
        currentSession = null
        TransportValidationRuntime.clearSession()
        runtimeCollector.clearSession()
    }

    fun exportCurrentSession(): File? {
        val session = snapshot() ?: run {
            Log.w(TAG, "Export requested without an active transport validation session snapshot")
            return null
        }
        val settings = runBlocking { settingsStore.transportValidationSettings.first() }
        val outputFile = TransportValidationDiagnosticsExporter.exportBundle(
            session = session,
            outputDirectory = File(context.filesDir, EXPORT_DIRECTORY_NAME),
            includeBinaryDumps = settings.binaryDumpsEnabled,
        )
        Log.i(TAG, "Exported passthrough transport validation bundle to ${outputFile.absolutePath}")
        return outputFile
    }

    fun recordOperatorObservation(observation: TransportValidationOperatorObservation) {
        runtimeCollector.recordOperatorObservation(observation)
    }

    companion object {
        private const val TAG = "TransportValidation"
        private const val EXPORT_DIRECTORY_NAME = "transport-validation"
        private const val IEC_PREAMBLE_BYTES = 8
        private const val REFERENCE_BURST_WINDOW_UNTIL_FAILURE = 64
        private const val TRUEHD_RUNTIME_BURST_MULTIPLIER = 8
    }

    private fun mapRuntimeBurst(
        sample: TransportValidationSample,
        burst: TransportValidationRuntimeBurst
    ): TransportValidationBurstRecord? {
        val bytes = burst.bytes
        return if (bytes.size >= IEC_PREAMBLE_BYTES) {
            TransportValidationReferenceParser.parseBurst(
                sample = sample,
                bytes = bytes,
                burstIndex = burst.burstIndex,
                sourcePtsUs = burst.sourcePtsUs,
            )
        } else {
            TransportValidationBurstRecord(
                codecFamily = sample.codecFamily,
                sampleId = sample.id,
                burstIndex = burst.burstIndex,
                sourcePtsUs = burst.sourcePtsUs,
                rawBytes = bytes.copyOf(),
                burstSizeBytes = bytes.size,
                pa = 0,
                pb = 0,
                rawPc = 0,
                pd = 0,
                payloadBytes = bytes.size,
                zeroPaddingBytes = 0,
                first64ByteHash = sha256Hex(bytes.copyOfRange(0, minOf(64, bytes.size))),
                fullBurstHash = sha256Hex(bytes),
                codecFailureHint = TransportValidationFailureCode.BURST_ALIGNMENT_FAILED,
            )
        }
    }

    private fun TransportValidationRuntimeRouteSnapshot.toAppSnapshot(): TransportValidationRouteSnapshot =
        TransportValidationRouteSnapshot(
            deviceName = deviceName,
            encoding = encoding,
            sampleRate = sampleRate.takeIf { it > 0 },
            channelMask = channelMask,
            directPlaybackSupported = directPlaybackSupported,
            audioTrackState = audioTrackState,
        )

    private fun mapRuntimeEvent(
        event: TransportValidationRuntimeEvent
    ): TransportValidationRuntimeAnalyticsEvent =
        TransportValidationRuntimeAnalyticsEvent(
            elapsedRealtimeMs = event.elapsedRealtimeMs,
            type = event.type,
            value = event.value,
            detail = event.detail,
        )

    private fun mergeRuntimeSnapshot(
        runtimeSnapshot: TransportValidationRuntimeSnapshot?,
        sinkRuntimeEvents: List<TransportValidationRuntimeAnalyticsEvent>,
    ): TransportValidationRuntimeSnapshot? {
        runtimeSnapshot ?: return null
        if (sinkRuntimeEvents.isEmpty()) {
            return runtimeSnapshot
        }
        val mergedAnalyticsEvents = runtimeSnapshot.analyticsEvents + sinkRuntimeEvents
        val sinkUnderrunCount =
            sinkRuntimeEvents
                .filter { it.type == TransportValidationRuntimeEventType.AUDIO_UNDERRUN }
                .sumOf { maxOf(1L, it.value ?: 1L) }
        val sinkRestartCount =
            sinkRuntimeEvents
                .filter { it.type == TransportValidationRuntimeEventType.AUDIOTRACK_RESTART }
                .sumOf { maxOf(1L, it.value ?: 1L) }
        val firstSinkUnderrunAtElapsedRealtimeMs =
            sinkRuntimeEvents.firstOrNull {
                it.type == TransportValidationRuntimeEventType.AUDIO_UNDERRUN
            }?.elapsedRealtimeMs
        val firstSinkRestartAtElapsedRealtimeMs =
            sinkRuntimeEvents.firstOrNull {
                it.type == TransportValidationRuntimeEventType.AUDIOTRACK_RESTART
            }?.elapsedRealtimeMs
        val sinkHealth = sinkRuntimeEvents.toSinkHealthSummary()
        val playbackHeadHealth =
            sinkRuntimeEvents.toPlaybackHeadHealthSummary(runtimeSnapshot.summary.thresholds)
        val normalizedRouteHealth =
            runtimeSnapshot.routeHealth?.normalizeForScoringWindow()
                ?: TransportValidationRouteHealthSummary()
        val mergedPlaybackStats =
            runtimeSnapshot.playbackStats?.copy(
                totalAudioUnderruns = maxOf(runtimeSnapshot.playbackStats.totalAudioUnderruns, sinkUnderrunCount)
            )
        val zeroWriteStreakForVerdict =
            sinkHealth.maxZeroWriteStreak.takeIf {
                sinkHealth.shouldTreatZeroWriteStreakAsContinuityFailure(
                    playbackHeadHealth = playbackHeadHealth,
                    thresholds = runtimeSnapshot.summary.thresholds,
                    audioUnderrunCount = maxOf(sinkUnderrunCount, sinkHealth.audioUnderrunCount),
                    audioTrackRestartCount = maxOf(sinkRestartCount, sinkHealth.audioTrackRestartCount),
                )
            } ?: 0
        val mergedSummary =
            TransportValidationRuntimeVerdictCalculator.calculate(
                runtimeSnapshot.summary.copy(
                    firstAudioUnderrunAtElapsedRealtimeMs =
                        runtimeSnapshot.summary.firstAudioUnderrunAtElapsedRealtimeMs
                            ?: firstSinkUnderrunAtElapsedRealtimeMs,
                    firstAudioTrackRestartAtElapsedRealtimeMs =
                        runtimeSnapshot.summary.firstAudioTrackRestartAtElapsedRealtimeMs
                            ?: firstSinkRestartAtElapsedRealtimeMs,
                    audioUnderrunCount =
                        maxOf(
                            runtimeSnapshot.summary.audioUnderrunCount,
                            maxOf(sinkUnderrunCount, sinkHealth.audioUnderrunCount),
                        ),
                    audioTrackRestartCount =
                        maxOf(
                            runtimeSnapshot.summary.audioTrackRestartCount,
                            maxOf(sinkRestartCount, sinkHealth.audioTrackRestartCount),
                        ),
                    maxZeroWriteStreak =
                        maxOf(runtimeSnapshot.summary.maxZeroWriteStreak, zeroWriteStreakForVerdict),
                    longestZeroWriteStreakMs =
                        maxOf(
                            runtimeSnapshot.summary.longestZeroWriteStreakMs,
                            sinkHealth.longestZeroWriteStreakMs,
                        ),
                    longestPlaybackHeadStallMs =
                        maxOf(
                            runtimeSnapshot.summary.longestPlaybackHeadStallMs,
                            playbackHeadHealth.longestStallMs,
                        ),
                    longestStuckRemainderMs =
                        maxOf(
                            runtimeSnapshot.summary.longestStuckRemainderMs,
                            sinkHealth.longestStuckRemainderMs,
                        ),
                    positionStalledWhilePlaying =
                        runtimeSnapshot.summary.positionStalledWhilePlaying ||
                            playbackHeadHealth.stalledWhilePlaying,
                    routeTupleChangedAfterStartup =
                        normalizedRouteHealth.routeTupleChangeCountAfterStableStart > 0,
                    routeTupleChangeCountAfterStableStart =
                        normalizedRouteHealth.routeTupleChangeCountAfterStableStart,
                    routeReopenCountAfterStart =
                        normalizedRouteHealth.audioTrackStateChangeCountAfterStableStart,
                ),
                nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                playbackStatsAvailable = mergedPlaybackStats != null,
            )
        return runtimeSnapshot.copy(
            summary = mergedSummary,
            playbackStats = mergedPlaybackStats,
            analyticsEvents = mergedAnalyticsEvents,
            sinkHealth = sinkHealth,
            routeHealth = normalizedRouteHealth,
            playbackHeadHealth = playbackHeadHealth,
        )
    }

    private fun runtimeBurstLimit(
        sample: TransportValidationSample,
        settings: TransportValidationSettings,
        referenceBurstCount: Int,
    ): Int =
        when (settings.captureMode) {
            TransportValidationCaptureMode.PREAMBLE_ONLY,
            TransportValidationCaptureMode.FIRST_N_BURSTS -> maxOf(1, settings.captureBurstCount)
            TransportValidationCaptureMode.UNTIL_FAILURE ->
                maxOf(referenceBurstCount, REFERENCE_BURST_WINDOW_UNTIL_FAILURE)
        }.let { baseLimit ->
            if (sample.codecFamily == TransportValidationCodecFamily.TRUEHD) {
                // TrueHD MAT output aggregates many smaller access units, so the live runtime
                // needs a wider input capture window than the requested packed-burst count.
                maxOf(baseLimit, baseLimit * TRUEHD_RUNTIME_BURST_MULTIPLIER)
            } else {
                baseLimit
            }
        }

    private fun referenceBurstLimit(
        settings: TransportValidationSettings,
    ): Int =
        when (settings.captureMode) {
            TransportValidationCaptureMode.PREAMBLE_ONLY,
            TransportValidationCaptureMode.FIRST_N_BURSTS -> maxOf(1, settings.captureBurstCount)
            TransportValidationCaptureMode.UNTIL_FAILURE -> REFERENCE_BURST_WINDOW_UNTIL_FAILURE
        }

    private fun trimToFailureIfNeeded(
        settings: TransportValidationSettings,
        bursts: List<TransportValidationBurstRecord>,
        failureBurstIndex: Int?,
    ): List<TransportValidationBurstRecord> {
        if (settings.captureMode != TransportValidationCaptureMode.UNTIL_FAILURE ||
            failureBurstIndex == null
        ) {
            return bursts
        }
        return bursts.filter { it.burstIndex <= failureBurstIndex }
    }

    private fun maxIndex(first: Int?, second: Int?): Int? =
        when {
            first == null -> second
            second == null -> first
            else -> maxOf(first, second)
        }

    internal fun List<TransportValidationRuntimeAnalyticsEvent>.toSinkHealthSummary():
        TransportValidationSinkHealthSummary {
        val orderedEvents = sortedBy { it.elapsedRealtimeMs }
        var writeAttemptCount = 0L
        var successfulWriteCount = 0L
        var zeroWriteCount = 0L
        var partialWriteCount = 0L
        var currentZeroWriteStreak = 0
        var maxZeroWriteStreak = 0
        var zeroWriteStreakStartMs: Long? = null
        var longestZeroWriteStreakMs = 0L
        var pendingRemainderStartedAtMs: Long? = null
        var longestStuckRemainderMs = 0L
        var remainderRetryEventCount = 0L
        var maxRemainderRetryCount = 0
        var maxTimeSinceLastSuccessfulWriteMs = 0L
        var maxPlaybackHeadDeltaFramesBeforeRetry = 0L
        var maxBufferFitDeltaFramesBeforeRetry = 0L
        val retryReasonCounts = linkedMapOf<String, Int>()
        var audioUnderrunCount = 0L
        var audioTrackRestartCount = 0L
        val writeEvents = mutableListOf<TransportValidationSinkWriteEventRecord>()
        orderedEvents.forEach { event ->
            val writeDetail = parseTransportValidationSinkWriteEventDetail(event.detail)
            if (event.type == TransportValidationRuntimeEventType.AUDIO_WRITE_ZERO ||
                event.type == TransportValidationRuntimeEventType.AUDIO_WRITE_DEFERRED ||
                event.type == TransportValidationRuntimeEventType.AUDIO_WRITE_PARTIAL ||
                event.type == TransportValidationRuntimeEventType.AUDIO_WRITE_SUCCESS
            ) {
                writeEvents +=
                    TransportValidationSinkWriteEventRecord(
                        elapsedRealtimeMs = event.elapsedRealtimeMs,
                        type = event.type,
                        requestedBytes = writeDetail.requestedBytes,
                        value = event.value,
                        detail = writeDetail,
                    )
            }
            writeDetail.retryReason?.let { retryReason ->
                remainderRetryEventCount += 1
                maxRemainderRetryCount = maxOf(maxRemainderRetryCount, writeDetail.retryCount ?: 0)
                maxTimeSinceLastSuccessfulWriteMs =
                    maxOf(
                        maxTimeSinceLastSuccessfulWriteMs,
                        writeDetail.sinceLastSuccessfulWriteMs ?: 0L,
                    )
                maxPlaybackHeadDeltaFramesBeforeRetry =
                    maxOf(
                        maxPlaybackHeadDeltaFramesBeforeRetry,
                        writeDetail.playbackHeadDeltaFrames ?: 0L,
                    )
                maxBufferFitDeltaFramesBeforeRetry =
                    maxOf(
                        maxBufferFitDeltaFramesBeforeRetry,
                        writeDetail.bufferFitDeltaFrames ?: 0L,
                    )
                retryReasonCounts[retryReason] = (retryReasonCounts[retryReason] ?: 0) + 1
            }
            when (event.type) {
                TransportValidationRuntimeEventType.AUDIO_WRITE_ZERO -> {
                    writeAttemptCount += 1
                    zeroWriteCount += 1
                    currentZeroWriteStreak += 1
                    maxZeroWriteStreak = maxOf(maxZeroWriteStreak, currentZeroWriteStreak)
                    if (zeroWriteStreakStartMs == null) {
                        zeroWriteStreakStartMs = event.elapsedRealtimeMs
                    }
                    if (pendingRemainderStartedAtMs != null) {
                        longestStuckRemainderMs =
                            maxOf(
                                longestStuckRemainderMs,
                                event.elapsedRealtimeMs - pendingRemainderStartedAtMs,
                            )
                    }
                }
                TransportValidationRuntimeEventType.AUDIO_WRITE_DEFERRED -> {
                    if (pendingRemainderStartedAtMs != null) {
                        longestStuckRemainderMs =
                            maxOf(
                                longestStuckRemainderMs,
                                event.elapsedRealtimeMs - pendingRemainderStartedAtMs,
                            )
                    }
                }
                TransportValidationRuntimeEventType.AUDIO_WRITE_PARTIAL -> {
                    writeAttemptCount += 1
                    successfulWriteCount += 1
                    partialWriteCount += 1
                    if (zeroWriteStreakStartMs != null) {
                        longestZeroWriteStreakMs =
                            maxOf(
                                longestZeroWriteStreakMs,
                                event.elapsedRealtimeMs - zeroWriteStreakStartMs,
                            )
                    }
                    currentZeroWriteStreak = 0
                    zeroWriteStreakStartMs = null
                    if (pendingRemainderStartedAtMs == null) {
                        pendingRemainderStartedAtMs = event.elapsedRealtimeMs
                    }
                }
                TransportValidationRuntimeEventType.AUDIO_WRITE_SUCCESS -> {
                    writeAttemptCount += 1
                    successfulWriteCount += 1
                    if (zeroWriteStreakStartMs != null) {
                        longestZeroWriteStreakMs =
                            maxOf(
                                longestZeroWriteStreakMs,
                                event.elapsedRealtimeMs - zeroWriteStreakStartMs,
                            )
                    }
                    currentZeroWriteStreak = 0
                    zeroWriteStreakStartMs = null
                    pendingRemainderStartedAtMs = null
                }
                TransportValidationRuntimeEventType.AUDIO_UNDERRUN ->
                    audioUnderrunCount += maxOf(1L, event.value ?: 1L)
                TransportValidationRuntimeEventType.AUDIOTRACK_RESTART ->
                    audioTrackRestartCount += maxOf(1L, event.value ?: 1L)
            }
        }
        return TransportValidationSinkHealthSummary(
            writeAttemptCount = writeAttemptCount,
            successfulWriteCount = successfulWriteCount,
            zeroWriteCount = zeroWriteCount,
            partialWriteCount = partialWriteCount,
            maxZeroWriteStreak = maxZeroWriteStreak,
            longestZeroWriteStreakMs = longestZeroWriteStreakMs,
            longestStuckRemainderMs = longestStuckRemainderMs,
            remainderRetryEventCount = remainderRetryEventCount,
            maxRemainderRetryCount = maxRemainderRetryCount,
            maxTimeSinceLastSuccessfulWriteMs = maxTimeSinceLastSuccessfulWriteMs,
            maxPlaybackHeadDeltaFramesBeforeRetry = maxPlaybackHeadDeltaFramesBeforeRetry,
            maxBufferFitDeltaFramesBeforeRetry = maxBufferFitDeltaFramesBeforeRetry,
            retryReasonCounts = retryReasonCounts.toMap(),
            audioUnderrunCount = audioUnderrunCount,
            audioTrackRestartCount = audioTrackRestartCount,
            writeEvents = writeEvents,
        )
    }

    private fun List<TransportValidationRuntimeAnalyticsEvent>.toPlaybackHeadHealthSummary(
        thresholds: TransportValidationRuntimeThresholds,
    ): TransportValidationPlaybackHeadHealthSummary {
        val samples =
            filter { it.type == TransportValidationRuntimeEventType.PLAYBACK_HEAD_POSITION }
                .sortedBy { it.elapsedRealtimeMs }
                .mapNotNull { event ->
                    event.value?.takeIf { it >= 0L }?.let { positionUs ->
                        TransportValidationPlaybackHeadSample(
                            elapsedRealtimeMs = event.elapsedRealtimeMs,
                            positionUs = positionUs,
                        )
                    }
                }
        if (samples.isEmpty()) {
            return TransportValidationPlaybackHeadHealthSummary()
        }
        var longestStallMs = 0L
        var backwardJumpCount = 0
        var monotonic = true
        var stallStartedAtMs = samples.first().elapsedRealtimeMs
        var lastPositionUs = samples.first().positionUs
        samples.drop(1).forEach { sample ->
            if (sample.positionUs < lastPositionUs) {
                backwardJumpCount += 1
                monotonic = false
                stallStartedAtMs = sample.elapsedRealtimeMs
            } else if (sample.positionUs == lastPositionUs) {
                longestStallMs = maxOf(longestStallMs, sample.elapsedRealtimeMs - stallStartedAtMs)
            } else {
                stallStartedAtMs = sample.elapsedRealtimeMs
            }
            lastPositionUs = sample.positionUs
        }
        return TransportValidationPlaybackHeadHealthSummary(
            sampleCount = samples.size,
            longestStallMs = longestStallMs,
            backwardJumpCount = backwardJumpCount,
            monotonic = monotonic,
            stalledWhilePlaying = longestStallMs >= thresholds.positionStallWindowMs,
            samples = samples,
        )
    }

    private fun TransportValidationRouteHealthSummary.normalizeForScoringWindow():
        TransportValidationRouteHealthSummary {
        val scoringStart = routeScoringStartAtElapsedRealtimeMs ?: return this
        val hasScoredSamples = samples.any { it.elapsedRealtimeMs >= scoringStart }
        if (hasScoredSamples) {
            return this
        }
        return copy(
            routeChangeCountAfterStableStart = 0,
            routeTupleChangeCountAfterStableStart = 0,
            audioTrackStateChangeCountAfterStableStart = 0,
        )
    }

    private fun TransportValidationSinkHealthSummary.shouldTreatZeroWriteStreakAsContinuityFailure(
        playbackHeadHealth: TransportValidationPlaybackHeadHealthSummary,
        thresholds: TransportValidationRuntimeThresholds,
        audioUnderrunCount: Long,
        audioTrackRestartCount: Long,
    ): Boolean =
        maxZeroWriteStreak >= thresholds.zeroWriteStreakThreshold &&
            (
                longestStuckRemainderMs >= thresholds.stuckRemainderDurationMs ||
                    playbackHeadHealth.stalledWhilePlaying ||
                    audioUnderrunCount >= thresholds.repeatedUnderrunThreshold ||
                    audioTrackRestartCount >= thresholds.restartChurnThreshold
            )

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            digest.forEach { append("%02x".format(it)) }
        }
    }
}
