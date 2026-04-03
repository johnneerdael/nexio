package com.nexio.tv.data.repository.benchmark

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioCapabilities
import com.nexio.tv.data.local.PlayerSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

internal data class DecoderCapabilityInfo(
    val mimeType: String,
    val hardwareAccelerated: Boolean,
    val softwareOnly: Boolean,
    val secureSupported: Boolean
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
            DeviceCapabilitySnapshot(
                model = Build.MODEL?.takeIf { it.isNotBlank() },
                manufacturer = Build.MANUFACTURER?.takeIf { it.isNotBlank() },
                sdkInt = Build.VERSION.SDK_INT,
                displayHdrTypes = captureDisplayHdrTypes(context),
                videoDecode = captureVideoDecodeCapabilities(),
                audioOutput = captureAudioOutputCapabilities(context, playerSettings),
                capturedAtMs = nowMs()
            )
        }.getOrNull()
    }

    private fun captureVideoDecodeCapabilities(): DeviceVideoDecodeCapabilities {
        val decoders = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .asSequence()
                .filterNot { it.isEncoder }
                .flatMap { codecInfo ->
                    codecInfo.supportedTypes.asSequence().mapNotNull { supportedType ->
                        supportedType.takeIf { it.isNotBlank() }?.let { mimeType ->
                            DecoderCapabilityInfo(
                                mimeType = mimeType.lowercase(Locale.US),
                                hardwareAccelerated = codecInfo.isHardwareAcceleratedCompat(),
                                softwareOnly = codecInfo.isSoftwareOnlyCompat(),
                                secureSupported = codecInfo.supportsSecurePlayback(mimeType)
                            )
                        }
                    }
                }
                .toList()
        }.getOrDefault(emptyList())

        return DeviceVideoDecodeCapabilities(
            h264 = buildCodecSupportForMime(decoders, MimeTypes.VIDEO_H264),
            hevc = buildCodecSupportForMime(decoders, MimeTypes.VIDEO_H265),
            av1 = buildCodecSupportForMime(decoders, MimeTypes.VIDEO_AV1),
            dolbyVision = buildCodecSupportForMime(decoders, MimeTypes.VIDEO_DOLBY_VISION)
        )
    }
}

@Suppress("DEPRECATION")
internal fun captureDisplayHdrTypes(context: Context): Set<DeviceHdrType> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return emptySet()
    val displayManager = context.getSystemService(DisplayManager::class.java) ?: return emptySet()
    val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return emptySet()
    val hdrTypes = display.hdrCapabilities?.supportedHdrTypes ?: return emptySet()
    return normalizeHdrTypes(hdrTypes)
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
): DeviceAudioOutputCapabilities {
    val detected = buildBenchmarkAudioCapabilities(
        context = context,
        playerSettings = playerSettings
    )
    return DeviceAudioOutputCapabilities(
        ac3 = buildAudioEncodingSupport(detected, C.ENCODING_AC3),
        eac3 = buildAudioEncodingSupport(
            detected,
            C.ENCODING_E_AC3,
            passthroughEncodings = intArrayOf(C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC)
        ),
        truehd = buildAudioEncodingSupport(detected, C.ENCODING_DOLBY_TRUEHD),
        dts = buildAudioEncodingSupport(
            detected,
            C.ENCODING_DTS,
            passthroughEncodings = intArrayOf(
                C.ENCODING_DTS,
                C.ENCODING_DTS_HD,
                C.ENCODING_DTS_UHD_P2
            )
        ),
        dtshd = buildAudioEncodingSupport(
            detected,
            C.ENCODING_DTS_HD,
            passthroughEncodings = intArrayOf(C.ENCODING_DTS_HD, C.ENCODING_DTS_UHD_P2)
        )
    )
}

@Suppress("DEPRECATION")
internal fun buildBenchmarkAudioCapabilities(
    context: Context,
    playerSettings: PlayerSettings = PlayerSettings()
): AudioCapabilities {
    applyBenchmarkAudioCapabilitySettings(playerSettings)
    val detected = AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null)
    return AudioCapabilities(
        buildBenchmarkSupportedEncodings(detected::supportsEncoding),
        detected.maxChannelCount
    )
}

internal fun buildBenchmarkSupportedEncodings(
    supportsEncoding: (Int) -> Boolean
): IntArray {
    val supportedEncodings = mutableListOf<Int>()
    BENCHMARK_AUDIO_ENCODINGS.forEach { encoding ->
        if (supportsEncoding(encoding)) {
            supportedEncodings += encoding
        }
    }
    if (supportsEncoding(C.ENCODING_E_AC3_JOC) &&
        C.ENCODING_E_AC3 !in supportedEncodings
    ) {
        supportedEncodings += C.ENCODING_E_AC3
    }
    if ((supportsEncoding(C.ENCODING_DTS_HD) ||
            supportsEncoding(C.ENCODING_DTS_UHD_P2)) &&
        C.ENCODING_DTS !in supportedEncodings
    ) {
        supportedEncodings += C.ENCODING_DTS
    }
    return supportedEncodings.toIntArray()
}

internal fun applyBenchmarkAudioCapabilitySettings(playerSettings: PlayerSettings) {
    AudioCapabilities.setExperimentalFireOsIecPassthroughEnabled(
        playerSettings.experimentalDtsIecPassthroughEnabled
    )
    AudioCapabilities.setFireOsCompatibilityFallbackEnabled(false)
    AudioCapabilities.setIecPackerAc3PassthroughEnabled(
        playerSettings.iecPackerAc3PassthroughEnabled
    )
    AudioCapabilities.setIecPackerAc3TranscodeEnabled(
        playerSettings.iecPackerAc3TranscodeEnabled
    )
    AudioCapabilities.setIecPackerEac3PassthroughEnabled(
        playerSettings.iecPackerEac3PassthroughEnabled
    )
    AudioCapabilities.setIecPackerDtsPassthroughEnabled(
        playerSettings.iecPackerDtsPassthroughEnabled
    )
    AudioCapabilities.setIecPackerTruehdPassthroughEnabled(
        playerSettings.iecPackerTruehdPassthroughEnabled
    )
    AudioCapabilities.setIecPackerDtshdPassthroughEnabled(
        playerSettings.iecPackerDtshdPassthroughEnabled
    )
    AudioCapabilities.setIecPackerDtshdCoreFallbackEnabled(
        playerSettings.iecPackerDtshdCoreFallbackEnabled
    )
    AudioCapabilities.setIecPackerAudioConfig(
        playerSettings.iecPackerAudioConfig
    )
    AudioCapabilities.setIecPackerAudioDevice(
        playerSettings.iecPackerAudioDevice
    )
    AudioCapabilities.setIecPackerPassthroughDevice(
        playerSettings.iecPackerPassthroughDevice
    )
    AudioCapabilities.setIecPackerMaxPcmChannelLayout(
        playerSettings.iecPackerMaxPcmChannelLayout.kodiChannelLayoutValue
    )
    AudioCapabilities.setFireOsIecSuperviseAudioDelayEnabled(
        playerSettings.fireOsIecSuperviseAudioDelayEnabled
    )
    AudioCapabilities.setFireOsIecVerboseLoggingEnabled(
        playerSettings.fireOsIecVerboseLoggingEnabled
    )
}

internal fun buildAudioEncodingSupport(
    audioCapabilities: AudioCapabilities,
    encoding: Int,
    passthroughEncodings: IntArray = intArrayOf(encoding)
): AudioEncodingSupport {
    return buildAudioEncodingSupport(
        supportsEncoding = audioCapabilities::supportsEncoding,
        passthroughEncodings = passthroughEncodings
    )
}

internal fun buildAudioEncodingSupport(
    supportsEncoding: (Int) -> Boolean,
    passthroughEncodings: IntArray
): AudioEncodingSupport {
    val supported = passthroughEncodings.any(supportsEncoding)
    return AudioEncodingSupport(
        supported = supported,
        passthroughLikely = supported
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

private fun MediaCodecInfo.supportsSecurePlayback(mimeType: String): Boolean {
    return runCatching {
        getCapabilitiesForType(mimeType)
            .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback)
    }.getOrDefault(false)
}

private val SOFTWARE_CODEC_NAME_PREFIXES = listOf(
    "OMX.google.",
    "OMX.ffmpeg.",
    "c2.android.",
    "c2.google."
)

private val BENCHMARK_AUDIO_ENCODINGS = intArrayOf(
    C.ENCODING_PCM_16BIT,
    C.ENCODING_AC3,
    C.ENCODING_AC4,
    C.ENCODING_DTS,
    C.ENCODING_E_AC3_JOC,
    C.ENCODING_E_AC3,
    C.ENCODING_DOLBY_TRUEHD
)
