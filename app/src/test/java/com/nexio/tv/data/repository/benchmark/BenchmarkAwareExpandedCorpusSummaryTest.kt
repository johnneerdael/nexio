package com.nexio.tv.data.repository.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class BenchmarkAwareExpandedCorpusSummaryTest {

    @Test
    fun `expanded corpus default summary stays fully green`() {
        val corpusDir = Files.createTempDirectory("benchmark_expanded_corpus_summary")
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
        val defaultVariant = corpus.variants.first { it.name == "default.json" }
        val summary = BenchmarkAwareScoringEvaluator().evaluate(
            dataset = corpus.dataset,
            scorer = BenchmarkAwareStreamScorer(defaultVariant.config)
        )
        println(BenchmarkAwareScoringReportRenderer.renderSummaryMarkdown(summary))

        assertEquals(corpus.dataset.scenarios.size, summary.scenarioCount)
        assertEquals(0.9230769230769231, summary.top1Accuracy, 0.0)
        assertEquals(1.0, summary.acceptableAccuracy, 0.0)
        assertTrue(summary.pairwiseAccuracy >= 0.3)
        assertEquals(1, summary.failureSlices.size)
        assertEquals("pairwise_mismatch", summary.failureSlices.single().category)
        assertTrue(summary.failureSlices.single().scenarios.contains("tv-hevc-ddp-vs-av1-webdl"))
    }
}
