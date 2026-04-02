package com.nexio.tv.data.repository.benchmark

import com.nexio.tv.data.local.DebridBenchmarkStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface DebridBenchmarkRuntimeState {
    data object Idle : DebridBenchmarkRuntimeState

    data class Running(
        val provider: DebridBenchmarkProvider,
        val summary: DebridBenchmarkSummary = DebridBenchmarkSummary()
    ) : DebridBenchmarkRuntimeState
}

@Singleton
class DebridBenchmarkService internal constructor(
    private val resolver: DebridBenchmarkCandidateResolver,
    private val store: DebridBenchmarkStore,
    private val transport: DebridBenchmarkTransport,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long
) {
    private val runMutex = Mutex()
    private val _activeState = MutableStateFlow<DebridBenchmarkRuntimeState>(
        DebridBenchmarkRuntimeState.Idle
    )
    private val _outcomes = MutableSharedFlow<DebridBenchmarkOutcome>(extraBufferCapacity = 4)
    private var activeJob: Job? = null

    @Inject
    constructor(
        resolver: DebridBenchmarkCandidateResolver,
        store: DebridBenchmarkStore,
        transport: DebridBenchmarkTransport
    ) : this(
        resolver = resolver,
        store = store,
        transport = transport,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        nowMs = System::currentTimeMillis
    )

    val activeState: StateFlow<DebridBenchmarkRuntimeState> = _activeState.asStateFlow()
    val outcomes: SharedFlow<DebridBenchmarkOutcome> = _outcomes.asSharedFlow()

    fun latestResult(provider: DebridBenchmarkProvider): Flow<DebridBenchmarkResult?> {
        return store.latestResult(provider)
    }

    suspend fun start(provider: DebridBenchmarkProvider): Boolean {
        return runMutex.withLock {
            if (activeJob?.isActive == true) {
                return false
            }

            _activeState.value = DebridBenchmarkRuntimeState.Running(provider)
            activeJob = scope.launch {
                runBenchmark(provider)
            }
            true
        }
    }

    suspend fun cancel() {
        val job = runMutex.withLock {
            val currentJob = activeJob
            activeJob = null
            _activeState.value = DebridBenchmarkRuntimeState.Idle
            currentJob
        }

        job?.cancelAndJoin()
    }

    fun onAppBackgrounded() {
        scope.launch {
            cancel()
        }
    }

    private suspend fun runBenchmark(provider: DebridBenchmarkProvider) {
        try {
            val candidate = resolver.resolve(provider)
            if (candidate == null) {
                emitOutcome(
                    provider = provider,
                    summary = DebridBenchmarkSummary(),
                    terminationReason = DebridBenchmarkTerminationReason.NO_PLAYABLE_LIBRARY_ITEM
                )
                return
            }

            val transportResult = transport.run(
                candidate = candidate,
                observer = DebridBenchmarkObserver { summary ->
                    _activeState.value = DebridBenchmarkRuntimeState.Running(
                        provider = provider,
                        summary = summary
                    )
                }
            )

            if (transportResult.terminationReason == DebridBenchmarkTerminationReason.COMPLETED) {
                store.saveLatest(
                    DebridBenchmarkResult(
                        provider = provider,
                        measuredAtMs = nowMs(),
                        summary = transportResult.summary,
                        terminationReason = transportResult.terminationReason
                        )
                )
            }
            emitOutcome(
                provider = provider,
                summary = transportResult.summary,
                terminationReason = transportResult.terminationReason
            )
        } catch (_: CancellationException) {
            emitOutcome(
                provider = provider,
                summary = DebridBenchmarkSummary(),
                terminationReason = DebridBenchmarkTerminationReason.CANCELED
            )
        } finally {
            clearActiveState()
        }
    }

    private suspend fun emitOutcome(
        provider: DebridBenchmarkProvider,
        summary: DebridBenchmarkSummary,
        terminationReason: DebridBenchmarkTerminationReason
    ) {
        _outcomes.emit(
            DebridBenchmarkOutcome(
                provider = provider,
                summary = summary,
                terminationReason = terminationReason
            )
        )
    }

    private suspend fun clearActiveState() {
        runMutex.withLock {
            activeJob = null
            _activeState.value = DebridBenchmarkRuntimeState.Idle
        }
    }
}
