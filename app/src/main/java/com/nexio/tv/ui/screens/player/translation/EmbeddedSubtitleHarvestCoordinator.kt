package com.nexio.tv.ui.screens.player.translation

import com.nexio.tv.domain.model.SubtitleTranslationSettings
import com.nexio.tv.ui.screens.player.TrackInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.security.MessageDigest
import java.util.Locale

internal data class EmbeddedSubtitleHarvestState(
    val streamUrl: String,
    val filename: String?,
    val headers: Map<String, String>,
    val selectedTrack: TrackInfo?,
    val selectedSupportedTextOrdinal: Int? = null,
    val selectedAddonSubtitlePresent: Boolean,
    val autoTranslateEnabled: Boolean,
    val targetLanguage: String?,
    val settings: SubtitleTranslationSettings,
    val container: EmbeddedSubtitleContainer? = EmbeddedSubtitleHarvestEligibility.containerFor(streamUrl, filename)
) {
    @Suppress("LongParameterList", "unused")
    constructor(
        streamUrl: String,
        filename: String?,
        headers: Map<String, String>,
        selectedTrack: TrackInfo?,
        selectedSupportedSubRipOrdinal: Int? = null,
        selectedAddonSubtitlePresent: Boolean,
        autoTranslateEnabled: Boolean,
        targetLanguage: String?,
        settings: SubtitleTranslationSettings,
        container: EmbeddedSubtitleContainer? = EmbeddedSubtitleHarvestEligibility.containerFor(streamUrl, filename),
        compatibilityMarker: Unit = Unit
    ) : this(
        streamUrl = streamUrl,
        filename = filename,
        headers = headers,
        selectedTrack = selectedTrack,
        selectedSupportedTextOrdinal = selectedSupportedSubRipOrdinal,
        selectedAddonSubtitlePresent = selectedAddonSubtitlePresent,
        autoTranslateEnabled = autoTranslateEnabled,
        targetLanguage = targetLanguage,
        settings = settings,
        container = container
    )

    val selectedSupportedSubRipOrdinal: Int?
        get() = selectedSupportedTextOrdinal
}

internal class EmbeddedSubtitleHarvestCoordinator(
    private val scope: CoroutineScope,
    private val timelineStore: TranslatedSubtitleTimelineStore,
    private val diagnostics: EmbeddedSubtitleHarvestDiagnosticsLogger = EmbeddedSubtitleHarvestDiagnostics,
    private val onTimelineSessionReset: () -> Unit = {},
    private val startHarvest: (
        TranslationTimelineSessionKey,
        EmbeddedSubtitleHarvestState
    ) -> Job,
    private val startTranslateLoop: (
        TranslationTimelineSessionKey,
        EmbeddedSubtitleHarvestState
    ) -> Job
) {
    private var activeKey: TranslationTimelineSessionKey? = null
    private var harvestJob: Job? = null
    private var translateLoopJob: Job? = null
    private var lastUnsupportedReason: String? = null

    @Synchronized
    fun update(state: EmbeddedSubtitleHarvestState) {
        val targetLanguage = state.targetLanguage?.trim().orEmpty()
        val baseEligibility = EmbeddedSubtitleHarvestEligibility.evaluate(
            streamUrl = state.streamUrl,
            filename = state.filename,
            selectedTrack = state.selectedTrack,
            selectedAddonSubtitlePresent = state.selectedAddonSubtitlePresent,
            autoTranslateEnabled = state.autoTranslateEnabled
        )
        val isEligible = baseEligibility.eligible &&
            state.settings.enabled &&
            state.settings.apiKey.isNotBlank() &&
            targetLanguage.isNotBlank() &&
            state.selectedSupportedTextOrdinal != null
        val reason = if (isEligible) "eligible" else state.unsupportedReason(targetLanguage, baseEligibility)
        diagnostics.stateEvaluated(
            state = state,
            eligible = isEligible,
            reason = reason
        )

        if (!isEligible) {
            if (activeKey == null && lastUnsupportedReason != reason) {
                diagnostics.unsupported(reason)
            }
            lastUnsupportedReason = reason
            cancel(reason = reason)
            return
        }

        val sessionKey = state.toSessionKey(targetLanguage)
        if (activeKey == sessionKey) return

        if (activeKey != null) {
            endActiveSession(reason = "session_changed")
        } else {
            cancelJobs()
            onTimelineSessionReset()
        }
        timelineStore.beginSession(sessionKey)
        activeKey = sessionKey
        lastUnsupportedReason = null
        diagnostics.sessionStarted(
            session = sessionKey,
            container = state.container,
            streamUrl = state.streamUrl,
            track = state.selectedTrack
        )
        harvestJob = startHarvest(sessionKey, state)
        translateLoopJob = startTranslateLoop(sessionKey, state)
    }

    @Synchronized
    fun cancel(reason: String) {
        endActiveSession(reason)
    }

    private fun endActiveSession(reason: String) {
        val cancelledKey = activeKey
        cancelJobs()
        timelineStore.clearActiveSession()
        activeKey = null
        onTimelineSessionReset()
        if (cancelledKey != null) {
            diagnostics.sessionCancelled(cancelledKey, reason)
        }
    }

    @Synchronized
    fun activeSessionKey(): TranslationTimelineSessionKey? = activeKey

    private fun cancelJobs() {
        harvestJob?.cancel()
        translateLoopJob?.cancel()
        harvestJob = null
        translateLoopJob = null
    }

    private fun EmbeddedSubtitleHarvestState.toSessionKey(
        targetLanguage: String
    ): TranslationTimelineSessionKey {
        val track = selectedTrack
        return TranslationTimelineSessionKey(
            streamKey = shortSha256Hex(
                buildString {
                    append(streamUrl.trim())
                    append('|')
                    append(filename?.trim().orEmpty())
                    append('|')
                    append(headers.toSortedMap().entries.joinToString(separator = "&") { entry ->
                        "${entry.key.trim()}=${entry.value.trim()}"
                    })
                    append('|')
                    append(container?.logValue.orEmpty())
                }
            ),
            trackKey = shortSha256Hex(
                listOf(
                    track?.index?.toString().orEmpty(),
                    track?.trackId.orEmpty(),
                    track?.language.orEmpty(),
                    track?.mimeType.orEmpty(),
                    track?.codec.orEmpty(),
                    track?.name.orEmpty(),
                    selectedSupportedTextOrdinal?.toString().orEmpty()
                ).joinToString(separator = "|")
            ),
            targetLanguage = targetLanguage.lowercase(Locale.ROOT),
            settingsKey = shortSha256Hex(
                listOf(
                    settings.provider.name,
                    settings.model,
                    settings.baseUrl,
                    settings.apiKey,
                    settings.subRipSystemPromptEnabled.toString()
                ).joinToString(separator = "|")
            )
        )
    }

    private fun shortSha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest
            .take(12)
            .joinToString(separator = "") { byte ->
                "%02x".format(Locale.US, byte.toInt() and 0xff)
            }
    }

    private fun EmbeddedSubtitleHarvestState.unsupportedReason(
        targetLanguage: String,
        baseEligibility: EmbeddedSubtitleEligibilityResult
    ): String {
        val track = selectedTrack
        return when {
            !autoTranslateEnabled -> "auto_translate_disabled"
            !settings.enabled -> "translation_settings_disabled"
            settings.apiKey.isBlank() -> "missing_api_key"
            targetLanguage.isBlank() -> "missing_target_language"
            selectedAddonSubtitlePresent -> "addon_subtitle_selected"
            track == null -> "missing_track"
            baseEligibility.container == null -> "unsupported_container"
            !EmbeddedSubtitleHarvestEligibility.isSupportedTextTrack(track) -> "unsupported_track"
            selectedSupportedTextOrdinal == null -> "missing_text_ordinal"
            else -> "ineligible"
        }
    }
}
