package com.nexio.tv.core.trace

import android.util.Log

interface LogcatChannelGate {
    fun isEnabled(channel: LogcatTraceChannel): Boolean
}

class LogcatRuntimeTraceSink(
    private val gate: LogcatChannelGate
) : RuntimeTraceSink {
    @Volatile private var written: Long = 0L

    override fun emit(event: TraceEventEnvelope<*>) {
        val channel = LogcatTraceChannel.forEventType(event.eventType) ?: return
        if (!gate.isEnabled(channel)) return
        Log.i(channel.tag, format(event))
        written++
    }

    override fun eventsWritten(): Long = written
    override fun eventsDropped(): Long = 0L

    private fun format(event: TraceEventEnvelope<*>): String {
        val payload = (event.payload as? Map<*, *>) ?: return baseLine(event)
        val curated = curatedFields(event.eventType, payload)
        val formatted = curated.entries.joinToString(" ") { (k, v) -> "$k=${formatValue(v)}" }
        return if (formatted.isEmpty()) baseLine(event) else "${baseLine(event)} $formatted"
    }

    private fun baseLine(event: TraceEventEnvelope<*>): String =
        "seq=${event.sequence} t=${event.eventType}"

    private fun curatedFields(eventType: String, payload: Map<*, *>): Map<String, Any?> = when (eventType) {
        "metadata.first_paint" -> linkedMapOf(
            "contentId" to payload["contentId"],
            "surface" to payload["surface"],
            "source" to payload["source"],
            "routerExecuted" to payload["routerExecuted"],
            "networkExecuted" to payload["networkExecuted"],
            "used" to payload["fieldsUsed"],
            "profile" to payload["profileHash"]
        )
        "metadata.route_decision" -> linkedMapOf(
            "contentId" to payload["contentId"],
            "provider" to payload["provider"],
            "mediaKind" to payload["mediaKind"],
            "reason" to payload["reason"]
        )
        "metadata.identity_resolution" -> linkedMapOf(
            "sourceId" to payload["sourceId"],
            "targetProvider" to payload["targetProvider"],
            "resolver" to payload["resolver"],
            "cacheDecision" to payload["cacheDecision"],
            "executedNetwork" to payload["executedNetwork"],
            "success" to payload["success"],
            "resultId" to payload["resultId"]
        )
        "metadata.provider_plan" -> linkedMapOf(
            "contentId" to payload["contentId"],
            "provider" to payload["provider"],
            "mediaKind" to payload["mediaKind"],
            "depth" to payload["depth"],
            "stepCount" to (payload["steps"] as? List<*>)?.size
        )
        "metadata.resolver_schedule" -> linkedMapOf(
            "depth" to payload["depth"],
            "scheduled" to payload["scheduled"],
            "skippedCount" to (payload["skipped"] as? Map<*, *>)?.size
        )
        "metadata.field_selected" -> linkedMapOf(
            "contentId" to payload["contentId"],
            "field" to payload["field"],
            "selectedProvider" to payload["selectedProvider"],
            "sourceRole" to payload["sourceRole"],
            "ownershipRule" to payload["ownershipRule"],
            "rejectedCount" to (payload["rejectedCandidates"] as? List<*>)?.size
        )
        "metadata.localization_plan" -> linkedMapOf(
            "contentId" to payload["contentId"],
            "provider" to payload["provider"],
            "requestedLanguage" to payload["requestedLanguage"],
            "fallbackLanguage" to payload["fallbackLanguage"],
            "requestedIsFallback" to payload["requestedIsFallback"],
            "localeCollapsedToFallback" to payload["localeCollapsedToFallback"]
        )
        "metadata.normalizer_warning" -> linkedMapOf(
            "contentId" to payload["contentId"],
            "reason" to payload["reason"]
        )
        "metadata.stable_id_bundle" -> linkedMapOf(
            "itemKey" to payload["itemKey"],
            "itemType" to payload["itemType"],
            "status" to payload["status"],
            "trigger" to payload["trigger"],
            "tmdbMovieId" to payload["tmdbMovieId"],
            "tvdbSeriesId" to payload["tvdbSeriesId"],
            "kitsuAnimeId" to payload["kitsuAnimeId"],
            "imdbId" to payload["imdbId"],
            "networkExecuted" to payload["networkExecuted"]
        )
        "metadata.trailer_preview_request" -> linkedMapOf(
            "itemId" to payload["itemId"],
            "itemType" to payload["itemType"],
            "fallbackRef" to payload["fallbackRef"],
            "published" to payload["published"],
            "negativeCached" to payload["negativeCached"],
            "forceRefresh" to payload["forceRefresh"]
        )
        "metadata.trailer_preview_state" -> linkedMapOf(
            "itemId" to payload["itemId"],
            "stage" to payload["stage"],
            "published" to payload["published"],
            "negativeCached" to payload["negativeCached"],
            "loading" to payload["loading"],
            "reason" to payload["reason"]
        )
        "metadata.trailer_surface_synced" -> linkedMapOf(
            "surface" to payload["surface"],
            "itemCount" to payload["itemCount"],
            "selectedRefCount" to payload["selectedRefCount"],
            "youtubeRefCount" to payload["youtubeRefCount"],
            "inAppRefCount" to payload["inAppRefCount"],
            "externalRefCount" to payload["externalRefCount"],
            "fallbackIdCount" to payload["fallbackIdCount"]
        )
        "metadata.trailer_lookup_result" -> linkedMapOf(
            "contentId" to payload["contentId"],
            "itemType" to payload["itemType"],
            "season" to payload["season"],
            "fallbackCount" to payload["fallbackCount"],
            "cacheDecision" to payload["cacheDecision"],
            "result" to payload["result"],
            "hasPlayback" to payload["hasPlayback"],
            "hasExternal" to payload["hasExternal"]
        )
        "media_clip.candidate_stored" -> linkedMapOf(
            "itemKey" to payload["itemKey"],
            "provider" to payload["provider"],
            "clipType" to payload["clipType"],
            "site" to payload["site"],
            "videoId" to payload["videoId"],
            "scope" to payload["scope"],
            "cacheDecision" to payload["cacheDecision"]
        )
        "media_clip.candidate_selected" -> linkedMapOf(
            "surface" to payload["surface"],
            "itemKey" to payload["itemKey"],
            "provider" to payload["provider"],
            "clipType" to payload["clipType"],
            "site" to payload["site"],
            "videoId" to payload["videoId"],
            "cacheDecision" to payload["cacheDecision"],
            "playbackUrlResolvedAtPlayTime" to payload["playbackUrlResolvedAtPlayTime"]
        )
        "metadata.trailer_autoplay_gate" -> linkedMapOf(
            "stage" to payload["stage"],
            "focusKey" to payload["focusKey"],
            "itemId" to payload["itemId"],
            "itemType" to payload["itemType"],
            "autoplay" to payload["autoplayEnabled"],
            "delay" to payload["delaySeconds"],
            "screensaver" to payload["screensaverVisible"],
            "splash" to payload["startupSplashVisible"],
            "takeover" to payload["externalTakeoverActive"],
            "stillFocused" to payload["selectionStillFocused"],
            "resumed" to payload["lifecycleResumed"],
            "unlocked" to payload["trailerPlaybackUnlocked"],
            "metadata" to payload["hasTrailerMetadata"],
            "resolved" to payload["hasResolvedPreview"],
            "external" to payload["hasResolvedExternalPreview"],
            "loading" to payload["loading"],
            "negative" to payload["negativeCached"],
            "retried" to payload["alreadyRetried"],
            "proceed" to payload["shouldProceed"],
            "reason" to payload["reason"]
        )
        "home.hydration_started" -> linkedMapOf(
            "railId" to payload["railId"],
            "itemKey" to payload["itemKey"],
            "firstPaintSource" to payload["firstPaintSource"],
            "trigger" to payload["trigger"],
            "priority" to payload["priority"],
            "workClass" to payload["workClass"]
        )
        "home.hydration_overlay_written" -> linkedMapOf(
            "itemKey" to payload["itemKey"],
            "canonicalProvider" to payload["canonicalProvider"],
            "canonicalId" to payload["canonicalId"],
            "imdbId" to payload["imdbId"],
            "displayHash" to payload["displayHash"]
        )
        "home.hydration_applied" -> linkedMapOf(
            "railId" to payload["railId"],
            "itemKey" to payload["itemKey"],
            "firstPaintSource" to payload["firstPaintSource"],
            "canonicalProvider" to payload["canonicalProvider"],
            "canonicalId" to payload["canonicalId"],
            "imdbId" to payload["imdbId"],
            "trigger" to payload["trigger"],
            "priority" to payload["priority"],
            "workClass" to payload["workClass"],
            "changedFields" to payload["changedFields"],
            "rowOrderChanged" to payload["rowOrderChanged"],
            "focusChanged" to payload["focusChanged"],
            "networkExecuted" to payload["networkExecuted"],
            "cacheDecision" to payload["cacheDecision"]
        )
        "home.hydration_ignored" -> linkedMapOf(
            "itemKey" to payload["itemKey"],
            "reason" to payload["reason"],
            "trigger" to payload["trigger"]
        )
        "home.hydration_failed_using_preview" -> linkedMapOf(
            "itemKey" to payload["itemKey"],
            "reason" to payload["reason"],
            "trigger" to payload["trigger"]
        )
        "screensaver.candidate_pool_built" -> linkedMapOf(
            "profile" to payload["profileHash"],
            "source" to payload["source"],
            "imageCandidateCount" to payload["imageCandidateCount"],
            "trailerCandidateCount" to payload["trailerCandidateCount"]
        )
        "screensaver.slide_selected" -> linkedMapOf(
            "itemKey" to payload["itemKey"],
            "source" to payload["source"],
            "ratingSource" to payload["ratingSource"],
            "artworkSource" to payload["artworkSource"],
            "matchesHomeSurface" to payload["matchesHomeSurface"]
        )
        "screensaver.trailer_candidate_selected" -> linkedMapOf(
            "itemKey" to payload["itemKey"],
            "source" to payload["source"],
            "trailerSource" to payload["trailerSource"],
            "fallbackYouTubeIdsOnly" to payload["fallbackYouTubeIdsOnly"]
        )
        "screensaver.surface_published" -> linkedMapOf(
            "surface" to payload["surface"],
            "published" to payload["published"],
            "itemCount" to payload["itemCount"],
            "logoCount" to payload["logoCount"],
            "trailerCandidateCount" to payload["trailerCandidateCount"],
            "selectedRefCount" to payload["selectedRefCount"],
            "fallbackIdCount" to payload["fallbackIdCount"]
        )
        "screensaver.scheduler_state" -> linkedMapOf(
            "stage" to payload["stage"],
            "route" to payload["route"],
            "eligible" to payload["eligible"],
            "visible" to payload["visible"],
            "slideCount" to payload["slideCount"],
            "trailerCandidateCount" to payload["trailerCandidateCount"],
            "trailerEnabled" to payload["trailerEnabled"],
            "trailerSessionReady" to payload["trailerSessionReady"],
            "lifecycleState" to payload["lifecycleState"],
            "reason" to payload["reason"]
        )
        "artwork.decision_lookup" -> linkedMapOf(
            "decisionKey" to payload["decisionKey"],
            "found" to payload["found"]
        )
        "artwork.decision_missing" -> linkedMapOf(
            "decisionKey" to payload["decisionKey"]
        )
        "artwork.decision_put" -> linkedMapOf(
            "decisionKey" to payload["decisionKey"],
            "provider" to payload["provider"],
            "imageType" to payload["imageType"],
            "sourceRole" to payload["sourceRole"],
            "rejectedCount" to payload["rejectedCount"],
            "hasFallbackCandidate" to payload["hasFallbackCandidate"]
        )
        "artwork.decision_store_load" -> linkedMapOf(
            "success" to payload["success"],
            "authoritative" to payload["authoritative"],
            "loadState" to payload["loadState"],
            "filePresent" to payload["filePresent"],
            "fileReadable" to payload["fileReadable"],
            "fileBytes" to payload["fileBytes"],
            "decisionCount" to payload["decisionCount"],
            "linkCount" to payload["linkCount"],
            "droppedDecisionCount" to payload["droppedDecisionCount"],
            "quarantinedDecisionCount" to payload["quarantinedDecisionCount"],
            "reason" to payload["reason"],
            "schemaVersion" to payload["schemaVersion"],
            "storedSchemaVersion" to payload["storedSchemaVersion"],
            "errorClass" to payload["errorClass"],
            "errorMessageHash" to payload["errorMessageHash"],
            "errorTopFrame" to payload["errorTopFrame"],
            "firstQuarantinedDecisionKeyHash" to payload["firstQuarantinedDecisionKeyHash"]
        )
        "artwork.decision_store_write" -> linkedMapOf(
            "success" to payload["success"],
            "decisionCount" to payload["decisionCount"],
            "linkCount" to payload["linkCount"],
            "errorClass" to payload["errorClass"]
        )
        "artwork.asset_materialized" -> linkedMapOf(
            "decisionKey" to payload["decisionKey"],
            "assetKey" to payload["assetKey"],
            "provider" to payload["provider"],
            "imageType" to payload["imageType"],
            "cacheDecision" to payload["cacheDecision"],
            "networkExecuted" to payload["networkExecuted"],
            "success" to payload["success"]
        )
        "artwork.fallback_materialized" -> linkedMapOf(
            "decisionKey" to payload["decisionKey"],
            "fallbackProvider" to payload["fallbackProvider"],
            "assetKey" to payload["assetKey"]
        )
        "home.snapshot_read" -> linkedMapOf(
            "success" to payload["success"],
            "profileId" to payload["profileId"],
            "snapshotFound" to payload["snapshotFound"],
            "catalogRowCount" to payload["catalogRowCount"],
            "fullCatalogRowCount" to payload["fullCatalogRowCount"],
            "heroItemCount" to payload["heroItemCount"],
            "requiredPosterProviderTag" to payload["requiredPosterProviderTag"],
            "reason" to payload["reason"],
            "errorClass" to payload["errorClass"]
        )
        "home.snapshot_write" -> linkedMapOf(
            "success" to payload["success"],
            "profileId" to payload["profileId"],
            "catalogRowCount" to payload["catalogRowCount"],
            "fullCatalogRowCount" to payload["fullCatalogRowCount"],
            "heroItemCount" to payload["heroItemCount"],
            "errorClass" to payload["errorClass"]
        )
        "home.snapshot_decision_lookup" -> linkedMapOf(
            "scope" to payload["scope"],
            "lookupResult" to payload["lookupResult"],
            "decisionFound" to payload["decisionFound"],
            "decisionKeyHash" to payload["decisionKeyHash"],
            "posterKind" to payload["posterKind"],
            "posterProviderTag" to payload["posterProviderTag"],
            "authoritative" to payload["authoritative"],
            "loadState" to payload["loadState"],
            "cacheLoaded" to payload["cacheLoaded"],
            "cacheDecisionCount" to payload["cacheDecisionCount"],
            "cacheLinkCount" to payload["cacheLinkCount"],
            "storeFilePresent" to payload["storeFilePresent"],
            "storeFileReadable" to payload["storeFileReadable"],
            "storeFileBytes" to payload["storeFileBytes"],
            "lastLoadSuccess" to payload["lastLoadSuccess"],
            "lastLoadReason" to payload["lastLoadReason"],
            "lastLoadErrorClass" to payload["lastLoadErrorClass"],
            "droppedDecisionCount" to payload["droppedDecisionCount"],
            "lookupErrorClass" to payload["lookupErrorClass"],
            "quarantinedDecisionCount" to payload["quarantinedDecisionCount"],
            "errorTopFrame" to payload["errorTopFrame"],
            "rehydrateRequestCount" to payload["rehydrateRequestCount"]
        )
        "home.snapshot_sanitize_artwork" -> linkedMapOf(
            "scope" to payload["scope"],
            "action" to payload["action"],
            "reason" to payload["reason"],
            "destructive" to payload["destructive"],
            "writeBackAllowed" to payload["writeBackAllowed"],
            "posterKind" to payload["posterKind"],
            "posterProviderTag" to payload["posterProviderTag"],
            "posterProviderTagAction" to payload["posterProviderTagAction"],
            "decisionFound" to payload["decisionFound"]
        )
        "home.snapshot_artwork_rehydrate_requested" -> linkedMapOf(
            "scope" to payload["scope"],
            "reason" to payload["reason"],
            "posterKind" to payload["posterKind"],
            "providerTag" to payload["providerTag"],
            "decisionKeyHash" to payload["decisionKeyHash"]
        )
        "screensaver.trailer_playback_resolution" -> linkedMapOf(
            "itemId" to payload["itemId"],
            "itemType" to payload["itemType"],
            "inputRef" to payload["inputRef"],
            "selectedRef" to payload["selectedRef"],
            "result" to payload["result"],
            "reason" to payload["reason"]
        )
        "runtime.trailer_playback_source" -> linkedMapOf(
            "ref" to payload["ref"],
            "result" to payload["result"],
            "hasVideo" to payload["hasVideo"],
            "hasAudio" to payload["hasAudio"],
            "hasUserAgent" to payload["hasUserAgent"]
        )
        "runtime.operation_start" -> linkedMapOf(
            "provider" to payload["provider"],
            "operationKey" to payload["operationKey"],
            "scope" to payload["scope"]
        )
        "runtime.operation_finish" -> linkedMapOf(
            "provider" to payload["provider"],
            "operationKey" to payload["operationKey"],
            "outcome" to payload["outcome"],
            "durationMs" to payload["durationMs"]
        )
        "runtime.operation_failed" -> linkedMapOf(
            "provider" to payload["provider"],
            "operationKey" to payload["operationKey"],
            "error" to payload["error"],
            "durationMs" to payload["durationMs"]
        )
        "runtime.cache_decision" -> linkedMapOf(
            "runtimeOperationId" to payload["runtimeOperationId"],
            "provider" to payload["provider"],
            "apiShapeId" to payload["apiShapeId"],
            "operationKey" to payload["operationKey"],
            "decision" to payload["decision"],
            "reason" to payload["reason"],
            "networkSuppressed" to payload["networkSuppressed"],
            "ttlMs" to payload["ttlMs"],
            "staleWindowMs" to payload["staleWindowMs"],
            "cacheKey" to payload["cacheKey"]
        )
        "http.request" -> linkedMapOf(
            "runtimeOperationId" to payload["runtimeOperationId"],
            "provider" to payload["provider"],
            "apiShapeId" to payload["apiShapeId"],
            "method" to payload["method"],
            "url" to payload["url"]
        )
        "http.response" -> linkedMapOf(
            "runtimeOperationId" to payload["runtimeOperationId"],
            "provider" to payload["provider"],
            "apiShapeId" to payload["apiShapeId"],
            "statusCode" to payload["statusCode"],
            "durationMs" to payload["durationMs"],
            "byteCount" to payload["byteCount"]
        )
        "http.error" -> linkedMapOf(
            "runtimeOperationId" to payload["runtimeOperationId"],
            "provider" to payload["provider"],
            "apiShapeId" to payload["apiShapeId"],
            "error" to payload["error"]
        )
        "trace.body_sample" -> linkedMapOf(
            "provider" to payload["provider"],
            "byteCount" to payload["byteCount"]
        )
        else -> emptyMap()
    }

    private fun formatValue(v: Any?): String = when (v) {
        null -> "null"
        is List<*> -> v.joinToString(separator = ",", prefix = "[", postfix = "]") { it?.toString() ?: "null" }
        is Map<*, *> -> v.entries.joinToString(separator = ",", prefix = "{", postfix = "}") { "${it.key}=${it.value}" }
        else -> v.toString()
    }
}
