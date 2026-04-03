package com.nexio.tv.data.repository.benchmark

import com.google.gson.Gson
import kotlin.math.roundToInt

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

enum class ShadowAudioSupportTier {
    FULL_IMMERSIVE_PASSTHROUGH,
    FULL_PASSTHROUGH,
    DECODED_MULTICHANNEL_PCM,
    CORE_FALLBACK,
    UNSUPPORTED
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
    val resolution: Map<ShadowResolutionTier, Int>,
    val source: Map<ShadowReleaseType, Int>,
    val codec: Map<ShadowVideoCodecTier, Int>,
    val hdr: Map<ShadowHdrTier, Int>,
    val audio: Map<ShadowAudioTier, Int>
)

data class ShadowSupportMultiplierConfig(
    val full: Double,
    val fallback: Double,
    val partial: Double,
    val unsupported: Double
) {
    fun apply(points: Int, supportLevel: ShadowSupportLevel): Int {
        val multiplier = when (supportLevel) {
            ShadowSupportLevel.FULL -> full
            ShadowSupportLevel.FALLBACK -> fallback
            ShadowSupportLevel.PARTIAL -> partial
            ShadowSupportLevel.UNSUPPORTED -> unsupported
        }
        return (points * multiplier).roundToInt()
    }
}

data class ShadowBitrateRealismCurveConfig(
    val scale: Double,
    val slope: Double,
    val min: Double,
    val max: Double
)

data class ShadowBitrateRealismConfig(
    val curve: ShadowBitrateRealismCurveConfig,
    val targetsMbps: Map<ShadowResolutionTier, Map<ShadowReleaseType, Double>>
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

data class ShadowStabilityBand(
    val maxCv: Double,
    val maxStalls: Int? = null,
    val maxGapMs: Long? = null,
    val reward: Int
)

data class ShadowTransportRewardConfig(
    val ratioBands: List<ShadowRatioBand>,
    val startupBands: List<ShadowStartupBand>,
    val seekBands: List<ShadowSeekBand>,
    val stabilityBands: List<ShadowStabilityBand>
)

data class ShadowAudioScoringConfig(
    val baseRewards: Map<ShadowAudioTier, Int>,
    val supportMultipliers: Map<ShadowAudioSupportTier, Double>,
    val immersiveLossPenalty: Map<ShadowAudioTier, Map<ShadowAudioSupportTier, Int>>,
    val downgradePenalty: Map<ShadowAudioSupportTier, Int>
)

data class ShadowSynergyConfig(
    val healthyRemux: Int,
    val dvHevcSupported: Int,
    val atmosSupportedAndHealthy: Int,
    val premiumFeatureStack: Int
)

data class ShadowPenaltyConfig(
    val tinyFake4k: Int,
    val tinyFake4kThresholdMbps: Double,
    val premiumTagImplausible: Int,
    val premiumTagImplausibleMaxRealismRatio: Double,
    val softwareDecode4k: Int,
    val atmosTooLowBitrate: Int,
    val atmosTooLowBitrateMaxRealismRatio: Double
)

data class BenchmarkAwareStreamScoringConfig(
    val viability: ShadowViabilityConfig,
    val burstMargins: Map<ShadowReleaseType, Double>,
    val contentRewards: ShadowContentRewardConfig,
    val supportMultipliers: ShadowSupportMultiplierConfig,
    val bitrateRealism: ShadowBitrateRealismConfig,
    val transportRewards: ShadowTransportRewardConfig,
    val audioScoring: ShadowAudioScoringConfig,
    val synergy: ShadowSynergyConfig,
    val penalties: ShadowPenaltyConfig
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
                    minimumRatio = 1.0,
                    comfortableRatio = 1.25,
                    preferStartupRatio = 1.40
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
                    resolution = mapOf(
                        ShadowResolutionTier.UHD_2160 to 30,
                        ShadowResolutionTier.FHD_1080 to 16,
                        ShadowResolutionTier.HD_720 to 6,
                        ShadowResolutionTier.OTHER to 0
                    ),
                    source = mapOf(
                        ShadowReleaseType.SMALL_ENCODE to 0,
                        ShadowReleaseType.NORMAL_ENCODE to 8,
                        ShadowReleaseType.HIGH_BITRATE_ENCODE to 16,
                        ShadowReleaseType.WEBDL to 12,
                        ShadowReleaseType.WEBRIP to 6,
                        ShadowReleaseType.BLURAY_ENCODE to 18,
                        ShadowReleaseType.REMUX to 28,
                        ShadowReleaseType.UNKNOWN to 0
                    ),
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
                        ShadowHdrTier.SDR to 0
                    ),
                    audio = mapOf(
                        ShadowAudioTier.TRUEHD_ATMOS to 20,
                        ShadowAudioTier.DTSX to 18,
                        ShadowAudioTier.TRUEHD to 16,
                        ShadowAudioTier.DTSHD to 15,
                        ShadowAudioTier.DDP_ATMOS to 13,
                        ShadowAudioTier.DDP to 10,
                        ShadowAudioTier.AC3 to 7,
                        ShadowAudioTier.DTS to 7,
                        ShadowAudioTier.OTHER to 0
                    )
                ),
                supportMultipliers = ShadowSupportMultiplierConfig(
                    full = 1.0,
                    fallback = 0.6,
                    partial = 0.5,
                    unsupported = 0.0
                ),
                bitrateRealism = ShadowBitrateRealismConfig(
                    curve = ShadowBitrateRealismCurveConfig(
                        scale = 24.0,
                        slope = 1.35,
                        min = -28.0,
                        max = 28.0
                    ),
                    targetsMbps = mapOf(
                        ShadowResolutionTier.HD_720 to mapOf(
                            ShadowReleaseType.SMALL_ENCODE to 2.5,
                            ShadowReleaseType.NORMAL_ENCODE to 4.0,
                            ShadowReleaseType.HIGH_BITRATE_ENCODE to 6.0,
                            ShadowReleaseType.WEBDL to 5.0,
                            ShadowReleaseType.WEBRIP to 4.0,
                            ShadowReleaseType.BLURAY_ENCODE to 8.0,
                            ShadowReleaseType.REMUX to 12.0,
                            ShadowReleaseType.UNKNOWN to 4.0
                        ),
                        ShadowResolutionTier.FHD_1080 to mapOf(
                            ShadowReleaseType.SMALL_ENCODE to 5.0,
                            ShadowReleaseType.NORMAL_ENCODE to 8.0,
                            ShadowReleaseType.HIGH_BITRATE_ENCODE to 12.0,
                            ShadowReleaseType.WEBDL to 10.0,
                            ShadowReleaseType.WEBRIP to 8.0,
                            ShadowReleaseType.BLURAY_ENCODE to 18.0,
                            ShadowReleaseType.REMUX to 28.0,
                            ShadowReleaseType.UNKNOWN to 8.0
                        ),
                        ShadowResolutionTier.UHD_2160 to mapOf(
                            ShadowReleaseType.SMALL_ENCODE to 10.0,
                            ShadowReleaseType.NORMAL_ENCODE to 18.0,
                            ShadowReleaseType.HIGH_BITRATE_ENCODE to 28.0,
                            ShadowReleaseType.WEBDL to 24.0,
                            ShadowReleaseType.WEBRIP to 18.0,
                            ShadowReleaseType.BLURAY_ENCODE to 45.0,
                            ShadowReleaseType.REMUX to 70.0,
                            ShadowReleaseType.UNKNOWN to 18.0
                        ),
                        ShadowResolutionTier.OTHER to mapOf(
                            ShadowReleaseType.UNKNOWN to 6.0
                        )
                    )
                ),
                transportRewards = ShadowTransportRewardConfig(
                    ratioBands = listOf(
                        ShadowRatioBand(1.0, 1.25, 8, 10),
                        ShadowRatioBand(1.25, 1.60, 18, 8),
                        ShadowRatioBand(1.60, 999.0, 26, 0)
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
                    ),
                    stabilityBands = listOf(
                        ShadowStabilityBand(0.05, maxStalls = 0, maxGapMs = 200L, reward = 6),
                        ShadowStabilityBand(0.08, maxStalls = 1, maxGapMs = 350L, reward = 4),
                        ShadowStabilityBand(0.12, reward = 2)
                    )
                ),
                audioScoring = ShadowAudioScoringConfig(
                    baseRewards = mapOf(
                        ShadowAudioTier.TRUEHD_ATMOS to 20,
                        ShadowAudioTier.DTSX to 18,
                        ShadowAudioTier.TRUEHD to 16,
                        ShadowAudioTier.DTSHD to 15,
                        ShadowAudioTier.DDP_ATMOS to 13,
                        ShadowAudioTier.DDP to 10,
                        ShadowAudioTier.AC3 to 7,
                        ShadowAudioTier.DTS to 7,
                        ShadowAudioTier.OTHER to 0
                    ),
                    supportMultipliers = mapOf(
                        ShadowAudioSupportTier.FULL_IMMERSIVE_PASSTHROUGH to 1.0,
                        ShadowAudioSupportTier.FULL_PASSTHROUGH to 1.0,
                        ShadowAudioSupportTier.DECODED_MULTICHANNEL_PCM to 0.7,
                        ShadowAudioSupportTier.CORE_FALLBACK to 0.4,
                        ShadowAudioSupportTier.UNSUPPORTED to 0.0
                    ),
                    immersiveLossPenalty = mapOf(
                        ShadowAudioTier.TRUEHD_ATMOS to mapOf(
                            ShadowAudioSupportTier.DECODED_MULTICHANNEL_PCM to 4,
                            ShadowAudioSupportTier.CORE_FALLBACK to 8,
                            ShadowAudioSupportTier.UNSUPPORTED to 12
                        ),
                        ShadowAudioTier.DTSX to mapOf(
                            ShadowAudioSupportTier.DECODED_MULTICHANNEL_PCM to 5,
                            ShadowAudioSupportTier.CORE_FALLBACK to 8,
                            ShadowAudioSupportTier.UNSUPPORTED to 12
                        ),
                        ShadowAudioTier.DDP_ATMOS to mapOf(
                            ShadowAudioSupportTier.DECODED_MULTICHANNEL_PCM to 3,
                            ShadowAudioSupportTier.CORE_FALLBACK to 6,
                            ShadowAudioSupportTier.UNSUPPORTED to 10
                        )
                    ),
                    downgradePenalty = mapOf(
                        ShadowAudioSupportTier.FULL_IMMERSIVE_PASSTHROUGH to 0,
                        ShadowAudioSupportTier.FULL_PASSTHROUGH to 0,
                        ShadowAudioSupportTier.DECODED_MULTICHANNEL_PCM to 0,
                        ShadowAudioSupportTier.CORE_FALLBACK to 6,
                        ShadowAudioSupportTier.UNSUPPORTED to 10
                    )
                ),
                synergy = ShadowSynergyConfig(
                    healthyRemux = 10,
                    dvHevcSupported = 8,
                    atmosSupportedAndHealthy = 7,
                    premiumFeatureStack = 10
                ),
                penalties = ShadowPenaltyConfig(
                    tinyFake4k = 35,
                    tinyFake4kThresholdMbps = 8.0,
                    premiumTagImplausible = 20,
                    premiumTagImplausibleMaxRealismRatio = 0.45,
                    softwareDecode4k = 8,
                    atmosTooLowBitrate = 10,
                    atmosTooLowBitrateMaxRealismRatio = 0.55
                )
            )
        }
    }
}
