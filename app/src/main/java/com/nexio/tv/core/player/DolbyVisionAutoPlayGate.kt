package com.nexio.tv.core.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import com.nexio.tv.ui.screens.stream.AutoPlayStreamAlternative
import com.nexio.tv.ui.screens.stream.StreamPlaybackInfo
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

private const val DV_AUTOPLAY_TAG = "DvAutoPlayGate"
private const val DV_AUTOPLAY_PROBE_TIMEOUT_MS = 5_000L

enum class DolbyVisionAutoPlayDecisionReason {
    NOT_AUTOPLAY,
    DISPLAY_SUPPORTS_DOLBY_VISION,
    NOT_DOLBY_VISION,
    NOT_WEB_DL,
    PROBE_NOT_DOLBY_VISION,
    PROFILE_ALLOWED,
    UNSUPPORTED_PROFILE_5,
    PROBE_TIMEOUT,
    PROBE_FAILED,
    PROBE_UNKNOWN,
    NO_FALLBACK_AVAILABLE
}

enum class DolbyVisionProfileProbeStatus {
    DETECTED,
    NOT_DOLBY_VISION,
    UNKNOWN,
    FAILED
}

data class DolbyVisionProfileProbeResult(
    val status: DolbyVisionProfileProbeStatus,
    val profileLabel: String? = null,
    val profileNumber: Int? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val hdrType: String? = null,
    val error: String? = null
) {
    companion object {
        fun detected(
            profileLabel: String,
            profileNumber: Int,
            videoCodec: String? = null,
            audioCodec: String? = null,
            hdrType: String? = null
        ): DolbyVisionProfileProbeResult {
            return DolbyVisionProfileProbeResult(
                status = DolbyVisionProfileProbeStatus.DETECTED,
                profileLabel = profileLabel,
                profileNumber = profileNumber,
                videoCodec = videoCodec,
                audioCodec = audioCodec,
                hdrType = hdrType
            )
        }

        fun notDolbyVision(
            videoCodec: String? = null,
            audioCodec: String? = null,
            hdrType: String? = null
        ): DolbyVisionProfileProbeResult {
            return DolbyVisionProfileProbeResult(
                status = DolbyVisionProfileProbeStatus.NOT_DOLBY_VISION,
                videoCodec = videoCodec,
                audioCodec = audioCodec,
                hdrType = hdrType
            )
        }

        fun unknown(
            videoCodec: String? = null,
            audioCodec: String? = null,
            hdrType: String? = null
        ): DolbyVisionProfileProbeResult {
            return DolbyVisionProfileProbeResult(
                status = DolbyVisionProfileProbeStatus.UNKNOWN,
                videoCodec = videoCodec,
                audioCodec = audioCodec,
                hdrType = hdrType
            )
        }

        fun failed(error: String? = null): DolbyVisionProfileProbeResult {
            return DolbyVisionProfileProbeResult(
                status = DolbyVisionProfileProbeStatus.FAILED,
                error = error
            )
        }
    }
}

interface DolbyVisionProfileProbe {
    suspend fun probe(
        context: Context,
        url: String,
        headers: Map<String, String>?,
        filename: String?
    ): DolbyVisionProfileProbeResult
}

interface NativeDolbyVisionProfileBackend {
    fun probe(url: String, requestHeadersBlob: String?): Int
    fun probeMetadataBlob(url: String, requestHeadersBlob: String?): String? = null
}

data class DolbyVisionAutoPlayGateResult(
    val playbackInfo: StreamPlaybackInfo,
    val fallbackApplied: Boolean,
    val reason: DolbyVisionAutoPlayDecisionReason,
    val probeResult: DolbyVisionProfileProbeResult? = null
)

class DolbyVisionAutoPlayGate(
    private val probe: DolbyVisionProfileProbe = CompositeDolbyVisionProfileProbe(),
    private val probeTimeoutMs: Long = DV_AUTOPLAY_PROBE_TIMEOUT_MS
) {

    suspend fun resolve(
        context: Context,
        playbackInfo: StreamPlaybackInfo,
        autoPlay: Boolean,
        displaySupportsDolbyVision: Boolean
    ): DolbyVisionAutoPlayGateResult {
        if (!autoPlay) {
            return finalizeResult(
                playbackInfo = playbackInfo,
                fallbackApplied = false,
                reason = DolbyVisionAutoPlayDecisionReason.NOT_AUTOPLAY
            )
        }
        logSelection(playbackInfo)
        if (displaySupportsDolbyVision) {
            return finalizeResult(
                playbackInfo = playbackInfo,
                fallbackApplied = false,
                reason = DolbyVisionAutoPlayDecisionReason.DISPLAY_SUPPORTS_DOLBY_VISION
            )
        }
        if (!playbackInfo.isDolbyVisionCandidate) {
            return finalizeResult(
                playbackInfo = playbackInfo,
                fallbackApplied = false,
                reason = DolbyVisionAutoPlayDecisionReason.NOT_DOLBY_VISION
            )
        }
        if (!playbackInfo.isWebDl) {
            return finalizeResult(
                playbackInfo = playbackInfo,
                fallbackApplied = false,
                reason = DolbyVisionAutoPlayDecisionReason.NOT_WEB_DL
            )
        }

        val url = playbackInfo.url
        if (url.isNullOrBlank()) {
            return fallbackOrPrimary(
                playbackInfo = playbackInfo,
                reason = DolbyVisionAutoPlayDecisionReason.PROBE_FAILED,
                probeResult = DolbyVisionProfileProbeResult.failed("missing_url")
            )
        }

        logEvent(
            event = "DV_PROFILE_PROBE_STARTED",
            details = buildString {
                append("stream=")
                append(playbackInfo.streamKey ?: "unknown")
                append(" timeoutMs=")
                append(probeTimeoutMs)
            }
        )
        val probeResult = withTimeoutOrNull(probeTimeoutMs) {
            probe.probe(
                context = context,
                url = url,
                headers = playbackInfo.headers,
                filename = playbackInfo.filename
            )
        }
        if (probeResult == null) {
            logEvent(
                event = "DV_PROFILE_TIMEOUT",
                details = "stream=${playbackInfo.streamKey ?: "unknown"} timeoutMs=$probeTimeoutMs"
            )
            return fallbackOrPrimary(
                playbackInfo = playbackInfo,
                reason = DolbyVisionAutoPlayDecisionReason.PROBE_TIMEOUT,
                probeResult = DolbyVisionProfileProbeResult.failed("probe_timeout")
            )
        }
        logProbeResult(probeResult)

        return when (probeResult.status) {
            DolbyVisionProfileProbeStatus.NOT_DOLBY_VISION -> finalizeResult(
                playbackInfo = playbackInfo,
                fallbackApplied = false,
                reason = DolbyVisionAutoPlayDecisionReason.PROBE_NOT_DOLBY_VISION,
                probeResult = probeResult
            )
            DolbyVisionProfileProbeStatus.DETECTED -> {
                if (probeResult.profileNumber == 5) {
                    fallbackOrPrimary(
                        playbackInfo = playbackInfo,
                        reason = DolbyVisionAutoPlayDecisionReason.UNSUPPORTED_PROFILE_5,
                        probeResult = probeResult
                    )
                } else {
                    finalizeResult(
                        playbackInfo = playbackInfo,
                        fallbackApplied = false,
                        reason = DolbyVisionAutoPlayDecisionReason.PROFILE_ALLOWED,
                        probeResult = probeResult
                    )
                }
            }
            DolbyVisionProfileProbeStatus.UNKNOWN -> fallbackOrPrimary(
                playbackInfo = playbackInfo,
                reason = DolbyVisionAutoPlayDecisionReason.PROBE_UNKNOWN,
                probeResult = probeResult
            )
            DolbyVisionProfileProbeStatus.FAILED -> fallbackOrPrimary(
                playbackInfo = playbackInfo,
                reason = DolbyVisionAutoPlayDecisionReason.PROBE_FAILED,
                probeResult = probeResult
            )
        }
    }

    private fun fallbackOrPrimary(
        playbackInfo: StreamPlaybackInfo,
        reason: DolbyVisionAutoPlayDecisionReason,
        probeResult: DolbyVisionProfileProbeResult
    ): DolbyVisionAutoPlayGateResult {
        val fallback = playbackInfo.autoPlayNonDolbyVisionFallback
        if (fallback == null) {
            return finalizeResult(
                playbackInfo = playbackInfo,
                fallbackApplied = false,
                reason = DolbyVisionAutoPlayDecisionReason.NO_FALLBACK_AVAILABLE,
                probeResult = probeResult
            )
        }
        return finalizeResult(
            playbackInfo = fallback.applyTo(playbackInfo),
            fallbackApplied = true,
            reason = reason,
            probeResult = probeResult
        )
    }

    private fun finalizeResult(
        playbackInfo: StreamPlaybackInfo,
        fallbackApplied: Boolean,
        reason: DolbyVisionAutoPlayDecisionReason,
        probeResult: DolbyVisionProfileProbeResult? = null
    ): DolbyVisionAutoPlayGateResult {
        logEvent(
            event = "FINAL_PLAYBACK_DECISION",
            details = buildString {
                append("selected=")
                append(playbackInfo.streamKey ?: "unknown")
                append(" fallbackApplied=")
                append(fallbackApplied)
                append(" reason=")
                append(reason)
                append(" profile=")
                append(probeResult?.profileLabel ?: probeResult?.status?.name ?: "none")
            }
        )
        return DolbyVisionAutoPlayGateResult(
            playbackInfo = playbackInfo,
            fallbackApplied = fallbackApplied,
            reason = reason,
            probeResult = probeResult
        )
    }

    private fun logSelection(playbackInfo: StreamPlaybackInfo) {
        logEvent(
            event = "PRIMARY_SELECTED",
            details = buildString {
                append("stream=")
                append(playbackInfo.streamKey ?: "unknown")
                append(" webdl=")
                append(playbackInfo.isWebDl)
                append(" dv=")
                append(playbackInfo.isDolbyVisionCandidate)
            }
        )
        playbackInfo.autoPlayNonDolbyVisionFallback?.let { fallback ->
            logEvent(
                event = "FALLBACK_SELECTED",
                details = buildString {
                    append("stream=")
                    append(fallback.streamKey ?: "unknown")
                    append(" webdl=")
                    append(fallback.isWebDl)
                    append(" dv=")
                    append(fallback.isDolbyVisionCandidate)
                }
            )
        }
    }

    private fun logProbeResult(probeResult: DolbyVisionProfileProbeResult) {
        val event = when (probeResult.status) {
            DolbyVisionProfileProbeStatus.DETECTED -> "DV_PROFILE_DETECTED"
            DolbyVisionProfileProbeStatus.NOT_DOLBY_VISION -> "DV_PROFILE_NOT_DOLBY_VISION"
            DolbyVisionProfileProbeStatus.UNKNOWN -> "DV_PROFILE_UNKNOWN"
            DolbyVisionProfileProbeStatus.FAILED -> "DV_PROFILE_FAILED"
        }
        logEvent(
            event = event,
            details = buildString {
                append("status=")
                append(probeResult.status)
                append(" profile=")
                append(probeResult.profileLabel ?: "none")
                append(" profileNumber=")
                append(probeResult.profileNumber?.toString() ?: "none")
                append(" videoCodec=")
                append(probeResult.videoCodec ?: "unknown")
                append(" audioCodec=")
                append(probeResult.audioCodec ?: "unknown")
                append(" hdrType=")
                append(probeResult.hdrType ?: "unknown")
                probeResult.error?.let {
                    append(" error=")
                    append(it)
                }
            }
        )
    }

    private fun logEvent(event: String, details: String) {
        runCatching {
            Log.i(DV_AUTOPLAY_TAG, "$event ts=${System.currentTimeMillis()} $details")
        }
    }
}

class FfmpegDolbyVisionProfileProbe(
    private val backend: NativeDolbyVisionProfileBackend = object : NativeDolbyVisionProfileBackend {
        override fun probe(url: String, requestHeadersBlob: String?): Int {
            return FfmpegLibrary.probeDolbyVisionProfile(url, requestHeadersBlob)
        }

        override fun probeMetadataBlob(url: String, requestHeadersBlob: String?): String? {
            return FfmpegLibrary.probeDolbyVisionMetadataBlob(url, requestHeadersBlob)
        }
    }
) : DolbyVisionProfileProbe {

    override suspend fun probe(
        context: Context,
        url: String,
        headers: Map<String, String>?,
        filename: String?
    ): DolbyVisionProfileProbeResult = withContext(Dispatchers.IO) {
        runCatching {
            val headerBlob = headers.toHeaderBlob()
            val metadata = parseNativeDolbyVisionMetadataBlob(
                backend.probeMetadataBlob(url, headerBlob)
            )
            when (val profile = backend.probe(url, headerBlob)) {
                -3 -> DolbyVisionProfileProbeResult.failed("ffmpeg_probe_failed").copy(
                    videoCodec = metadata.videoCodec,
                    audioCodec = metadata.audioCodec,
                    hdrType = metadata.hdrType
                )
                -2 -> DolbyVisionProfileProbeResult.unknown(
                    videoCodec = metadata.videoCodec,
                    audioCodec = metadata.audioCodec,
                    hdrType = metadata.hdrType
                )
                -1 -> DolbyVisionProfileProbeResult.notDolbyVision(
                    videoCodec = metadata.videoCodec,
                    audioCodec = metadata.audioCodec,
                    hdrType = metadata.hdrType
                )
                else -> DolbyVisionProfileProbeResult.detected(
                    profileLabel = "dv_profile_$profile",
                    profileNumber = profile,
                    videoCodec = metadata.videoCodec,
                    audioCodec = metadata.audioCodec,
                    hdrType = metadata.hdrType
                )
            }
        }.getOrElse { error ->
            Log.w(DV_AUTOPLAY_TAG, "FFmpeg Dolby Vision probe failed: ${error.message}")
            DolbyVisionProfileProbeResult.failed(error.message)
        }
    }
}

private data class NativeDolbyVisionMetadata(
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val hdrType: String? = null
)

private fun parseNativeDolbyVisionMetadataBlob(blob: String?): NativeDolbyVisionMetadata {
    if (blob.isNullOrBlank()) return NativeDolbyVisionMetadata()
    val entries = blob.split(';')
        .mapNotNull { entry ->
            val delimiter = entry.indexOf('=')
            if (delimiter <= 0 || delimiter == entry.lastIndex) return@mapNotNull null
            val key = entry.substring(0, delimiter).trim()
            val value = entry.substring(delimiter + 1).trim()
            if (key.isEmpty() || value.isEmpty() || value == "unknown") null else key to value
        }
        .toMap()
    return NativeDolbyVisionMetadata(
        videoCodec = entries["video"],
        audioCodec = entries["audio"],
        hdrType = entries["hdr"]
    )
}

class CompositeDolbyVisionProfileProbe(
    private val probes: List<DolbyVisionProfileProbe> = listOf(
        FfmpegDolbyVisionProfileProbe()
    ),
    private val continueAfterUnknown: Boolean = false
) : DolbyVisionProfileProbe {

    override suspend fun probe(
        context: Context,
        url: String,
        headers: Map<String, String>?,
        filename: String?
    ): DolbyVisionProfileProbeResult {
        var unknownResult: DolbyVisionProfileProbeResult? = null
        var failedResult: DolbyVisionProfileProbeResult? = null

        probes.forEach { probe ->
            val result = probe.probe(
                context = context,
                url = url,
                headers = headers,
                filename = filename
            )
            when (result.status) {
                DolbyVisionProfileProbeStatus.DETECTED,
                DolbyVisionProfileProbeStatus.NOT_DOLBY_VISION -> return result
                DolbyVisionProfileProbeStatus.UNKNOWN -> {
                    if (!continueAfterUnknown) {
                        return result
                    }
                    if (unknownResult == null) unknownResult = result
                }
                DolbyVisionProfileProbeStatus.FAILED -> failedResult = result
            }
        }

        return unknownResult ?: failedResult ?: DolbyVisionProfileProbeResult.unknown()
    }
}

fun supportsDolbyVisionDisplay(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
    val displayManager = context.getSystemService(DisplayManager::class.java) ?: return false
    val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
    val hdrTypes = display.hdrCapabilities?.supportedHdrTypes ?: return false
    return hdrTypes.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION)
}

private fun AutoPlayStreamAlternative.applyTo(base: StreamPlaybackInfo): StreamPlaybackInfo {
    return base.copy(
        url = url,
        streamName = streamName,
        headers = headers,
        filename = filename,
        videoHash = videoHash,
        videoSize = videoSize,
        streamKey = streamKey,
        isWebDl = isWebDl,
        isDolbyVisionCandidate = isDolbyVisionCandidate,
        autoPlayNonDolbyVisionFallback = null
    )
}

private fun Map<String, String>?.toHeaderBlob(): String? {
    if (this.isNullOrEmpty()) return null
    return entries.joinToString(separator = "\r\n", postfix = "\r\n") { (key, value) ->
        "$key: $value"
    }
}
