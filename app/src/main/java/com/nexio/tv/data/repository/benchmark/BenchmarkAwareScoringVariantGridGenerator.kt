package com.nexio.tv.data.repository.benchmark

import java.nio.file.Files
import java.nio.file.Path

data class BenchmarkAwareGeneratedVariant(
    val name: String,
    val config: BenchmarkAwareStreamScoringConfig
)

object BenchmarkAwareScoringVariantGridGenerator {

    fun generate(base: BenchmarkAwareStreamScoringConfig): List<BenchmarkAwareGeneratedVariant> {
        val variants = mutableListOf<BenchmarkAwareGeneratedVariant>()
        variants += BenchmarkAwareGeneratedVariant("default.json", base)
        variants += BenchmarkAwareGeneratedVariant(
            "pcm-heavy.json",
            base.copy(
                audioScoring = base.audioScoring.copy(
                    supportMultipliers = base.audioScoring.supportMultipliers +
                        (ShadowAudioSupportTier.DECODED_MULTICHANNEL_PCM to 0.95)
                )
            )
        )
        variants += BenchmarkAwareGeneratedVariant(
            "immersive-heavy.json",
            base.copy(
                contentRewards = base.contentRewards.copy(
                    hdr = base.contentRewards.hdr + (ShadowHdrTier.DOLBY_VISION to 20),
                    audio = base.contentRewards.audio +
                        (ShadowAudioTier.TRUEHD_ATMOS to 24) +
                        (ShadowAudioTier.DDP_ATMOS to 16)
                ),
                synergy = base.synergy.copy(
                    dvHevcSupported = base.synergy.dvHevcSupported + 2,
                    premiumFeatureStack = base.synergy.premiumFeatureStack + 4
                )
            )
        )
        variants += BenchmarkAwareGeneratedVariant(
            "bitrate-strict.json",
            base.copy(
                penalties = base.penalties.copy(
                    tinyFake4k = base.penalties.tinyFake4k + 10,
                    premiumTagImplausible = base.penalties.premiumTagImplausible + 6
                ),
                bitrateRealism = base.bitrateRealism.copy(
                    curve = base.bitrateRealism.curve.copy(
                        scale = base.bitrateRealism.curve.scale + 4.0,
                        slope = base.bitrateRealism.curve.slope + 0.15
                    )
                )
            )
        )
        variants += BenchmarkAwareGeneratedVariant(
            "transport-conservative.json",
            base.copy(
                viability = base.viability.copy(
                    safeBudgetMultiplier = 0.80,
                    comfortableRatio = 1.30,
                    preferStartupRatio = 1.50
                ),
                transportRewards = base.transportRewards.copy(
                    ratioBands = listOf(
                        ShadowRatioBand(1.0, 1.20, 6, 8),
                        ShadowRatioBand(1.20, 1.50, 16, 8),
                        ShadowRatioBand(1.50, 999.0, 24, 0)
                    )
                )
            )
        )
        return variants
    }

    fun writeVariants(
        outputDirectory: Path,
        base: BenchmarkAwareStreamScoringConfig
    ): List<Path> {
        Files.createDirectories(outputDirectory)
        return generate(base).map { variant ->
            val path = outputDirectory.resolve(variant.name)
            Files.write(path, variant.config.toJson().toByteArray())
            path
        }
    }
}
