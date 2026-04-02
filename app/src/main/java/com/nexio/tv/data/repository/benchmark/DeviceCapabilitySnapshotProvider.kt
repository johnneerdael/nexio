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

    fun capture(): DeviceCapabilitySnapshot? {
        return runCatching {
            DeviceCapabilitySnapshot(
                model = Build.MODEL?.takeIf { it.isNotBlank() },
                manufacturer = Build.MANUFACTURER?.takeIf { it.isNotBlank() },
                sdkInt = Build.VERSION.SDK_INT,
                displayHdrTypes = captureDisplayHdrTypes(context),
                videoDecode = captureVideoDecodeCapabilities(),
                audioOutput = captureAudioOutputCapabilities(context),
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

internal fun captureAudioOutputCapabilities(context: Context): DeviceAudioOutputCapabilities {
    val detected = AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null)
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
