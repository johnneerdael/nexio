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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.net.URLDecoder
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

class CompositeDolbyVisionProfileProbe(
    private val probes: List<DolbyVisionProfileProbe> = listOf(
        FfmpegDolbyVisionProfileProbe(),
        NextLibDolbyVisionProfileProbe()
    )
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
                DolbyVisionProfileProbeStatus.UNKNOWN -> if (unknownResult == null) unknownResult = result
                DolbyVisionProfileProbeStatus.FAILED -> failedResult = result
            }
        }

        return unknownResult ?: failedResult ?: DolbyVisionProfileProbeResult.unknown()
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
            val candidates = buildProbeUrlCandidates(url)
            var notDolbyVisionObserved = false
            candidates.forEach { candidateUrl ->
                probeWithMediaInfo(
                    context = context,
                    url = candidateUrl,
                    filename = filename
                )?.let { result ->
                    when (result.status) {
                        DolbyVisionProfileProbeStatus.NOT_DOLBY_VISION -> {
                            notDolbyVisionObserved = true
                        }
                        else -> return@runCatching result
                    }
                }
                probeWithExtractor(
                    context = context,
                    url = candidateUrl,
                    headers = headers
                )?.let { result ->
                    when (result.status) {
                        DolbyVisionProfileProbeStatus.NOT_DOLBY_VISION -> {
                            notDolbyVisionObserved = true
                        }
                        else -> return@runCatching result
                    }
                }
            }
            if (notDolbyVisionObserved) {
                DolbyVisionProfileProbeResult.notDolbyVision()
            } else {
                DolbyVisionProfileProbeResult.unknown()
            }
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

    private fun buildProbeUrlCandidates(sourceUrl: String): List<String> {
        if (sourceUrl.isBlank()) return emptyList()
        val embeddedResolveUrl = extractEmbeddedResolveUrl(sourceUrl)
        return buildList {
            add(sourceUrl)
            if (!embeddedResolveUrl.isNullOrBlank() && embeddedResolveUrl != sourceUrl) {
                add(embeddedResolveUrl)
            }
        }
    }

    private fun extractEmbeddedResolveUrl(sourceUrl: String): String? {
        val marker = "/resolve/"
        val markerIndex = sourceUrl.indexOf(marker, ignoreCase = true)
        if (markerIndex < 0) return null

        val afterResolve = sourceUrl.substring(markerIndex + marker.length)
        val nestedEncoded = afterResolve.substringAfter('/', missingDelimiterValue = "")
            .substringAfter('/', missingDelimiterValue = "")
        if (nestedEncoded.isBlank()) return null

        return runCatching {
            URLDecoder.decode(nestedEncoded, Charsets.UTF_8.name())
        }.getOrNull()
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
        isWebDl = isWebDl,
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
