package com.nexio.tv.core.trace

import com.nexio.tv.core.metadata.router.StableIdBundle
import com.nexio.tv.core.metadata.router.StableIdResolutionTrigger
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
        success: Boolean,
        /** F2-C-07: optional failure reason; set to "unsupported_id_prefix" when the adapter
         *  received a route whose ID has an unrecognised scheme (e.g. spotify:, netflix-id-format). */
        reason: String? = null
    ) {
        val sid = sessionId() ?: return
        val basePayload = mapOf(
            "sourceId" to sourceId,
            "targetProvider" to targetProvider,
            "resolver" to resolver,
            "apiShapeId" to apiShapeId,
            "cacheDecision" to cacheDecision,
            "executedNetwork" to executedNetwork,
            "resultId" to resultId,
            "success" to success
        )
        sink.emit(
            TraceEventEnvelope(
                traceSessionId = sid,
                sequence = seq.incrementAndGet(),
                wallClockMs = System.currentTimeMillis(),
                elapsedRealtimeMs = System.nanoTime() / 1_000_000,
                threadName = Thread.currentThread().name,
                eventType = "metadata.identity_resolution",
                payload = if (reason != null) basePayload + ("reason" to reason) else basePayload
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

    fun emitNormalizerWarning(contentId: String, reason: String) {
        val sid = sessionId() ?: return
        sink.emit(
            TraceEventEnvelope(
                traceSessionId = sid,
                sequence = seq.incrementAndGet(),
                wallClockMs = System.currentTimeMillis(),
                elapsedRealtimeMs = System.nanoTime() / 1_000_000,
                threadName = Thread.currentThread().name,
                eventType = "metadata.normalizer_warning",
                payload = mapOf(
                    "contentId" to contentId,
                    "reason" to reason
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

    fun emitStableIdBundle(
        bundle: StableIdBundle,
        trigger: StableIdResolutionTrigger
    ) {
        val sid = sessionId() ?: return
        sink.emit(
            TraceEventEnvelope(
                traceSessionId = sid,
                sequence = seq.incrementAndGet(),
                wallClockMs = System.currentTimeMillis(),
                elapsedRealtimeMs = System.nanoTime() / 1_000_000,
                threadName = Thread.currentThread().name,
                eventType = "metadata.stable_id_bundle",
                payload = mapOf(
                    "itemKey" to bundle.itemKey,
                    "itemType" to bundle.itemType.name,
                    "status" to bundle.status.name,
                    "trigger" to trigger.name,
                    "tmdbMovieId" to bundle.canonical.tmdbMovieId,
                    "tvdbSeriesId" to bundle.canonical.tvdbSeriesId,
                    "kitsuAnimeId" to bundle.canonical.kitsuAnimeId,
                    "imdbId" to bundle.sidecars.imdbId,
                    "networkExecuted" to bundle.evidence.any { it.networkExecuted },
                    "evidence" to bundle.evidence.map { evidence ->
                        mapOf(
                            "source" to evidence.source,
                            "target" to evidence.target,
                            "networkExecuted" to evidence.networkExecuted,
                            "resultId" to evidence.resultId
                        )
                    }
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

    fun emitHomeHydrationStarted(
        railId: String?,
        itemKey: String,
        firstPaintSource: String,
        trigger: String,
        priority: String,
        workClass: String
    ) {
        emitHomeHydrationEvent(
            eventType = "home.hydration_started",
            payload = mapOf(
                "railId" to railId,
                "itemKey" to itemKey,
                "firstPaintSource" to firstPaintSource,
                "trigger" to trigger,
                "priority" to priority,
                "workClass" to workClass
            )
        )
    }

    fun emitHomeHydrationOverlayWritten(
        itemKey: String,
        canonicalProvider: String,
        canonicalId: String,
        imdbId: String?,
        displayHash: String
    ) {
        emitHomeHydrationEvent(
            eventType = "home.hydration_overlay_written",
            payload = mapOf(
                "itemKey" to itemKey,
                "canonicalProvider" to canonicalProvider,
                "canonicalId" to canonicalId,
                "imdbId" to imdbId,
                "displayHash" to displayHash
            )
        )
    }

    fun emitHomeHydrationApplied(
        railId: String?,
        itemKey: String,
        firstPaintSource: String,
        canonicalProvider: String,
        canonicalId: String,
        imdbId: String?,
        trigger: String,
        priority: String,
        workClass: String,
        changedFields: List<String>,
        displayHashBefore: String,
        displayHashAfter: String,
        rowOrderChanged: Boolean,
        focusChanged: Boolean,
        networkExecuted: Boolean,
        cacheDecision: String?
    ) {
        emitHomeHydrationEvent(
            eventType = "home.hydration_applied",
            payload = mapOf(
                "railId" to railId,
                "itemKey" to itemKey,
                "firstPaintSource" to firstPaintSource,
                "canonicalProvider" to canonicalProvider,
                "canonicalId" to canonicalId,
                "imdbId" to imdbId,
                "trigger" to trigger,
                "priority" to priority,
                "workClass" to workClass,
                "changedFields" to changedFields,
                "displayHashBefore" to displayHashBefore,
                "displayHashAfter" to displayHashAfter,
                "rowOrderChanged" to rowOrderChanged,
                "focusChanged" to focusChanged,
                "networkExecuted" to networkExecuted,
                "cacheDecision" to cacheDecision
            )
        )
    }

    fun emitHomeHydrationIgnored(
        itemKey: String,
        reason: String,
        trigger: String
    ) {
        emitHomeHydrationEvent(
            eventType = "home.hydration_ignored",
            payload = mapOf(
                "itemKey" to itemKey,
                "reason" to reason,
                "trigger" to trigger
            )
        )
    }

    fun emitHomeHydrationFailedUsingPreview(
        itemKey: String,
        reason: String,
        trigger: String
    ) {
        emitHomeHydrationEvent(
            eventType = "home.hydration_failed_using_preview",
            payload = mapOf(
                "itemKey" to itemKey,
                "reason" to reason,
                "trigger" to trigger
            )
        )
    }

    fun emitLocalizationPlan(
        contentId: String,
        provider: String,
        policyVersion: Int,
        requestedLanguage: String,
        fallbackLanguage: String,
        requestedIsFallback: Boolean,
        allowProviderFallbackForMissingLocalizedFields: Boolean,
        perEpisodeFallbacksAttempted: Int,
        perEpisodeFallbacksAllowed: Int,
        /** F2-E-01: true when the requested locale was not on the provider whitelist and was
         *  silently collapsed to the English fallback. Surfaces in trace bundles for diagnostics. */
        localeCollapsedToFallback: Boolean = false
    ) {
        val sid = sessionId() ?: return
        sink.emit(
            TraceEventEnvelope(
                traceSessionId = sid,
                sequence = seq.incrementAndGet(),
                wallClockMs = System.currentTimeMillis(),
                elapsedRealtimeMs = System.nanoTime() / 1_000_000,
                threadName = Thread.currentThread().name,
                eventType = "metadata.localization_plan",
                payload = mapOf(
                    "contentId" to contentId,
                    "provider" to provider,
                    "policyVersion" to policyVersion,
                    "requestedLanguage" to requestedLanguage,
                    "fallbackLanguage" to fallbackLanguage,
                    "requestedIsFallback" to requestedIsFallback,
                    "allowProviderFallbackForMissingLocalizedFields" to allowProviderFallbackForMissingLocalizedFields,
                    "perEpisodeFallbacksAttempted" to perEpisodeFallbacksAttempted,
                    "perEpisodeFallbacksAllowed" to perEpisodeFallbacksAllowed,
                    "localeCollapsedToFallback" to localeCollapsedToFallback  // F2-E-01
                )
            )
        )
    }

    private fun emitHomeHydrationEvent(
        eventType: String,
        payload: Map<String, Any?>
    ) {
        val sid = sessionId() ?: return
        sink.emit(
            TraceEventEnvelope(
                traceSessionId = sid,
                sequence = seq.incrementAndGet(),
                wallClockMs = System.currentTimeMillis(),
                elapsedRealtimeMs = System.nanoTime() / 1_000_000,
                threadName = Thread.currentThread().name,
                eventType = eventType,
                payload = payload
            )
        )
    }
}
