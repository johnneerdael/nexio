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
import kotlinx.coroutines.flow.StateFlow
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
        } catch (_: CancellationException) {
            // Cancellation is reflected through active state reset and transport cancellation.
        } finally {
            clearActiveState()
        }
    }

    private suspend fun clearActiveState() {
        runMutex.withLock {
            activeJob = null
            _activeState.value = DebridBenchmarkRuntimeState.Idle
        }
    }
}
