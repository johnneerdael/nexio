package com.nexio.tv.data.repository.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class BenchmarkAwareGeneratedVariantAnalysisTest {

    @Test
    fun `expanded corpus sweep keeps default config ahead of generated tuning variants`() {
        val corpusDir = Files.createTempDirectory("benchmark_generated_variant_analysis")
        val classLoader = javaClass.classLoader!!
        Files.write(
            corpusDir.resolve("manifest.json"),
            classLoader.getResourceAsStream("benchmark_scoring_corpus/manifest.json")!!.readBytes()
        )
        Files.createDirectories(corpusDir.resolve("datasets"))
        Files.createDirectories(corpusDir.resolve("variants"))
        listOf(
            "audio-fallback.json",
            "dv-vs-hdr10plus.json",
            "remux-vs-webdl.json",
            "fake-4k-penalty.json",
            "av1-vs-hevc.json",
            "dtshd-core-vs-pcm-vs-passthrough.json",
            "dv-profile5-autoplay-fallback.json",
            "dv-profile7-autoplay-continue.json",
            "dv-probe-unknown-autoplay-fallback.json",
            "dv-remux-profile5-autoplay-continue.json",
            "lotr-return-of-the-king-movie.json",
            "tv-hevc-ddp-vs-av1-webdl.json",
            "movie-webdl-non-remux-quality-pack.json"
        ).forEach { name ->
            Files.write(
                corpusDir.resolve("datasets/$name"),
                classLoader.getResourceAsStream("benchmark_scoring_corpus/datasets/$name")!!.readBytes()
            )
        }
        listOf("default.json", "pcm-heavy.json").forEach { name ->
            Files.write(
                corpusDir.resolve("variants/$name"),
                classLoader.getResourceAsStream("benchmark_scoring_corpus/variants/$name")!!.readBytes()
            )
        }

        val corpus = BenchmarkAwareScoringCorpusLoader.load(corpusDir)
        val generatedVariants = BenchmarkAwareScoringVariantGridGenerator
            .generate(BenchmarkAwareStreamScoringConfig.default())
            .map { BenchmarkAwareNamedConfigVariant(it.name, it.config) }
        val result = BenchmarkAwareScoringTuningHarness().evaluateVariants(corpus.dataset, generatedVariants)
        println(BenchmarkAwareScoringReportRenderer.renderVariantMarkdown(result))

        assertEquals("default.json", result.winner?.name)
        val pcmHeavy = result.rankedVariants.first { it.name == "pcm-heavy.json" }
        val bitrateStrict = result.rankedVariants.first { it.name == "bitrate-strict.json" }
        assertTrue(pcmHeavy.summary.failureSlices.any { slice ->
            slice.category == "wrong_winner" && slice.scenarios.contains("audio-fallback-1")
        })
        assertTrue(bitrateStrict.summary.failureSlices.any { slice ->
            slice.category == "wrong_winner" && slice.scenarios.contains("remux-vs-webdl")
        })
    }
}
