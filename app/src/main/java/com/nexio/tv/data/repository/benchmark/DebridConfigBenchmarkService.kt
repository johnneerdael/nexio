package com.nexio.tv.data.repository.benchmark

import com.nexio.tv.data.local.DebridConfigBenchmarkStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class DebridConfigBenchmarkService internal constructor(
    private val resolver: DebridBenchmarkCandidateResolver,
    private val store: DebridConfigBenchmarkStore,
    private val transport: OptimizedBenchmarkTransport,
    private val memoryGate: DebridConfigBenchmarkMemoryGate,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val executionGate: DebridBenchmarkExecutionGate
) {
    @Inject
    constructor(
        resolver: DebridBenchmarkCandidateResolver,
        store: DebridConfigBenchmarkStore,
        transport: OptimizedBenchmarkTransport,
        memoryGate: DebridConfigBenchmarkMemoryGate,
        executionGate: DebridBenchmarkExecutionGate
    ) : this(
        resolver = resolver,
        store = store,
        transport = transport,
        memoryGate = memoryGate,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        nowMs = System::currentTimeMillis,
        executionGate = executionGate
    )

    private val runMutex = Mutex()
    private val _activeState = MutableStateFlow<DebridConfigBenchmarkRuntimeState>(
        DebridConfigBenchmarkRuntimeState.Idle
    )
    private val _outcomes = MutableSharedFlow<DebridConfigBenchmarkOutcome>(extraBufferCapacity = 4)
    private var activeJob: Job? = null

    val activeState: StateFlow<DebridConfigBenchmarkRuntimeState> = _activeState.asStateFlow()
    val outcomes: SharedFlow<DebridConfigBenchmarkOutcome> = _outcomes.asSharedFlow()

    fun latestResult(provider: DebridBenchmarkProvider): Flow<DebridConfigBenchmarkResult?> {
        return store.latestResult(provider)
    }

    suspend fun start(provider: DebridBenchmarkProvider): Boolean {
        return runMutex.withLock {
            if (activeJob?.isActive == true) return false
            if (!executionGate.tryAcquire()) return false
            val matrix = certificationMatrixForProvider(provider)
            _activeState.value = DebridConfigBenchmarkRuntimeState.Running(
                provider = provider,
                completedProfiles = 0,
                totalProfiles = matrix.size,
                summary = null
            )
            activeJob = scope.launch {
                runSession(provider)
            }
            true
        }
    }

    suspend fun cancel() {
        val job = runMutex.withLock {
            val currentJob = activeJob
            activeJob = null
            _activeState.value = DebridConfigBenchmarkRuntimeState.Idle
            currentJob
        }
        job?.cancelAndJoin()
        executionGate.release()
    }

    private suspend fun runSession(provider: DebridBenchmarkProvider) {
        try {
            when (val resolution = resolver.resolve(provider)) {
                DebridBenchmarkCandidateResolution.NoLargeDownload -> {
                    _outcomes.emit(
                        DebridConfigBenchmarkOutcome(
                            provider = provider,
                            terminationReason = DebridBenchmarkTerminationReason.NO_LARGE_DOWNLOAD
                        )
                    )
                }
                DebridBenchmarkCandidateResolution.NoPlayableLibraryItem -> {
                    _outcomes.emit(
                        DebridConfigBenchmarkOutcome(
                            provider = provider,
                            terminationReason = DebridBenchmarkTerminationReason.NO_PLAYABLE_LIBRARY_ITEM
                        )
                    )
                }
                is DebridBenchmarkCandidateResolution.Candidate -> {
                    val result = runResolvedCandidateSession(provider, resolution.value)
                    store.saveLatest(result)
                    _outcomes.emit(
                        DebridConfigBenchmarkOutcome(
                            provider = provider,
                            terminationReason = DebridBenchmarkTerminationReason.COMPLETED,
                            result = result
                        )
                    )
                }
            }
        } catch (_: CancellationException) {
            _outcomes.emit(
                DebridConfigBenchmarkOutcome(
                    provider = provider,
                    terminationReason = DebridBenchmarkTerminationReason.CANCELED
                )
            )
        } catch (error: Exception) {
            _outcomes.emit(
                DebridConfigBenchmarkOutcome(
                    provider = provider,
                    terminationReason = DebridBenchmarkTerminationReason.FAILED
                )
            )
        } finally {
            runMutex.withLock {
                activeJob = null
                _activeState.value = DebridConfigBenchmarkRuntimeState.Idle
            }
            executionGate.release()
        }
    }

    private suspend fun runResolvedCandidateSession(
        provider: DebridBenchmarkProvider,
        candidate: DebridBenchmarkCandidate
    ): DebridConfigBenchmarkResult {
        val profileResults = mutableListOf<DebridConfigBenchmarkProfileResult>()
        var bestSuccessfulTransportResult: DebridConfigBenchmarkTransportResult? = null
        val matrix = certificationMatrixForProvider(provider)

        matrix.forEachIndexed { index, profile ->
            _activeState.value = DebridConfigBenchmarkRuntimeState.Running(
                provider = provider,
                currentProfile = profile,
                completedProfiles = index,
                totalProfiles = matrix.size,
                summary = null
            )
            when (val gateDecision = memoryGate.evaluate(profile.parallelConnectionCount, profile.chunkSizeMb)) {
                ProfileRunDecision.Run -> {
                    val snapshot = DebridBenchmarkTransportConfigSnapshot(
                        useParallelConnections = true,
                        parallelConnectionCount = profile.parallelConnectionCount,
                        parallelChunkSizeMb = profile.chunkSizeMb
                    )
                    val transportResult = transport.runConfigProfile(
                        candidate = candidate,
                        configSnapshot = snapshot,
                        measurementDurationMs = CONFIG_MEASUREMENT_DURATION_MS,
                        observer = DebridBenchmarkObserver { summary ->
                            _activeState.value = DebridConfigBenchmarkRuntimeState.Running(
                                provider = provider,
                                currentProfile = profile,
                                completedProfiles = index,
                                totalProfiles = matrix.size,
                                summary = summary
                            )
                        }
                    )
                    if (transportResult.terminationReason == DebridBenchmarkTerminationReason.COMPLETED) {
                        profileResults += DebridConfigBenchmarkProfileResult(
                            parallelConnectionCount = profile.parallelConnectionCount,
                            chunkSizeMb = profile.chunkSizeMb,
                            status = DebridConfigBenchmarkStatus.SUCCESS,
                            averageThroughputMbps = transportResult.averageThroughputMbps,
                            transferredBytes = transportResult.transferredBytes,
                            elapsedMs = transportResult.elapsedMs,
                            configSnapshot = snapshot
                        )
                        val currentBestMbps = bestSuccessfulTransportResult?.averageThroughputMbps
                            ?: Double.NEGATIVE_INFINITY
                        val candidateMbps = transportResult.averageThroughputMbps ?: Double.NEGATIVE_INFINITY
                        if (candidateMbps > currentBestMbps) {
                            bestSuccessfulTransportResult = transportResult
                        }
                    } else {
                        val failureReason = transportResult.failure?.message
                            ?: transportResult.terminationReason.wireKey
                        profileResults += failedProfile(profile, failureReason)
                    }
                }
                is ProfileRunDecision.Unsupported -> {
                    profileResults += DebridConfigBenchmarkProfileResult(
                        parallelConnectionCount = profile.parallelConnectionCount,
                        chunkSizeMb = profile.chunkSizeMb,
                        status = DebridConfigBenchmarkStatus.UNSUPPORTED,
                        unsupportedReason = gateDecision.reason,
                        configSnapshot = DebridBenchmarkTransportConfigSnapshot(
                            useParallelConnections = true,
                            parallelConnectionCount = profile.parallelConnectionCount,
                            parallelChunkSizeMb = profile.chunkSizeMb
                        )
                    )
                }
            }
        }

        val bestProfile = selectBestProfile(profileResults, provider)

        val measuredAtMs = nowMs()
        val summary = DebridConfigBenchmarkSessionSummary(
            totalProfileCount = profileResults.size,
            successfulProfileCount = profileResults.count { it.status == DebridConfigBenchmarkStatus.SUCCESS },
            failedProfileCount = profileResults.count { it.status == DebridConfigBenchmarkStatus.FAILED },
            unsupportedProfileCount = profileResults.count { it.status == DebridConfigBenchmarkStatus.UNSUPPORTED },
            totalElapsedMs = profileResults.sumOf { it.elapsedMs ?: 0L },
            bestProfile = bestProfile
        )
        val capabilityEnvelope = summary.toCapabilityEnvelope(provider.storageKey, measuredAtMs)
        val observedTransportClass = bestSuccessfulTransportResult?.observedTransportClass
        val isConnectionClose = observedTransportClass == "connection_close"
        val runtimeTransportHints = capabilityEnvelope.let { envelope ->
            val bestChunkBytes = bestProfile?.chunkSizeMb?.toLong()?.times(1024L * 1024L)
                ?: envelope.maxSafeUrgentChunkBytes
            RuntimeTransportHintsV2(
                artifactVersion = RuntimeTransportHintsV2.CURRENT_ARTIFACT_VERSION,
                serviceKey = serviceKeyForBenchmarkProvider(provider),
                measuredAtMs = measuredAtMs,
                observedTransportClass = observedTransportClass,
                observedHostScope = bestSuccessfulTransportResult?.observedHostScope,
                recommendedUrgentChunkBytes = bestChunkBytes,
                recommendedUrgentWorkers = bestProfile?.parallelConnectionCount
                    ?: envelope.maxSafeUrgentWorkers,
                connectionBudgetHint = if (isConnectionClose) {
                    (bestProfile?.parallelConnectionCount ?: envelope.maxSafeUrgentWorkers)
                        .coerceAtLeast(2) * 2
                } else null,
                retryMode = if (isConnectionClose) {
                    TransportRetryMode.CONNECTION_CLOSE.wireKey
                } else {
                    TransportRetryMode.DEFAULT.wireKey
                }
            )
        }

        val transportCharacteristics = bestSuccessfulTransportResult?.let { best ->
            val successCount = profileResults.count { it.status == DebridConfigBenchmarkStatus.SUCCESS }
            val totalElapsedSec = (profileResults.sumOf { it.elapsedMs ?: 0L }).coerceAtLeast(1L) / 1000.0
            BenchmarkTransportCharacteristics(
                negotiatedProtocol = best.negotiatedProtocol,
                connectionBehavior = best.connectionHeader ?: best.observedTransportClass,
                connectionReuseDetected = !isConnectionClose,
                requestsPerConnection = if (!isConnectionClose && successCount > 0) {
                    successCount.toDouble()
                } else {
                    1.0
                },
                maxConnectionsPerSecond = if (isConnectionClose) {
                    successCount.toDouble() / totalElapsedSec
                } else {
                    null
                }
            )
        }

        return DebridConfigBenchmarkResult(
            provider = provider,
            measuredAtMs = measuredAtMs,
            candidate = DebridBenchmarkCandidateMetadata(
                filename = candidate.filename,
                sizeBytes = candidate.sourceSizeBytes,
                host = runCatching { java.net.URI(candidate.directUrl).host }.getOrNull(),
                directUrlFingerprint = null
            ),
            summary = summary.copy(
                capabilityEnvelope = capabilityEnvelope,
                runtimeTransportHints = runtimeTransportHints
            ),
            profiles = profileResults,
            transportCharacteristics = transportCharacteristics
        )
    }

    private fun failedProfile(
        profile: DebridConfigBenchmarkProfileMetadata,
        failureReason: String
    ): DebridConfigBenchmarkProfileResult {
        return DebridConfigBenchmarkProfileResult(
            parallelConnectionCount = profile.parallelConnectionCount,
            chunkSizeMb = profile.chunkSizeMb,
            status = DebridConfigBenchmarkStatus.FAILED,
            failureReason = failureReason,
            configSnapshot = DebridBenchmarkTransportConfigSnapshot(
                useParallelConnections = true,
                parallelConnectionCount = profile.parallelConnectionCount,
                parallelChunkSizeMb = profile.chunkSizeMb
            )
        )
    }

    internal companion object {
        internal const val CONFIG_MEASUREMENT_DURATION_MS = 30_000L

        /** Real-Debrid: connection-close provider with rate-limited connection churn. */
        internal val RD_CERTIFICATION_MATRIX = listOf(
            DebridConfigBenchmarkProfileMetadata(2, 16),
            DebridConfigBenchmarkProfileMetadata(2, 24),
            DebridConfigBenchmarkProfileMetadata(2, 32),
            DebridConfigBenchmarkProfileMetadata(3, 16),
            DebridConfigBenchmarkProfileMetadata(3, 24)
        )

        /** Real-Debrid diagnostic profiles — run only for telemetry, not certification. */
        internal val RD_DIAGNOSTIC_MATRIX = listOf(
            DebridConfigBenchmarkProfileMetadata(3, 8),
            DebridConfigBenchmarkProfileMetadata(4, 8),
            DebridConfigBenchmarkProfileMetadata(4, 16)
        )

        /** Premiumize: keep-alive provider with strong connection reuse. */
        internal val PM_CERTIFICATION_MATRIX = listOf(
            DebridConfigBenchmarkProfileMetadata(2, 16),
            DebridConfigBenchmarkProfileMetadata(3, 16),
            DebridConfigBenchmarkProfileMetadata(2, 24),
            DebridConfigBenchmarkProfileMetadata(3, 24)
        )

        /** Premiumize diagnostic profiles — 4-worker only if stall-free. */
        internal val PM_DIAGNOSTIC_MATRIX = listOf(
            DebridConfigBenchmarkProfileMetadata(4, 16)
        )

        /** Fallback matrix for providers without measured behavior (TB, ED). */
        internal val GENERIC_MATRIX = listOf(
            DebridConfigBenchmarkProfileMetadata(2, 8),
            DebridConfigBenchmarkProfileMetadata(3, 8),
            DebridConfigBenchmarkProfileMetadata(2, 16),
            DebridConfigBenchmarkProfileMetadata(3, 16),
            DebridConfigBenchmarkProfileMetadata(2, 24)
        )

        internal fun certificationMatrixForProvider(provider: DebridBenchmarkProvider): List<DebridConfigBenchmarkProfileMetadata> {
            return when (provider) {
                DebridBenchmarkProvider.REAL_DEBRID -> RD_CERTIFICATION_MATRIX
                DebridBenchmarkProvider.PREMIUMIZE -> PM_CERTIFICATION_MATRIX
                else -> GENERIC_MATRIX
            }
        }

        internal fun diagnosticMatrixForProvider(provider: DebridBenchmarkProvider): List<DebridConfigBenchmarkProfileMetadata> {
            return when (provider) {
                DebridBenchmarkProvider.REAL_DEBRID -> RD_DIAGNOSTIC_MATRIX
                DebridBenchmarkProvider.PREMIUMIZE -> PM_DIAGNOSTIC_MATRIX
                else -> emptyList()
            }
        }
    }
}

/**
 * Multi-factor profile scoring. Considers throughput, failure shape, and provider fitness.
 * Replaces throughput-only maxByOrNull selection.
 */
internal fun selectBestProfile(
    profileResults: List<DebridConfigBenchmarkProfileResult>,
    provider: DebridBenchmarkProvider
): DebridConfigBenchmarkProfileResult? {
    val successful = profileResults.filter { it.status == DebridConfigBenchmarkStatus.SUCCESS }
    if (successful.isEmpty()) return null

    val totalProfiles = profileResults.size.coerceAtLeast(1)
    val failedCount = profileResults.count { it.status == DebridConfigBenchmarkStatus.FAILED }
    val failureRatio = failedCount.toDouble() / totalProfiles

    return successful.maxByOrNull { profile ->
        scoreBenchmarkProfile(profile, failureRatio, provider)
    }
}

internal fun scoreBenchmarkProfile(
    profile: DebridConfigBenchmarkProfileResult,
    sessionFailureRatio: Double,
    provider: DebridBenchmarkProvider
): Double {
    val throughput = profile.averageThroughputMbps ?: return Double.NEGATIVE_INFINITY

    // Throughput normalized to 0-1 range (1000 Mbps ceiling)
    val throughputScore = (throughput / 1000.0).coerceIn(0.0, 1.0)

    // Stability: penalize sessions with high failure rates
    val stabilityScore = 1.0 - sessionFailureRatio

    // Provider fitness: prefer profiles matching known-good configurations
    val fitnessScore = providerFitnessScore(profile, provider)

    // Weighted: throughput 50%, stability 30%, fitness 20%
    return throughputScore * 0.50 + stabilityScore * 0.30 + fitnessScore * 0.20
}

private fun providerFitnessScore(
    profile: DebridConfigBenchmarkProfileResult,
    provider: DebridBenchmarkProvider
): Double {
    val workers = profile.parallelConnectionCount
    val chunkMb = profile.chunkSizeMb
    return when (provider) {
        DebridBenchmarkProvider.REAL_DEBRID -> when {
            workers == 2 && chunkMb in 16..24 -> 1.0
            workers == 2 && chunkMb == 32 -> 0.9
            workers == 3 && chunkMb >= 16 -> 0.7
            else -> 0.4
        }
        DebridBenchmarkProvider.PREMIUMIZE -> when {
            workers == 3 && chunkMb == 16 -> 1.0
            workers == 2 && chunkMb == 16 -> 0.9
            workers == 3 && chunkMb == 24 -> 0.8
            workers == 2 && chunkMb == 24 -> 0.7
            else -> 0.4
        }
        else -> when {
            workers <= 2 && chunkMb <= 16 -> 0.8
            workers <= 3 && chunkMb <= 16 -> 0.7
            else -> 0.5
        }
    }
}
