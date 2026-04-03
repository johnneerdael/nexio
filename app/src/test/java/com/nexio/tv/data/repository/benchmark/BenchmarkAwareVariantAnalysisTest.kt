package com.nexio.tv.data.repository.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class BenchmarkAwareVariantAnalysisTest {

    @Test
    fun `expanded corpus sweep surfaces failure slices for weaker variants`() {
        val corpusDir = Files.createTempDirectory("benchmark_variant_analysis")
        val classLoader = javaClass.classLoader!!
        Files.write(corpusDir.resolve("manifest.json"), classLoader.getResourceAsStream("benchmark_scoring_corpus/manifest.json")!!.readBytes())
        Files.createDirectories(corpusDir.resolve("datasets"))
        Files.createDirectories(corpusDir.resolve("variants"))
        listOf(
            "audio-fallback.json",
            "dv-vs-hdr10plus.json",
            "remux-vs-webdl.json",
            "fake-4k-penalty.json",
            "av1-vs-hevc.json",
            "dtshd-core-vs-pcm-vs-passthrough.json",
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
        val result = BenchmarkAwareScoringTuningHarness().evaluateVariants(corpus.dataset, corpus.variants)
        println(BenchmarkAwareScoringReportRenderer.renderVariantMarkdown(result))

        assertEquals("default.json", result.winner?.name)
        val pcmHeavy = result.rankedVariants.first { it.name == "pcm-heavy.json" }
        assertTrue(pcmHeavy.summary.failureSlices.isNotEmpty())
        assertTrue(pcmHeavy.summary.failureSlices.any { slice ->
            slice.scenarios.contains("audio-fallback-1")
        })
    }
}
