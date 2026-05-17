package com.nexio.tv.ui.screens.player.translation

import android.util.Log
import com.nexio.tv.ui.screens.player.TrackInfo
import java.net.URI
import java.util.Locale

internal interface EmbeddedSubtitleHarvestDiagnosticsLogger {
    fun sessionStarted(
        session: TranslationTimelineSessionKey,
        streamUrl: String,
        track: TrackInfo?
    )

    fun sessionCancelled(session: TranslationTimelineSessionKey?, reason: String)

    fun unsupported(reason: String)
}

internal object EmbeddedSubtitleHarvestDiagnostics : EmbeddedSubtitleHarvestDiagnosticsLogger {
    const val PREFIX = "EMBEDDED_SUB_TIMELINE"
    private const val TAG = "Nexio.Player"
    private const val TIMELINE_MODE = "embedded_mkv_timeline"
    private const val RENDERER_FALLBACK_MODE = "renderer_prefetch_fallback"
    private const val RENDERER_LOOKUP_LOG_INTERVAL_MS = 10_000L
    private const val MAX_RENDERER_LOOKUP_LOG_KEYS = 2_048
    private val rendererLookupLastLogMs = LinkedHashMap<String, Long>()

    override fun sessionStarted(
        session: TranslationTimelineSessionKey,
        streamUrl: String,
        track: TrackInfo?
    ) {
        log(sessionStartedLine(session, streamUrl, track))
    }

    fun sessionStartedLine(
        session: TranslationTimelineSessionKey,
        streamUrl: String,
        track: TrackInfo?
    ): String {
        return "$PREFIX event=session_started session=${field(session.streamKey)} " +
            "translationMode=$TIMELINE_MODE streamHost=${field(hostFor(streamUrl))} " +
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

    fun harvestFailed(session: TranslationTimelineSessionKey, reason: String) {
        log(harvestFailedLine(session, reason))
    }

    fun harvestFailedLine(session: TranslationTimelineSessionKey, reason: String): String {
        return "$PREFIX event=harvest_failed session=${field(session.streamKey)} " +
            "reason=${field(reason)}"
    }

    fun cueHarvested(
        session: TranslationTimelineSessionKey,
        cueKey: TranslationTimelineCueKey?,
        sourceLanguage: String?
    ) {
        val key = cueKey ?: return
        log(cueHarvestedLine(session, key, sourceLanguage))
    }

    fun cueHarvestedLine(
        session: TranslationTimelineSessionKey,
        cueKey: TranslationTimelineCueKey?,
        sourceLanguage: String?
    ): String {
        return cueLine(
            event = "cue_harvested",
            session = session,
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
            cueKey = cueKey,
            extra = " translationMode=$TIMELINE_MODE"
        )
    }

    fun progress(
        session: TranslationTimelineSessionKey,
        harvested: Int,
        stats: TranslationTimelineStats,
        fallbackOriginal: Long
    ) {
        log(progressLine(session, harvested, stats, fallbackOriginal))
    }

    fun progressLine(
        session: TranslationTimelineSessionKey,
        harvested: Int,
        stats: TranslationTimelineStats,
        fallbackOriginal: Long
    ): String {
        return "$PREFIX event=progress session=${field(session.streamKey)} " +
            "harvested=$harvested sourceStored=${stats.sourceCueCount} " +
            "translated=${stats.translatedCueCount} pendingBackfill=${stats.pendingBackfillCount} " +
            "lookupHit=${stats.hitCount} lookupMiss=${stats.missCount} " +
            "fallbackOriginal=$fallbackOriginal"
    }

    private fun cueLine(
        event: String,
        session: TranslationTimelineSessionKey,
        cueKey: TranslationTimelineCueKey?,
        extra: String = ""
    ): String {
        return "$PREFIX event=$event session=${field(session.streamKey)} " +
            "cueTimeUs=${cueKey?.presentationTimeUs ?: -1L} " +
            "cueHash=${field(cueKey?.sourceTextHash)}$extra"
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
