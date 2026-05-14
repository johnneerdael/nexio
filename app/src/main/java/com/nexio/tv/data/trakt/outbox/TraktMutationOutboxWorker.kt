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

    suspend fun snapshot(profileId: Int? = null): TraktMutationOutboxSnapshot =
        if (profileId == null) store.read() else store.read(profileId)

    suspend fun enqueue(envelope: TraktMutationEnvelope): TraktMutationEnvelope = mutex.withLock {
        val nowMs = timeProvider()
        val updated = policy.enqueue(
            snapshot = store.read(envelope.profileId),
            envelope = envelope,
            nowMs = nowMs
        )
        store.write(updated, envelope.profileId)
        updated.items.first { it.id == envelope.id }
    }

    suspend fun recoverExpiredLeases(profileId: Int? = null): Int = mutex.withLock {
        val nowMs = timeProvider()
        val before = if (profileId == null) store.read() else store.read(profileId)
        val after = policy.recoverExpiredLeases(before, nowMs)
        if (before == after) return@withLock 0
        if (profileId == null) store.write(after) else store.write(after, profileId)
        before.items.count { item ->
            item.state == TraktMutationLifecycleState.LEASED &&
                item.leaseExpiresAtMs != null &&
                item.leaseExpiresAtMs <= nowMs
        }
    }

    suspend fun leaseNextReady(profileId: Int? = null): TraktMutationLease? = mutex.withLock {
        val nowMs = timeProvider()
        val recovered = policy.recoverExpiredLeases(
            if (profileId == null) store.read() else store.read(profileId),
            nowMs
        )
        val selection = policy.leaseNextReady(
            snapshot = recovered,
            nowMs = nowMs,
            fairnessState = fairnessState,
            leaseDurationMs = leaseDurationMs,
            leaseTokenFactory = leaseTokenFactory
        )
        if (selection == null) {
            if (profileId == null) store.write(recovered) else store.write(recovered, profileId)
            return@withLock null
        }
        fairnessState = selection.lease.fairnessState
        if (profileId == null) store.write(selection.snapshot) else store.write(selection.snapshot, profileId)
        selection.lease
    }

    suspend fun settle(
        profileId: Int,
        leaseToken: String,
        settlement: TraktMutationSettlement
    ): TraktMutationEnvelope? = mutex.withLock {
        val nowMs = timeProvider()
        val result = policy.settleLease(
            snapshot = store.read(profileId),
            leaseToken = leaseToken,
            settlement = settlement,
            nowMs = nowMs
        )
        store.write(result.snapshot, profileId)
        result.settledEnvelope
    }

    suspend fun classifyFailure(
        failure: TraktMutationExecutionResult.Failure,
        attemptCount: Int,
        provider: com.nexio.tv.domain.model.TrackingProvider? = null
    ): TraktMutationSettlement = mutex.withLock {
        policy.classifyFailure(
            failure = failure,
            attemptCount = attemptCount,
            nowMs = timeProvider(),
            provider = provider
        )
    }
}
