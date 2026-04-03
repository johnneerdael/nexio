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
            _activeState.value = DebridConfigBenchmarkRuntimeState.Running(
                provider = provider,
                completedProfiles = 0,
                totalProfiles = DEFAULT_MATRIX.size,
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

        DEFAULT_MATRIX.forEachIndexed { index, profile ->
            _activeState.value = DebridConfigBenchmarkRuntimeState.Running(
                provider = provider,
                currentProfile = profile,
                completedProfiles = index,
                totalProfiles = DEFAULT_MATRIX.size,
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
                                totalProfiles = DEFAULT_MATRIX.size,
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

        val bestProfile = profileResults
            .filter { it.status == DebridConfigBenchmarkStatus.SUCCESS }
            .maxByOrNull { it.averageThroughputMbps ?: Double.NEGATIVE_INFINITY }

        return DebridConfigBenchmarkResult(
            provider = provider,
            measuredAtMs = nowMs(),
            candidate = DebridBenchmarkCandidateMetadata(
                filename = candidate.filename,
                sizeBytes = candidate.sourceSizeBytes,
                host = runCatching { java.net.URI(candidate.directUrl).host }.getOrNull(),
                directUrlFingerprint = null
            ),
            summary = DebridConfigBenchmarkSessionSummary(
                totalProfileCount = profileResults.size,
                successfulProfileCount = profileResults.count { it.status == DebridConfigBenchmarkStatus.SUCCESS },
                failedProfileCount = profileResults.count { it.status == DebridConfigBenchmarkStatus.FAILED },
                unsupportedProfileCount = profileResults.count { it.status == DebridConfigBenchmarkStatus.UNSUPPORTED },
                totalElapsedMs = profileResults.sumOf { it.elapsedMs ?: 0L },
                bestProfile = bestProfile
            ),
            profiles = profileResults
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

    private companion object {
        private const val CONFIG_MEASUREMENT_DURATION_MS = 30_000L

        private val DEFAULT_MATRIX = listOf(
            DebridConfigBenchmarkProfileMetadata(2, 8),
            DebridConfigBenchmarkProfileMetadata(3, 8),
            DebridConfigBenchmarkProfileMetadata(4, 8),
            DebridConfigBenchmarkProfileMetadata(2, 16),
            DebridConfigBenchmarkProfileMetadata(3, 16),
            DebridConfigBenchmarkProfileMetadata(4, 16),
            DebridConfigBenchmarkProfileMetadata(2, 24),
            DebridConfigBenchmarkProfileMetadata(3, 24),
            DebridConfigBenchmarkProfileMetadata(4, 24),
            DebridConfigBenchmarkProfileMetadata(2, 32),
            DebridConfigBenchmarkProfileMetadata(3, 32),
            DebridConfigBenchmarkProfileMetadata(4, 32)
        )
    }
}
