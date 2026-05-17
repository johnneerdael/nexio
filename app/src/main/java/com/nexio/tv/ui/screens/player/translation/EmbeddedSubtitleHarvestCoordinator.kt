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
    val selectedAddonSubtitlePresent: Boolean,
    val autoTranslateEnabled: Boolean,
    val targetLanguage: String?,
    val settings: SubtitleTranslationSettings
)

internal class EmbeddedSubtitleHarvestCoordinator(
    private val scope: CoroutineScope,
    private val timelineStore: TranslatedSubtitleTimelineStore,
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

        if (!isEligible) {
            cancel(reason = "ineligible")
            return
        }

        val sessionKey = state.toSessionKey(targetLanguage)
        if (activeKey == sessionKey) return

        cancelJobs()
        timelineStore.beginSession(sessionKey)
        activeKey = sessionKey
        harvestJob = startHarvest(sessionKey, state)
        translateLoopJob = startTranslateLoop(sessionKey, state)
    }

    @Synchronized
    fun cancel(reason: String) {
        cancelJobs()
        timelineStore.clearActiveSession()
        activeKey = null
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
}
