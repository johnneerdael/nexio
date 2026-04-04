package com.nexio.tv.data.repository.benchmark

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import com.nexio.tv.data.local.PlayerSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

internal data class DecoderCapabilityInfo(
    val codecName: String,
    val mimeType: String,
    val hardwareAccelerated: Boolean,
    val softwareOnly: Boolean,
    val secureSupported: Boolean
)

internal data class VideoDecodeCaptureResult(
    val capabilities: DeviceVideoDecodeCapabilities,
    val evidence: DeviceVideoDecoderEvidence
)

internal data class DisplayHdrCaptureResult(
    val types: Set<DeviceHdrType>,
    val evidence: DeviceHdrCapabilityEvidence?
)

internal data class AudioCapabilityCaptureResult(
    val capabilities: DeviceAudioOutputCapabilities,
    val evidence: DeviceAudioCapabilityEvidence?
)

internal data class AudioCapabilityProbeSpec(
    val bucket: String,
    val encoding: Int,
    val channelMask: Int,
    val sampleRateHz: Int
)

@Singleton
class DeviceCapabilitySnapshotProvider internal constructor(
    @ApplicationContext private val context: Context,
    private val nowMs: () -> Long
) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context = context,
        nowMs = System::currentTimeMillis
    )

    fun capture(playerSettings: PlayerSettings = PlayerSettings()): DeviceCapabilitySnapshot? {
        return runCatching {
            val hdrCapture = captureDisplayHdr(context)
            val videoCapture = captureVideoDecodeCapabilities()
            val audioCapture = captureAudioOutputCapabilities(context, playerSettings)
            DeviceCapabilitySnapshot(
                model = Build.MODEL?.takeIf { it.isNotBlank() },
                manufacturer = Build.MANUFACTURER?.takeIf { it.isNotBlank() },
                sdkInt = Build.VERSION.SDK_INT,
                displayHdrTypes = hdrCapture.types,
                videoDecode = videoCapture.capabilities,
                audioOutput = audioCapture.capabilities,
                evidence = DeviceCapabilityEvidence(
                    hdr = hdrCapture.evidence,
                    audio = audioCapture.evidence,
                    video = videoCapture.evidence
                ),
                capturedAtMs = nowMs()
            )
        }.getOrNull()
    }

    private fun captureVideoDecodeCapabilities(): VideoDecodeCaptureResult {
        val decoders = runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .asSequence()
                .filterNot { it.isEncoder }
                .filterNot { it.isAliasCompat() }
                .flatMap { codecInfo ->
                    codecInfo.supportedTypes.asSequence().mapNotNull { supportedType ->
                        supportedType
                            .takeIf { it.isNotBlank() }
                            ?.lowercase(Locale.US)
                            ?.takeIf { it in BENCHMARK_VIDEO_MIME_TYPES }
                            ?.let { mimeType ->
                            DecoderCapabilityInfo(
                                codecName = codecInfo.name,
                                mimeType = mimeType,
                                hardwareAccelerated = codecInfo.isHardwareAcceleratedCompat(),
                                softwareOnly = codecInfo.isSoftwareOnlyCompat(),
                                secureSupported = codecInfo.supportsSecurePlayback(mimeType)
                            )
                        }
                    }
                }
                .toList()
        }.getOrDefault(emptyList())

        return VideoDecodeCaptureResult(
            capabilities = DeviceVideoDecodeCapabilities(
                h264 = buildCodecSupportForMime(decoders, MimeTypes.VIDEO_H264),
                hevc = buildCodecSupportForMime(decoders, MimeTypes.VIDEO_H265),
                av1 = buildCodecSupportForMime(decoders, MimeTypes.VIDEO_AV1),
                dolbyVision = buildCodecSupportForMime(decoders, MimeTypes.VIDEO_DOLBY_VISION)
            ),
            evidence = DeviceVideoDecoderEvidence(
                scannedDecoderCount = decoders.size,
                decoders = decoders.map { decoder ->
                    VideoDecoderEvidence(
                        codecName = decoder.codecName,
                        mimeType = decoder.mimeType,
                        hardwareAccelerated = decoder.hardwareAccelerated,
                        softwareOnly = decoder.softwareOnly,
                        secureSupported = decoder.secureSupported
                    )
                }
            )
        )
    }
}

@Suppress("DEPRECATION")
internal fun captureDisplayHdr(context: Context): DisplayHdrCaptureResult {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
        return DisplayHdrCaptureResult(types = emptySet(), evidence = null)
    }
    val displayManager = context.getSystemService(DisplayManager::class.java)
        ?: return DisplayHdrCaptureResult(types = emptySet(), evidence = null)
    val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        ?: return DisplayHdrCaptureResult(types = emptySet(), evidence = null)
    val hdrTypes = display.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
    return DisplayHdrCaptureResult(
        types = normalizeHdrTypes(hdrTypes),
        evidence = DeviceHdrCapabilityEvidence(
            displayId = display.displayId,
            rawSupportedHdrTypes = hdrTypes.map(::hdrTypeWireName)
        )
    )
}

internal fun normalizeHdrTypes(supportedHdrTypes: IntArray): Set<DeviceHdrType> {
    return buildSet {
        supportedHdrTypes.forEach { hdrType ->
            when (hdrType) {
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> add(DeviceHdrType.DOLBY_VISION)
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> add(DeviceHdrType.HDR10)
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> add(DeviceHdrType.HDR10_PLUS)
                Display.HdrCapabilities.HDR_TYPE_HLG -> add(DeviceHdrType.HLG)
            }
        }
    }
}

internal fun captureAudioOutputCapabilities(
    context: Context,
    playerSettings: PlayerSettings = PlayerSettings()
): AudioCapabilityCaptureResult {
    playerSettings
    val audioManager = context.getSystemService(AudioManager::class.java)
        ?: return AudioCapabilityCaptureResult(
            capabilities = DeviceAudioOutputCapabilities(),
            evidence = null
        )
    val mediaAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
        .build()
    val directPlaybackProbes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        captureDirectPlaybackProbes(audioManager, mediaAttributes)
    } else {
        emptyList()
    }
    val normalized = buildAudioOutputCapabilitiesFromProbes(directPlaybackProbes)
    return AudioCapabilityCaptureResult(
        capabilities = normalized,
        evidence = DeviceAudioCapabilityEvidence(
            discoveryMode = when {
                directPlaybackProbes.isNotEmpty() -> "direct_playback_support"
                else -> "none"
            },
            routedDeviceTypes = emptyList(),
            outputDevices = emptyList(),
            directProfiles = emptyList(),
            directPlaybackProbes = directPlaybackProbes
        )
    )
}

@androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun captureDirectPlaybackProbes(
    audioManager: AudioManager,
    audioAttributes: AudioAttributes
): List<AudioPlaybackProbeEvidence> {
    return buildAudioCapabilityProbeSpecs().map { spec ->
        val audioFormat = AudioFormat.Builder()
            .setEncoding(spec.encoding)
            .setChannelMask(spec.channelMask)
            .setSampleRate(spec.sampleRateHz)
            .build()
        val support = runCatching {
            AudioManager.getDirectPlaybackSupport(audioFormat, audioAttributes)
        }.getOrDefault(AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED)
        AudioPlaybackProbeEvidence(
            bucket = spec.bucket,
            format = audioEncodingWireName(spec.encoding),
            channelMask = spec.channelMask,
            sampleRateHz = spec.sampleRateHz,
            supportMode = directPlaybackSupportWireName(support)
        )
    }
}

internal fun buildAudioOutputCapabilitiesFromProbes(
    probes: List<AudioPlaybackProbeEvidence>
): DeviceAudioOutputCapabilities {
    fun bucketSupport(bucket: String): AudioEncodingSupport {
        val relevant = probes.filter { it.bucket == bucket }
        val passthroughLikely = relevant.any { it.supportMode == DIRECT_PLAYBACK_BITSTREAM_SUPPORTED_WIRE }
        val supported = passthroughLikely
        return AudioEncodingSupport(supported = supported, passthroughLikely = passthroughLikely)
    }
    return DeviceAudioOutputCapabilities(
        ac3 = bucketSupport("ac3"),
        eac3 = bucketSupport("eac3"),
        atmos = bucketSupport("atmos"),
        truehd = bucketSupport("truehd"),
        dts = bucketSupport("dts"),
        dtshd = bucketSupport("dtshd"),
        dtsx = bucketSupport("dtsx")
    )
}

internal fun buildCodecSupportForMime(
    decoders: List<DecoderCapabilityInfo>,
    mimeType: String
): CodecSupport? {
    val matching = decoders.filter { it.mimeType.equals(mimeType, ignoreCase = true) }
    if (matching.isEmpty()) return null
    return CodecSupport(
        hardwareAccelerated = matching.any { it.hardwareAccelerated },
        softwareOnlyAvailable = matching.any { it.softwareOnly },
        secureSupported = matching.any { it.secureSupported }
    )
}

private fun MediaCodecInfo.isHardwareAcceleratedCompat(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        isHardwareAccelerated
    } else {
        !isSoftwareOnlyCompat()
    }
}

private fun MediaCodecInfo.isSoftwareOnlyCompat(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        isSoftwareOnly
    } else {
        SOFTWARE_CODEC_NAME_PREFIXES.any { prefix ->
            name.startsWith(prefix, ignoreCase = true)
        }
    }
}

private fun MediaCodecInfo.isAliasCompat(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isAlias
}

private fun MediaCodecInfo.supportsSecurePlayback(mimeType: String): Boolean {
    return runCatching {
        getCapabilitiesForType(mimeType)
            .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback)
    }.getOrDefault(false)
}

private fun AudioDeviceInfo.isBenchmarkPassthroughDevice(): Boolean {
    return type == AudioDeviceInfo.TYPE_HDMI ||
        type == AudioDeviceInfo.TYPE_HDMI_ARC ||
        type == AudioDeviceInfo.TYPE_HDMI_EARC
}

internal fun hdrTypeWireName(hdrType: Int): String {
    return when (hdrType) {
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> DeviceHdrType.DOLBY_VISION.wireKey
        Display.HdrCapabilities.HDR_TYPE_HDR10 -> DeviceHdrType.HDR10.wireKey
        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> DeviceHdrType.HDR10_PLUS.wireKey
        Display.HdrCapabilities.HDR_TYPE_HLG -> DeviceHdrType.HLG.wireKey
        else -> "unknown:$hdrType"
    }
}

internal fun deviceTypeWireName(deviceType: Int): String {
    return when (deviceType) {
        AudioDeviceInfo.TYPE_HDMI -> "hdmi"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "hdmi_arc"
        AudioDeviceInfo.TYPE_HDMI_EARC -> "hdmi_earc"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "builtin_speaker"
        else -> "type_$deviceType"
    }
}

internal fun audioEncodingWireName(encoding: Int): String {
    return when (encoding) {
        C.ENCODING_PCM_16BIT -> "pcm_16bit"
        C.ENCODING_AC3 -> "ac3"
        C.ENCODING_E_AC3 -> "eac3"
        C.ENCODING_E_AC3_JOC -> "eac3_joc"
        C.ENCODING_DOLBY_TRUEHD -> "truehd"
        C.ENCODING_DTS -> "dts"
        C.ENCODING_DTS_HD -> "dtshd"
        reflectedAudioEncoding("ENCODING_DTS_UHD_P1") -> "dts_uhd_p1"
        reflectedAudioEncoding("ENCODING_DTS_UHD_P2") -> "dts_uhd_p2"
        C.ENCODING_AC4 -> "ac4"
        else -> "encoding_$encoding"
    }
}

internal fun directPlaybackSupportWireName(value: Int): String {
    return when (value) {
        AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED -> DIRECT_PLAYBACK_NOT_SUPPORTED_WIRE
        AudioManager.DIRECT_PLAYBACK_OFFLOAD_SUPPORTED -> DIRECT_PLAYBACK_OFFLOAD_SUPPORTED_WIRE
        AudioManager.DIRECT_PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED -> DIRECT_PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED_WIRE
        AudioManager.DIRECT_PLAYBACK_BITSTREAM_SUPPORTED -> DIRECT_PLAYBACK_BITSTREAM_SUPPORTED_WIRE
        else -> "unknown:$value"
    }
}

internal fun buildAudioCapabilityProbeSpecs(): List<AudioCapabilityProbeSpec> {
    val specs = mutableListOf(
        AudioCapabilityProbeSpec("ac3", C.ENCODING_AC3, AudioFormat.CHANNEL_OUT_5POINT1, 48_000),
        AudioCapabilityProbeSpec("eac3", C.ENCODING_E_AC3, AudioFormat.CHANNEL_OUT_5POINT1, 48_000),
        AudioCapabilityProbeSpec("eac3", C.ENCODING_E_AC3, AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, 48_000),
        AudioCapabilityProbeSpec("atmos", C.ENCODING_E_AC3_JOC, AudioFormat.CHANNEL_OUT_5POINT1, 48_000),
        AudioCapabilityProbeSpec("atmos", C.ENCODING_E_AC3_JOC, AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, 48_000),
        AudioCapabilityProbeSpec("truehd", C.ENCODING_DOLBY_TRUEHD, AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, 48_000),
        AudioCapabilityProbeSpec("dts", C.ENCODING_DTS, AudioFormat.CHANNEL_OUT_5POINT1, 48_000),
        AudioCapabilityProbeSpec("dtshd", C.ENCODING_DTS_HD, AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, 48_000)
    )
    reflectedAudioEncoding("ENCODING_DTS_UHD_P1")?.let {
        specs += AudioCapabilityProbeSpec("dtsx", it, AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, 48_000)
    }
    reflectedAudioEncoding("ENCODING_DTS_UHD_P2")?.let {
        specs += AudioCapabilityProbeSpec("dtsx", it, AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, 48_000)
    }
    return specs
}

private fun reflectedAudioEncoding(fieldName: String): Int? {
    return runCatching { AudioFormat::class.java.getField(fieldName).getInt(null) }.getOrNull()
}

private const val DIRECT_PLAYBACK_NOT_SUPPORTED_WIRE = "not_supported"
private const val DIRECT_PLAYBACK_OFFLOAD_SUPPORTED_WIRE = "offload_supported"
private const val DIRECT_PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED_WIRE = "offload_gapless_supported"
private const val DIRECT_PLAYBACK_BITSTREAM_SUPPORTED_WIRE = "bitstream_supported"

private val SOFTWARE_CODEC_NAME_PREFIXES = listOf(
    "OMX.google.",
    "OMX.ffmpeg.",
    "c2.android.",
    "c2.google."
)

private val BENCHMARK_VIDEO_MIME_TYPES = setOf(
    MimeTypes.VIDEO_H264.lowercase(Locale.US),
    MimeTypes.VIDEO_H265.lowercase(Locale.US),
    MimeTypes.VIDEO_AV1.lowercase(Locale.US),
    MimeTypes.VIDEO_DOLBY_VISION.lowercase(Locale.US)
)
