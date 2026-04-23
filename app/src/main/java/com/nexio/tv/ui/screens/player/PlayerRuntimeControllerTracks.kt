package com.nexio.tv.ui.screens.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.Util
import androidx.media3.common.util.UnstableApi
import com.nexio.tv.core.player.AndroidFrameRateSettings
import com.nexio.tv.core.player.FrameRateUtils
import com.nexio.tv.core.player.resolveDolbyVisionProfileFromCodecString
import com.nexio.tv.data.local.AudioLanguageOption
import com.nexio.tv.data.local.SUBTITLE_LANGUAGE_FORCED
import com.nexio.tv.domain.model.Subtitle
import com.nexio.tv.ui.screens.player.ass.isEmbeddedAssSsaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

internal fun PlayerRuntimeController.updateAvailableTracks(tracks: Tracks) {
    val audioTracks = mutableListOf<TrackInfo>()
    val subtitleTracks = mutableListOf<TrackInfo>()
    var selectedAudioIndex = -1
    var selectedSubtitleIndex = -1
    var videoWidth = 0
    var videoHeight = 0
    var videoCodec: String? = null
    var hdrType: String? = null
    var selectedAudioCodec: String? = null
    var selectedAudioChannelLayout: String? = null
    var selectedAudioMimeType: String? = null
    var selectedAudioCodecs: String? = null
    var selectedAudioLanguage: String? = null
    var hasVideoTrack = false
    var firstVideoFormat: Format? = null
    var selectedVideoFormat: Format? = null
    var bestVideoTrackSupport = C.FORMAT_UNSUPPORTED_TYPE
    var selectedVideoTrackSupport = C.FORMAT_UNSUPPORTED_TYPE

    tracks.groups.forEachIndexed { groupIndex, trackGroup ->
        val trackType = trackGroup.type
        
        when (trackType) {
            C.TRACK_TYPE_VIDEO -> {
                if (trackGroup.length > 0) {
                    hasVideoTrack = true
                    if (firstVideoFormat == null) {
                        firstVideoFormat = trackGroup.getTrackFormat(0)
                    }
                }
                
                for (i in 0 until trackGroup.length) {
                    val support = trackGroup.getTrackSupport(i)
                    if (formatSupportRank(support) > formatSupportRank(bestVideoTrackSupport)) {
                        bestVideoTrackSupport = support
                    }
                    if (trackGroup.isTrackSelected(i)) {
                        val format = trackGroup.getTrackFormat(i)
                        selectedVideoFormat = format
                        selectedVideoTrackSupport = support
                        val vWidth = format.width.takeIf { it > 0 } ?: 0
                        val vHeight = format.height.takeIf { it > 0 } ?: 0
                        if (vWidth > 0 && vHeight > 0) {
                            videoWidth = vWidth
                            videoHeight = vHeight
                        }
                        videoCodec = CustomDefaultTrackNameProvider.formatNameFromMime(format.sampleMimeType)
                            ?: CustomDefaultTrackNameProvider.formatNameFromMime(format.codecs)
                        val colorInfo = format.colorInfo
                        if (colorInfo != null) {
                            val isDolbyVision = format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION ||
                                format.codecs?.startsWith("dvh", ignoreCase = true) == true ||
                                format.codecs?.startsWith("dvhe", ignoreCase = true) == true
                            hdrType = when {
                                isDolbyVision -> "Dolby Vision"
                                colorInfo.colorTransfer == C.COLOR_TRANSFER_ST2084 -> {
                                    if (format.codecs?.contains("hev1.2.4", ignoreCase = true) == true) {
                                        "HDR10+"
                                    } else {
                                        "HDR10"
                                    }
                                }
                                colorInfo.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
                                else -> null
                            }
                        }
                        if (format.frameRate > 0f) {
                            val raw = format.frameRate
                            val snapped = FrameRateUtils.snapToStandardRate(raw)
                            val ambiguousCinemaTrack = PlayerFrameRateHeuristics.isAmbiguousCinema24(raw)
                            if (!ambiguousCinemaTrack) {
                                frameRateProbeJob?.cancel()
                            }
                            _uiState.update {
                                it.copy(
                                    detectedFrameRateRaw = raw,
                                    detectedFrameRate = snapped,
                                    detectedFrameRateSource = FrameRateSource.TRACK
                                )
                            }
                            maybeApplyTrackBasedAfrFallback(
                                rawFrameRate = raw,
                                snappedFrameRate = snapped,
                                videoWidth = vWidth,
                                videoHeight = vHeight
                            )
                        }
                        break
                    }
                }
            }
            C.TRACK_TYPE_AUDIO -> {
                for (i in 0 until trackGroup.length) {
                    val format = trackGroup.getTrackFormat(i)
                    val isSelected = trackGroup.isTrackSelected(i)
                    if (isSelected) {
                        selectedAudioIndex = audioTracks.size
                        selectedAudioCodec = CustomDefaultTrackNameProvider.formatNameFromMime(format.sampleMimeType)
                        selectedAudioChannelLayout = CustomDefaultTrackNameProvider.getChannelLayoutName(
                            format.channelCount
                        )
                        selectedAudioMimeType = format.sampleMimeType
                        selectedAudioCodecs = format.codecs
                        selectedAudioLanguage = format.language
                    }

                    
                    val codecName = CustomDefaultTrackNameProvider.formatNameFromMime(format.sampleMimeType)
                    val channelLayout = CustomDefaultTrackNameProvider.getChannelLayoutName(
                        format.channelCount
                    )
                    val baseName = format.label ?: format.language ?: "Audio ${audioTracks.size + 1}"
                    val suffix = listOfNotNull(codecName, channelLayout).joinToString(" ")
                    val displayName = if (suffix.isNotEmpty()) "$baseName ($suffix)" else baseName

                    audioTracks.add(
                        TrackInfo(
                            index = audioTracks.size,
                            name = displayName,
                            language = format.language,
                            trackId = format.id,
                            codec = codecName,
                            channelCount = format.channelCount.takeIf { it > 0 },
                            isSelected = isSelected
                        )
                    )
                }
            }
            C.TRACK_TYPE_TEXT -> {
                for (i in 0 until trackGroup.length) {
                    val format = trackGroup.getTrackFormat(i)
                    // Skip addon subtitle tracks — they are managed separately
                    if (format.id?.contains(PlayerRuntimeController.ADDON_SUBTITLE_TRACK_ID_PREFIX) == true) continue
                    val isSelected = trackGroup.isTrackSelected(i)
                    if (isSelected) {
                        selectedSubtitleIndex = subtitleTracks.size
                        if (format.isEmbeddedAssSsaFormat()) {
                            assSsaRenderController?.selectTrackByFormat(format)
                        }
                    }
                    
                    val hasForcedFlag = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0
                    val trackTexts = listOfNotNull(format.label, format.language, format.id)
                    val nameHintForced = trackTexts.any { it.contains("forced", ignoreCase = true) }
                    val isSongsAndSigns = trackTexts.any {
                        it.contains("songs", ignoreCase = true) && it.contains("sign", ignoreCase = true)
                    }

                    subtitleTracks.add(
                        TrackInfo(
                            index = subtitleTracks.size,
                            name = format.label ?: format.language ?: "Subtitle ${subtitleTracks.size + 1}",
                            language = format.language,
                            trackId = format.id,
                            isForced = hasForcedFlag || nameHintForced || isSongsAndSigns,
                            isSelected = isSelected,
                            mimeType = format.sampleMimeType
                        )
                    )
                }
            }
        }
    }

    currentStreamHasVideoTrack = hasVideoTrack
    val effectiveVideoFormat = selectedVideoFormat ?: firstVideoFormat
    if (effectiveVideoFormat != null) {
        currentVideoTrackMimeType = effectiveVideoFormat.sampleMimeType
        currentVideoTrackCodecs = effectiveVideoFormat.codecs
        currentVideoTrackWidth = effectiveVideoFormat.width.coerceAtLeast(0)
        currentVideoTrackHeight = effectiveVideoFormat.height.coerceAtLeast(0)
        currentVideoTrackIsLikelyDv5 = isDolbyVisionProfile5VideoFormat(effectiveVideoFormat.codecs)
        currentVideoTrackSelected = selectedVideoFormat != null
        currentVideoTrackBestSupport = if (selectedVideoFormat != null) {
            selectedVideoTrackSupport
        } else {
            bestVideoTrackSupport
        }
        currentVideoTrackIsLikelyVc1 = isLikelyVc1VideoFormat(
            sampleMimeType = effectiveVideoFormat.sampleMimeType,
            codecs = effectiveVideoFormat.codecs,
            label = effectiveVideoFormat.label
        )
        val videoTrackSignature = buildString {
            append(currentVideoTrackMimeType ?: "unknown")
            append('|')
            append(currentVideoTrackCodecs ?: "unknown")
            append('|')
            append(currentVideoTrackWidth)
            append('x')
            append(currentVideoTrackHeight)
            append("|dv5=")
            append(currentVideoTrackIsLikelyDv5)
            append("|vc1=")
            append(currentVideoTrackIsLikelyVc1)
            append("|selected=")
            append(currentVideoTrackSelected)
            append("|support=")
            append(Util.getFormatSupportString(currentVideoTrackBestSupport))
            append("|dv5ToneMapSetting=")
            append(isDv5SoftwareToneMapSettingEnabledForCurrentPlayback)
            append("|dv5HwToneMapSetting=")
            append(isDv5HardwareToneMapSettingEnabledForCurrentPlayback)
            append("|dv5HwToneMapNativeSupported=")
            append(isDv5HardwareToneMapNativeSupportedForCurrentPlayback)
            append("|dv5ToneMapNativeSupported=")
            append(isDv5SoftwareToneMapNativeSupportedForCurrentPlayback)
            append("|dvDisplayCapable=")
            append(isCurrentDisplayDolbyVisionCapable)
            append("|shieldDevice=")
            append(isCurrentDeviceNvidiaShield)
            append("|dv5HwToneMapActive=")
            append(isDv5HardwareToneMapActiveForCurrentPlayback)
            append("|dv5ToneMapActive=")
            append(isDv5SoftwareToneMapActiveForCurrentPlayback)
            append("|vc1Fallback=")
            append(isVc1SoftwareFallbackActiveForCurrentPlayback)
            append("|vc1TrackBypass=")
            append(isVc1TrackSelectionBypassActiveForCurrentPlayback)
        }
        if (videoTrackSignature != lastLoggedVideoTrackSignature) {
            lastLoggedVideoTrackSignature = videoTrackSignature
            Log.i(
                PlayerRuntimeController.TAG,
                "VIDEO_TRACK: mime=${currentVideoTrackMimeType ?: "unknown"} " +
                    "codecs=${currentVideoTrackCodecs ?: "unknown"} " +
                    "size=${currentVideoTrackWidth}x${currentVideoTrackHeight} " +
                    "dv5=$currentVideoTrackIsLikelyDv5 " +
                    "vc1=$currentVideoTrackIsLikelyVc1 " +
                    "selected=$currentVideoTrackSelected " +
                    "support=${Util.getFormatSupportString(currentVideoTrackBestSupport)} " +
                    "dv5ToneMapSetting=$isDv5SoftwareToneMapSettingEnabledForCurrentPlayback " +
                    "dv5HwToneMapSetting=$isDv5HardwareToneMapSettingEnabledForCurrentPlayback " +
                    "dv5HwToneMapNativeSupported=$isDv5HardwareToneMapNativeSupportedForCurrentPlayback " +
                    "dv5ToneMapNativeSupported=$isDv5SoftwareToneMapNativeSupportedForCurrentPlayback " +
                    "dvDisplayCapable=$isCurrentDisplayDolbyVisionCapable " +
                    "shieldDevice=$isCurrentDeviceNvidiaShield " +
                    "dv5HwToneMapActive=$isDv5HardwareToneMapActiveForCurrentPlayback " +
                    "dv5ToneMapActive=$isDv5SoftwareToneMapActiveForCurrentPlayback " +
                    "vc1FallbackActive=$isVc1SoftwareFallbackActiveForCurrentPlayback " +
                    "vc1TrackBypassActive=$isVc1TrackSelectionBypassActiveForCurrentPlayback"
            )
        }
        if (currentVideoTrackIsLikelyDv5 &&
            isDv5HardwareToneMapSettingEnabledForCurrentPlayback &&
            isDv5HardwareToneMapNativeSupportedForCurrentPlayback &&
            isCurrentDeviceNvidiaShield &&
            !isCurrentDisplayDolbyVisionCapable &&
            !isDv5HardwareToneMapActiveForCurrentPlayback
        ) {
            val currentPosition = backendCurrentPosition()
            dv5HardwareToneMapPreferredStreamUrls.add(currentStreamUrl)
            dv5SoftwareToneMapPreferredStreamUrls.remove(currentStreamUrl)
            Log.w(
                PlayerRuntimeController.TAG,
                "VIDEO_TRACK: likely DV5 on Shield non-DV display, retrying with hardware tone-map path " +
                    "host=${Uri.parse(currentStreamUrl).host ?: "unknown"} positionMs=$currentPosition"
            )
            retryCurrentStreamWithDv5HardwareToneMap(currentPosition)
            return
        }
        if (currentVideoTrackIsLikelyDv5 &&
            isDv5SoftwareToneMapSettingEnabledForCurrentPlayback &&
            !isDv5HardwareToneMapSettingEnabledForCurrentPlayback &&
            isDv5SoftwareToneMapNativeSupportedForCurrentPlayback &&
            !isCurrentDisplayDolbyVisionCapable &&
            !isDv5HardwareToneMapActiveForCurrentPlayback &&
            !isDv5SoftwareToneMapActiveForCurrentPlayback
        ) {
            val currentPosition = backendCurrentPosition()
            dv5SoftwareToneMapPreferredStreamUrls.add(currentStreamUrl)
            Log.w(
                PlayerRuntimeController.TAG,
                "VIDEO_TRACK: likely DV5 on non-DV display, retrying with FFmpeg/software path " +
                    "host=${Uri.parse(currentStreamUrl).host ?: "unknown"} positionMs=$currentPosition"
            )
            retryCurrentStreamWithDv5SoftwareToneMap(currentPosition)
            return
        }
        if (currentVideoTrackIsLikelyVc1 &&
            !currentVideoTrackSelected &&
            isVc1SoftwareFallbackActiveForCurrentPlayback &&
            !isVc1TrackSelectionBypassActiveForCurrentPlayback
        ) {
            val currentPosition = backendCurrentPosition()
            vc1TrackSelectionBypassStreamUrls.add(currentStreamUrl)
            Log.w(
                PlayerRuntimeController.TAG,
                    "VIDEO_TRACK: VC-1 track present but unselected after software-preferred retry, " +
                        "forcing track-selection bypass support=${Util.getFormatSupportString(currentVideoTrackBestSupport)} " +
                    "host=${Uri.parse(currentStreamUrl).host ?: "unknown"} positionMs=$currentPosition"
            )
            retryCurrentStreamWithVc1TrackSelectionBypass(currentPosition)
            return
        }
    } else {
        currentVideoTrackMimeType = null
        currentVideoTrackCodecs = null
        currentVideoTrackWidth = 0
        currentVideoTrackHeight = 0
        currentVideoTrackIsLikelyDv5 = false
        currentVideoTrackSelected = false
        currentVideoTrackBestSupport = C.FORMAT_UNSUPPORTED_TYPE
        currentVideoTrackIsLikelyVc1 = false
        lastLoggedVideoTrackSignature = null
    }

    hasScannedTextTracksOnce = true
    Log.d(
        PlayerRuntimeController.TAG,
        "TRACKS updated: internalSubs=${subtitleTracks.size}, selectedInternalIndex=$selectedSubtitleIndex, " +
            "selectedAddon=${_uiState.value.selectedAddonSubtitle?.lang}, " +
            "pendingAddonLang=$pendingAddonSubtitleLanguage, pendingAddonTrackId=$pendingAddonSubtitleTrackId"
    )

    val pendingAddonTrackId = pendingAddonSubtitleTrackId
    if (!pendingAddonTrackId.isNullOrBlank()) {
        Log.i(
            PlayerRuntimeController.TAG,
            "ADDON_SUB: onTracksChanged pendingTrackId=$pendingAddonTrackId " +
                "internalSubs=${subtitleTracks.size} " +
                "subTracks=${subtitleTracks.map { "${it.language ?: "und"}|${it.trackId ?: "?"}|${it.name}" }}"
        )
        if (applyAddonSubtitleOverride(pendingAddonTrackId)) {
            Log.i(PlayerRuntimeController.TAG, "ADDON_SUB: pending track applied id=$pendingAddonTrackId")
            pendingAddonSubtitleTrackId = null
            pendingAddonSubtitleLanguage = null
        } else {
            Log.w(
                PlayerRuntimeController.TAG,
                "ADDON_SUB: pending track id=$pendingAddonTrackId not yet present in track list"
            )
        }
    }

    val pendingLang = pendingAddonSubtitleLanguage
    if (
        pendingAddonSubtitleTrackId.isNullOrBlank() &&
        pendingLang != null &&
        subtitleTracks.isNotEmpty() &&
        _uiState.value.selectedAddonSubtitle == null
    ) {
        val preferredIndex = findBestInternalSubtitleTrackIndex(
            subtitleTracks = subtitleTracks,
            targets = listOf(pendingLang)
        )
        if (preferredIndex >= 0) {
            selectSubtitleTrack(preferredIndex)
            selectedSubtitleIndex = preferredIndex
        } else {
            Log.d(
                PlayerRuntimeController.TAG,
                "Skipping pending subtitle track switch: no text track matches language=$pendingLang"
            )
        }
        pendingAddonSubtitleLanguage = null
    }

    logStartupAudioDiagnosis(audioTracks, selectedAudioIndex)
    maybeApplyRememberedAudioSelection(audioTracks)
    maybeRestorePendingAudioSelectionAfterSubtitleRefresh(audioTracks)?.let { restoredIndex ->
        selectedAudioIndex = restoredIndex
    }
    maybeApplyStartupPreferredAudioSelection(
        audioTracks = audioTracks,
        currentSelectedIndex = selectedAudioIndex
    )?.let { startupIndex ->
        selectedAudioIndex = startupIndex
    }
    if (selectedAudioIndex in audioTracks.indices) {
        val selectedAudioTrack = audioTracks[selectedAudioIndex]
        selectedAudioCodec = selectedAudioTrack.codec
        selectedAudioChannelLayout = CustomDefaultTrackNameProvider.getChannelLayoutName(
            selectedAudioTrack.channelCount ?: 0
        )
        val audioTrackSignature = buildString {
            append(selectedAudioMimeType ?: "unknown")
            append('|')
            append(selectedAudioCodecs ?: "unknown")
            append('|')
            append(selectedAudioLanguage ?: "und")
            append('|')
            append(selectedAudioTrack.channelCount ?: 0)
            append('|')
            append(selectedAudioCodec ?: "unknown")
            append('|')
            append(selectedAudioChannelLayout ?: "unknown")
        }
        if (audioTrackSignature != lastLoggedAudioTrackSignature) {
            lastLoggedAudioTrackSignature = audioTrackSignature
            Log.i(
                PlayerRuntimeController.TAG,
                "AUDIO_TRACK: mime=${selectedAudioMimeType ?: "unknown"} " +
                    "codecs=${selectedAudioCodecs ?: "unknown"} " +
                    "lang=${selectedAudioLanguage ?: "und"} " +
                    "channels=${selectedAudioTrack.channelCount ?: 0} " +
                    "codec=${selectedAudioCodec ?: "unknown"} " +
                    "layout=${selectedAudioChannelLayout ?: "unknown"} " +
                    "selectedIndex=$selectedAudioIndex"
            )
        }
    }

    _uiState.update {
        it.copy(
            audioTracks = audioTracks,
            subtitleTracks = subtitleTracks,
            selectedAudioTrackIndex = selectedAudioIndex,
            selectedSubtitleTrackIndex = selectedSubtitleIndex,
            videoResolutionWidth = videoWidth,
            videoResolutionHeight = videoHeight,
            videoCodecName = videoCodec,
            videoHdrType = hdrType,
            audioCodecName = selectedAudioCodec,
            audioChannelLayout = selectedAudioChannelLayout
        )
    }
    if (currentStreamHasVideoTrack) {
        maybeScheduleFirstFrameWatchdog()
    } else {
        cancelFirstFrameWatchdog()
    }
    tryAutoSelectPreferredSubtitleFromAvailableTracks()
    maybeAdjustAssSsaPipelineForTracks(tracks)
}

/**
 * Always-on diagnostic that runs on every track refresh, regardless of
 * whether the startup picker actually does work this round (e.g. when
 * `hasAppliedRememberedAudioSelection` or `autoAudioSelected` short-circuits
 * the apply path). It logs:
 *   - what the user's preference / target list resolves to
 *   - which track the startup algorithm WOULD pick from scratch (a pure
 *     simulation, no state mutation)
 *   - which track is currently actually selected
 *   - the gating flags so we can see why the picker did or didn't fire
 *   - the full track list (lang|name) so we can validate inference
 *
 * Use `adb logcat | grep AUDIO_STARTUP_EVAL` to spot stale-cache hijacks
 * (current ≠ wouldPick) and language tagging issues (wouldPick=-1 with
 * resolvedTargets non-empty means none of our targets matched any track).
 */
internal fun PlayerRuntimeController.logStartupAudioDiagnosis(
    audioTracks: List<TrackInfo>,
    currentSelectedIndex: Int
) {
    if (audioTracks.isEmpty()) return
    val preferredAudioLanguages = resolvePreferredAudioLanguages(
        preferredAudioLanguage = lastPreferredAudioLanguage,
        secondaryPreferredAudioLanguage = lastSecondaryPreferredAudioLanguage,
        deviceLanguages = run {
            val localeList = context.resources.configuration.locales
            List(localeList.size()) { localeList[it].isO3Language }
        },
        originalLanguage = originalLanguage
    )
    val capability = detectStartupAudioCapabilitySupport()
    val wouldPickIndex: Int = when {
        preferredAudioLanguages.isNotEmpty() -> findBestStartupAudioTrackIndex(
            audioTracks = audioTracks,
            targets = preferredAudioLanguages,
            originalLanguage = originalLanguage,
            capabilitySupport = capability
        )
        lastPreferredAudioLanguage.trim().equals(AudioLanguageOption.ORIGINAL, ignoreCase = true) ->
            findOriginalTrackFallbackIndex(audioTracks)
        else -> -1
    }
    fun describe(index: Int): String {
        val track = audioTracks.getOrNull(index) ?: return "<none>"
        return "${track.language ?: "und"}|${track.name}"
    }
    Log.i(
        PlayerRuntimeController.TAG,
        "AUDIO_STARTUP_EVAL: pref=$lastPreferredAudioLanguage origLang=$originalLanguage " +
            "targets=$preferredAudioLanguages " +
            "wouldPick=[$wouldPickIndex]${describe(wouldPickIndex)} " +
            "current=[$currentSelectedIndex]${describe(currentSelectedIndex)} " +
            "autoSelected=$autoAudioSelected " +
            "rememberedApplied=$hasAppliedRememberedAudioSelection " +
            "rememberedLang=$rememberedAudioLanguage " +
            "tracks=${audioTracks.mapIndexed { i, t -> "[$i]${t.language ?: "und"}|${t.name}" }}"
    )
}

internal fun PlayerRuntimeController.maybeApplyStartupPreferredAudioSelection(
    audioTracks: List<TrackInfo>,
    currentSelectedIndex: Int
): Int? {
    if (autoAudioSelected) return null
    if (audioTracks.isEmpty()) return null
    if (isSafeAudioModeActiveForCurrentPlayback) {
        autoAudioSelected = true
        Log.i(
            PlayerRuntimeController.TAG,
            "AUDIO_STARTUP: Safe audio mode active; preserving device-constrained " +
                "selection index=$currentSelectedIndex"
        )
        return null
    }
    if (hasAppliedRememberedAudioSelection) {
        autoAudioSelected = true
        return null
    }

    val preferredAudioLanguages = resolvePreferredAudioLanguages(
        preferredAudioLanguage = lastPreferredAudioLanguage,
        secondaryPreferredAudioLanguage = lastSecondaryPreferredAudioLanguage,
        deviceLanguages = run {
            val localeList = context.resources.configuration.locales
            List(localeList.size()) { localeList[it].isO3Language }
        },
        originalLanguage = originalLanguage
    )
    Log.i(
        PlayerRuntimeController.TAG,
        "AUDIO_STARTUP: pref=$lastPreferredAudioLanguage origLang=$originalLanguage " +
            "resolvedTargets=$preferredAudioLanguages " +
            "tracks=${audioTracks.map { "${it.language ?: "und"}|${it.name}" }}"
    )

    if (preferredAudioLanguages.isEmpty()) {
        // Preference is "Original language" but no usable metadata is
        // available (addon returned null / display name could not be
        // normalized). Avoid silently letting Media3 play track 0 (often a
        // dubbed foreign track) — run an original-track fallback that uses
        // naming conventions to locate a likely ORIGINAL / non-dubbed track.
        if (lastPreferredAudioLanguage.trim().equals(AudioLanguageOption.ORIGINAL, ignoreCase = true)) {
            val fallbackIndex = findOriginalTrackFallbackIndex(audioTracks)
            if (fallbackIndex >= 0) {
                autoAudioSelected = true
                if (fallbackIndex == currentSelectedIndex) return fallbackIndex
                selectAudioTrack(fallbackIndex)
                Log.i(
                    PlayerRuntimeController.TAG,
                    "AUDIO_STARTUP: Original-language fallback selected trackIndex=$fallbackIndex"
                )
                return fallbackIndex
            }
        }
        autoAudioSelected = true
        return null
    }

    val bestIndex = resolveStartupAudioSelectionIndex(
        audioTracks = audioTracks,
        targets = preferredAudioLanguages,
        currentSelectedIndex = currentSelectedIndex,
        originalLanguage = originalLanguage,
        capabilitySupport = detectStartupAudioCapabilitySupport()
    )
    autoAudioSelected = true
    if (bestIndex < 0) return null
    if (bestIndex == currentSelectedIndex) return bestIndex

    selectAudioTrack(bestIndex)
    return bestIndex
}

/**
 * Last-resort picker used when the user selected "Original language" but no
 * usable original-language metadata was available. Scores each track for
 * "looks like an original, not dubbed" signal using the existing naming
 * convention classifier, and returns the best candidate — or -1 when no
 * confident choice can be made (in which case we leave selection alone).
 */
private fun findOriginalTrackFallbackIndex(audioTracks: List<TrackInfo>): Int {
    if (audioTracks.isEmpty()) return -1

    data class Scored(val index: Int, val score: Int)

    val scored = audioTracks.mapIndexedNotNull { index, track ->
        val trackTypes = PlayerAudioTrackNamingConventions.trackTypes(track)
        // Reject commentary/descriptive/voice-over tracks outright.
        if (trackTypes.any { it == AudioTrackType.COMMENTARY ||
                it == AudioTrackType.DESCRIPTIVE_AUDIO ||
                it == AudioTrackType.ISOLATED_SCORE ||
                it == AudioTrackType.VOICEOVER }
        ) {
            return@mapIndexedNotNull null
        }
        var score = 0
        if (AudioTrackType.ORIGINAL in trackTypes) score += 100
        if (AudioTrackType.DUBBED in trackTypes) score -= 100
        val haystack = listOfNotNull(track.name, track.trackId)
            .joinToString(" ")
            .lowercase(Locale.ROOT)
        if (Regex("""\b(main|default|original|orig)\b""").containsMatchIn(haystack)) score += 20
        if (Regex("""\b(dub|dubbed)\b""").containsMatchIn(haystack)) score -= 40
        // Prefer English as the statistical original for most addon content
        // when nothing else distinguishes the tracks. This only kicks in when
        // all other signals are neutral.
        if (PlayerSubtitleUtils.normalizeLanguageCode(track.language ?: "") == "en") score += 5
        Scored(index, score)
    }

    val best = scored.maxByOrNull { it.score } ?: return -1
    // Only commit to a fallback when at least one signal fired — otherwise
    // leave Media3's default in place rather than guess blindly.
    return if (best.score > 0) best.index else -1
}

internal fun PlayerRuntimeController.detectStartupAudioCapabilitySupport(): StartupAudioCapabilitySupport {
    val detected = androidx.media3.exoplayer.audio.AudioCapabilities.getCapabilities(
        context,
        androidx.media3.common.AudioAttributes.DEFAULT,
        null
    )
    return StartupAudioCapabilitySupport(
        ac3Supported = detected.supportsEncoding(C.ENCODING_AC3),
        eac3Supported = detected.supportsEncoding(C.ENCODING_E_AC3) ||
            detected.supportsEncoding(C.ENCODING_E_AC3_JOC),
        truehdSupported = detected.supportsEncoding(C.ENCODING_DOLBY_TRUEHD),
        dtsSupported = detected.supportsEncoding(C.ENCODING_DTS),
        dtshdSupported = detected.supportsEncoding(C.ENCODING_DTS_HD),
        aacSupported = true,
        unknownSupported = true
    )
}

internal fun PlayerRuntimeController.maybeAdjustAssSsaPipelineForTracks(tracks: Tracks) {
    if (assSsaPipelineSwitchInFlight) return

    val desiredUseAssSsaPipeline = tracks.hasSelectedAssSsaTextTrack()
    val adjustment = resolveAssSsaPipelineTrackAdjustment(
        desiredUseAssSsaPipeline = desiredUseAssSsaPipeline,
        activePlayerUsesAssSsaRenderer = activePlayerUsesAssSsaRenderer,
        fallbackHandled = assSsaPipelineFallbackHandledForCurrentStream
    )
    adjustment.overrideForCurrentStream?.let { assSsaPipelineOverrideForCurrentStream = it }
    _uiState.update {
        it.copy(useAssSsaRenderOverlay = desiredUseAssSsaPipeline && activePlayerUsesAssSsaRenderer)
    }
    if (adjustment.shouldReinitializePlayer) {
        assSsaPipelineSwitchInFlight = true
        val fromPositionMs = backendCurrentPosition()
        Log.i(
            PlayerRuntimeController.TAG,
            "ASS_SSA_RENDER: selected after player init; reinitializing playback " +
                "positionMs=$fromPositionMs"
        )
        scheduleDeferredPlayerReinitialize(fromPositionMs = fromPositionMs)
        return
    }
    if (desiredUseAssSsaPipeline && !activePlayerUsesAssSsaRenderer && !adjustment.shouldReinitializePlayer) {
        Log.w(
            PlayerRuntimeController.TAG,
            "ASS_SSA_RENDER: selected after player init; keeping current playback to avoid reinitialization"
        )
    }
}

private fun Tracks.hasSelectedAssSsaTextTrack(): Boolean {
    groups.forEach { trackGroup ->
        if (trackGroup.type != C.TRACK_TYPE_TEXT) return@forEach
        for (index in 0 until trackGroup.length) {
            if (!trackGroup.isTrackSelected(index)) continue
            val format = trackGroup.getTrackFormat(index)
            if (format.isEmbeddedAssSsaFormat()) return true
        }
    }
    return false
}

internal fun Tracks.hasSelectedAssSsaTextTrackForTesting(): Boolean {
    return hasSelectedAssSsaTextTrack()
}

internal fun PlayerRuntimeController.maybeApplyTrackBasedAfrFallback(
    rawFrameRate: Float,
    snappedFrameRate: Float,
    videoWidth: Int,
    videoHeight: Int
) {
    if (trackAfrAppliedForCurrentStream) return
    val activity = currentHostActivity() ?: return
    val streamUrlSnapshot = currentStreamUrl
    trackAfrAppliedForCurrentStream = true

    scope.launch {
        if (!AndroidFrameRateSettings.canRequestFrameRate(context)) {
            return@launch
        }
        if (currentStreamUrl != streamUrlSnapshot) {
            return@launch
        }
        val prefer23976ProbeBias = rawFrameRate in 23.95f..24.12f
        val targetFrameRate = FrameRateUtils.refineFrameRateForDisplay(
            activity = activity,
            detectedFps = snappedFrameRate,
            prefer23976Near24 = prefer23976ProbeBias
        )
        val result = FrameRateUtils.matchFrameRateAndWait(
            activity = activity,
            frameRate = targetFrameRate,
            videoWidth = videoWidth.takeIf { it > 0 },
            videoHeight = videoHeight.takeIf { it > 0 },
            resolutionMatchingEnabled = AndroidFrameRateSettings.canRequestResolutionSwitch(context)
        )
        if (result != null && currentStreamUrl == streamUrlSnapshot) {
            _uiState.update {
                it.copy(
                    displayModeInfo = DisplayModeInfo(
                        width = result.appliedMode.physicalWidth,
                        height = result.appliedMode.physicalHeight,
                        refreshRate = result.appliedMode.refreshRate
                    ),
                    showDisplayModeInfo = true
                )
            }
        } else if (currentStreamUrl == streamUrlSnapshot) {
            trackAfrAppliedForCurrentStream = false
        }
    }
}

private fun isLikelyVc1VideoFormat(
    sampleMimeType: String?,
    codecs: String?,
    label: String?
): Boolean {
    val tokens = listOfNotNull(sampleMimeType, codecs, label)
        .flatMap { value -> value.split(',', ' ', '/', '(', ')') }
        .map { it.trim().lowercase(Locale.ROOT) }
        .filter { it.isNotEmpty() }
    return tokens.any { token ->
        token == "wvc1" ||
            token == "vc-1" ||
            token == "vc1" ||
            token == "wmv3"
    }
}

private fun isDolbyVisionProfile5VideoFormat(codecs: String?): Boolean {
    return resolveDolbyVisionProfileFromCodecString(codecs) == 5
}

private fun formatSupportRank(@C.FormatSupport formatSupport: Int): Int {
    return when (formatSupport) {
        C.FORMAT_HANDLED -> 4
        C.FORMAT_EXCEEDS_CAPABILITIES -> 3
        C.FORMAT_UNSUPPORTED_DRM -> 2
        C.FORMAT_UNSUPPORTED_SUBTYPE -> 1
        else -> 0
    }
}

internal fun PlayerRuntimeController.maybeApplyRememberedAudioSelection(audioTracks: List<TrackInfo>) {
    if (hasAppliedRememberedAudioSelection) return
    if (!streamReuseLastLinkEnabled) return
    if (audioTracks.isEmpty()) return
    if (isSafeAudioModeActiveForCurrentPlayback) {
        hasAppliedRememberedAudioSelection = true
        Log.i(
            PlayerRuntimeController.TAG,
            "AUDIO_STARTUP: Safe audio mode active; skipping remembered audio override " +
                "to preserve device-constrained track selection"
        )
        return
    }
    if (rememberedAudioLanguage.isNullOrBlank() && rememberedAudioName.isNullOrBlank()) return

    // If the user's preference is "Original language", do not let a stale
    // remembered audio language from a previous session override the original
    // unless the remembered language actually IS the original. Otherwise a
    // one-time manual switch (e.g. to Polish) silently hijacks every future
    // playback, defeating the entire point of the Original-language setting.
    if (lastPreferredAudioLanguage.trim().equals(AudioLanguageOption.ORIGINAL, ignoreCase = true)) {
        val normalizedRememberedLang = rememberedAudioLanguage
            ?.takeIf { it.isNotBlank() }
            ?.let(PlayerSubtitleUtils::normalizeLanguageCode)
        val normalizedOriginal = originalLanguage
            ?.takeIf { it.isNotBlank() }
            ?.let(PlayerSubtitleUtils::normalizeLanguageCode)
        if (
            normalizedRememberedLang != null &&
            normalizedOriginal != null &&
            normalizedRememberedLang != normalizedOriginal
        ) {
            Log.i(
                PlayerRuntimeController.TAG,
                "AUDIO_STARTUP: Skipping remembered audio lang=$normalizedRememberedLang " +
                    "(does not match original=$normalizedOriginal); " +
                    "deferring to Original-language preference"
            )
            // Leave hasAppliedRememberedAudioSelection = false so the
            // startup preferred selector runs and picks the original track.
            return
        }
    }

    val targetLang = normalizeTrackMatchValue(rememberedAudioLanguage)
    val targetName = normalizeTrackMatchValue(rememberedAudioName)

    val index = audioTracks.indexOfFirst { track ->
        val trackLang = normalizeTrackMatchValue(track.language)
        val trackName = normalizeTrackMatchValue(track.name)
        val langMatch = !targetLang.isNullOrBlank() &&
            !trackLang.isNullOrBlank() &&
            (trackLang == targetLang || trackLang.startsWith("$targetLang-"))
        val nameMatch = !targetName.isNullOrBlank() &&
            !trackName.isNullOrBlank() &&
            (trackName == targetName || trackName.contains(targetName))
        langMatch || nameMatch
    }
    if (index < 0) {
        hasAppliedRememberedAudioSelection = true
        return
    }

    selectAudioTrack(index)
    hasAppliedRememberedAudioSelection = true
}

internal fun PlayerRuntimeController.normalizeTrackMatchValue(value: String?): String? = value
    ?.lowercase()
    ?.replace(Regex("\\s+"), " ")
    ?.trim()
    ?.takeIf { it.isNotBlank() }

internal fun PlayerRuntimeController.maybeRestorePendingAudioSelectionAfterSubtitleRefresh(
    audioTracks: List<TrackInfo>
): Int? {
    val pending = pendingAudioSelectionAfterSubtitleRefresh ?: return null
    if (pending.streamUrl != currentStreamUrl) {
        pendingAudioSelectionAfterSubtitleRefresh = null
        return null
    }
    if (audioTracks.isEmpty()) return null
    if (isSafeAudioModeActiveForCurrentPlayback) {
        pendingAudioSelectionAfterSubtitleRefresh = null
        return null
    }

    val targetLang = normalizeTrackMatchValue(pending.language)
    val targetName = normalizeTrackMatchValue(pending.name)

    fun languageMatches(trackLanguage: String?): Boolean {
        val trackLang = normalizeTrackMatchValue(trackLanguage)
        return !targetLang.isNullOrBlank() &&
            !trackLang.isNullOrBlank() &&
            (trackLang == targetLang ||
                trackLang.startsWith("$targetLang-") ||
                trackLang.startsWith("${targetLang}_"))
    }

    val exactNameIndex = if (!targetName.isNullOrBlank()) {
        audioTracks.indexOfFirst { track ->
            normalizeTrackMatchValue(track.name) == targetName
        }
    } else {
        -1
    }

    val nameContainsIndex = if (exactNameIndex < 0 && !targetName.isNullOrBlank()) {
        audioTracks.indexOfFirst { track ->
            normalizeTrackMatchValue(track.name)?.contains(targetName) == true
        }
    } else {
        -1
    }

    val languageIndex = if (exactNameIndex < 0 && nameContainsIndex < 0) {
        audioTracks.indexOfFirst { track -> languageMatches(track.language) }
    } else {
        -1
    }

    val index = when {
        exactNameIndex >= 0 -> exactNameIndex
        nameContainsIndex >= 0 -> nameContainsIndex
        else -> languageIndex
    }

    pendingAudioSelectionAfterSubtitleRefresh = null
    if (index < 0) {
        Log.d(
            PlayerRuntimeController.TAG,
            "Audio restore skipped after subtitle refresh: no match for lang=$targetLang name=$targetName"
        )
        return null
    }

    val restoredTrack = audioTracks[index]
    Log.d(
        PlayerRuntimeController.TAG,
        "Restoring audio after subtitle refresh index=$index lang=${restoredTrack.language} name=${restoredTrack.name}"
    )
    selectAudioTrack(index)
    return index
}

internal fun PlayerRuntimeController.subtitleLanguageTargets(): List<String> {
    val preferred = _uiState.value.subtitleStyle.preferredLanguage.lowercase()
    if (preferred == "none") return emptyList()
    val secondary = _uiState.value.subtitleStyle.secondaryPreferredLanguage?.lowercase()
    return listOfNotNull(preferred, secondary)
}

internal fun PlayerRuntimeController.findBestInternalSubtitleTrackIndex(
    subtitleTracks: List<TrackInfo>,
    targets: List<String>
): Int {
    for ((targetPosition, target) in targets.withIndex()) {
        if (target == SUBTITLE_LANGUAGE_FORCED) {
            val forcedIndex = findBestForcedSubtitleTrackIndex(subtitleTracks)
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
                val brazilianFromGenericPt = findBrazilianPortugueseInGenericPtTracks(subtitleTracks)
                if (brazilianFromGenericPt >= 0) {
                    Log.d(
                        PlayerRuntimeController.TAG,
                        "AUTO_SUB pick internal pt-br via generic-pt tags index=$brazilianFromGenericPt"
                    )
                    return brazilianFromGenericPt
                }
                // Specific PT-BR rule:
                // generic "pt" tracks without brazilian tags are not accepted as PT-BR.
                if (targetPosition == 0) {
                    return -1
                }
            }
            continue
        }
        if (candidateIndexes.size == 1) return candidateIndexes.first()

        if (normalizedTarget == "pt" || normalizedTarget == "pt-br") {
            val tieBroken = breakPortugueseSubtitleTie(
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

private fun findBestForcedSubtitleTrackIndex(subtitleTracks: List<TrackInfo>): Int {
    // isForced is set from both the ExoPlayer SELECTION_FLAG_FORCED and name/label/id containing "forced"
    return subtitleTracks.indexOfFirst { it.isForced }
}

internal fun PlayerRuntimeController.findBrazilianPortugueseInGenericPtTracks(
    subtitleTracks: List<TrackInfo>
): Int {
    val genericPtIndexes = subtitleTracks.indices.filter { index ->
        val trackLanguage = subtitleTracks[index].language ?: return@filter false
        PlayerSubtitleUtils.normalizeLanguageCode(trackLanguage) == "pt"
    }
    if (genericPtIndexes.isEmpty()) return -1

    return genericPtIndexes.firstOrNull { index ->
        subtitleHasAnyTag(subtitleTracks[index], PlayerRuntimeController.PORTUGUESE_BRAZILIAN_TAGS) &&
            !subtitleHasAnyTag(subtitleTracks[index], PlayerRuntimeController.PORTUGUESE_EUROPEAN_TAGS)
    } ?: genericPtIndexes.firstOrNull { index ->
        subtitleHasAnyTag(subtitleTracks[index], PlayerRuntimeController.PORTUGUESE_BRAZILIAN_TAGS)
    } ?: -1
}

internal fun PlayerRuntimeController.breakPortugueseSubtitleTie(
    subtitleTracks: List<TrackInfo>,
    candidateIndexes: List<Int>,
    normalizedTarget: String
): Int {
    fun hasBrazilianTags(index: Int): Boolean {
        return subtitleHasAnyTag(subtitleTracks[index], PlayerRuntimeController.PORTUGUESE_BRAZILIAN_TAGS)
    }

    fun hasEuropeanTags(index: Int): Boolean {
        return subtitleHasAnyTag(subtitleTracks[index], PlayerRuntimeController.PORTUGUESE_EUROPEAN_TAGS)
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

internal fun PlayerRuntimeController.subtitleHasAnyTag(track: TrackInfo, tags: List<String>): Boolean {
    val haystack = listOfNotNull(track.name, track.language, track.trackId)
        .joinToString(" ")
        .lowercase(Locale.ROOT)
    return tags.any { tag -> haystack.contains(tag) }
}

internal fun PlayerRuntimeController.tryAutoSelectPreferredSubtitleFromAvailableTracks() {
    if (autoSubtitleSelected) return

    val state = _uiState.value
    val targets = subtitleLanguageTargets()
    val allowStartupAiFallback = shouldAllowStartupSubtitleAiFallback(
        autoSubtitleSelected = autoSubtitleSelected
    )
    Log.d(
        PlayerRuntimeController.TAG,
        "AUTO_SUB eval: targets=$targets, scannedText=$hasScannedTextTracksOnce, " +
            "internalCount=${state.subtitleTracks.size}, selectedInternal=${state.selectedSubtitleTrackIndex}, " +
            "addonCount=${state.addonSubtitles.size}, selectedAddon=${state.selectedAddonSubtitle?.lang}"
    )
    if (targets.isEmpty()) {
        autoSubtitleSelected = true
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB stop: preferred=none")
        return
    }

    val startupDecision = decideStartupSubtitleAutoSelection(
        subtitleTracks = state.subtitleTracks,
        addonSubtitles = state.addonSubtitles,
        preferredLanguage = state.subtitleStyle.preferredLanguage,
        secondaryLanguage = state.subtitleStyle.secondaryPreferredLanguage,
        hasScannedTextTracksOnce = hasScannedTextTracksOnce,
        playerReady = backendIsReady(),
        addonSubtitleDiscoveryPending =
            state.isLoadingAddonSubtitles || startupSubtitlePreparationJob?.isActive == true,
        aiTranslationConfigured =
            subtitleTranslationSettings.enabled && subtitleTranslationSettings.apiKey.isNotBlank(),
        startupPhase = allowStartupAiFallback,
        videoRelease = currentParsedRelease
    )
    when (startupDecision) {
        is StartupSubtitleAutoSelectionDecision.Internal -> {
            autoSubtitleSelected = true
            val currentInternal = state.selectedSubtitleTrackIndex
            val currentAddon = state.selectedAddonSubtitle
            if (currentInternal != startupDecision.index || currentAddon != null) {
                Log.d(
                    PlayerRuntimeController.TAG,
                    "AUTO_SUB startup pick internal index=${startupDecision.index} " +
                        "lang=${state.subtitleTracks[startupDecision.index].language} ai=${startupDecision.enableAiTranslation}"
                )
                selectSubtitleTrack(startupDecision.index)
                _uiState.update {
                    it.copy(
                        selectedSubtitleTrackIndex = startupDecision.index,
                        selectedAddonSubtitle = null
                    )
                }
            }
            if (startupDecision.enableAiTranslation) {
                enableAiSubtitles()
            }
            return
        }
        is StartupSubtitleAutoSelectionDecision.Addon -> {
            autoSubtitleSelected = true
            Log.d(
                PlayerRuntimeController.TAG,
                "AUTO_SUB startup pick addon lang=${startupDecision.subtitle.lang} " +
                    "id=${startupDecision.subtitle.id} ai=${startupDecision.enableAiTranslation}"
            )
            if (startupDecision.enableAiTranslation) {
                _uiState.update {
                    it.copy(
                        aiSubtitlesEnabled = true,
                        aiSubtitleError = null
                    )
                }
                translateAndSelectAddonSubtitle(startupDecision.subtitle)
            } else {
                selectAddonSubtitle(startupDecision.subtitle)
            }
            return
        }
        StartupSubtitleAutoSelectionDecision.DeferAddonFallback -> {
            Log.d(PlayerRuntimeController.TAG, "AUTO_SUB defer startup addon fallback")
            return
        }
        StartupSubtitleAutoSelectionDecision.None -> Unit
    }

    val internalIndex = findBestInternalSubtitleTrackIndex(
        subtitleTracks = state.subtitleTracks,
        targets = targets
    )
    if (internalIndex >= 0) {
        autoSubtitleSelected = true
        val currentInternal = state.selectedSubtitleTrackIndex
        val currentAddon = state.selectedAddonSubtitle
        if (currentInternal != internalIndex || currentAddon != null) {
            Log.d(PlayerRuntimeController.TAG, "AUTO_SUB pick internal index=$internalIndex lang=${state.subtitleTracks[internalIndex].language}")
            selectSubtitleTrack(internalIndex)
            _uiState.update { it.copy(selectedSubtitleTrackIndex = internalIndex, selectedAddonSubtitle = null) }
        } else {
            Log.d(PlayerRuntimeController.TAG, "AUTO_SUB stop: preferred internal already selected")
        }
        return
    }

    if (targets.contains(SUBTITLE_LANGUAGE_FORCED)) {
        if (hasScannedTextTracksOnce) {
            autoSubtitleSelected = true
            Log.d(PlayerRuntimeController.TAG, "AUTO_SUB stop: forced subtitles requested but no forced internal track found")
            return
        }
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB defer forced: text tracks not scanned yet")
        return
    }

    val selectedAddonMatchesTarget = state.selectedAddonSubtitle != null &&
        targets.any { target -> PlayerSubtitleUtils.matchesLanguageCode(state.selectedAddonSubtitle.lang, target) }
    if (selectedAddonMatchesTarget) {
        autoSubtitleSelected = true
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB stop: matching addon already selected (no internal match)")
        return
    }

    // Wait until we have at least one full text-track scan to avoid choosing addon too early.
    if (!hasScannedTextTracksOnce) {
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB defer addon fallback: text tracks not scanned yet")
        return
    }

    val playerReady = backendIsReady()
    if (!playerReady) {
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB defer addon fallback: player not ready")
        return
    }

    val addonMatch = state.addonSubtitles.firstOrNull { subtitle ->
        targets.any { target -> PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, target) }
    }
    if (addonMatch != null) {
        autoSubtitleSelected = true
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB pick addon lang=${addonMatch.lang} id=${addonMatch.id}")
        selectAddonSubtitleRespectingAi(addonMatch)
    } else {
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB no addon match for targets=$targets")
    }
}

internal fun shouldAllowStartupSubtitleAiFallback(
    autoSubtitleSelected: Boolean
): Boolean {
    // The AI-translation tier must stay available until the auto-selector has
    // actually committed a decision. Previously this gate also closed once the
    // first frame rendered or once Media3's own default text-track pick landed
    // in `selectedSubtitleTrackIndex`, which raced the addon-discovery + text-
    // track-scan convergence: if the video started painting before the auto
    // pass reached the AI tier, the tier was silently skipped and the user
    // ended up with an untranslated English fallback (or no subs at all).
    return !autoSubtitleSelected
}

internal fun PlayerRuntimeController.startFrameRateProbe(
    url: String,
    headers: Map<String, String>,
    frameRateMatchingEnabled: Boolean,
    preserveCurrentDetection: Boolean = false,
    allowAmbiguousTrackOverride: Boolean = false
) {
    frameRateProbeJob?.cancel()
    _uiState.update { state ->
        if (!preserveCurrentDetection) {
            state.copy(
                detectedFrameRateRaw = 0f,
                detectedFrameRate = 0f,
                detectedFrameRateSource = null,
                afrProbeRunning = false
            )
        } else {
            state.copy(afrProbeRunning = false)
        }
    }
    if (!frameRateMatchingEnabled) return

    val token = ++frameRateProbeToken
    frameRateProbeJob = scope.launch(Dispatchers.IO) {
        try {
            delay(PlayerRuntimeController.TRACK_FRAME_RATE_GRACE_MS)
            if (!isActive) return@launch
            val stateSnapshot = withContext(Dispatchers.Main) { _uiState.value }
            val trackAlreadySet = stateSnapshot.detectedFrameRateSource == FrameRateSource.TRACK &&
                stateSnapshot.detectedFrameRate > 0f
            if (trackAlreadySet) {
                if (!allowAmbiguousTrackOverride) return@launch

                val trackRaw = if (stateSnapshot.detectedFrameRateRaw > 0f) {
                    stateSnapshot.detectedFrameRateRaw
                } else {
                    stateSnapshot.detectedFrameRate
                }
                if (!PlayerFrameRateHeuristics.isAmbiguousCinema24(trackRaw)) return@launch
            }

            withContext(Dispatchers.Main) {
                if (token == frameRateProbeToken) {
                    _uiState.update { it.copy(afrProbeRunning = true) }
                }
            }

            val detection = FrameRateUtils.detectFrameRateFromSource(context, url, headers)
                ?: return@launch
            if (!isActive) return@launch
            withContext(Dispatchers.Main) {
                if (token == frameRateProbeToken) {
                    val state = _uiState.value
                    val shouldApplyInitial = state.detectedFrameRate <= 0f
                    val shouldOverrideAmbiguousTrack = allowAmbiguousTrackOverride &&
                        PlayerFrameRateHeuristics.shouldProbeOverrideTrack(state, detection)

                    if (shouldApplyInitial || shouldOverrideAmbiguousTrack) {
                        _uiState.update {
                            it.copy(
                                detectedFrameRateRaw = detection.raw,
                                detectedFrameRate = detection.snapped,
                                detectedFrameRateSource = FrameRateSource.PROBE
                            )
                        }
                    }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                if (token == frameRateProbeToken) {
                    _uiState.update { it.copy(afrProbeRunning = false) }
                }
            }
        }
    }
}

internal fun PlayerRuntimeController.applySubtitlePreferences(preferred: String, secondary: String?) {
    _exoPlayer?.let { player ->
        val builder = player.trackSelectionParameters.buildUpon()

        if (preferred == "none") {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            builder.setPreferredTextLanguage(null)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            if (preferred == SUBTITLE_LANGUAGE_FORCED) {
                builder.setPreferredTextLanguage(null)
            } else {
                builder.setPreferredTextLanguage(preferred)
            }
        }

        player.trackSelectionParameters = builder.build()
    }
}
