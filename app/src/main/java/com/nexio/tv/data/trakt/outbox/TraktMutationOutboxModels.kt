package com.nexio.tv.data.trakt.outbox

import com.google.gson.JsonObject
import java.util.UUID

enum class TraktMutationPriorityBucket(
    val sortOrder: Int,
    val maxBurst: Int
) {
    SCROBBLE(sortOrder = 0, maxBurst = 3),
    WATCHED(sortOrder = 1, maxBurst = 2),
    WATCHLIST(sortOrder = 2, maxBurst = 1),
    LISTS(sortOrder = 3, maxBurst = 1)
}

enum class TraktMutationLifecycleState {
    QUEUED,
    LEASED,
    WAITING_RETRY,
    SUCCEEDED,
    TERMINAL_FAILED,
    COLLAPSED
}

data class TraktMutationEnvelope(
    val id: String = UUID.randomUUID().toString(),
    val profileId: Int = 1,
    val adapterKey: String,
    val mutationKind: String,
    val priority: TraktMutationPriorityBucket,
    val collapseKey: String? = null,
    val payload: JsonObject = JsonObject(),
    val rollbackPayload: JsonObject? = null,
    val metadata: JsonObject = JsonObject(),
    val state: TraktMutationLifecycleState = TraktMutationLifecycleState.QUEUED,
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val nextAttemptAtMs: Long = 0L,
    val attemptCount: Int = 0,
    val leaseToken: String? = null,
    val leaseExpiresAtMs: Long? = null,
    val supersededById: String? = null,
    val lastError: String? = null,
    val lastHttpStatusCode: Int? = null,
    val completedAtMs: Long? = null
) {
    fun isReadyLike(): Boolean {
        return state == TraktMutationLifecycleState.QUEUED ||
            state == TraktMutationLifecycleState.WAITING_RETRY
    }

    fun isQueueCollapsible(): Boolean {
        return state == TraktMutationLifecycleState.QUEUED ||
            state == TraktMutationLifecycleState.WAITING_RETRY
    }

    fun deepCopy(): TraktMutationEnvelope {
        return copy(
            payload = payload.deepCopy(),
            rollbackPayload = rollbackPayload?.deepCopy(),
            metadata = metadata.deepCopy()
        )
    }
}

data class TraktMutationOutboxSnapshot(
    val items: List<TraktMutationEnvelope> = emptyList(),
    val nextWritableAtMs: Long = 0L,
    val updatedAtMs: Long = 0L
)

data class TraktMutationLease(
    val envelope: TraktMutationEnvelope,
    val fairnessState: TraktMutationFairnessState
)

data class TraktMutationFairnessState(
    val lastBucket: TraktMutationPriorityBucket? = null,
    val consecutiveSelections: Int = 0
)

sealed interface TraktMutationSettlement {
    data class Succeeded(
        val httpStatusCode: Int? = null
    ) : TraktMutationSettlement

    data class Retryable(
        val retryAtMs: Long,
        val reason: String,
        val httpStatusCode: Int? = null
    ) : TraktMutationSettlement

    data class TerminalFailure(
        val reason: String,
        val httpStatusCode: Int? = null
    ) : TraktMutationSettlement
}

sealed interface TraktMutationExecutionResult {
    data class Success(
        val httpStatusCode: Int? = null
    ) : TraktMutationExecutionResult

    data class Failure(
        val httpStatusCode: Int? = null,
        val retryAfterHeader: String? = null,
        val reason: String? = null,
        val throwable: Throwable? = null
    ) : TraktMutationExecutionResult
}

interface TraktMutationAdapter {
    val adapterKey: String

    suspend fun applyOptimistic(envelope: TraktMutationEnvelope)

    suspend fun execute(envelope: TraktMutationEnvelope): TraktMutationExecutionResult

    suspend fun reconcileSuccess(envelope: TraktMutationEnvelope)

    suspend fun rollbackToServerTruth(
        envelope: TraktMutationEnvelope,
        failure: TraktMutationSettlement.TerminalFailure
    )
}
