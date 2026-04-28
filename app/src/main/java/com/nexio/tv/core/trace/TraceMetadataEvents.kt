package com.nexio.tv.core.trace

import java.util.concurrent.atomic.AtomicLong

/**
 * Helper for metadata-layer instrumentation.
 *
 * `emitFirstPaint` is wired in production via [FirstPaintTracer], invoked from
 * the canonical Home first-paint boundary so the validator rule
 * `PreviewMustNotRouteOrNetwork` has a real event to evaluate for both addon
 * previews and rail previews.
 *
 * `emitRouteDecision` is invoked from [com.nexio.tv.core.metadata.router.MetadataRouter]
 * at its single private route() builder, so every MetadataRoute construction emits
 * one metadata.route_decision event when a trace session is active.
 *
 * The Hilt graph for production binding lives in
 * [com.nexio.tv.core.di.RuntimeTraceModule]; the `sessionId` lambda is bound to
 * `TraceSessionManager.activeSession()?.traceSessionId` there.
 */
class TraceMetadataEvents(
    private val sink: RuntimeTraceSink,
    private val sessionId: () -> String?
) {
    private val seq = AtomicLong(0L)

    fun emitFirstPaint(
        contentId: String,
        itemType: String,
        surface: SourceSurface,
        source: String,
        routerExecuted: Boolean,
        networkExecuted: Boolean,
        fieldsUsed: List<String>,
        profileHash: String?
    ) {
        val sid = sessionId() ?: return
        sink.emit(
            TraceEventEnvelope(
                traceSessionId = sid,
                sequence = seq.incrementAndGet(),
                wallClockMs = System.currentTimeMillis(),
                elapsedRealtimeMs = System.nanoTime() / 1_000_000,
                threadName = Thread.currentThread().name,
                eventType = "metadata.first_paint",
                payload = mapOf(
                    "contentId" to contentId,
                    "itemType" to itemType,
                    "surface" to surface.name,
                    "source" to source,
                    "routerExecuted" to routerExecuted,
                    "networkExecuted" to networkExecuted,
                    "fieldsUsed" to fieldsUsed,
                    "profileHash" to profileHash
                )
            )
        )
    }

    fun emitIdentityResolution(
        sourceId: String,
        targetProvider: String,
        resolver: String,
        apiShapeId: String,
        cacheDecision: String,
        executedNetwork: Boolean,
        resultId: String?,
        success: Boolean
    ) {
        val sid = sessionId() ?: return
        sink.emit(
            TraceEventEnvelope(
                traceSessionId = sid,
                sequence = seq.incrementAndGet(),
                wallClockMs = System.currentTimeMillis(),
                elapsedRealtimeMs = System.nanoTime() / 1_000_000,
                threadName = Thread.currentThread().name,
                eventType = "metadata.identity_resolution",
                payload = mapOf(
                    "sourceId" to sourceId,
                    "targetProvider" to targetProvider,
                    "resolver" to resolver,
                    "apiShapeId" to apiShapeId,
                    "cacheDecision" to cacheDecision,
                    "executedNetwork" to executedNetwork,
                    "resultId" to resultId,
                    "success" to success
                )
            )
        )
    }

    fun emitProviderPlan(
        contentId: String,
        provider: String,
        mediaKind: String,
        depth: String,
        steps: List<Map<String, Any?>>
    ) {
        val sid = sessionId() ?: return
        sink.emit(
            TraceEventEnvelope(
                traceSessionId = sid,
                sequence = seq.incrementAndGet(),
                wallClockMs = System.currentTimeMillis(),
                elapsedRealtimeMs = System.nanoTime() / 1_000_000,
                threadName = Thread.currentThread().name,
                eventType = "metadata.provider_plan",
                payload = mapOf(
                    "contentId" to contentId,
                    "provider" to provider,
                    "mediaKind" to mediaKind,
                    "depth" to depth,
                    "steps" to steps
                )
            )
        )
    }

    fun emitResolverSchedule(
        depth: String,
        scheduled: List<String>,
        skipped: Map<String, String>
    ) {
        val sid = sessionId() ?: return
        sink.emit(
            TraceEventEnvelope(
                traceSessionId = sid,
                sequence = seq.incrementAndGet(),
                wallClockMs = System.currentTimeMillis(),
                elapsedRealtimeMs = System.nanoTime() / 1_000_000,
                threadName = Thread.currentThread().name,
                eventType = "metadata.resolver_schedule",
                payload = mapOf(
                    "depth" to depth,
                    "scheduled" to scheduled,
                    "skipped" to skipped
                )
            )
        )
    }

    fun emitFieldSelected(
        contentId: String,
        field: String,
        selectedProvider: String,
        sourceRole: String,
        valuePreview: String,
        ownershipRule: String,
        rejectedCandidates: List<Map<String, Any?>>
    ) {
        val sid = sessionId() ?: return
        sink.emit(
            TraceEventEnvelope(
                traceSessionId = sid,
                sequence = seq.incrementAndGet(),
                wallClockMs = System.currentTimeMillis(),
                elapsedRealtimeMs = System.nanoTime() / 1_000_000,
                threadName = Thread.currentThread().name,
                eventType = "metadata.field_selected",
                payload = mapOf(
                    "contentId" to contentId,
                    "field" to field,
                    "selectedProvider" to selectedProvider,
                    "sourceRole" to sourceRole,
                    "valuePreview" to valuePreview,
                    "ownershipRule" to ownershipRule,
                    "rejectedCandidates" to rejectedCandidates
                )
            )
        )
    }

    fun emitRouteDecision(
        contentId: String,
        parentId: String,
        itemType: String,
        provider: String,
        mediaKind: String,
        reason: String,
        usedInputs: List<String>,
        ignoredInputs: List<String>,
        targetIdRequiresIdentityResolution: Boolean,
        targetIds: Map<String, String>
    ) {
        val sid = sessionId() ?: return
        sink.emit(
            TraceEventEnvelope(
                traceSessionId = sid,
                sequence = seq.incrementAndGet(),
                wallClockMs = System.currentTimeMillis(),
                elapsedRealtimeMs = System.nanoTime() / 1_000_000,
                threadName = Thread.currentThread().name,
                eventType = "metadata.route_decision",
                payload = mapOf(
                    "contentId" to contentId,
                    "parentId" to parentId,
                    "itemType" to itemType,
                    "provider" to provider,
                    "mediaKind" to mediaKind,
                    "reason" to reason,
                    "usedInputs" to usedInputs,
                    "ignoredInputs" to ignoredInputs,
                    "targetIdRequiresIdentityResolution" to targetIdRequiresIdentityResolution,
                    "targetIds" to targetIds
                )
            )
        )
    }

    fun emitScrobbleRejected(
        envelopeProfileId: Int,
        activeProfileId: Int,
        operation: String,
        reason: String
    ) {
        val sid = sessionId() ?: return
        sink.emit(
            TraceEventEnvelope(
                traceSessionId = sid,
                sequence = seq.incrementAndGet(),
                wallClockMs = System.currentTimeMillis(),
                elapsedRealtimeMs = System.nanoTime() / 1_000_000,
                threadName = Thread.currentThread().name,
                eventType = "playback.scrobble_rejected",
                payload = mapOf(
                    "envelopeProfileId" to envelopeProfileId,
                    "activeProfileId" to activeProfileId,
                    "operation" to operation,
                    "reason" to reason
                )
            )
        )
    }
}
