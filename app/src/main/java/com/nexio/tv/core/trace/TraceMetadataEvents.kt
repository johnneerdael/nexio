package com.nexio.tv.core.trace

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper for metadata-layer instrumentation. Tasks 20-27 will extend this with
 * emitRouteDecision, emitProviderPlan, emitLocalizationPlan, emitResolverSchedule,
 * emitFieldSelected, emitIdentityResolution.
 *
 * `emitFirstPaint` documents the case where the UI renders cached addon-preview
 * metadata without invoking MetadataRouter. This emission point is not yet wired
 * because the codebase has no preview-only render path; the helper is here so
 * future preview UI work can call it.
 */
// TODO(trace): add Hilt @Provides factory that wires sessionId from TraceSessionManager.
@Singleton
class TraceMetadataEvents @Inject constructor(
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
}
