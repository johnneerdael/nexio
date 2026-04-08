package com.nexio.tv.data.trakt.outbox

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class TraktMutationOutboxWorker(
    private val store: TraktMutationOutboxStore,
    private val policy: TraktMutationOutboxPolicy = TraktMutationOutboxPolicy(),
    private val timeProvider: () -> Long = System::currentTimeMillis,
    private val leaseDurationMs: Long = 30_000L,
    private val leaseTokenFactory: () -> String = { UUID.randomUUID().toString() }
) {
    private val mutex = Mutex()
    private var fairnessState = TraktMutationFairnessState()

    suspend fun snapshot(): TraktMutationOutboxSnapshot = store.read()

    suspend fun enqueue(envelope: TraktMutationEnvelope): TraktMutationEnvelope = mutex.withLock {
        val nowMs = timeProvider()
        val updated = policy.enqueue(
            snapshot = store.read(),
            envelope = envelope,
            nowMs = nowMs
        )
        store.write(updated)
        updated.items.first { it.id == envelope.id }
    }

    suspend fun recoverExpiredLeases(): Int = mutex.withLock {
        val nowMs = timeProvider()
        val before = store.read()
        val after = policy.recoverExpiredLeases(before, nowMs)
        if (before == after) return@withLock 0
        store.write(after)
        before.items.count { item ->
            item.state == TraktMutationLifecycleState.LEASED &&
                item.leaseExpiresAtMs != null &&
                item.leaseExpiresAtMs <= nowMs
        }
    }

    suspend fun leaseNextReady(): TraktMutationLease? = mutex.withLock {
        val nowMs = timeProvider()
        val recovered = policy.recoverExpiredLeases(store.read(), nowMs)
        val selection = policy.leaseNextReady(
            snapshot = recovered,
            nowMs = nowMs,
            fairnessState = fairnessState,
            leaseDurationMs = leaseDurationMs,
            leaseTokenFactory = leaseTokenFactory
        )
        if (selection == null) {
            store.write(recovered)
            return@withLock null
        }
        fairnessState = selection.lease.fairnessState
        store.write(selection.snapshot)
        selection.lease
    }

    suspend fun settle(
        leaseToken: String,
        settlement: TraktMutationSettlement
    ): TraktMutationEnvelope? = mutex.withLock {
        val nowMs = timeProvider()
        val result = policy.settleLease(
            snapshot = store.read(),
            leaseToken = leaseToken,
            settlement = settlement,
            nowMs = nowMs
        )
        store.write(result.snapshot)
        result.settledEnvelope
    }

    suspend fun classifyFailure(
        failure: TraktMutationExecutionResult.Failure,
        attemptCount: Int
    ): TraktMutationSettlement = mutex.withLock {
        policy.classifyFailure(
            failure = failure,
            attemptCount = attemptCount,
            nowMs = timeProvider()
        )
    }
}
