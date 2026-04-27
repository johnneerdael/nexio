package com.nexio.tv.core.trace

import java.util.concurrent.atomic.AtomicLong

/**
 * Helper for metadata-layer instrumentation. Tasks 20-27 will extend this with
 * emitProviderPlan, emitLocalizationPlan, emitResolverSchedule, emitFieldSelected,
 * emitIdentityResolution.
 *
 * `emitFirstPaint` documents the case where the UI renders cached addon-preview
 * metadata without invoking MetadataRouter. This emission point is not yet wired
 * because the codebase has no preview-only render path; the helper is here so
 * future preview UI work can call it.
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
        source: String,
        routerExecuted: Boolean,
        networkExecuted: Boolean,
        fieldsUsed: List<String>
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
                    "source" to source,
                    "routerExecuted" to routerExecuted,
                    "networkExecuted" to networkExecuted,
                    "fieldsUsed" to fieldsUsed
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
}
