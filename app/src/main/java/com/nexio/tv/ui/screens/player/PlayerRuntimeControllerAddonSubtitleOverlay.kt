package com.nexio.tv.ui.screens.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.subrip.SubripParser
import androidx.media3.extractor.text.webvtt.WebvttParser
import androidx.media3.exoplayer.ExoPlayer
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.domain.model.Subtitle
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ADDON_SUBTITLE_OVERLAY_IDLE_DELAY_MS = 500L
private const val ADDON_SUBTITLE_OVERLAY_MIN_DELAY_MS = 40L

internal data class TimedAddonCueGroup(
    val startMs: Long,
    val endMs: Long,
    val cues: List<Cue>
)

internal fun PlayerRuntimeController.activateAddonSubtitleOverlay(
    subtitle: Subtitle,
    selectedSubtitle: Subtitle = subtitle
) {
    val player = _exoPlayer ?: run {
        Log.w(PlayerRuntimeController.TAG, "ADDON_SUB: overlay activation skipped; player is gone")
        return
    }
    val subtitleMimeType = PlayerSubtitleUtils.mimeTypeFromUrl(subtitle.url)
    val generation = startAddonSubtitleOverlayGeneration()

    addonSubtitleOverlayJob = scope.launch {
        val cueGroups = runCatching {
            withContext(Dispatchers.IO) {
                parseAddonSubtitleCueGroups(subtitle, subtitleMimeType)
            }
        }.onFailure { error ->
            Log.w(
                PlayerRuntimeController.TAG,
                "ADDON_SUB: overlay parse/download failed; falling back to media-source refresh",
                error
            )
        }.getOrNull()

        if (!isAddonSubtitleOverlayGenerationActive(generation, player)) {
            return@launch
        }

        if (cueGroups.isNullOrEmpty()) {
            // SRT/VTT overlay selection must never rebuffer. If parsing fails after the
            // Media3 parser plus plain-SRT fallback, leave playback untouched and clear
            // the overlay instead of falling back to setMediaSource/prepare.
            clearAddonSubtitleOverlayAfterTextFailure(generation)
            return@launch
        }

        startAddonSubtitleOverlayState(
            player = player,
            generation = generation,
            selectedSubtitle = selectedSubtitle,
            cueGroups = cueGroups
        )
        pollAddonSubtitleOverlayCues(
            player = player,
            generation = generation,
            cueGroups = cueGroups
        )
    }
}

internal fun PlayerRuntimeController.activateAddonSubtitleOverlayCueGroups(
    selectedSubtitle: Subtitle,
    cueGroups: List<TimedAddonCueGroup>
) {
    val player = _exoPlayer ?: run {
        Log.w(PlayerRuntimeController.TAG, "ADDON_SUB: translated overlay activation skipped; player is gone")
        return
    }
    val generation = startAddonSubtitleOverlayGeneration()
    if (cueGroups.isEmpty()) {
        clearAddonSubtitleOverlayAfterTextFailure(generation)
        return
    }
    startAddonSubtitleOverlayState(
        player = player,
        generation = generation,
        selectedSubtitle = selectedSubtitle,
        cueGroups = cueGroups
    )
    addonSubtitleOverlayJob = scope.launch {
        pollAddonSubtitleOverlayCues(
            player = player,
            generation = generation,
            cueGroups = cueGroups
        )
    }
}

internal fun PlayerRuntimeController.deactivateAddonSubtitleOverlay() {
    addonSubtitleOverlayGeneration += 1L
    addonSubtitleOverlayJob?.cancel()
    addonSubtitleOverlayJob = null
    addonSubtitleOverlayCueGroups = emptyList()
    _uiState.update { it.copy(addonOverlayCues = emptyList()) }
}

private fun PlayerRuntimeController.startAddonSubtitleOverlayState(
    player: ExoPlayer,
    generation: Long,
    selectedSubtitle: Subtitle,
    cueGroups: List<TimedAddonCueGroup>
) {
    if (!isAddonSubtitleOverlayGenerationActive(generation, player)) return

    addonSubtitleOverlayCueGroups = cueGroups
    pendingAddonSubtitleLanguage = null
    pendingAddonSubtitleTrackId = null
    pendingAudioSelectionAfterSubtitleRefresh = null

    player.trackSelectionParameters = player.trackSelectionParameters
        .buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        .build()

    _uiState.update {
        it.copy(
            isSwappingAddonSubtitle = false,
            selectedAddonSubtitle = selectedSubtitle,
            selectedSubtitleTrackIndex = -1,
            addonOverlayCues = emptyList()
        )
    }
}

private suspend fun PlayerRuntimeController.pollAddonSubtitleOverlayCues(
    player: ExoPlayer,
    generation: Long,
    cueGroups: List<TimedAddonCueGroup>
) {
    var lastCues: List<Cue> = emptyList()
    while (
        currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive != false &&
        isAddonSubtitleOverlayGenerationActive(generation, player)
    ) {
        val positionMs = delayedAddonSubtitleOverlayPositionMs(player.currentPosition, subtitleDelayUs.get())
        val activeCues = activeAddonOverlayCuesAt(
            cueGroups = cueGroups,
            positionMs = positionMs
        )
        if (activeCues != lastCues) {
            lastCues = activeCues
            _uiState.update { it.copy(addonOverlayCues = activeCues) }
        }
        delay(nextAddonOverlayUpdateDelayMs(cueGroups, positionMs))
    }
}

internal fun nextAddonOverlayUpdateDelayMs(
    cueGroups: List<TimedAddonCueGroup>,
    positionMs: Long
): Long {
    val nextBoundary = cueGroups
        .asSequence()
        .flatMap { sequenceOf(it.startMs, it.endMs) }
        .filter { it > positionMs }
        .minOrNull()
        ?: return ADDON_SUBTITLE_OVERLAY_IDLE_DELAY_MS

    return (nextBoundary - positionMs).coerceAtLeast(ADDON_SUBTITLE_OVERLAY_MIN_DELAY_MS)
}

internal suspend fun PlayerRuntimeController.loadAddonSubtitleOverlayCueGroups(
    subtitle: Subtitle
): List<TimedAddonCueGroup> = withContext(Dispatchers.IO) {
    parseAddonSubtitleCueGroups(
        subtitle = subtitle,
        mimeType = PlayerSubtitleUtils.mimeTypeFromUrl(subtitle.url)
    )
}

@OptIn(UnstableApi::class)
internal fun translateTimedAddonCueGroups(
    cueGroups: List<TimedAddonCueGroup>,
    translatedTexts: Map<String, String>
): List<TimedAddonCueGroup> {
    return cueGroups.map { cueGroup ->
        cueGroup.copy(
            cues = cueGroup.cues.map { cue ->
                val sourceText = cue.text?.toString()?.trim()
                val translatedText = sourceText?.let(translatedTexts::get)?.takeIf(String::isNotBlank)
                if (translatedText == null) {
                    cue
                } else {
                    cue.buildUpon().setText(translatedText).build()
                }
            }
        )
    }
}

internal fun sourceTextsForTranslation(cueGroups: List<TimedAddonCueGroup>): List<String> {
    return cueGroups
        .flatMap { it.cues }
        .mapNotNull { cue -> cue.text?.toString()?.trim()?.takeIf(String::isNotBlank) }
        .distinct()
}

internal fun delayedAddonSubtitleOverlayPositionMs(
    currentPositionMs: Long,
    subtitleDelayUs: Long
): Long {
    // Keep the same sign convention as SubtitleOffsetRenderer:
    // positive delay renders subtitle cues later by looking up an earlier media position.
    return (currentPositionMs - subtitleDelayUs / 1000L).coerceAtLeast(0L)
}

internal fun activeAddonOverlayCuesAt(
    cueGroups: List<TimedAddonCueGroup>,
    positionMs: Long
): List<Cue> {
    if (cueGroups.isEmpty()) return emptyList()

    var low = 0
    var high = cueGroups.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (cueGroups[mid].startMs <= positionMs) {
            low = mid + 1
        } else {
            high = mid
        }
    }

    val activeCues = mutableListOf<Cue>()
    for (index in low - 1 downTo 0) {
        val cueGroup = cueGroups[index]
        if (cueGroup.endMs > positionMs) {
            activeCues.addAll(0, cueGroup.cues)
        }
    }
    return activeCues
}

private fun PlayerRuntimeController.startAddonSubtitleOverlayGeneration(): Long {
    addonSubtitleOverlayJob?.cancel()
    addonSubtitleOverlayGeneration += 1L
    addonSubtitleOverlayCueGroups = emptyList()
    _uiState.update { it.copy(addonOverlayCues = emptyList()) }
    return addonSubtitleOverlayGeneration
}

private fun PlayerRuntimeController.isAddonSubtitleOverlayGenerationActive(
    generation: Long,
    player: ExoPlayer
): Boolean {
    return addonSubtitleOverlayGeneration == generation && _exoPlayer === player
}

private fun PlayerRuntimeController.clearAddonSubtitleOverlayAfterTextFailure(generation: Long) {
    if (addonSubtitleOverlayGeneration == generation) {
        addonSubtitleOverlayGeneration += 1L
    }
    addonSubtitleOverlayJob = null
    addonSubtitleOverlayCueGroups = emptyList()
    _uiState.update {
        it.copy(
            isSwappingAddonSubtitle = false,
            selectedAddonSubtitle = null,
            addonOverlayCues = emptyList()
        )
    }
}

@OptIn(UnstableApi::class)
private suspend fun PlayerRuntimeController.parseAddonSubtitleCueGroups(
    subtitle: Subtitle,
    mimeType: String
): List<TimedAddonCueGroup> {
    return parseAddonSubtitleOverlayCueGroups(
        url = subtitle.url,
        mimeType = mimeType,
        bytes = readAddonSubtitleBytes(subtitle.url)
    )
}

@OptIn(UnstableApi::class)
internal fun parseAddonSubtitleOverlayCueGroups(
    url: String,
    mimeType: String = PlayerSubtitleUtils.mimeTypeFromUrl(url),
    bytes: ByteArray
): List<TimedAddonCueGroup> {
    val parser = when (mimeType) {
        MimeTypes.APPLICATION_SUBRIP -> SubripParser()
        MimeTypes.TEXT_VTT -> WebvttParser()
        else -> return emptyList()
    }
    val parsedCueGroups = mutableListOf<CuesWithTiming>()
    runCatching {
        val parserBytes = bytes.withTrailingSubtitleParserNewline()
        parser.parse(
            parserBytes,
            0,
            parserBytes.size,
            SubtitleParser.OutputOptions.allCues()
        ) { cuesWithTiming ->
            parsedCueGroups += cuesWithTiming
        }
    }.onFailure { error ->
        if (mimeType != MimeTypes.APPLICATION_SUBRIP) throw error
        return parsePlainSubripCueGroups(bytes)
    }
    return parsedCueGroups
        .mapNotNull(::toTimedAddonCueGroup)
        .sortedWith(compareBy<TimedAddonCueGroup> { it.startMs }.thenBy { it.endMs })
}

private val SUBRIP_TIMING_PATTERN = Regex(
    """(?m)^(\d{2}:\d{2}:\d{2}[,.]\d{3})\s+-->\s+(\d{2}:\d{2}:\d{2}[,.]\d{3})(?:\s.*)?$"""
)

@OptIn(UnstableApi::class)
private fun parsePlainSubripCueGroups(bytes: ByteArray): List<TimedAddonCueGroup> {
    val text = bytes.toString(StandardCharsets.UTF_8)
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .trim()
    if (text.isBlank()) return emptyList()

    return text.split(Regex("\n{2,}"))
        .mapNotNull { block ->
            val lines = block.lines().filter { it.isNotBlank() }
            val timingIndex = lines.indexOfFirst { line -> SUBRIP_TIMING_PATTERN.matches(line) }
            if (timingIndex < 0 || timingIndex >= lines.lastIndex) return@mapNotNull null
            val match = SUBRIP_TIMING_PATTERN.matchEntire(lines[timingIndex]) ?: return@mapNotNull null
            val startMs = parseSubripTimeMs(match.groupValues[1]) ?: return@mapNotNull null
            val endMs = parseSubripTimeMs(match.groupValues[2]) ?: return@mapNotNull null
            if (endMs <= startMs) return@mapNotNull null
            val cueText = lines.drop(timingIndex + 1).joinToString("\n").trim()
            if (cueText.isBlank()) return@mapNotNull null
            TimedAddonCueGroup(
                startMs = startMs,
                endMs = endMs,
                cues = listOf(Cue.Builder().setText(cueText).build())
            )
        }
        .sortedWith(compareBy<TimedAddonCueGroup> { it.startMs }.thenBy { it.endMs })
}

private fun parseSubripTimeMs(value: String): Long? {
    val parts = value.replace(',', '.').split(':', '.')
    if (parts.size != 4) return null
    val hours = parts[0].toLongOrNull() ?: return null
    val minutes = parts[1].toLongOrNull() ?: return null
    val seconds = parts[2].toLongOrNull() ?: return null
    val millis = parts[3].toLongOrNull() ?: return null
    return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis
}

private fun ByteArray.withTrailingSubtitleParserNewline(): ByteArray {
    if (isEmpty()) return this
    if (last() == '\n'.code.toByte()) return this
    return this + byteArrayOf('\n'.code.toByte(), '\n'.code.toByte())
}

@OptIn(UnstableApi::class)
private fun toTimedAddonCueGroup(cuesWithTiming: CuesWithTiming): TimedAddonCueGroup? {
    if (cuesWithTiming.cues.isEmpty()) return null
    val startUs = cuesWithTiming.startTimeUs
    val endUs = cuesWithTiming.endTimeUs
    if (startUs == C.TIME_UNSET || endUs == C.TIME_UNSET || endUs <= startUs) return null
    return TimedAddonCueGroup(
        startMs = startUs / 1000L,
        endMs = endUs / 1000L,
        cues = cuesWithTiming.cues
    )
}

private suspend fun PlayerRuntimeController.readAddonSubtitleBytes(url: String): ByteArray {
    if (url.startsWith("file:", ignoreCase = true)) {
        return File(URI(url)).readBytes()
    }

    return addonSubtitleBytesFromDownloadResult(
        subtitleSourceDownloadIntegrationProvider.downloadText(url)
    )
}

internal fun addonSubtitleBytesFromDownloadResult(
    result: IntegrationCallResult<String>
): ByteArray {
    return when (result) {
        is IntegrationCallResult.Success -> result.value.toByteArray(StandardCharsets.UTF_8)
        is IntegrationCallResult.HttpError -> {
            throw IllegalStateException("Addon subtitle download failed http=${result.statusCode}")
        }
        is IntegrationCallResult.NetworkError -> throw result.throwable
        IntegrationCallResult.Missing -> {
            throw IllegalStateException("Addon subtitle response body is empty")
        }
    }
}
