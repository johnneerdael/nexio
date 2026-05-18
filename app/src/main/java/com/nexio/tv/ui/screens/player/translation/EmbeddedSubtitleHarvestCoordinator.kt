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
    val selectedSupportedSubRipOrdinal: Int? = null,
    val selectedAddonSubtitlePresent: Boolean,
    val autoTranslateEnabled: Boolean,
    val targetLanguage: String?,
    val settings: SubtitleTranslationSettings
)

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
        val isEligible = EmbeddedSubtitleHarvestEligibility.isEligible(
            streamUrl = state.streamUrl,
            filename = state.filename,
            selectedTrack = state.selectedTrack,
            selectedAddonSubtitlePresent = state.selectedAddonSubtitlePresent,
            autoTranslateEnabled = state.autoTranslateEnabled
        ) &&
            state.settings.enabled &&
            state.settings.apiKey.isNotBlank() &&
            targetLanguage.isNotBlank()
        val reason = if (isEligible) "eligible" else state.unsupportedReason(targetLanguage)
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
                }
            ),
            trackKey = shortSha256Hex(
                listOf(
                    track?.index?.toString().orEmpty(),
                    track?.trackId.orEmpty(),
                    track?.language.orEmpty(),
                    track?.mimeType.orEmpty(),
                    track?.codec.orEmpty(),
                    track?.name.orEmpty()
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

    private fun EmbeddedSubtitleHarvestState.unsupportedReason(targetLanguage: String): String {
        val track = selectedTrack
        return when {
            !autoTranslateEnabled -> "auto_translate_disabled"
            !settings.enabled -> "translation_settings_disabled"
            settings.apiKey.isBlank() -> "missing_api_key"
            targetLanguage.isBlank() -> "missing_target_language"
            selectedAddonSubtitlePresent -> "addon_subtitle_selected"
            track == null -> "missing_track"
            !EmbeddedSubtitleHarvestEligibility.isMatroska(streamUrl, filename) -> "not_mkv"
            !EmbeddedSubtitleHarvestEligibility.isSubRip(track) -> "unsupported_track"
            else -> "ineligible"
        }
    }
}
