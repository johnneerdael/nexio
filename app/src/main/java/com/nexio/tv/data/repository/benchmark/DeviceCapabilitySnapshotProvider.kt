package com.nexio.tv.data.repository.benchmark

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
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
    val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .filter { it.isBenchmarkPassthroughDevice() }
    val routedDevices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        runCatching { audioManager.getAudioDevicesForAttributes(mediaAttributes) }.getOrDefault(emptyList())
            .filter { it.isBenchmarkPassthroughDevice() }
    } else {
        emptyList()
    }
    val prioritizedDevices = if (routedDevices.isNotEmpty()) routedDevices else outputDevices
    val directProfiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        runCatching { audioManager.getDirectProfilesForAttributes(mediaAttributes) }.getOrDefault(emptyList())
    } else {
        emptyList()
    }
    val deviceEncodings = prioritizedDevices
        .flatMap { device -> device.encodings.toList() }
        .toSet()
    val directProfileEncodings = directProfiles
        .map { it.format }
        .toSet()
    val normalized = DeviceAudioOutputCapabilities(
        ac3 = buildAudioEncodingSupport(
            directProfileEncodings = directProfileEncodings,
            deviceEncodings = deviceEncodings,
            passthroughEncodings = intArrayOf(C.ENCODING_AC3)
        ),
        eac3 = buildAudioEncodingSupport(
            directProfileEncodings = directProfileEncodings,
            deviceEncodings = deviceEncodings,
            passthroughEncodings = intArrayOf(C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC)
        ),
        truehd = buildAudioEncodingSupport(
            directProfileEncodings = directProfileEncodings,
            deviceEncodings = deviceEncodings,
            passthroughEncodings = intArrayOf(C.ENCODING_DOLBY_TRUEHD)
        ),
        dts = buildAudioEncodingSupport(
            directProfileEncodings = directProfileEncodings,
            deviceEncodings = deviceEncodings,
            passthroughEncodings = intArrayOf(
                C.ENCODING_DTS,
                C.ENCODING_DTS_HD,
                C.ENCODING_DTS_UHD_P2
            )
        ),
        dtshd = buildAudioEncodingSupport(
            directProfileEncodings = directProfileEncodings,
            deviceEncodings = deviceEncodings,
            passthroughEncodings = intArrayOf(C.ENCODING_DTS_HD, C.ENCODING_DTS_UHD_P2)
        )
    )
    return AudioCapabilityCaptureResult(
        capabilities = normalized,
        evidence = DeviceAudioCapabilityEvidence(
            discoveryMode = when {
                directProfiles.isNotEmpty() -> "direct_profiles"
                prioritizedDevices.isNotEmpty() -> "device_encodings"
                else -> "none"
            },
            routedDeviceTypes = routedDevices.map { deviceTypeWireName(it.type) }.distinct(),
            outputDevices = prioritizedDevices.map { device ->
                AudioOutputDeviceEvidence(
                    id = device.id,
                    type = deviceTypeWireName(device.type),
                    productName = device.productName?.toString()?.takeIf { it.isNotBlank() },
                    encodings = device.encodings.map(::audioEncodingWireName).distinct()
                )
            },
            directProfiles = directProfiles.map { profile ->
                AudioDirectProfileEvidence(
                    format = audioEncodingWireName(profile.format),
                    channelMasks = profile.channelMasks.toList(),
                    sampleRates = profile.sampleRates.toList()
                )
            }
        )
    )
}

internal fun buildAudioEncodingSupport(
    directProfileEncodings: Set<Int>,
    deviceEncodings: Set<Int>,
    passthroughEncodings: IntArray
): AudioEncodingSupport {
    val supported = passthroughEncodings.any { encoding ->
        encoding in directProfileEncodings || encoding in deviceEncodings
    }
    val passthroughLikely = if (directProfileEncodings.isNotEmpty()) {
        passthroughEncodings.any { it in directProfileEncodings }
    } else {
        supported
    }
    return AudioEncodingSupport(
        supported = supported,
        passthroughLikely = passthroughLikely
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
        C.ENCODING_DTS_UHD_P2 -> "dts_uhd_p2"
        C.ENCODING_AC4 -> "ac4"
        else -> "encoding_$encoding"
    }
}

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
