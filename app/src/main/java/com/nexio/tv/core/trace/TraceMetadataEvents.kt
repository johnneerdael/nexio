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
        val sid = traceSessionIdForEmission()
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
        val sid = traceSessionIdForEmission()
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
        val sid = traceSessionIdForEmission()
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
        val sid = traceSessionIdForEmission()
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
        val sid = traceSessionIdForEmission()
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
        val sid = traceSessionIdForEmission()
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
        val sid = traceSessionIdForEmission()
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
        val sid = traceSessionIdForEmission()
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
        val sid = traceSessionIdForEmission()
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

    fun emitScreensaverCandidatePoolBuilt(
        profileHash: String?,
        source: String,
        imageCandidateCount: Int,
        trailerCandidateCount: Int
    ) {
        emitScreensaverEvent(
            eventType = "screensaver.candidate_pool_built",
            payload = mapOf(
                "profileHash" to profileHash,
                "source" to source,
                "imageCandidateCount" to imageCandidateCount,
                "trailerCandidateCount" to trailerCandidateCount
            )
        )
    }

    fun emitScreensaverSlideSelected(
        itemKey: String,
        source: String,
        ratingSource: String?,
        artworkSource: String?,
        matchesHomeSurface: Boolean
    ) {
        emitScreensaverEvent(
            eventType = "screensaver.slide_selected",
            payload = mapOf(
                "itemKey" to itemKey,
                "source" to source,
                "ratingSource" to optionalTraceValue(ratingSource),
                "artworkSource" to optionalTraceValue(artworkSource),
                "matchesHomeSurface" to matchesHomeSurface
            )
        )
    }

    fun emitScreensaverTrailerCandidateSelected(
        itemKey: String,
        source: String,
        trailerSource: String?,
        fallbackYouTubeIdsOnly: Boolean
    ) {
        emitScreensaverEvent(
            eventType = "screensaver.trailer_candidate_selected",
            payload = mapOf(
                "itemKey" to itemKey,
                "source" to source,
                "trailerSource" to optionalTraceValue(trailerSource),
                "fallbackYouTubeIdsOnly" to fallbackYouTubeIdsOnly
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
                "railId" to optionalTraceValue(railId),
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
                "imdbId" to optionalTraceValue(imdbId),
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
                "railId" to optionalTraceValue(railId),
                "itemKey" to itemKey,
                "firstPaintSource" to firstPaintSource,
                "canonicalProvider" to canonicalProvider,
                "canonicalId" to canonicalId,
                "imdbId" to optionalTraceValue(imdbId),
                "trigger" to trigger,
                "priority" to priority,
                "workClass" to workClass,
                "changedFields" to changedFields,
                "displayHashBefore" to displayHashBefore,
                "displayHashAfter" to displayHashAfter,
                "rowOrderChanged" to rowOrderChanged,
                "focusChanged" to focusChanged,
                "networkExecuted" to networkExecuted,
                "cacheDecision" to optionalTraceValue(cacheDecision)
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
        val sid = traceSessionIdForEmission()
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

    fun emitAnimeWorkResolved(
        sourceKitsuId: String,
        groupKey: String,
        memberCount: Int,
        confidence: String,
        evidence: List<String>,
    ) {
        val sid = traceSessionIdForEmission()
        sink.emit(
            TraceEventEnvelope(
                traceSessionId = sid,
                sequence = seq.incrementAndGet(),
                wallClockMs = System.currentTimeMillis(),
                elapsedRealtimeMs = System.nanoTime() / 1_000_000,
                threadName = Thread.currentThread().name,
                eventType = "anime.work_resolved",
                payload = mapOf(
                    "sourceKitsuId" to sourceKitsuId,
                    "groupKey" to groupKey,
                    "memberCount" to memberCount,
                    "confidence" to confidence,
                    "evidence" to evidence,
                )
            )
        )
    }

    fun emitAnimeSeasonProjectionBuilt(
        groupKey: String,
        selectedSeason: Int,
        seasonCount: Int,
        source: String,
        confidence: String,
    ) {
        val sid = traceSessionIdForEmission()
        sink.emit(
            TraceEventEnvelope(
                traceSessionId = sid,
                sequence = seq.incrementAndGet(),
                wallClockMs = System.currentTimeMillis(),
                elapsedRealtimeMs = System.nanoTime() / 1_000_000,
                threadName = Thread.currentThread().name,
                eventType = "anime.season_projection_built",
                payload = mapOf(
                    "groupKey" to groupKey,
                    "selectedSeason" to selectedSeason,
                    "seasonCount" to seasonCount,
                    "source" to source,
                    "confidence" to confidence,
                )
            )
        )
    }

    fun emitAnimeEpisodeCoordinateResolved(
        sourceKitsuId: String,
        sourceSeason: Int,
        sourceEpisode: Int,
        targetProvider: String,
        targetSeriesId: String,
        targetSeason: Int,
        targetEpisode: Int,
        confidence: String,
        uses: List<String>,
        evidence: List<String>,
    ) {
        val sid = traceSessionIdForEmission()
        sink.emit(
            TraceEventEnvelope(
                traceSessionId = sid,
                sequence = seq.incrementAndGet(),
                wallClockMs = System.currentTimeMillis(),
                elapsedRealtimeMs = System.nanoTime() / 1_000_000,
                threadName = Thread.currentThread().name,
                eventType = "anime.episode_coordinate_resolved",
                payload = mapOf(
                    "sourceKitsuId" to sourceKitsuId,
                    "sourceSeason" to sourceSeason,
                    "sourceEpisode" to sourceEpisode,
                    "targetProvider" to targetProvider,
                    "targetSeriesId" to targetSeriesId,
                    "targetSeason" to targetSeason,
                    "targetEpisode" to targetEpisode,
                    "confidence" to confidence,
                    "uses" to uses,
                    "evidence" to evidence,
                )
            )
        )
    }

    fun emitAnimeEpisodeCoordinateUnresolved(
        sourceKitsuId: String,
        sourceSeason: Int,
        sourceEpisode: Int,
        target: String,
        fallbackReason: String,
        confidence: String,
    ) {
        val sid = traceSessionIdForEmission()
        sink.emit(
            TraceEventEnvelope(
                traceSessionId = sid,
                sequence = seq.incrementAndGet(),
                wallClockMs = System.currentTimeMillis(),
                elapsedRealtimeMs = System.nanoTime() / 1_000_000,
                threadName = Thread.currentThread().name,
                eventType = "anime.episode_coordinate_unresolved",
                payload = mapOf(
                    "sourceKitsuId" to sourceKitsuId,
                    "sourceSeason" to sourceSeason,
                    "sourceEpisode" to sourceEpisode,
                    "target" to target,
                    "fallbackReason" to fallbackReason,
                    "confidence" to confidence,
                )
            )
        )
    }

    fun emitAnimeCuratedHit(
        source: String,
        kitsuId: String,
        target: String,
    ) {
        val sid = traceSessionIdForEmission()
        sink.emit(
            TraceEventEnvelope(
                traceSessionId = sid,
                sequence = seq.incrementAndGet(),
                wallClockMs = System.currentTimeMillis(),
                elapsedRealtimeMs = System.nanoTime() / 1_000_000,
                threadName = Thread.currentThread().name,
                eventType = "anime.curated_hit",
                payload = mapOf(
                    "source" to source,
                    "kitsuId" to kitsuId,
                    "target" to target,
                )
            )
        )
    }

    fun emitAnimeUnresolvedTopKitsuId(
        kitsuId: String,
        reason: String,
    ) {
        val sid = traceSessionIdForEmission()
        sink.emit(
            TraceEventEnvelope(
                traceSessionId = sid,
                sequence = seq.incrementAndGet(),
                wallClockMs = System.currentTimeMillis(),
                elapsedRealtimeMs = System.nanoTime() / 1_000_000,
                threadName = Thread.currentThread().name,
                eventType = "anime.unresolved_top_kitsu_ids",
                payload = mapOf(
                    "kitsuId" to kitsuId,
                    "reason" to reason,
                )
            )
        )
    }

    fun emitAnimeScrobbleRejected(
        contentId: String,
        reason: String,
        provider: String,
    ) {
        val sid = traceSessionIdForEmission()
        sink.emit(
            TraceEventEnvelope(
                traceSessionId = sid,
                sequence = seq.incrementAndGet(),
                wallClockMs = System.currentTimeMillis(),
                elapsedRealtimeMs = System.nanoTime() / 1_000_000,
                threadName = Thread.currentThread().name,
                eventType = "tracking.scrobble_rejected",
                payload = mapOf(
                    "contentId" to contentId,
                    "reason" to reason,
                    "provider" to provider,
                )
            )
        )
    }

    fun emitAnimePremiumThumbnailCoordinateSelected(
        sourceKitsuId: String,
        selectedProvider: String?,
        selectedSeriesId: String?,
        selectedSeason: Int?,
        selectedEpisode: Int?,
        confidence: String,
        fallbackToPrimary: Boolean,
    ) {
        val sid = traceSessionIdForEmission()
        sink.emit(
            TraceEventEnvelope(
                traceSessionId = sid,
                sequence = seq.incrementAndGet(),
                wallClockMs = System.currentTimeMillis(),
                elapsedRealtimeMs = System.nanoTime() / 1_000_000,
                threadName = Thread.currentThread().name,
                eventType = "premium.thumbnail_coordinate_selected",
                payload = mapOf(
                    "sourceKitsuId" to sourceKitsuId,
                    "selectedProvider" to selectedProvider,
                    "selectedSeriesId" to selectedSeriesId,
                    "selectedSeason" to selectedSeason,
                    "selectedEpisode" to selectedEpisode,
                    "confidence" to confidence,
                    "fallbackToPrimary" to fallbackToPrimary,
                )
            )
        )
    }

    fun emitStreamRequestClassified(
        contentId: String,
        parentId: String,
        contentIsAnime: Boolean,
        evidence: String,
    ) {
        emitTraceEvent(
            eventType = "stream.request_classified",
            payload = mapOf(
                "contentId" to contentId,
                "parentId" to parentId,
                "contentIsAnime" to contentIsAnime,
                "evidence" to evidence,
            )
        )
    }

    fun emitStreamAddonBucketed(
        addonIdHash: String,
        addonIsAnime: Boolean,
        contentIsAnime: Boolean,
        isAnimeBucket: Boolean,
    ) {
        emitTraceEvent(
            eventType = "stream.addon_bucketed",
            payload = mapOf(
                "addonIdHash" to addonIdHash,
                "addonIsAnime" to addonIsAnime,
                "contentIsAnime" to contentIsAnime,
                "isAnimeBucket" to isAnimeBucket,
            )
        )
    }

    private fun emitHomeHydrationEvent(
        eventType: String,
        payload: Map<String, Any?>
    ) {
        emitTraceEvent(eventType, payload)
    }

    private fun emitScreensaverEvent(
        eventType: String,
        payload: Map<String, Any?>
    ) {
        emitTraceEvent(eventType, payload)
    }

    private fun emitTraceEvent(
        eventType: String,
        payload: Map<String, Any?>
    ) {
        val sid = traceSessionIdForEmission()
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

    private fun optionalTraceValue(value: String?): String = value ?: OPTIONAL_NONE

    private fun traceSessionIdForEmission(): String = sessionId() ?: LOGCAT_ONLY_TRACE_SESSION_ID

    private companion object {
        const val LOGCAT_ONLY_TRACE_SESSION_ID = "logcat-only"
        const val OPTIONAL_NONE = "none"
    }
}
