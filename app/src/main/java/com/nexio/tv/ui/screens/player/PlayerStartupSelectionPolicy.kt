package com.nexio.tv.ui.screens.player

import com.nexio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.nexio.tv.data.local.SUBTITLE_LANGUAGE_FORCED
import com.nexio.tv.domain.model.Subtitle
import java.util.Locale

internal fun findBestStartupAudioTrackIndex(
    audioTracks: List<TrackInfo>,
    targets: List<String>
): Int {
    for (target in targets) {
        val match = audioTracks.indexOfFirst { track ->
            audioTrackMatchesLanguage(track, target)
        }
        if (match >= 0) return match
    }
    return -1
}

internal fun audioTrackMatchesLanguage(track: TrackInfo, target: String): Boolean {
    if (PlayerSubtitleUtils.matchesLanguageCode(track.language, target)) {
        return true
    }
    val normalizedTarget = PlayerSubtitleUtils.normalizeLanguageCode(target)
    val haystack = listOfNotNull(track.name, track.language, track.trackId)
        .joinToString(" ")
        .lowercase(Locale.ROOT)
    if (haystack.isBlank()) return false
    val expectedTerms = languageMatchTerms(normalizedTarget)
    return expectedTerms.any { term -> haystack.contains(term) }
}

private fun languageMatchTerms(normalizedTarget: String): Set<String> {
    val codeTerms = setOf(
        normalizedTarget,
        normalizedTarget.replace('-', ' '),
        normalizedTarget.replace('-', '_')
    )
    val nameTerms = AVAILABLE_SUBTITLE_LANGUAGES
        .firstOrNull { PlayerSubtitleUtils.normalizeLanguageCode(it.code) == normalizedTarget }
        ?.let { language ->
            setOf(
                language.name.lowercase(Locale.ROOT),
                language.name.substringBefore(" (").lowercase(Locale.ROOT)
            )
        }
        .orEmpty()
    return codeTerms + nameTerms
}

internal sealed interface StartupSubtitleAutoSelectionDecision {
    data object None : StartupSubtitleAutoSelectionDecision
    data object DeferAddonFallback : StartupSubtitleAutoSelectionDecision
    data class Internal(
        val index: Int,
        val enableAiTranslation: Boolean
    ) : StartupSubtitleAutoSelectionDecision
    data class Addon(
        val subtitle: Subtitle,
        val enableAiTranslation: Boolean
    ) : StartupSubtitleAutoSelectionDecision
}

internal fun decideStartupSubtitleAutoSelection(
    subtitleTracks: List<TrackInfo>,
    addonSubtitles: List<Subtitle>,
    preferredLanguage: String,
    secondaryLanguage: String?,
    hasScannedTextTracksOnce: Boolean,
    playerReady: Boolean,
    aiTranslationConfigured: Boolean,
    startupPhase: Boolean
): StartupSubtitleAutoSelectionDecision {
    val normalizedPreferred = PlayerSubtitleUtils.normalizeLanguageCode(preferredLanguage)
        .takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
    val normalizedSecondary = secondaryLanguage
        ?.takeIf { it.isNotBlank() }
        ?.let(PlayerSubtitleUtils::normalizeLanguageCode)
        ?.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) }

    if (normalizedPreferred == null && normalizedSecondary == null) {
        return StartupSubtitleAutoSelectionDecision.None
    }

    if (normalizedPreferred == "forced") {
        if (!hasScannedTextTracksOnce) return StartupSubtitleAutoSelectionDecision.DeferAddonFallback
        val forcedIndex = subtitleTracks.indexOfFirst { it.isForced }
        return if (forcedIndex >= 0) {
            StartupSubtitleAutoSelectionDecision.Internal(
                index = forcedIndex,
                enableAiTranslation = false
            )
        } else {
            StartupSubtitleAutoSelectionDecision.None
        }
    }

    fun findInternal(language: String?): Int {
        if (language == null) return -1
        return findBestInternalSubtitleTrackIndexForStartup(
            subtitleTracks = subtitleTracks,
            targets = listOf(language)
        )
    }

    fun findAddon(language: String?): Subtitle? {
        if (language == null) return null
        if (!hasScannedTextTracksOnce || !playerReady) {
            return null
        }
        return addonSubtitles.firstOrNull { subtitle ->
            PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, language)
        }
    }

    val internalPreferred = findInternal(normalizedPreferred)
    if (internalPreferred >= 0) {
        return StartupSubtitleAutoSelectionDecision.Internal(
            index = internalPreferred,
            enableAiTranslation = false
        )
    }

    val addonPreferred = findAddon(normalizedPreferred)
    if (addonPreferred != null) {
        return StartupSubtitleAutoSelectionDecision.Addon(
            subtitle = addonPreferred,
            enableAiTranslation = false
        )
    }

    val internalSecondary = findInternal(normalizedSecondary)
    if (internalSecondary >= 0) {
        return StartupSubtitleAutoSelectionDecision.Internal(
            index = internalSecondary,
            enableAiTranslation = startupPhase && aiTranslationConfigured && normalizedPreferred != null
        )
    }

    val addonSecondary = findAddon(normalizedSecondary)
    if (addonSecondary != null) {
        return StartupSubtitleAutoSelectionDecision.Addon(
            subtitle = addonSecondary,
            enableAiTranslation = startupPhase && aiTranslationConfigured && normalizedPreferred != null
        )
    }

    return if (!hasScannedTextTracksOnce || !playerReady) {
        StartupSubtitleAutoSelectionDecision.DeferAddonFallback
    } else {
        StartupSubtitleAutoSelectionDecision.None
    }
}

internal fun findBestInternalSubtitleTrackIndexForStartup(
    subtitleTracks: List<TrackInfo>,
    targets: List<String>
): Int {
    for ((targetPosition, target) in targets.withIndex()) {
        if (target == SUBTITLE_LANGUAGE_FORCED) {
            val forcedIndex = subtitleTracks.indexOfFirst { it.isForced }
            if (forcedIndex >= 0) return forcedIndex
            if (targetPosition == 0) return -1
            continue
        }
        val normalizedTarget = PlayerSubtitleUtils.normalizeLanguageCode(target)
        val candidateIndexes = subtitleTracks.indices.filter { index ->
            PlayerSubtitleUtils.matchesLanguageCode(subtitleTracks[index].language, target)
        }
        if (candidateIndexes.isEmpty()) {
            if (normalizedTarget == "pt-br") {
                val brazilianFromGenericPt = findBrazilianPortugueseInGenericPtTracksForStartup(subtitleTracks)
                if (brazilianFromGenericPt >= 0) {
                    return brazilianFromGenericPt
                }
                if (targetPosition == 0) {
                    return -1
                }
            }
            continue
        }
        if (candidateIndexes.size == 1) return candidateIndexes.first()

        if (normalizedTarget == "pt" || normalizedTarget == "pt-br") {
            val tieBroken = breakPortugueseSubtitleTieForStartup(
                subtitleTracks = subtitleTracks,
                candidateIndexes = candidateIndexes,
                normalizedTarget = normalizedTarget
            )
            if (tieBroken >= 0) return tieBroken
        }
        return candidateIndexes.first()
    }
    return -1
}

private fun findBrazilianPortugueseInGenericPtTracksForStartup(
    subtitleTracks: List<TrackInfo>
): Int {
    val genericPtIndexes = subtitleTracks.indices.filter { index ->
        val trackLanguage = subtitleTracks[index].language ?: return@filter false
        PlayerSubtitleUtils.normalizeLanguageCode(trackLanguage) == "pt"
    }
    if (genericPtIndexes.isEmpty()) return -1

    return genericPtIndexes.firstOrNull { index ->
        subtitleHasAnyTagForStartup(subtitleTracks[index], PlayerRuntimeController.PORTUGUESE_BRAZILIAN_TAGS) &&
            !subtitleHasAnyTagForStartup(subtitleTracks[index], PlayerRuntimeController.PORTUGUESE_EUROPEAN_TAGS)
    } ?: genericPtIndexes.firstOrNull { index ->
        subtitleHasAnyTagForStartup(subtitleTracks[index], PlayerRuntimeController.PORTUGUESE_BRAZILIAN_TAGS)
    } ?: -1
}

private fun breakPortugueseSubtitleTieForStartup(
    subtitleTracks: List<TrackInfo>,
    candidateIndexes: List<Int>,
    normalizedTarget: String
): Int {
    fun hasBrazilianTags(index: Int): Boolean {
        return subtitleHasAnyTagForStartup(subtitleTracks[index], PlayerRuntimeController.PORTUGUESE_BRAZILIAN_TAGS)
    }

    fun hasEuropeanTags(index: Int): Boolean {
        return subtitleHasAnyTagForStartup(subtitleTracks[index], PlayerRuntimeController.PORTUGUESE_EUROPEAN_TAGS)
    }

    return if (normalizedTarget == "pt-br") {
        candidateIndexes.firstOrNull { hasBrazilianTags(it) && !hasEuropeanTags(it) }
            ?: candidateIndexes.firstOrNull { hasBrazilianTags(it) }
            ?: candidateIndexes.first()
    } else {
        candidateIndexes.firstOrNull { hasEuropeanTags(it) && !hasBrazilianTags(it) }
            ?: candidateIndexes.firstOrNull { hasEuropeanTags(it) }
            ?: candidateIndexes.firstOrNull { !hasBrazilianTags(it) }
            ?: candidateIndexes.first()
    }
}

private fun subtitleHasAnyTagForStartup(track: TrackInfo, tags: List<String>): Boolean {
    val haystack = listOfNotNull(track.name, track.language, track.trackId)
        .joinToString(" ")
        .lowercase(Locale.ROOT)
    return tags.any { tag -> haystack.contains(tag) }
}
