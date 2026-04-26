package com.nexio.tv.core.integration

data class IntegrationAuditEvent(
    val traceId: String,
    val provider: IntegrationProvider,
    val apiShapeId: String?,
    val headerPolicyId: String?,
    val adapterClass: String,
    val cacheKeyHash: String?,
    val cacheKeyTemplate: String?,
    val scopeKeyHash: String?,
    val workClass: IntegrationWorkClass,
    val cachePolicy: String,
    val codec: String?,
    val laneConcurrency: Int,
    val playbackActive: Boolean,
    val phase: IntegrationAuditPhase,
    val outcome: IntegrationOutcome?,
    val httpStatus: Int?,
    val retryAfterMs: Long?,
    val freshUntilEpochMs: Long?,
    val staleUntilEpochMs: Long?,
    val networkStarted: Boolean,
    val loaderInvoked: Boolean,
    val durationMs: Long,
    val timestampEpochMs: Long
)

enum class IntegrationAuditPhase {
    REQUEST_RECEIVED,
    FRESH_CACHE_HIT,
    STALE_CACHE_HIT,
    PLAYBACK_BLOCKED,
    BACKOFF_BLOCKED,
    LANE_QUEUED,
    NETWORK_START,
    NETWORK_END,
    CACHE_WRITE,
    MISSING,
    FAILED
}

enum class IntegrationOutcome {
    SUCCESS,
    HTTP_ERROR,
    NETWORK_ERROR,
    MISSING
}

interface IntegrationAuditSink {
    fun record(event: IntegrationAuditEvent)
}

object NoOpIntegrationAuditSink : IntegrationAuditSink {
    override fun record(event: IntegrationAuditEvent) = Unit
}
