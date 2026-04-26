package com.nexio.tv.data.trakt.outbox

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class ProviderMutationOutboxCoordinator @Inject constructor(
    private val worker: TraktMutationOutboxWorker,
    adapters: Set<@JvmSuppressWildcards TraktMutationAdapter>
) {
    companion object {
        private const val TAG = "ProviderOutboxCoordinator"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val adaptersByKey = adapters.associateBy { it.adapterKey }
    private val drainMutex = Mutex()
    private var drainJob: Job? = null

    init {
        scope.launch {
            runCatching {
                worker.recoverExpiredLeases()
                requestDrain()
            }.onFailure { error ->
                Log.w(TAG, "Failed to bootstrap persisted provider outbox drain: ${error.message}")
            }
        }
    }

    suspend fun enqueueAndDrain(envelope: TraktMutationEnvelope): TraktMutationEnvelope {
        val adapter = adapterFor(envelope.adapterKey)
        adapter.applyOptimistic(envelope)
        val queued = worker.enqueue(envelope)
        ensureDraining()
        return queued
    }

    suspend fun enqueueAndAwait(
        envelope: TraktMutationEnvelope,
        timeoutMs: Long = 30_000L
    ): TraktMutationEnvelope {
        val queued = enqueueAndDrain(envelope)
        lateinit var settled: TraktMutationEnvelope
        withTimeout(timeoutMs) {
            while (true) {
                val current = worker.snapshot().items.firstOrNull { it.id == queued.id }
                    ?: run {
                        settled = queued
                        return@withTimeout
                    }
                when (current.state) {
                    TraktMutationLifecycleState.SUCCEEDED,
                    TraktMutationLifecycleState.TERMINAL_FAILED,
                    TraktMutationLifecycleState.COLLAPSED -> {
                        settled = current
                        return@withTimeout
                    }
                    else -> delay(25L)
                }
            }
        }
        return settled
    }

    suspend fun requestDrain() {
        ensureDraining()
    }

    suspend fun snapshot(): TraktMutationOutboxSnapshot = worker.snapshot()

    private suspend fun ensureDraining() {
        drainMutex.withLock {
            if (drainJob?.isActive == true) return
            drainJob = scope.launch { drainLoop() }
        }
    }

    private suspend fun drainLoop() {
        while (true) {
            val lease = worker.leaseNextReady()
            if (lease == null) {
                val waitMs = nextWakeDelayMs(worker.snapshot())
                if (waitMs == null) {
                    drainMutex.withLock {
                        val latestSnapshot = worker.snapshot()
                        if (nextWakeDelayMs(latestSnapshot) == null) {
                            drainJob = null
                            return
                        }
                    }
                    continue
                }
                delay(waitMs)
                continue
            }

            val adapter = adapterFor(lease.envelope.adapterKey)
            val execution = runCatching { adapter.execute(lease.envelope) }
                .getOrElse { error ->
                    TraktMutationExecutionResult.Failure(
                        reason = error.message ?: error::class.java.simpleName,
                        throwable = error
                    )
                }

            when (execution) {
                is TraktMutationExecutionResult.Success -> {
                    worker.settle(
                        leaseToken = lease.envelope.leaseToken ?: return,
                        settlement = TraktMutationSettlement.Succeeded(
                            httpStatusCode = execution.httpStatusCode
                        )
                    )
                    runCatching { adapter.reconcileSuccess(lease.envelope) }
                        .onFailure { error ->
                            Log.w(TAG, "Failed to reconcile successful provider mutation ${lease.envelope.id}: ${error.message}")
                        }
                }

                is TraktMutationExecutionResult.Failure -> {
                    val settlement = worker.classifyFailure(
                        failure = execution,
                        attemptCount = lease.envelope.attemptCount
                    )
                    val settled = worker.settle(
                        leaseToken = lease.envelope.leaseToken ?: return,
                        settlement = settlement
                    )
                    if (settlement is TraktMutationSettlement.TerminalFailure && settled != null) {
                        runCatching { adapter.rollbackToServerTruth(settled, settlement) }
                            .onFailure { error ->
                                Log.w(TAG, "Failed to rollback terminal provider mutation ${settled.id}: ${error.message}")
                            }
                    }
                }
            }
        }
    }

    private fun adapterFor(adapterKey: String): TraktMutationAdapter {
        return adaptersByKey[adapterKey]
            ?: error("No TraktMutationAdapter registered for key=$adapterKey")
    }

    private fun nextWakeDelayMs(snapshot: TraktMutationOutboxSnapshot): Long? {
        val nowMs = System.currentTimeMillis()
        val candidateTimes = buildList {
            if (snapshot.nextWritableAtMs > nowMs) {
                add(snapshot.nextWritableAtMs)
            }
            snapshot.items.forEach { envelope ->
                when (envelope.state) {
                    TraktMutationLifecycleState.QUEUED,
                    TraktMutationLifecycleState.WAITING_RETRY -> add(envelope.nextAttemptAtMs)
                    TraktMutationLifecycleState.LEASED -> envelope.leaseExpiresAtMs?.let(::add)
                    else -> Unit
                }
            }
        }.filter { it > nowMs }

        val nextAtMs = candidateTimes.minOrNull() ?: return null
        return max(1L, nextAtMs - nowMs)
    }
}
