package com.nexio.tv.ui.components

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.nexio.tv.data.local.SubtitleTranslationSettingsDataStore
import com.nexio.tv.data.repository.SubtitleTranslationService
import com.nexio.tv.domain.model.SubtitleTranslationSettings
import com.nexio.tv.ui.screens.player.TimedAddonCueGroup
import com.nexio.tv.ui.screens.player.activeAddonOverlayCuesAt
import com.nexio.tv.ui.screens.player.nextAddonOverlayUpdateDelayMs
import com.nexio.tv.ui.screens.player.parseAddonSubtitleOverlayCueGroups
import com.nexio.tv.ui.screens.player.sourceTextsForTranslation
import com.nexio.tv.ui.screens.player.translateTimedAddonCueGroups
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val TAG = "TrailerSubtitleOverlay"

/**
 * Trailer-side subtitle pipeline — modelled on the stream player's
 * AddonSubtitleOverlay design (see PlayerRuntimeControllerAddonSubtitleOverlay).
 *
 * Why this design over Media3 `SubtitleConfiguration` + `CueGroupSubtitleTranslator`:
 *   1. SubtitleConfiguration sideloads a SingleSampleMediaSource inside the
 *      trailer's MergingMediaSource. The merging gate doesn't release until
 *      every child reports prepared, so the TextRenderer's cue-translator
 *      prefetch effectively blocks trailer playback start.
 *   2. With a short prefetch the TextRenderer emits source cues immediately
 *      then layers translated cues on top, producing a dual-language display.
 *   3. App-controlled overlay sidesteps both: we parse the SRT, disable
 *      Media3's text track, poll the player position on a coroutine, push
 *      currently-active cues into a Compose state, and render them via the
 *      PlayerView's overlayFrameLayout — same SubtitleView the stream player
 *      uses (see ensureExternalSubtitleOverlay).
 */

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface TrailerSubtitleOverlayAccess {
    fun subtitleTranslationService(): SubtitleTranslationService
    fun subtitleTranslationSettingsDataStore(): SubtitleTranslationSettingsDataStore

    companion object {
        fun from(context: Context): TrailerSubtitleOverlayAccess {
            return EntryPointAccessors
                .fromApplication(
                    context.applicationContext,
                    TrailerSubtitleOverlayAccess::class.java
                )
        }
    }
}

/**
 * Parse an SRT file at the given `file://` URI into `TimedAddonCueGroup`
 * entries. Uses the stream player's existing parser so cue timing /
 * formatting is identical across surfaces. Returns empty list on failure.
 */
@OptIn(UnstableApi::class)
internal suspend fun parseTrailerSubtitleCueGroups(
    fileUri: String
): List<TimedAddonCueGroup> = withContext(Dispatchers.IO) {
    runCatching {
        val file = File(URI(fileUri))
        if (!file.exists() || file.length() == 0L) return@runCatching emptyList()
        val bytes = file.readBytes()
        parseAddonSubtitleOverlayCueGroups(
            url = fileUri,
            mimeType = MimeTypes.APPLICATION_SUBRIP,
            bytes = bytes
        )
    }.onFailure { error ->
        Log.w(TAG, "parse failed for $fileUri", error)
    }.getOrDefault(emptyList())
}

/**
 * Translate a list of cue groups in one provider call. Returns the
 * translated list on success, the original list on any failure (network,
 * malformed response, etc.) so caption rendering never breaks.
 */
internal suspend fun translateTrailerCueGroups(
    service: SubtitleTranslationService,
    settings: SubtitleTranslationSettings,
    sourceLanguageCode: String,
    targetLanguageCode: String,
    cueGroups: List<TimedAddonCueGroup>
): List<TimedAddonCueGroup> {
    if (cueGroups.isEmpty()) return cueGroups
    if (!settings.enabled || settings.apiKey.isBlank()) return cueGroups
    if (targetLanguageCode.isBlank()) return cueGroups
    val sourceTexts = sourceTextsForTranslation(cueGroups)
    if (sourceTexts.isEmpty()) return cueGroups

    val result = runCatching {
        service.translateCueTexts(
            texts = sourceTexts,
            targetLanguageCode = targetLanguageCode,
            sourceLanguageCode = sourceLanguageCode,
            settings = settings
        )
    }.getOrNull() ?: return cueGroups

    val translatedTexts = result.getOrElse { error ->
        Log.w(TAG, "translation failed", error)
        return cueGroups
    }
    if (translatedTexts.isEmpty()) return cueGroups

    return translateTimedAddonCueGroups(cueGroups, translatedTexts)
}

/**
 * Poll the player position and call `onActiveCues(cues)` whenever the
 * displayed cue set changes. Runs until the surrounding coroutine is
 * cancelled. Cadence adapts: tight while a cue is on/about-to-change,
 * idle when no cues are coming up.
 */
internal suspend fun pollTrailerActiveCues(
    player: ExoPlayer,
    cueGroups: List<TimedAddonCueGroup>,
    onActiveCues: (List<Cue>) -> Unit
) {
    if (cueGroups.isEmpty()) {
        onActiveCues(emptyList())
        return
    }
    var lastCues: List<Cue> = emptyList()
    while (currentCoroutineContext().isActive) {
        val positionMs = player.currentPosition
        val active = activeAddonOverlayCuesAt(cueGroups, positionMs)
        if (active != lastCues) {
            lastCues = active
            onActiveCues(active)
        }
        delay(nextAddonOverlayUpdateDelayMs(cueGroups, positionMs))
    }
}
