package com.nexio.tv.data.trakt.outbox

import java.io.IOException

class TraktMutationOutboxPolicy(
    private val transientStatusCodes: Set<Int> = setOf(429, 500, 502, 503, 504, 520, 521, 522),
    private val terminalStatusCodes: Set<Int> = setOf(400, 401, 403, 404, 405, 409, 410, 412, 420, 422, 423, 426),
    private val minWriteIntervalMs: Long = 1_000L
) {
    data class LeaseSelection(
        val snapshot: TraktMutationOutboxSnapshot,
        val lease: TraktMutationLease
    )

    data class SettlementResult(
        val snapshot: TraktMutationOutboxSnapshot,
        val settledEnvelope: TraktMutationEnvelope?
    )

    fun enqueue(
        snapshot: TraktMutationOutboxSnapshot,
        envelope: TraktMutationEnvelope,
        nowMs: Long
    ): TraktMutationOutboxSnapshot {
        val normalized = envelope.deepCopy().copy(
            createdAtMs = envelope.createdAtMs.takeIf { it > 0L } ?: nowMs,
            updatedAtMs = nowMs,
            nextAttemptAtMs = envelope.nextAttemptAtMs.takeIf { it > 0L } ?: nowMs,
            state = TraktMutationLifecycleState.QUEUED,
            leaseToken = null,
            leaseExpiresAtMs = null,
            supersededById = null,
            lastError = null,
            completedAtMs = null
        )
        val collapsed = collapsePending(snapshot.items, normalized, nowMs)
        return snapshot.copy(
            items = (collapsed + normalized).sortedWith(queueComparator),
            updatedAtMs = nowMs
        )
    }

    fun recoverExpiredLeases(
        snapshot: TraktMutationOutboxSnapshot,
        nowMs: Long
    ): TraktMutationOutboxSnapshot {
        var changed = false
        val recovered = snapshot.items.map { item ->
            if (
                item.state == TraktMutationLifecycleState.LEASED &&
                item.leaseExpiresAtMs != null &&
                item.leaseExpiresAtMs <= nowMs
            ) {
                changed = true
                item.copy(
                    state = TraktMutationLifecycleState.QUEUED,
                    updatedAtMs = nowMs,
                    nextAttemptAtMs = nowMs,
                    leaseToken = null,
                    leaseExpiresAtMs = null
                )
            } else {
                item
            }
        }
        if (!changed) return snapshot
        return snapshot.copy(items = recovered.sortedWith(queueComparator), updatedAtMs = nowMs)
    }

    fun leaseNextReady(
        snapshot: TraktMutationOutboxSnapshot,
        nowMs: Long,
        fairnessState: TraktMutationFairnessState,
        leaseDurationMs: Long,
        leaseTokenFactory: () -> String
    ): LeaseSelection? {
        if (snapshot.nextWritableAtMs > nowMs) return null
        val candidate = chooseReadyItem(snapshot.items, nowMs, fairnessState) ?: return null
        val leaseToken = leaseTokenFactory()
        val leasedEnvelope = candidate.deepCopy().copy(
            state = TraktMutationLifecycleState.LEASED,
            updatedAtMs = nowMs,
            leaseToken = leaseToken,
            leaseExpiresAtMs = nowMs + leaseDurationMs
        )
        val updatedItems = snapshot.items.map { item ->
            if (item.id == leasedEnvelope.id) leasedEnvelope else item
        }
        val updatedFairness = updateFairness(fairnessState, leasedEnvelope.priority)
        return LeaseSelection(
            snapshot = snapshot.copy(items = updatedItems.sortedWith(queueComparator), updatedAtMs = nowMs),
            lease = TraktMutationLease(
                envelope = leasedEnvelope,
                fairnessState = updatedFairness
            )
        )
    }

    fun settleLease(
        snapshot: TraktMutationOutboxSnapshot,
        leaseToken: String,
        settlement: TraktMutationSettlement,
        nowMs: Long
    ): SettlementResult {
        val target = snapshot.items.firstOrNull { it.leaseToken == leaseToken }
            ?: return SettlementResult(snapshot = snapshot, settledEnvelope = null)
        val settled = when (settlement) {
            is TraktMutationSettlement.Succeeded -> target.copy(
                state = TraktMutationLifecycleState.SUCCEEDED,
                updatedAtMs = nowMs,
                nextAttemptAtMs = nowMs,
                attemptCount = target.attemptCount + 1,
                leaseToken = null,
                leaseExpiresAtMs = null,
                lastError = null,
                lastHttpStatusCode = settlement.httpStatusCode,
                completedAtMs = nowMs
            )

            is TraktMutationSettlement.Retryable -> target.copy(
                state = TraktMutationLifecycleState.WAITING_RETRY,
                updatedAtMs = nowMs,
                nextAttemptAtMs = settlement.retryAtMs.coerceAtLeast(nowMs + minWriteIntervalMs),
                attemptCount = target.attemptCount + 1,
                leaseToken = null,
                leaseExpiresAtMs = null,
                lastError = settlement.reason,
                lastHttpStatusCode = settlement.httpStatusCode
            )

            is TraktMutationSettlement.TerminalFailure -> target.copy(
                state = TraktMutationLifecycleState.TERMINAL_FAILED,
                updatedAtMs = nowMs,
                nextAttemptAtMs = nowMs,
                attemptCount = target.attemptCount + 1,
                leaseToken = null,
                leaseExpiresAtMs = null,
                lastError = settlement.reason,
                lastHttpStatusCode = settlement.httpStatusCode,
                completedAtMs = nowMs
            )
        }

        val nextWritableAtMs = when (settlement) {
            is TraktMutationSettlement.Retryable -> {
                if (settlement.httpStatusCode == 429) {
                    maxOf(snapshot.nextWritableAtMs, settlement.retryAtMs, nowMs + minWriteIntervalMs)
                } else {
                    maxOf(snapshot.nextWritableAtMs, nowMs + minWriteIntervalMs)
                }
            }

            else -> maxOf(snapshot.nextWritableAtMs, nowMs + minWriteIntervalMs)
        }

        val updatedItems = snapshot.items.map { item ->
            if (item.id == settled.id) settled else item
        }
        return SettlementResult(
            snapshot = snapshot.copy(
                items = updatedItems.sortedWith(queueComparator),
                nextWritableAtMs = nextWritableAtMs,
                updatedAtMs = nowMs
            ),
            settledEnvelope = settled
        )
    }

    fun classifyFailure(
        failure: TraktMutationExecutionResult.Failure,
        attemptCount: Int,
        nowMs: Long
    ): TraktMutationSettlement {
        val statusCode = failure.httpStatusCode
        val reason = failure.reason ?: failure.throwable?.message ?: "Unknown Trakt failure"
        if (statusCode == 429) {
            val retryDelayMs = parseRetryAfterMs(failure.retryAfterHeader) ?: fallbackRetryDelayMs(attemptCount)
            return TraktMutationSettlement.Retryable(
                retryAtMs = nowMs + retryDelayMs,
                reason = reason,
                httpStatusCode = statusCode
            )
        }
        if (failure.throwable is IOException || (statusCode != null && statusCode in transientStatusCodes)) {
            return TraktMutationSettlement.Retryable(
                retryAtMs = nowMs + fallbackRetryDelayMs(attemptCount),
                reason = reason,
                httpStatusCode = statusCode
            )
        }
        if (statusCode != null && statusCode in terminalStatusCodes) {
            return TraktMutationSettlement.TerminalFailure(
                reason = reason,
                httpStatusCode = statusCode
            )
        }
        return if (statusCode != null && statusCode in 400..499) {
            TraktMutationSettlement.TerminalFailure(
                reason = reason,
                httpStatusCode = statusCode
            )
        } else {
            TraktMutationSettlement.Retryable(
                retryAtMs = nowMs + fallbackRetryDelayMs(attemptCount),
                reason = reason,
                httpStatusCode = statusCode
            )
        }
    }

    private fun collapsePending(
        items: List<TraktMutationEnvelope>,
        incoming: TraktMutationEnvelope,
        nowMs: Long
    ): List<TraktMutationEnvelope> {
        val collapseKey = incoming.collapseKey?.takeIf { it.isNotBlank() } ?: return items
        return items.map { existing ->
            if (
                existing.isQueueCollapsible() &&
                existing.adapterKey == incoming.adapterKey &&
                existing.mutationKind == incoming.mutationKind &&
                existing.collapseKey == collapseKey &&
                existing.priority == incoming.priority &&
                existing.profileId == incoming.profileId
            ) {
                existing.copy(
                    state = TraktMutationLifecycleState.COLLAPSED,
                    updatedAtMs = nowMs,
                    supersededById = incoming.id,
                    leaseToken = null,
                    leaseExpiresAtMs = null,
                    completedAtMs = nowMs
                )
            } else {
                existing
            }
        }
    }

    private fun chooseReadyItem(
        items: List<TraktMutationEnvelope>,
        nowMs: Long,
        fairnessState: TraktMutationFairnessState
    ): TraktMutationEnvelope? {
        val ready = items
            .filter { it.isReadyLike() && it.nextAttemptAtMs <= nowMs }
            .sortedWith(queueComparator)
        if (ready.isEmpty()) return null

        val readyByBucket = ready.groupBy { it.priority }
        val preferredBucket = chooseBucket(readyByBucket.keys, fairnessState)
        return readyByBucket[preferredBucket]
            ?.minWithOrNull(queueComparator)
            ?: ready.firstOrNull()
    }

    private fun chooseBucket(
        readyBuckets: Set<TraktMutationPriorityBucket>,
        fairnessState: TraktMutationFairnessState
    ): TraktMutationPriorityBucket {
        val ordered = readyBuckets.sortedBy { it.sortOrder }
        val lastBucket = fairnessState.lastBucket ?: return ordered.first()
        val lowerReady = ordered.filter { it.sortOrder > lastBucket.sortOrder }
        if (
            lowerReady.isNotEmpty() &&
            fairnessState.consecutiveSelections >= lastBucket.maxBurst &&
            lastBucket in readyBuckets
        ) {
            return lowerReady.first()
        }
        return ordered.first()
    }

    private fun updateFairness(
        fairnessState: TraktMutationFairnessState,
        selectedBucket: TraktMutationPriorityBucket
    ): TraktMutationFairnessState {
        return if (fairnessState.lastBucket == selectedBucket) {
            fairnessState.copy(consecutiveSelections = fairnessState.consecutiveSelections + 1)
        } else {
            TraktMutationFairnessState(lastBucket = selectedBucket, consecutiveSelections = 1)
        }
    }

    private fun parseRetryAfterMs(headerValue: String?): Long? {
        return headerValue
            ?.trim()
            ?.toLongOrNull()
            ?.coerceAtLeast(1L)
            ?.times(1_000L)
    }

    private fun fallbackRetryDelayMs(attemptCount: Int): Long {
        return when (attemptCount.coerceAtLeast(0)) {
            0 -> 2_000L
            1 -> 4_000L
            2 -> 8_000L
            3 -> 16_000L
            else -> 30_000L
        }
    }

    companion object {
        private val queueComparator = compareBy<TraktMutationEnvelope>(
            { it.priority.sortOrder },
            { it.nextAttemptAtMs },
            { it.createdAtMs },
            { it.updatedAtMs }
        )
    }
}
