package com.nexio.tv.ui.screens.player.translation

import android.util.Log
import com.nexio.tv.ui.screens.player.TrackInfo
import java.net.URI
import java.util.Locale

internal interface EmbeddedSubtitleHarvestDiagnosticsLogger {
    fun stateEvaluated(
        state: EmbeddedSubtitleHarvestState,
        eligible: Boolean,
        reason: String
    )

    fun sessionStarted(
        session: TranslationTimelineSessionKey,
        container: EmbeddedSubtitleContainer?,
        streamUrl: String,
        track: TrackInfo?
    )

    fun sessionCancelled(session: TranslationTimelineSessionKey?, reason: String)

    fun harvestCompleted(
        session: TranslationTimelineSessionKey,
        container: EmbeddedSubtitleContainer,
        harvested: Int,
        durationMs: Long
    ) = Unit

    fun unsupported(reason: String)
}

internal object EmbeddedSubtitleHarvestDiagnostics : EmbeddedSubtitleHarvestDiagnosticsLogger {
    const val PREFIX = "EMBEDDED_SUB_TIMELINE"
    private const val TAG = "Nexio.Player"
    private const val TIMELINE_MODE = "embedded_text_timeline"
    private const val RENDERER_FALLBACK_MODE = "renderer_prefetch_fallback"
    private const val RENDERER_LOOKUP_LOG_INTERVAL_MS = 10_000L
    private const val MAX_RENDERER_LOOKUP_LOG_KEYS = 2_048
    private const val STATE_LOG_INTERVAL_MS = 30_000L
    private val rendererLookupLastLogMs = LinkedHashMap<String, Long>()
    private val stateLastLogMs = LinkedHashMap<String, Long>()

    override fun stateEvaluated(
        state: EmbeddedSubtitleHarvestState,
        eligible: Boolean,
        reason: String
    ) {
        val line = stateEvaluatedLine(state, eligible, reason)
        if (shouldLogState(line)) {
            log(line)
        }
    }

    fun stateEvaluatedLine(
        state: EmbeddedSubtitleHarvestState,
        eligible: Boolean,
        reason: String
    ): String {
        val track = state.selectedTrack
        return "$PREFIX event=state eligible=$eligible reason=${field(reason)} " +
            "translationMode=${if (eligible) TIMELINE_MODE else RENDERER_FALLBACK_MODE} " +
            "streamHost=${field(hostFor(state.streamUrl))} filename=${field(state.filename)} " +
            "container=${containerField(state.container)} " +
            "autoTranslate=${state.autoTranslateEnabled} settingsEnabled=${state.settings.enabled} " +
            "hasApiKey=${state.settings.apiKey.isNotBlank()} targetLanguage=${field(state.targetLanguage)} " +
            "addonSubtitle=${state.selectedAddonSubtitlePresent} selectedTrack=${track != null} " +
            "trackIndex=${track?.index ?: -1} trackId=${field(track?.trackId)} " +
            "mime=${field(track?.mimeType)} codec=${field(track?.codec)} language=${field(track?.language)} " +
            "selectedTextOrdinal=${state.selectedSupportedTextOrdinal ?: -1}"
    }

    override fun sessionStarted(
        session: TranslationTimelineSessionKey,
        container: EmbeddedSubtitleContainer?,
        streamUrl: String,
        track: TrackInfo?
    ) {
        log(sessionStartedLine(session, container, streamUrl, track))
    }

    fun sessionStartedLine(
        session: TranslationTimelineSessionKey,
        container: EmbeddedSubtitleContainer?,
        streamUrl: String,
        track: TrackInfo?
    ): String {
        return "$PREFIX event=session_started session=${field(session.streamKey)} " +
            "container=${containerField(container)} translationMode=$TIMELINE_MODE " +
            "streamHost=${field(hostFor(streamUrl))} " +
            "trackIndex=${track?.index ?: -1} trackId=${field(track?.trackId)} " +
            "mime=${field(track?.mimeType)} language=${field(track?.language)} " +
            "trackName=${field(track?.name)}"
    }

    override fun sessionCancelled(session: TranslationTimelineSessionKey?, reason: String) {
        log(sessionCancelledLine(session, reason))
    }

    fun sessionCancelledLine(session: TranslationTimelineSessionKey?, reason: String): String {
        val sessionField = session?.streamKey?.let { " session=${field(it)}" }.orEmpty()
        return "$PREFIX event=session_cancelled$sessionField reason=${field(reason)}"
    }

    override fun unsupported(reason: String) {
        log(unsupportedLine(reason))
    }

    fun unsupportedLine(reason: String): String {
        return "$PREFIX event=unsupported translationMode=$RENDERER_FALLBACK_MODE " +
            "reason=${field(reason)}"
    }

    fun harvestFailed(
        session: TranslationTimelineSessionKey,
        container: EmbeddedSubtitleContainer?,
        reason: String
    ) {
        log(harvestFailedLine(session, container, reason))
    }

    fun harvestFailedLine(
        session: TranslationTimelineSessionKey,
        container: EmbeddedSubtitleContainer?,
        reason: String
    ): String {
        return "$PREFIX event=harvest_failed session=${field(session.streamKey)} " +
            "container=${containerField(container)} reason=${field(reason)}"
    }

    override fun harvestCompleted(
        session: TranslationTimelineSessionKey,
        container: EmbeddedSubtitleContainer,
        harvested: Int,
        durationMs: Long
    ) {
        log(harvestCompletedLine(session, container, harvested, durationMs))
    }

    fun harvestCompletedLine(
        session: TranslationTimelineSessionKey,
        container: EmbeddedSubtitleContainer,
        harvested: Int,
        durationMs: Long
    ): String {
        return "$PREFIX event=harvest_completed session=${field(session.streamKey)} " +
            "container=${containerField(container)} harvested=$harvested durationMs=$durationMs"
    }

    fun initialSeekApplied(
        session: TranslationTimelineSessionKey,
        container: EmbeddedSubtitleContainer,
        requestedTimeUs: Long,
        seekTimeUs: Long,
        seekPosition: Long,
        seekable: Boolean
    ) {
        log(initialSeekAppliedLine(session, container, requestedTimeUs, seekTimeUs, seekPosition, seekable))
    }

    fun initialSeekAppliedLine(
        session: TranslationTimelineSessionKey,
        container: EmbeddedSubtitleContainer,
        requestedTimeUs: Long,
        seekTimeUs: Long,
        seekPosition: Long,
        seekable: Boolean
    ): String {
        return "$PREFIX event=initial_seek_applied session=${field(session.streamKey)} " +
            "container=${containerField(container)} requestedTimeUs=$requestedTimeUs " +
            "seekTimeUs=$seekTimeUs seekPosition=$seekPosition seekable=$seekable"
    }

    fun cueHarvested(
        session: TranslationTimelineSessionKey,
        container: EmbeddedSubtitleContainer,
        cueKey: TranslationTimelineCueKey?,
        sourceLanguage: String?
    ) {
        val key = cueKey ?: return
        log(cueHarvestedLine(session, container, key, sourceLanguage))
    }

    fun cueHarvestedLine(
        session: TranslationTimelineSessionKey,
        container: EmbeddedSubtitleContainer,
        cueKey: TranslationTimelineCueKey?,
        sourceLanguage: String?
    ): String {
        return cueLine(
            event = "cue_harvested",
            session = session,
            container = container,
            cueKey = cueKey,
            extra = " sourceLanguage=${field(sourceLanguage)}"
        )
    }

    fun cueTranslated(
        session: TranslationTimelineSessionKey,
        cueKey: TranslationTimelineCueKey?
    ) {
        val key = cueKey ?: return
        log(cueTranslatedLine(session, key))
    }

    fun cueTranslatedLine(
        session: TranslationTimelineSessionKey,
        cueKey: TranslationTimelineCueKey?
    ): String {
        return cueLine(
            event = "cue_translated",
            session = session,
            container = null,
            cueKey = cueKey
        )
    }

    fun rendererLookup(
        session: TranslationTimelineSessionKey,
        cueKey: TranslationTimelineCueKey?,
        hit: Boolean
    ) {
        val key = cueKey ?: return
        if (!shouldLogRendererLookup(session, key, hit)) return
        log(rendererLookupLine(session, key, hit))
    }

    fun rendererLookupLine(
        session: TranslationTimelineSessionKey,
        cueKey: TranslationTimelineCueKey?,
        hit: Boolean
    ): String {
        val event = if (hit) "renderer_lookup_hit" else "renderer_lookup_miss"
        return cueLine(
            event = event,
            session = session,
            container = null,
            cueKey = cueKey,
            extra = " translationMode=$TIMELINE_MODE"
        )
    }

    fun progress(
        session: TranslationTimelineSessionKey,
        container: EmbeddedSubtitleContainer?,
        harvested: Int,
        stats: TranslationTimelineStats,
        fallbackOriginal: Long
    ) {
        log(progressLine(session, container, harvested, stats, fallbackOriginal))
    }

    fun progressLine(
        session: TranslationTimelineSessionKey,
        container: EmbeddedSubtitleContainer?,
        harvested: Int,
        stats: TranslationTimelineStats,
        fallbackOriginal: Long
    ): String {
        return "$PREFIX event=progress session=${field(session.streamKey)} " +
            "container=${containerField(container)} harvested=$harvested sourceStored=${stats.sourceCueCount} " +
            "translated=${stats.translatedCueCount} pendingBackfill=${stats.pendingBackfillCount} " +
            "lookupHit=${stats.hitCount} lookupMiss=${stats.missCount} " +
            "fallbackOriginal=$fallbackOriginal"
    }

    private fun cueLine(
        event: String,
        session: TranslationTimelineSessionKey,
        container: EmbeddedSubtitleContainer?,
        cueKey: TranslationTimelineCueKey?,
        extra: String = ""
    ): String {
        return "$PREFIX event=$event session=${field(session.streamKey)} " +
            "container=${containerField(container)} cueTimeUs=${cueKey?.presentationTimeUs ?: -1L} " +
            "cueHash=${field(cueKey?.sourceTextHash)}$extra"
    }

    private fun containerField(container: EmbeddedSubtitleContainer?): String {
        return container?.logValue ?: "unknown"
    }

    private fun log(line: String) {
        runCatching { Log.d(TAG, line) }
    }

    private fun shouldLogRendererLookup(
        session: TranslationTimelineSessionKey,
        cueKey: TranslationTimelineCueKey,
        hit: Boolean
    ): Boolean {
        val nowMs = System.currentTimeMillis()
        val lookupKey = buildString {
            append(session.streamKey)
            append('|')
            append(session.trackKey)
            append('|')
            append(session.targetLanguage)
            append('|')
            append(cueKey.presentationTimeUs)
            append('|')
            append(cueKey.sourceTextHash)
            append('|')
            append(hit)
        }
        return synchronized(rendererLookupLastLogMs) {
            val lastLogMs = rendererLookupLastLogMs[lookupKey]
            if (lastLogMs != null && nowMs - lastLogMs < RENDERER_LOOKUP_LOG_INTERVAL_MS) {
                false
            } else {
                rendererLookupLastLogMs[lookupKey] = nowMs
                while (rendererLookupLastLogMs.size > MAX_RENDERER_LOOKUP_LOG_KEYS) {
                    val firstKey = rendererLookupLastLogMs.keys.firstOrNull() ?: break
                    rendererLookupLastLogMs.remove(firstKey)
                }
                true
            }
        }
    }

    private fun shouldLogState(line: String): Boolean {
        val nowMs = System.currentTimeMillis()
        return synchronized(stateLastLogMs) {
            val lastLogMs = stateLastLogMs[line]
            if (lastLogMs != null && nowMs - lastLogMs < STATE_LOG_INTERVAL_MS) {
                false
            } else {
                stateLastLogMs[line] = nowMs
                while (stateLastLogMs.size > 64) {
                    val firstKey = stateLastLogMs.keys.firstOrNull() ?: break
                    stateLastLogMs.remove(firstKey)
                }
                true
            }
        }
    }

    private fun hostFor(streamUrl: String): String? {
        return runCatching { URI(streamUrl).host }
            .getOrNull()
            ?.lowercase(Locale.ROOT)
            ?: streamUrl.substringBefore('/').takeIf(String::isNotBlank)
    }

    private fun field(value: String?): String {
        return value
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.replace(Regex("\\s+"), "_")
            ?: "none"
    }
}
