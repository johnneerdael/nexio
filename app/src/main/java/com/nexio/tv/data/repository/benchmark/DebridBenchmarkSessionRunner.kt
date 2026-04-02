package com.nexio.tv.data.repository.benchmark

import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.PlayerSettingsDataStore
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

data class DebridBenchmarkSessionResult(
    val summary: DebridBenchmarkSummary,
    val terminationReason: DebridBenchmarkTerminationReason,
    val result: DebridBenchmarkResult? = null,
    val failureDetails: DebridBenchmarkFailureDetails? = null
)

@Singleton
class DebridBenchmarkSessionRunner internal constructor(
    private val directTransport: DirectProfileBenchmarkTransport,
    private val optimizedTransport: OptimizedBenchmarkTransport,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val deviceCapabilitySnapshotProvider: DeviceCapabilitySnapshotProvider,
    private val nowMs: () -> Long
) {

    @Inject
    constructor(
        directTransport: DirectProfileBenchmarkTransport,
        optimizedTransport: OptimizedBenchmarkTransport,
        playerSettingsDataStore: PlayerSettingsDataStore,
        deviceCapabilitySnapshotProvider: DeviceCapabilitySnapshotProvider
    ) : this(
        directTransport = directTransport,
        optimizedTransport = optimizedTransport,
        playerSettingsDataStore = playerSettingsDataStore,
        deviceCapabilitySnapshotProvider = deviceCapabilitySnapshotProvider,
        nowMs = System::currentTimeMillis
    )

    suspend fun run(
        provider: DebridBenchmarkProvider,
        candidate: DebridBenchmarkCandidate,
        observer: DebridBenchmarkObserver
    ): DebridBenchmarkSessionResult {
        val candidateMetadata = DebridBenchmarkCandidateMetadata(
            filename = candidate.filename,
            sizeBytes = candidate.sourceSizeBytes,
            host = runCatching { URI(candidate.directUrl).host }.getOrNull(),
            directUrlFingerprint = sha256(candidate.directUrl)
        )
        val seekTargets = defaultSeekTargets(candidate.sourceSizeBytes)
        val directResult = directTransport.runProfile(
            candidate = candidate,
            observer = observer,
            seekTargets = seekTargets
        )
        if (directResult.terminationReason != DebridBenchmarkTerminationReason.COMPLETED) {
            return DebridBenchmarkSessionResult(
                summary = directResult.summary,
                terminationReason = directResult.terminationReason,
                failureDetails = DebridBenchmarkFailureDetails(
                    candidate = candidateMetadata,
                    failedTransport = DebridBenchmarkTransportMode.DIRECT,
                    transportFailure = directResult.failure,
                    direct = directResult.profile
                )
            )
        }

        val playerSettings = playerSettingsDataStore.playerSettings.first()
        val configSnapshot = playerSettings.toBenchmarkTransportConfigSnapshot()
        val optimizedResult = optimizedTransport.runProfile(
            candidate = candidate,
            configSnapshot = configSnapshot,
            observer = observer,
            seekTargets = seekTargets
        )
        if (optimizedResult.terminationReason != DebridBenchmarkTerminationReason.COMPLETED) {
            if (directResult.profile.isActionableForAutoplay()) {
                val deviceSnapshot = deviceCapabilitySnapshotProvider.capture()
                val completedResult = DebridBenchmarkResult(
                    provider = provider,
                    measuredAtMs = nowMs(),
                    summary = directResult.summary,
                    terminationReason = DebridBenchmarkTerminationReason.COMPLETED,
                    candidate = candidateMetadata,
                    device = deviceSnapshot,
                    session = DebridBenchmarkSessionMetadata(
                        benchmarkVersion = 3,
                        executionOrder = listOf(
                            DebridBenchmarkPhaseExecution(
                                phase = DebridBenchmarkPhase.STARTUP,
                                order = listOf(
                                    DebridBenchmarkTransportMode.DIRECT,
                                    DebridBenchmarkTransportMode.OPTIMIZED
                                )
                            ),
                            DebridBenchmarkPhaseExecution(
                                phase = DebridBenchmarkPhase.SUSTAINED,
                                order = listOf(
                                    DebridBenchmarkTransportMode.DIRECT,
                                    DebridBenchmarkTransportMode.OPTIMIZED
                                )
                            ),
                            DebridBenchmarkPhaseExecution(
                                phase = DebridBenchmarkPhase.SEEK,
                                order = listOf(
                                    DebridBenchmarkTransportMode.DIRECT,
                                    DebridBenchmarkTransportMode.OPTIMIZED
                                )
                            )
                        ),
                        totalElapsedMs = (directResult.profile.sustained.elapsedMs ?: 0L) +
                            optimizedResult.summary.elapsedMs
                    ),
                    direct = directResult.profile.withDerivedDecisionMetrics(),
                    optimized = optimizedResult.profile
                        .markNonActionable()
                        .withDerivedDecisionMetrics(),
                    comparison = DebridBenchmarkComparisonSummary(
                        sustainedWinner = DebridBenchmarkTransportMode.DIRECT,
                        seekWinner = DebridBenchmarkTransportMode.DIRECT,
                        stabilityWinner = DebridBenchmarkTransportMode.DIRECT
                    )
                )
                return DebridBenchmarkSessionResult(
                    summary = completedResult.summary,
                    terminationReason = DebridBenchmarkTerminationReason.COMPLETED,
                    result = completedResult
                )
            }
            return DebridBenchmarkSessionResult(
                summary = optimizedResult.summary,
                terminationReason = optimizedResult.terminationReason,
                failureDetails = DebridBenchmarkFailureDetails(
                    candidate = candidateMetadata,
                    failedTransport = DebridBenchmarkTransportMode.OPTIMIZED,
                    transportFailure = optimizedResult.failure,
                    direct = directResult.profile,
                    optimized = optimizedResult.profile
                )
            )
        }

        val deviceSnapshot = deviceCapabilitySnapshotProvider.capture()
        val completedResult = DebridBenchmarkResult(
            provider = provider,
            measuredAtMs = nowMs(),
            summary = optimizedResult.summary,
            terminationReason = DebridBenchmarkTerminationReason.COMPLETED,
            candidate = candidateMetadata,
            device = deviceSnapshot,
            session = DebridBenchmarkSessionMetadata(
                benchmarkVersion = 3,
                executionOrder = listOf(
                    DebridBenchmarkPhaseExecution(
                        phase = DebridBenchmarkPhase.STARTUP,
                        order = listOf(
                            DebridBenchmarkTransportMode.DIRECT,
                            DebridBenchmarkTransportMode.OPTIMIZED
                        )
                    ),
                    DebridBenchmarkPhaseExecution(
                        phase = DebridBenchmarkPhase.SUSTAINED,
                        order = listOf(
                            DebridBenchmarkTransportMode.DIRECT,
                            DebridBenchmarkTransportMode.OPTIMIZED
                        )
                    ),
                    DebridBenchmarkPhaseExecution(
                        phase = DebridBenchmarkPhase.SEEK,
                        order = listOf(
                            DebridBenchmarkTransportMode.DIRECT,
                            DebridBenchmarkTransportMode.OPTIMIZED
                        )
                    )
                ),
                totalElapsedMs = (directResult.profile.sustained.elapsedMs ?: 0L) +
                    (optimizedResult.profile.sustained.elapsedMs ?: 0L)
            ),
            direct = directResult.profile.withDerivedDecisionMetrics(),
            optimized = optimizedResult.profile.withDerivedDecisionMetrics(),
            comparison = DebridBenchmarkComparisonSummary(
                sustainedWinner = compareSustainedWinner(
                    direct = directResult.profile.withDerivedDecisionMetrics(),
                    optimized = optimizedResult.profile.withDerivedDecisionMetrics()
                ),
                seekWinner = compareSeekWinner(
                    direct = directResult.profile.withDerivedDecisionMetrics(),
                    optimized = optimizedResult.profile.withDerivedDecisionMetrics()
                ),
                stabilityWinner = compareStabilityWinner(
                    direct = directResult.profile.withDerivedDecisionMetrics(),
                    optimized = optimizedResult.profile.withDerivedDecisionMetrics()
                )
            )
        )
        return DebridBenchmarkSessionResult(
            summary = completedResult.summary,
            terminationReason = DebridBenchmarkTerminationReason.COMPLETED,
            result = completedResult
        )
    }

    private fun defaultSeekTargets(sourceSizeBytes: Long?): List<Long> {
        val sizeBytes = sourceSizeBytes ?: return emptyList()
        if (sizeBytes <= 0L) return emptyList()
        val lastByte = (sizeBytes - 1L).coerceAtLeast(0L)
        return listOf(sizeBytes / 4L, sizeBytes / 2L, (sizeBytes * 3L) / 4L)
            .map { it.coerceAtMost(lastByte) }
            .distinct()
    }

    private fun PlayerSettings.toBenchmarkTransportConfigSnapshot(): DebridBenchmarkTransportConfigSnapshot {
        return DebridBenchmarkTransportConfigSnapshot(
            useParallelConnections = useParallelConnections,
            parallelConnectionCount = parallelConnectionCount,
            parallelChunkSizeMb = parallelChunkSizeMb
        )
    }

    private fun compareSustainedWinner(
        direct: DebridBenchmarkTransportProfile,
        optimized: DebridBenchmarkTransportProfile
    ): DebridBenchmarkTransportMode {
        if (direct.isActionableForAutoplay() && !optimized.isActionableForAutoplay()) {
            return DebridBenchmarkTransportMode.DIRECT
        }
        if (optimized.isActionableForAutoplay() && !direct.isActionableForAutoplay()) {
            return DebridBenchmarkTransportMode.OPTIMIZED
        }
        val directScore = direct.sustained.p10ThroughputMbps ?: Double.NEGATIVE_INFINITY
        val optimizedScore = optimized.sustained.p10ThroughputMbps ?: Double.NEGATIVE_INFINITY
        return if (optimizedScore > directScore) {
            DebridBenchmarkTransportMode.OPTIMIZED
        } else {
            DebridBenchmarkTransportMode.DIRECT
        }
    }

    private fun compareSeekWinner(
        direct: DebridBenchmarkTransportProfile,
        optimized: DebridBenchmarkTransportProfile
    ): DebridBenchmarkTransportMode {
        if (direct.isActionableForAutoplay() && !optimized.isActionableForAutoplay()) {
            return DebridBenchmarkTransportMode.DIRECT
        }
        if (optimized.isActionableForAutoplay() && !direct.isActionableForAutoplay()) {
            return DebridBenchmarkTransportMode.OPTIMIZED
        }
        val directScore = listOf(
            direct.seek.seekFailRate ?: 1.0,
            (direct.seek.seekTtfbP95Ms ?: Long.MAX_VALUE).toDouble(),
            (direct.seek.seekTtfbP99Ms ?: Long.MAX_VALUE).toDouble()
        )
        val optimizedScore = listOf(
            optimized.seek.seekFailRate ?: 1.0,
            (optimized.seek.seekTtfbP95Ms ?: Long.MAX_VALUE).toDouble(),
            (optimized.seek.seekTtfbP99Ms ?: Long.MAX_VALUE).toDouble()
        )
        return if (compareScoreVectors(optimizedScore, directScore) < 0) {
            DebridBenchmarkTransportMode.OPTIMIZED
        } else {
            DebridBenchmarkTransportMode.DIRECT
        }
    }

    private fun compareStabilityWinner(
        direct: DebridBenchmarkTransportProfile,
        optimized: DebridBenchmarkTransportProfile
    ): DebridBenchmarkTransportMode {
        if (direct.isActionableForAutoplay() && !optimized.isActionableForAutoplay()) {
            return DebridBenchmarkTransportMode.DIRECT
        }
        if (optimized.isActionableForAutoplay() && !direct.isActionableForAutoplay()) {
            return DebridBenchmarkTransportMode.OPTIMIZED
        }
        val directScore = listOf(
            direct.sustained.throughputCv ?: Double.POSITIVE_INFINITY,
            (direct.sustained.stallCount ?: Int.MAX_VALUE).toDouble(),
            (direct.sustained.maxReadGapMs ?: Long.MAX_VALUE).toDouble()
        )
        val optimizedScore = listOf(
            optimized.sustained.throughputCv ?: Double.POSITIVE_INFINITY,
            (optimized.sustained.stallCount ?: Int.MAX_VALUE).toDouble(),
            (optimized.sustained.maxReadGapMs ?: Long.MAX_VALUE).toDouble()
        )
        return if (compareScoreVectors(optimizedScore, directScore) < 0) {
            DebridBenchmarkTransportMode.OPTIMIZED
        } else {
            DebridBenchmarkTransportMode.DIRECT
        }
    }

    private fun compareScoreVectors(left: List<Double>, right: List<Double>): Int {
        for (index in left.indices) {
            val comparison = left[index].compareTo(right[index])
            if (comparison != 0) {
                return comparison
            }
        }
        return 0
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString(separator = "") { "%02x".format(it) }.take(16)
    }
}

private fun DebridBenchmarkTransportProfile.withDerivedDecisionMetrics(): DebridBenchmarkTransportProfile {
    val safeBudgetMbps = if (sustained.actionable) {
        sustained.p10ThroughputMbps?.times(0.85)
    } else {
        null
    }
    return copy(
        decision = DebridBenchmarkTransportDecisionMetrics(
            safeSustainedBudgetMbps = safeBudgetMbps,
            actionable = sustained.actionable
        )
    )
}

private fun DebridBenchmarkTransportProfile.markNonActionable(): DebridBenchmarkTransportProfile {
    return copy(
        sustained = sustained.copy(actionable = false),
        decision = DebridBenchmarkTransportDecisionMetrics(
            safeSustainedBudgetMbps = null,
            actionable = false
        )
    )
}

private fun DebridBenchmarkTransportProfile.isActionableForAutoplay(): Boolean {
    return decision?.actionable ?: sustained.actionable
}
