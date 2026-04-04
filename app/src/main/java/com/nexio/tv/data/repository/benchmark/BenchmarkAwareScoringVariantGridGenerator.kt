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
                contentRewards = base.contentRewards.copy(
                    audio = base.contentRewards.audio +
                        (ShadowAudioTier.TRUEHD_ATMOS to 18) +
                        (ShadowAudioTier.TRUEHD to 14)
                )
            )
        )
        variants += BenchmarkAwareGeneratedVariant(
            "immersive-heavy.json",
            base.copy(
                contentRewards = base.contentRewards.copy(
                    hdr = base.contentRewards.hdr +
                        (ShadowHdrTier.DOLBY_VISION to 18) +
                        (ShadowHdrTier.HDR10_PLUS to 14),
                    audio = base.contentRewards.audio +
                        (ShadowAudioTier.TRUEHD_ATMOS to 18) +
                        (ShadowAudioTier.DTSX to 18) +
                        (ShadowAudioTier.DDP_ATMOS to 18)
                )
            )
        )
        variants += BenchmarkAwareGeneratedVariant(
            "bitrate-strict.json",
            base.copy(
                viability = base.viability.copy(
                    minimumRatio = 1.20,
                    comfortableRatio = 1.25,
                    preferStartupRatio = 1.30
                )
            )
        )
        variants += BenchmarkAwareGeneratedVariant(
            "transport-conservative.json",
            base.copy(
                viability = base.viability.copy(
                    minimumRatio = 1.25,
                    comfortableRatio = 1.30,
                    preferStartupRatio = 1.35
                ),
                transportRewards = base.transportRewards.copy(
                    ratioBands = listOf(
                        ShadowRatioBand(1.25, 1.30, 5, 0),
                        ShadowRatioBand(1.30, 1.40, 10, 0),
                        ShadowRatioBand(1.40, 999.0, 15, 0)
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
