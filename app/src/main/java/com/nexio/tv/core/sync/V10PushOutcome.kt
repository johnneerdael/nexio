package com.nexio.tv.core.sync

/**
 * Domain-level outcome of a Contract v10 push. Wraps the raw `V10PushResult`
 * envelope so service callers can branch on a typed result instead of
 * inspecting the JSON.
 */
sealed interface V10PushOutcome {
    data class Applied(
        val currentUpdatedAtMs: Long,
        val syncRevision: Long? = null
    ) : V10PushOutcome

    data class StaleBase(val currentUpdatedAtMs: Long) : V10PushOutcome

    data class FieldConflict(
        val currentUpdatedAtMs: Long,
        val conflictPaths: List<String>,
        val syncRevision: Long
    ) : V10PushOutcome

    data class Failed(val cause: Throwable) : V10PushOutcome
}
