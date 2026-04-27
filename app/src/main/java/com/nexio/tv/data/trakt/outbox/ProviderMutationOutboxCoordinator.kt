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
    private val drainJobs = mutableMapOf<Int, Job>()
    private val profileIds: IntRange = 1..4

    init {
        scope.launch {
            runCatching {
                profileIds.forEach { profileId ->
                    worker.recoverExpiredLeases(profileId)
                }
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
        ensureDraining(queued.profileId)
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
                val current = worker.snapshot(queued.profileId).items.firstOrNull { it.id == queued.id }
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
        profileIds.forEach { profileId ->
            ensureDraining(profileId)
        }
    }

    suspend fun snapshot(): TraktMutationOutboxSnapshot = worker.snapshot()

    suspend fun snapshot(profileId: Int): TraktMutationOutboxSnapshot = worker.snapshot(profileId)

    private suspend fun ensureDraining(profileId: Int) {
        drainMutex.withLock {
            if (drainJobs[profileId]?.isActive == true) return
            drainJobs[profileId] = scope.launch { drainLoop(profileId) }
        }
    }

    private suspend fun drainLoop(profileId: Int) {
        while (true) {
            val lease = worker.leaseNextReady(profileId)
            if (lease == null) {
                val waitMs = nextWakeDelayMs(worker.snapshot(profileId))
                if (waitMs == null) {
                    drainMutex.withLock {
                        val latestSnapshot = worker.snapshot(profileId)
                        if (nextWakeDelayMs(latestSnapshot) == null) {
                            drainJobs.remove(profileId)
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
                        profileId = lease.envelope.profileId,
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
                        profileId = lease.envelope.profileId,
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
