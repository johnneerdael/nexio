package com.nexio.tv.core.trace

data class TraceSession(
    val traceSessionId: String,
    val startedAtEpochMs: Long,
    val appVersion: String,
    val buildType: String,
    val gitSha: String?,
    val deviceModel: String,
    val androidVersion: String,
    val activeProfileHash: String?,
    val mode: TraceMode,
    val salt: String
) {
    init {
        require(traceSessionId.isNotBlank()) { "TraceSession.traceSessionId must not be blank" }
        require(startedAtEpochMs > 0L) { "TraceSession.startedAtEpochMs must be positive" }
        require(salt.isNotBlank()) { "TraceSession.salt must not be blank" }
    }
}
