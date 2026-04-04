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
        val playerSettings = playerSettingsDataStore.playerSettings.first()
        val configSnapshot = playerSettings.toBenchmarkTransportConfigSnapshot()
        val optimizedResult = optimizedTransport.runProfile(
            candidate = candidate,
            configSnapshot = configSnapshot,
            observer = observer,
            seekTargets = seekTargets
        )
        val optimizedProfile = optimizedResult.profile.withDerivedDecisionMetrics()
        if (optimizedResult.terminationReason != DebridBenchmarkTerminationReason.COMPLETED) {
            return DebridBenchmarkSessionResult(
                summary = optimizedResult.summary,
                terminationReason = optimizedResult.terminationReason,
                failureDetails = DebridBenchmarkFailureDetails(
                    candidate = candidateMetadata,
                    failedTransport = DebridBenchmarkTransportMode.OPTIMIZED,
                    transportFailure = optimizedResult.failure,
                    optimized = optimizedResult.profile
                )
            )
        }

        val deviceSnapshot = deviceCapabilitySnapshotProvider.capture(playerSettings)
        val completedResult = DebridBenchmarkResult(
            provider = provider,
            measuredAtMs = nowMs(),
            summary = optimizedResult.summary,
            terminationReason = DebridBenchmarkTerminationReason.COMPLETED,
            candidate = candidateMetadata,
            device = deviceSnapshot,
            session = benchmarkSessionMetadata(
                optimizedElapsedMs = optimizedResult.profile.sustained.elapsedMs ?: 0L
            ),
            direct = null,
            optimized = optimizedProfile,
            comparison = null
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

    private fun benchmarkSessionMetadata(
        optimizedElapsedMs: Long
    ): DebridBenchmarkSessionMetadata {
        return DebridBenchmarkSessionMetadata(
            benchmarkVersion = 3,
            executionOrder = DEFAULT_EXECUTION_ORDER,
            totalElapsedMs = optimizedElapsedMs
        )
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString(separator = "") { "%02x".format(it) }.take(16)
    }

    private companion object {
        private val DEFAULT_EXECUTION_ORDER = listOf(
            DebridBenchmarkPhaseExecution(
                phase = DebridBenchmarkPhase.STARTUP,
                order = listOf(DebridBenchmarkTransportMode.OPTIMIZED)
            ),
            DebridBenchmarkPhaseExecution(
                phase = DebridBenchmarkPhase.SUSTAINED,
                order = listOf(DebridBenchmarkTransportMode.OPTIMIZED)
            ),
            DebridBenchmarkPhaseExecution(
                phase = DebridBenchmarkPhase.SEEK,
                order = listOf(DebridBenchmarkTransportMode.OPTIMIZED)
            )
        )
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

private fun DebridBenchmarkTransportProfile.isActionableForAutoplay(): Boolean {
    return decision?.actionable ?: sustained.actionable
}
