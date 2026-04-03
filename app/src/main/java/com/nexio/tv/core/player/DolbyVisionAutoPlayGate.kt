package com.nexio.tv.core.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaExtractor
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.container.DolbyVisionConfig
import com.nexio.tv.ui.screens.stream.AutoPlayStreamAlternative
import com.nexio.tv.ui.screens.stream.StreamPlaybackInfo
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import io.github.anilbeesetti.nextlib.mediainfo.MediaInfo
import io.github.anilbeesetti.nextlib.mediainfo.MediaInfoBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.util.Locale

private const val DV_AUTOPLAY_TAG = "DvAutoPlayGate"

enum class DolbyVisionAutoPlayDecisionReason {
    NOT_AUTOPLAY,
    DISPLAY_SUPPORTS_DOLBY_VISION,
    NOT_DOLBY_VISION,
    PROBE_NOT_DOLBY_VISION,
    PROFILE_ALLOWED,
    UNSUPPORTED_PROFILE_5,
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
    val error: String? = null
) {
    companion object {
        fun detected(profileLabel: String, profileNumber: Int): DolbyVisionProfileProbeResult {
            return DolbyVisionProfileProbeResult(
                status = DolbyVisionProfileProbeStatus.DETECTED,
                profileLabel = profileLabel,
                profileNumber = profileNumber
            )
        }

        fun notDolbyVision(): DolbyVisionProfileProbeResult {
            return DolbyVisionProfileProbeResult(status = DolbyVisionProfileProbeStatus.NOT_DOLBY_VISION)
        }

        fun unknown(): DolbyVisionProfileProbeResult {
            return DolbyVisionProfileProbeResult(status = DolbyVisionProfileProbeStatus.UNKNOWN)
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

fun interface NativeDolbyVisionProfileBackend {
    fun probe(url: String, requestHeadersBlob: String?): Int
}

data class DolbyVisionAutoPlayGateResult(
    val playbackInfo: StreamPlaybackInfo,
    val fallbackApplied: Boolean,
    val reason: DolbyVisionAutoPlayDecisionReason,
    val probeResult: DolbyVisionProfileProbeResult? = null
)

class DolbyVisionAutoPlayGate(
    private val probe: DolbyVisionProfileProbe = FfmpegDolbyVisionProfileProbe()
) {

    suspend fun resolve(
        context: Context,
        playbackInfo: StreamPlaybackInfo,
        autoPlay: Boolean,
        displaySupportsDolbyVision: Boolean
    ): DolbyVisionAutoPlayGateResult {
        if (!autoPlay) {
            return DolbyVisionAutoPlayGateResult(
                playbackInfo = playbackInfo,
                fallbackApplied = false,
                reason = DolbyVisionAutoPlayDecisionReason.NOT_AUTOPLAY
            )
        }
        if (displaySupportsDolbyVision) {
            return DolbyVisionAutoPlayGateResult(
                playbackInfo = playbackInfo,
                fallbackApplied = false,
                reason = DolbyVisionAutoPlayDecisionReason.DISPLAY_SUPPORTS_DOLBY_VISION
            )
        }
        if (!playbackInfo.isDolbyVisionCandidate) {
            return DolbyVisionAutoPlayGateResult(
                playbackInfo = playbackInfo,
                fallbackApplied = false,
                reason = DolbyVisionAutoPlayDecisionReason.NOT_DOLBY_VISION
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

        val probeResult = probe.probe(
            context = context,
            url = url,
            headers = playbackInfo.headers,
            filename = playbackInfo.filename
        )

        return when (probeResult.status) {
            DolbyVisionProfileProbeStatus.NOT_DOLBY_VISION -> DolbyVisionAutoPlayGateResult(
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
                    DolbyVisionAutoPlayGateResult(
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
            return DolbyVisionAutoPlayGateResult(
                playbackInfo = playbackInfo,
                fallbackApplied = false,
                reason = DolbyVisionAutoPlayDecisionReason.NO_FALLBACK_AVAILABLE,
                probeResult = probeResult
            )
        }
        return DolbyVisionAutoPlayGateResult(
            playbackInfo = fallback.applyTo(playbackInfo),
            fallbackApplied = true,
            reason = reason,
            probeResult = probeResult
        )
    }
}

class FfmpegDolbyVisionProfileProbe(
    private val backend: NativeDolbyVisionProfileBackend = object : NativeDolbyVisionProfileBackend {
        override fun probe(url: String, requestHeadersBlob: String?): Int {
            return FfmpegLibrary.probeDolbyVisionProfile(url, requestHeadersBlob)
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
            when (val profile = backend.probe(url, headers.toHeaderBlob())) {
                -3 -> DolbyVisionProfileProbeResult.failed("ffmpeg_probe_failed")
                -2 -> DolbyVisionProfileProbeResult.unknown()
                -1 -> DolbyVisionProfileProbeResult.notDolbyVision()
                else -> DolbyVisionProfileProbeResult.detected(
                    profileLabel = "dv_profile_$profile",
                    profileNumber = profile
                )
            }
        }.getOrElse { error ->
            Log.w(DV_AUTOPLAY_TAG, "FFmpeg Dolby Vision probe failed: ${error.message}")
            DolbyVisionProfileProbeResult.failed(error.message)
        }
    }
}

class NextLibDolbyVisionProfileProbe : DolbyVisionProfileProbe {

    override suspend fun probe(
        context: Context,
        url: String,
        headers: Map<String, String>?,
        filename: String?
    ): DolbyVisionProfileProbeResult = withContext(Dispatchers.IO) {
        runCatching {
            probeWithMediaInfo(
                context = context,
                url = url,
                filename = filename
            ) ?: probeWithExtractor(
                context = context,
                url = url,
                headers = headers
            ) ?: DolbyVisionProfileProbeResult.unknown()
        }.getOrElse { error ->
            Log.w(DV_AUTOPLAY_TAG, "Dolby Vision probe failed: ${error.message}")
            DolbyVisionProfileProbeResult.failed(error.message)
        }
    }

    private fun probeWithMediaInfo(
        context: Context,
        url: String,
        filename: String?
    ): DolbyVisionProfileProbeResult? {
        var mediaInfo: MediaInfo? = null
        return try {
            val uri = android.net.Uri.parse(url)
            mediaInfo = MediaInfoBuilder().from(context = context, uri = uri).build()
            val videoStream = mediaInfo?.videoStream ?: return null
            val probeValues = buildList {
                addAll(extractStringValues(videoStream))
                filename?.let { add("filename=$it") }
            }
            parseProbeValues(probeValues)
        } catch (error: Throwable) {
            Log.w(DV_AUTOPLAY_TAG, "NextLib Dolby Vision probe failed: ${error.message}")
            null
        } finally {
            runCatching { mediaInfo?.release() }
        }
    }

    private fun extractStringValues(videoStream: Any): List<String> {
        return videoStream.javaClass.methods
            .asSequence()
            .filter { method ->
                method.parameterCount == 0 &&
                    method.declaringClass != Any::class.java &&
                    (method.returnType == String::class.java ||
                        Number::class.java.isAssignableFrom(method.returnType) ||
                        method.returnType == java.lang.Integer.TYPE ||
                        method.returnType == java.lang.Long.TYPE ||
                        method.returnType == java.lang.Boolean.TYPE ||
                        method.returnType == java.lang.Float.TYPE ||
                        method.returnType == java.lang.Double.TYPE)
            }
            .mapNotNull { method ->
                runCatching { method.invoke(videoStream) }
                    .getOrNull()
                    ?.toString()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "${method.name}=$it" }
            }
            .toList()
    }

    private fun probeWithExtractor(
        context: Context,
        url: String,
        headers: Map<String, String>?
    ): DolbyVisionProfileProbeResult? {
        val extractor = MediaExtractor()
        return try {
            val uri = android.net.Uri.parse(url)
            when (uri.scheme?.lowercase(Locale.ROOT)) {
                "http", "https" -> extractor.setDataSource(url, headers ?: emptyMap())
                else -> extractor.setDataSource(context, uri, headers ?: emptyMap())
            }

            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME)?.lowercase(Locale.ROOT)
                if (mime?.startsWith("video/") != true) continue

                val codecString = runCatching {
                    format.getString(android.media.MediaFormat.KEY_CODECS_STRING)
                }.getOrNull()
                parseDolbyVisionProfile(codecString)?.let { (label, profile) ->
                    return DolbyVisionProfileProbeResult.detected(label, profile)
                }

                val csd0 = runCatching { format.getByteBuffer("csd-0") }.getOrNull()
                if (csd0 != null) {
                    val bytes = ByteArray(csd0.remaining())
                    csd0.duplicate().get(bytes)
                    val profile = runCatching {
                        DolbyVisionConfig.parse(ParsableByteArray(bytes))?.profile
                    }.getOrNull()
                    if (profile != null) {
                        return DolbyVisionProfileProbeResult.detected(
                            profileLabel = "dv_profile_$profile",
                            profileNumber = profile
                        )
                    }
                }

                if (mime == "video/dolby-vision" || codecString?.contains("dvhe", ignoreCase = true) == true ||
                    codecString?.contains("dvh1", ignoreCase = true) == true
                ) {
                    return DolbyVisionProfileProbeResult.unknown()
                }
            }
            null
        } catch (error: Throwable) {
            Log.w(DV_AUTOPLAY_TAG, "Extractor Dolby Vision probe failed: ${error.message}")
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun parseProbeValues(values: List<String>): DolbyVisionProfileProbeResult {
        values.forEach { value ->
            parseDolbyVisionProfile(value)?.let { (label, profile) ->
                return DolbyVisionProfileProbeResult.detected(label, profile)
            }
        }
        val normalized = values.joinToString(separator = " ").lowercase(Locale.ROOT)
        return when {
            normalized.contains("dolby vision") || normalized.contains("dvhe") || normalized.contains("dvh1") ->
                DolbyVisionProfileProbeResult.unknown()
            else -> DolbyVisionProfileProbeResult.notDolbyVision()
        }
    }
}

private fun parseDolbyVisionProfile(value: String?): Pair<String, Int>? {
    val normalized = value.orEmpty()
    val match = DOLBY_VISION_CODEC_REGEX.find(normalized) ?: return null
    val family = match.groupValues[1].lowercase(Locale.ROOT)
    val profile = match.groupValues[2].toIntOrNull() ?: return null
    val label = "$family.${match.groupValues[2]}"
    return label to profile
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
        isDolbyVisionCandidate = isDolbyVisionCandidate,
        autoPlayNonDolbyVisionFallback = null
    )
}

private val DOLBY_VISION_CODEC_REGEX = Regex("""\b(dvhe|dvh1)\.(\d{2})\b""", RegexOption.IGNORE_CASE)

private fun Map<String, String>?.toHeaderBlob(): String? {
    if (this.isNullOrEmpty()) return null
    return entries.joinToString(separator = "\r\n", postfix = "\r\n") { (key, value) ->
        "$key: $value"
    }
}
