package com.nexio.tv.core.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import com.nexio.tv.ui.screens.stream.AutoPlayStreamAlternative
import com.nexio.tv.ui.screens.stream.StreamPlaybackInfo
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import com.nexio.tv.data.repository.benchmark.BenchmarkAwareStreamScoringConfig
import com.nexio.tv.data.repository.benchmark.DeviceCapabilitySnapshot
import com.nexio.tv.data.repository.benchmark.DeviceCapabilitySnapshotProvider
import com.nexio.tv.data.repository.benchmark.DeviceHdrType
import com.nexio.tv.data.repository.benchmark.ShadowAudioTier
import com.nexio.tv.data.repository.benchmark.ShadowHdrTier
import com.nexio.tv.data.repository.benchmark.ShadowSupportLevel
import com.nexio.tv.data.repository.benchmark.ShadowVideoCodecTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.Locale

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
    fun probeBlob(url: String, requestHeadersBlob: String?): String? = null
    fun probeStreamMetadataJson(url: String, requestHeadersBlob: String?): String? = null
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
        displaySupportsDolbyVision: Boolean,
        precomputedProbeResult: DolbyVisionProfileProbeResult? = null
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
            return findViableFallback(
                context = context,
                playbackInfo = playbackInfo,
                reason = DolbyVisionAutoPlayDecisionReason.PROBE_FAILED,
                probeResult = DolbyVisionProfileProbeResult.failed("missing_url")
            )
        }

        val probeResult = precomputedProbeResult ?: run {
            logEvent(
                event = "DV_PROFILE_PROBE_STARTED",
                details = buildString {
                    append("stream=")
                    append(playbackInfo.streamKey ?: "unknown")
                    append(" timeoutMs=")
                    append(probeTimeoutMs)
                }
            )
            withTimeoutOrNull(probeTimeoutMs) {
                probe.probe(
                    context = context,
                    url = url,
                    headers = playbackInfo.headers,
                    filename = playbackInfo.filename
                )
            }
        }
        if (precomputedProbeResult != null) {
            logEvent(
                event = "DV_PROFILE_PROBE_REUSED",
                details = buildString {
                    append("stream=")
                    append(playbackInfo.streamKey ?: "unknown")
                    append(" status=")
                    append(precomputedProbeResult.status)
                    append(" profile=")
                    append(precomputedProbeResult.profileLabel ?: "none")
                }
            )
        }
        if (probeResult == null) {
            logEvent(
                event = "DV_PROFILE_TIMEOUT",
                details = "stream=${playbackInfo.streamKey ?: "unknown"} timeoutMs=$probeTimeoutMs"
            )
            return findViableFallback(
                context = context,
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
                    findViableFallback(
                        context = context,
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
            DolbyVisionProfileProbeStatus.UNKNOWN -> findViableFallback(
                context = context,
                playbackInfo = playbackInfo,
                reason = DolbyVisionAutoPlayDecisionReason.PROBE_UNKNOWN,
                probeResult = probeResult
            )
            DolbyVisionProfileProbeStatus.FAILED -> findViableFallback(
                context = context,
                playbackInfo = playbackInfo,
                reason = DolbyVisionAutoPlayDecisionReason.PROBE_FAILED,
                probeResult = probeResult
            )
        }
    }

    private suspend fun findViableFallback(
        context: Context,
        playbackInfo: StreamPlaybackInfo,
        reason: DolbyVisionAutoPlayDecisionReason,
        probeResult: DolbyVisionProfileProbeResult
    ): DolbyVisionAutoPlayGateResult {
        val candidates = playbackInfo.autoPlayFallbackCandidates
        if (candidates.isEmpty()) {
            return finalizeResult(
                playbackInfo = playbackInfo,
                fallbackApplied = false,
                reason = DolbyVisionAutoPlayDecisionReason.NO_FALLBACK_AVAILABLE,
                probeResult = probeResult
            )
        }

        logEvent(
            event = "FALLBACK_SEARCH_STARTED",
            details = "candidateCount=${candidates.size} reason=$reason"
        )

        for ((index, candidate) in candidates.withIndex()) {
            val candidateKey = candidate.streamKey ?: "unknown"

            if (!candidate.isDolbyVisionCandidate) {
                logEvent(
                    event = "FALLBACK_CANDIDATE_ACCEPTED",
                    details = "index=$index stream=$candidateKey reason=not_dv"
                )
                return finalizeResult(
                    playbackInfo = candidate.applyTo(playbackInfo),
                    fallbackApplied = true,
                    reason = reason,
                    probeResult = probeResult
                )
            }

            val candidateUrl = candidate.url
            if (candidateUrl.isNullOrBlank()) {
                logEvent(
                    event = "FALLBACK_CANDIDATE_SKIPPED",
                    details = "index=$index stream=$candidateKey reason=missing_url"
                )
                continue
            }

            logEvent(
                event = "FALLBACK_CANDIDATE_PROBE",
                details = "index=$index stream=$candidateKey"
            )
            val candidateProbe = withTimeoutOrNull(probeTimeoutMs) {
                probe.probe(
                    context = context,
                    url = candidateUrl,
                    headers = candidate.headers,
                    filename = candidate.filename
                )
            }

            if (candidateProbe == null) {
                logEvent(
                    event = "FALLBACK_CANDIDATE_SKIPPED",
                    details = "index=$index stream=$candidateKey reason=probe_timeout"
                )
                continue
            }

            when (candidateProbe.status) {
                DolbyVisionProfileProbeStatus.DETECTED -> {
                    if (candidateProbe.profileNumber == 5) {
                        logEvent(
                            event = "FALLBACK_CANDIDATE_SKIPPED",
                            details = "index=$index stream=$candidateKey reason=dv_profile_5"
                        )
                        continue
                    }
                    logEvent(
                        event = "FALLBACK_CANDIDATE_ACCEPTED",
                        details = "index=$index stream=$candidateKey profile=${candidateProbe.profileLabel}"
                    )
                    return finalizeResult(
                        playbackInfo = candidate.applyTo(playbackInfo),
                        fallbackApplied = true,
                        reason = reason,
                        probeResult = candidateProbe
                    )
                }
                DolbyVisionProfileProbeStatus.NOT_DOLBY_VISION -> {
                    logEvent(
                        event = "FALLBACK_CANDIDATE_ACCEPTED",
                        details = "index=$index stream=$candidateKey reason=probe_not_dv"
                    )
                    return finalizeResult(
                        playbackInfo = candidate.applyTo(playbackInfo),
                        fallbackApplied = true,
                        reason = reason,
                        probeResult = candidateProbe
                    )
                }
                else -> {
                    logEvent(
                        event = "FALLBACK_CANDIDATE_SKIPPED",
                        details = "index=$index stream=$candidateKey reason=${candidateProbe.status}"
                    )
                    continue
                }
            }
        }

        logEvent(
            event = "FALLBACK_SEARCH_EXHAUSTED",
            details = "candidatesChecked=${candidates.size}"
        )
        return finalizeResult(
            playbackInfo = playbackInfo,
            fallbackApplied = false,
            reason = DolbyVisionAutoPlayDecisionReason.NO_FALLBACK_AVAILABLE,
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
        if (playbackInfo.autoPlayFallbackCandidates.isNotEmpty()) {
            logEvent(
                event = "FALLBACK_CANDIDATES",
                details = "count=${playbackInfo.autoPlayFallbackCandidates.size}"
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
    private val backend: NativeDolbyVisionProfileBackend = DefaultNativeDolbyVisionProfileBackend
) : DolbyVisionProfileProbe {
    override suspend fun probe(
        context: Context,
        url: String,
        headers: Map<String, String>?,
        filename: String?
    ): DolbyVisionProfileProbeResult = withContext(Dispatchers.IO) {
        runCatching {
            val headerBlob = headers.toHeaderBlob()
            val deviceSnapshot = runCatching { context.applicationContext }
                .getOrNull()
                ?.let { appContext ->
                    runCatching { DeviceCapabilitySnapshotProvider(appContext).capture() }.getOrNull()
                }
            val metadata = if (backend === DefaultNativeDolbyVisionProfileBackend) {
                FfmpegStreamMetadataProbe.probe(url = url, headers = headers.orEmpty())
            } else {
                FfmpegStreamMetadataProbe.parse(backend.probeStreamMetadataJson(url, headerBlob))
            }
            parseStreamMetadataProbeResult(
                metadata = metadata,
                device = deviceSnapshot
            ) ?: DolbyVisionProfileProbeResult.failed("ffprobe_probe_failed")
        }.getOrElse { error ->
            Log.w(DV_AUTOPLAY_TAG, "FFmpeg Dolby Vision probe failed: ${error.message}")
            DolbyVisionProfileProbeResult.failed(error.message)
        }
    }
}

private object DefaultNativeDolbyVisionProfileBackend : NativeDolbyVisionProfileBackend {
    override fun probe(url: String, requestHeadersBlob: String?): Int {
        return FfmpegLibrary.probeDolbyVisionProfile(url, requestHeadersBlob)
    }

    override fun probeMetadataBlob(url: String, requestHeadersBlob: String?): String? {
        return FfmpegLibrary.probeDolbyVisionMetadataBlob(url, requestHeadersBlob)
    }

    override fun probeBlob(url: String, requestHeadersBlob: String?): String? {
        return FfmpegLibrary.probeDolbyVisionProbeBlob(url, requestHeadersBlob)
    }

    override fun probeStreamMetadataJson(url: String, requestHeadersBlob: String?): String? {
        return FfmpegLibrary.probeDolbyVisionStreamMetadataJson(url, requestHeadersBlob)
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

private fun parseNativeDolbyVisionProbeBlob(blob: String?): DolbyVisionProfileProbeResult {
    if (blob.isNullOrBlank()) return DolbyVisionProfileProbeResult.failed("empty_probe_blob")
    val entries = blob.split(';')
        .mapNotNull { entry ->
            val delimiter = entry.indexOf('=')
            if (delimiter <= 0 || delimiter == entry.lastIndex) return@mapNotNull null
            val key = entry.substring(0, delimiter).trim()
            val value = entry.substring(delimiter + 1).trim()
            if (key.isEmpty() || value.isEmpty()) null else key to value
        }
        .toMap()
    val videoCodec = entries["video"]?.takeUnless { it == "unknown" }
    val audioCodec = entries["audio"]?.takeUnless { it == "unknown" }
    val hdrType = entries["hdr"]?.takeUnless { it == "unknown" }
    return when (entries["status"]) {
        "detected" -> {
            val profileNumber = entries["profile"]?.toIntOrNull()
            if (profileNumber == null) {
                DolbyVisionProfileProbeResult.failed("invalid_profile_blob").copy(
                    videoCodec = videoCodec,
                    audioCodec = audioCodec,
                    hdrType = hdrType
                )
            } else {
                DolbyVisionProfileProbeResult.detected(
                    profileLabel = "dv_profile_$profileNumber",
                    profileNumber = profileNumber,
                    videoCodec = videoCodec,
                    audioCodec = audioCodec,
                    hdrType = hdrType
                )
            }
        }
        "not_dolby_vision" -> DolbyVisionProfileProbeResult.notDolbyVision(
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            hdrType = hdrType
        )
        "unknown" -> DolbyVisionProfileProbeResult.unknown(
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            hdrType = hdrType
        )
        else -> DolbyVisionProfileProbeResult.failed(entries["error"]).copy(
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            hdrType = hdrType
        )
    }
}

private fun parseStreamMetadataProbeResult(
    metadata: FfmpegStreamMetadataProbeResult?,
    device: DeviceCapabilitySnapshot?
): DolbyVisionProfileProbeResult? {
    val streams = metadata?.streams ?: return null

    val selectedVideo = selectBestVideoStream(streams, device) ?: return null
    val selectedAudio = selectBestAudioStream(streams, device)

    return if (selectedVideo.stream.dvProfile != null) {
        DolbyVisionProfileProbeResult.detected(
            profileLabel = "dv_profile_${selectedVideo.stream.dvProfile}",
            profileNumber = selectedVideo.stream.dvProfile,
            videoCodec = selectedVideo.stream.codecName,
            audioCodec = selectedAudio?.stream?.codecName,
            hdrType = selectedVideo.selectedHdrTier.toProbeHdrType()
        )
    } else {
        DolbyVisionProfileProbeResult.notDolbyVision(
            videoCodec = selectedVideo.stream.codecName,
            audioCodec = selectedAudio?.stream?.codecName,
            hdrType = selectedVideo.selectedHdrTier.toProbeHdrType()
        )
    }
}

private data class SelectedVideoStream(
    val stream: FfmpegStreamMetadata,
    val selectedHdrTier: ShadowHdrTier
)

private data class SelectedAudioStream(
    val stream: FfmpegStreamMetadata
)

private fun selectBestVideoStream(
    streams: List<FfmpegStreamMetadata>,
    device: DeviceCapabilitySnapshot?
): SelectedVideoStream? {
    val rewards = BenchmarkAwareStreamScoringConfig.default().contentRewards
    return streams.asSequence()
        .filter { it.codecType.equals("video", ignoreCase = true) }
        .map { stream ->
            val codecTier = resolveProbeVideoCodecTier(stream.codecName, device)
            val hdrPolicy = resolveProbeHdrPolicy(resolveProbeHdrTier(stream), device)
            val supported = codecTier != ShadowVideoCodecTier.UNSUPPORTED &&
                hdrPolicy.second != ShadowSupportLevel.UNSUPPORTED
            val score = rewards.codec.getValue(codecTier) +
                if (hdrPolicy.second == ShadowSupportLevel.UNSUPPORTED) 0 else rewards.hdr.getValue(hdrPolicy.first)
            Triple(SelectedVideoStream(stream, hdrPolicy.first), supported, score)
        }
        .sortedWith(
            compareByDescending<Triple<SelectedVideoStream, Boolean, Int>> { it.second }
                .thenByDescending { it.third }
                .thenByDescending { it.first.stream.dvProfile != null }
        )
        .map { it.first }
        .firstOrNull()
}

private fun selectBestAudioStream(
    streams: List<FfmpegStreamMetadata>,
    device: DeviceCapabilitySnapshot?
): SelectedAudioStream? {
    val rewards = BenchmarkAwareStreamScoringConfig.default().contentRewards
    return streams.asSequence()
        .filter { it.codecType.equals("audio", ignoreCase = true) }
        .map { stream ->
            val tier = resolveProbeAudioTier(stream.codecName)
            val supported = probeAudioTierSupported(tier, device)
            val points = rewards.audio.getValue(tier)
            Triple(SelectedAudioStream(stream), supported, if (supported) points else -points)
        }
        .sortedWith(
            compareByDescending<Triple<SelectedAudioStream, Boolean, Int>> { it.second }
                .thenByDescending { it.third }
        )
        .map { it.first }
        .firstOrNull()
}

private fun resolveProbeVideoCodecTier(
    codecName: String?,
    device: DeviceCapabilitySnapshot?
): ShadowVideoCodecTier {
    val normalized = codecName.orEmpty().lowercase(Locale.US)
    return when {
        normalized.contains("av1") -> when {
            device == null -> ShadowVideoCodecTier.AV1_HW
            device.videoDecode.av1?.hardwareAccelerated == true -> ShadowVideoCodecTier.AV1_HW
            device.videoDecode.av1?.softwareOnlyAvailable == true -> ShadowVideoCodecTier.AV1_SW
            else -> ShadowVideoCodecTier.UNSUPPORTED
        }
        normalized.contains("hevc") || normalized.contains("h265") -> when {
            device == null -> ShadowVideoCodecTier.HEVC_HW
            device.videoDecode.hevc?.hardwareAccelerated == true -> ShadowVideoCodecTier.HEVC_HW
            device.videoDecode.hevc?.softwareOnlyAvailable == true -> ShadowVideoCodecTier.HEVC_SW
            else -> ShadowVideoCodecTier.UNSUPPORTED
        }
        normalized.contains("h264") || normalized.contains("avc") -> when {
            device == null -> ShadowVideoCodecTier.H264_HW
            device.videoDecode.h264?.hardwareAccelerated == true -> ShadowVideoCodecTier.H264_HW
            else -> ShadowVideoCodecTier.UNSUPPORTED
        }
        normalized.contains("vc1") || normalized.contains("vc-1") || normalized.contains("wvc1") -> ShadowVideoCodecTier.VC1
        normalized.contains("mpeg2") || normalized.contains("mpeg-2") -> ShadowVideoCodecTier.MPEG2
        normalized.isBlank() -> ShadowVideoCodecTier.OTHER
        else -> ShadowVideoCodecTier.OTHER
    }
}

private fun resolveProbeHdrTier(stream: FfmpegStreamMetadata): ShadowHdrTier {
    return when {
        stream.dvProfile != null -> ShadowHdrTier.DOLBY_VISION
        stream.hdr10Plus -> ShadowHdrTier.HDR10_PLUS
        stream.colorTransfer.equals("arib-std-b67", ignoreCase = true) -> ShadowHdrTier.HLG
        stream.colorTransfer.equals("smpte2084", ignoreCase = true) &&
            stream.colorPrimaries.equals("bt2020", ignoreCase = true) -> ShadowHdrTier.HDR10
        else -> ShadowHdrTier.SDR
    }
}

private fun resolveProbeHdrPolicy(
    originalHdrTier: ShadowHdrTier,
    device: DeviceCapabilitySnapshot?
): Pair<ShadowHdrTier, ShadowSupportLevel> {
    val originalSupport = resolveProbeHdrSupportLevel(originalHdrTier, device)
    return when (originalHdrTier) {
        ShadowHdrTier.DOLBY_VISION -> when {
            originalSupport == ShadowSupportLevel.FULL ->
                ShadowHdrTier.DOLBY_VISION to ShadowSupportLevel.FULL
            resolveProbeHdrSupportLevel(ShadowHdrTier.HDR10, device) == ShadowSupportLevel.FULL ->
                ShadowHdrTier.HDR10 to ShadowSupportLevel.FALLBACK
            else -> ShadowHdrTier.DOLBY_VISION to ShadowSupportLevel.UNSUPPORTED
        }
        ShadowHdrTier.HDR10_PLUS -> when {
            originalSupport == ShadowSupportLevel.FULL ->
                ShadowHdrTier.HDR10_PLUS to ShadowSupportLevel.FULL
            resolveProbeHdrSupportLevel(ShadowHdrTier.HDR10, device) == ShadowSupportLevel.FULL ->
                ShadowHdrTier.HDR10 to ShadowSupportLevel.FALLBACK
            else -> ShadowHdrTier.HDR10_PLUS to ShadowSupportLevel.UNSUPPORTED
        }
        else -> originalHdrTier to originalSupport
    }
}

private fun resolveProbeHdrSupportLevel(
    hdrTier: ShadowHdrTier,
    device: DeviceCapabilitySnapshot?
): ShadowSupportLevel {
    val hdrTypes = device?.displayHdrTypes ?: return ShadowSupportLevel.FULL
    return when (hdrTier) {
        ShadowHdrTier.DOLBY_VISION ->
            if (DeviceHdrType.DOLBY_VISION in hdrTypes) ShadowSupportLevel.FULL else ShadowSupportLevel.UNSUPPORTED
        ShadowHdrTier.HDR10_PLUS ->
            if (DeviceHdrType.HDR10_PLUS in hdrTypes) ShadowSupportLevel.FULL
            else if (DeviceHdrType.HDR10 in hdrTypes) ShadowSupportLevel.PARTIAL
            else ShadowSupportLevel.UNSUPPORTED
        ShadowHdrTier.HDR10 ->
            if (DeviceHdrType.HDR10 in hdrTypes || DeviceHdrType.HDR10_PLUS in hdrTypes) ShadowSupportLevel.FULL
            else ShadowSupportLevel.UNSUPPORTED
        ShadowHdrTier.HLG ->
            if (DeviceHdrType.HLG in hdrTypes) ShadowSupportLevel.FULL else ShadowSupportLevel.UNSUPPORTED
        ShadowHdrTier.SDR -> ShadowSupportLevel.FULL
    }
}

private fun resolveProbeAudioTier(codecName: String?): ShadowAudioTier {
    val normalized = codecName.orEmpty().lowercase(Locale.US)
    return when {
        normalized.contains("truehd") -> ShadowAudioTier.TRUEHD
        normalized.contains("eac3") -> ShadowAudioTier.DDP
        normalized.contains("ac3") -> ShadowAudioTier.AC3
        normalized == "dts" || normalized.contains("dts") -> ShadowAudioTier.DTS
        else -> ShadowAudioTier.OTHER
    }
}

private fun probeAudioTierSupported(
    tier: ShadowAudioTier,
    device: DeviceCapabilitySnapshot?
): Boolean {
    val output = device?.audioOutput ?: return true
    return when (tier) {
        ShadowAudioTier.TRUEHD_ATMOS -> output.truehd.passthroughLikely
        ShadowAudioTier.DTSX -> output.dtsx.passthroughLikely
        ShadowAudioTier.TRUEHD -> output.truehd.passthroughLikely
        ShadowAudioTier.DTSHD -> output.dtshd.passthroughLikely
        ShadowAudioTier.DDP_ATMOS -> output.atmos.passthroughLikely || output.eac3.passthroughLikely
        ShadowAudioTier.DDP -> output.eac3.passthroughLikely
        ShadowAudioTier.AC3 -> output.ac3.passthroughLikely
        ShadowAudioTier.DTS -> output.dts.passthroughLikely
        ShadowAudioTier.OTHER -> true
    }
}

private fun ShadowHdrTier.toProbeHdrType(): String {
    return when (this) {
        ShadowHdrTier.DOLBY_VISION -> "dolbyvision"
        ShadowHdrTier.HDR10_PLUS -> "hdr10+"
        ShadowHdrTier.HDR10 -> "hdr10"
        ShadowHdrTier.HLG -> "hlg"
        ShadowHdrTier.SDR -> "sdr"
    }
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
    @Suppress("DEPRECATION")
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
        autoPlayFallbackCandidates = emptyList()
    )
}

private fun Map<String, String>?.toHeaderBlob(): String? {
    if (this.isNullOrEmpty()) return null
    return entries.joinToString(separator = "\r\n", postfix = "\r\n") { (key, value) ->
        "$key: $value"
    }
}
