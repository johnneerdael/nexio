package com.nexio.tv.data.repository.benchmark

import com.google.gson.Gson

enum class ShadowResolutionTier {
    UHD_2160,
    FHD_1080,
    HD_720,
    OTHER
}

enum class ShadowReleaseType {
    SMALL_ENCODE,
    NORMAL_ENCODE,
    HIGH_BITRATE_ENCODE,
    WEBDL,
    WEBRIP,
    BLURAY_ENCODE,
    REMUX,
    UNKNOWN
}

enum class ShadowVideoCodecTier {
    AV1_HW,
    HEVC_HW,
    H264_HW,
    AV1_SW,
    HEVC_SW,
    VC1,
    MPEG2,
    OTHER,
    UNSUPPORTED
}

enum class ShadowHdrTier {
    DOLBY_VISION,
    HDR10_PLUS,
    HDR10,
    HLG,
    SDR
}

enum class ShadowAudioTier {
    TRUEHD_ATMOS,
    DTSX,
    TRUEHD,
    DTSHD,
    DDP_ATMOS,
    DDP,
    AC3,
    DTS,
    OTHER
}

enum class ShadowSupportLevel {
    FULL,
    FALLBACK,
    PARTIAL,
    UNSUPPORTED
}

data class ShadowViabilityConfig(
    val safeBudgetMultiplier: Double,
    val minimumRatio: Double,
    val comfortableRatio: Double,
    val preferStartupRatio: Double
)

data class ShadowContentRewardConfig(
    val codec: Map<ShadowVideoCodecTier, Int>,
    val hdr: Map<ShadowHdrTier, Int>,
    val audio: Map<ShadowAudioTier, Int>
)

data class ShadowRatioBand(
    val min: Double,
    val max: Double,
    val base: Int,
    val gain: Int
)

data class ShadowStartupBand(
    val maxMs: Long,
    val reward: Int
)

data class ShadowSeekBand(
    val maxP95Ms: Long,
    val maxFailRate: Double,
    val reward: Int
)

data class ShadowTransportRewardConfig(
    val ratioBands: List<ShadowRatioBand>,
    val startupBands: List<ShadowStartupBand>,
    val seekBands: List<ShadowSeekBand>
)

data class BenchmarkAwareStreamScoringConfig(
    val viability: ShadowViabilityConfig,
    val burstMargins: Map<ShadowReleaseType, Double>,
    val contentRewards: ShadowContentRewardConfig,
    val transportRewards: ShadowTransportRewardConfig
) {
    fun toJson(gson: Gson = Gson()): String = gson.toJson(this)

    companion object {
        fun fromJson(json: String, gson: Gson = Gson()): BenchmarkAwareStreamScoringConfig {
            return gson.fromJson(json, BenchmarkAwareStreamScoringConfig::class.java)
        }

        fun default(): BenchmarkAwareStreamScoringConfig {
            return BenchmarkAwareStreamScoringConfig(
                viability = ShadowViabilityConfig(
                    safeBudgetMultiplier = 0.85,
                    minimumRatio = 1.15,
                    comfortableRatio = 1.20,
                    preferStartupRatio = 1.25
                ),
                burstMargins = mapOf(
                    ShadowReleaseType.SMALL_ENCODE to 1.20,
                    ShadowReleaseType.NORMAL_ENCODE to 1.35,
                    ShadowReleaseType.HIGH_BITRATE_ENCODE to 1.45,
                    ShadowReleaseType.WEBDL to 1.35,
                    ShadowReleaseType.WEBRIP to 1.35,
                    ShadowReleaseType.BLURAY_ENCODE to 1.35,
                    ShadowReleaseType.REMUX to 1.60,
                    ShadowReleaseType.UNKNOWN to 1.35
                ),
                contentRewards = ShadowContentRewardConfig(
                    codec = mapOf(
                        ShadowVideoCodecTier.AV1_HW to 14,
                        ShadowVideoCodecTier.HEVC_HW to 10,
                        ShadowVideoCodecTier.H264_HW to 4,
                        ShadowVideoCodecTier.AV1_SW to -18,
                        ShadowVideoCodecTier.HEVC_SW to -10,
                        ShadowVideoCodecTier.VC1 to -16,
                        ShadowVideoCodecTier.MPEG2 to -16,
                        ShadowVideoCodecTier.OTHER to 0,
                        ShadowVideoCodecTier.UNSUPPORTED to -24
                    ),
                    hdr = mapOf(
                        ShadowHdrTier.DOLBY_VISION to 16,
                        ShadowHdrTier.HDR10_PLUS to 12,
                        ShadowHdrTier.HDR10 to 7,
                        ShadowHdrTier.HLG to 4,
                        ShadowHdrTier.SDR to 0
                    ),
                    audio = mapOf(
                        ShadowAudioTier.TRUEHD_ATMOS to 16,
                        ShadowAudioTier.DTSX to 16,
                        ShadowAudioTier.DDP_ATMOS to 16,
                        ShadowAudioTier.TRUEHD to 12,
                        ShadowAudioTier.DTSHD to 12,
                        ShadowAudioTier.DDP to 10,
                        ShadowAudioTier.AC3 to 7,
                        ShadowAudioTier.DTS to 7,
                        ShadowAudioTier.OTHER to 0
                    )
                ),
                transportRewards = ShadowTransportRewardConfig(
                    ratioBands = listOf(
                        ShadowRatioBand(1.15, 1.20, 5, 0),
                        ShadowRatioBand(1.20, 1.25, 10, 0),
                        ShadowRatioBand(1.25, 999.0, 15, 0)
                    ),
                    startupBands = listOf(
                        ShadowStartupBand(150L, 6),
                        ShadowStartupBand(300L, 4),
                        ShadowStartupBand(600L, 2)
                    ),
                    seekBands = listOf(
                        ShadowSeekBand(250L, 0.005, 8),
                        ShadowSeekBand(350L, 0.01, 6),
                        ShadowSeekBand(500L, 0.02, 3)
                    )
                )
            )
        }
    }
}
