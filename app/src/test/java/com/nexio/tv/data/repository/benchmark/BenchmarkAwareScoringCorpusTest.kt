package com.nexio.tv.data.repository.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.io.path.Path

class BenchmarkAwareScoringCorpusTest {

    @Test
    fun `corpus loader reads manifest and datasets from sample resources`() {
        val corpusDir = copySampleCorpusToTempDir()

        val corpus = BenchmarkAwareScoringCorpusLoader.load(corpusDir)

        assertEquals("sample-benchmark-scoring-corpus", corpus.manifest.name)
        assertEquals(13, corpus.dataset.scenarios.size)
        assertTrue(corpus.dataset.scenarios.any { it.id == "audio-fallback-1" })
        assertTrue(corpus.dataset.scenarios.any { it.id == "dv-vs-hdr10plus" })
        assertTrue(corpus.dataset.scenarios.any { it.id == "dv-profile5-autoplay-fallback" })
        assertTrue(corpus.dataset.scenarios.any { it.id == "dv-profile7-autoplay-continue" })
        assertTrue(corpus.dataset.scenarios.any { it.id == "dv-probe-unknown-autoplay-fallback" })
        assertTrue(corpus.dataset.scenarios.any { it.id == "dv-remux-profile5-autoplay-continue" })
        assertTrue(corpus.dataset.scenarios.any { it.id == "lotr-return-of-the-king-movie" })
        assertTrue(corpus.dataset.scenarios.any { it.id == "tv-hevc-ddp-vs-av1-webdl" })
        assertTrue(corpus.dataset.scenarios.any { it.id == "movie-webdl-non-remux-quality-pack" })
        assertEquals(2, corpus.variants.size)
    }

    @Test
    fun `sample corpus documentation and report fixtures exist`() {
        val classLoader = javaClass.classLoader!!
        assertTrue(classLoader.getResource("benchmark_scoring_corpus/README.md") != null)
        assertTrue(classLoader.getResource("benchmark_scoring_corpus/reports/default-summary.md") != null)
        assertTrue(classLoader.getResource("benchmark_scoring_corpus/reports/variants.md") != null)
        assertTrue(classLoader.getResource("benchmark_scoring_corpus/templates/template-scenario.json") != null)
    }

    @Test
    fun `report renderer emits markdown summary and variant table`() {
        val summary = BenchmarkAwareEvaluationSummary(
            scenarioCount = 1,
            exactTop1Matches = 1,
            acceptableMatches = 1,
            pairwiseSatisfied = 1,
            pairwiseTotal = 1,
            top1Accuracy = 1.0,
            acceptableAccuracy = 1.0,
            pairwiseAccuracy = 1.0,
            scenarios = listOf(
                BenchmarkAwareScenarioEvaluation(
                    scenarioId = "audio-fallback-1",
                    selectedStreamKey = "ddp_atmos",
                    expectedWinnerStreamKey = "ddp_atmos",
                    topMatch = true,
                    acceptableMatch = true,
                    pairwiseSatisfied = 1,
                    pairwiseTotal = 1,
                    winnerFinalScore = 120,
                    winnerProvider = "real_debrid",
                    winnerTransport = "optimized",
                    failureCategory = null
                )
            ),
            failureSlices = emptyList()
        )
        val variantResult = BenchmarkAwareTuningHarnessResult(
            rankedVariants = listOf(
                BenchmarkAwareVariantEvaluation("default", summary, 8.0)
            )
        )

        val summaryMarkdown = BenchmarkAwareScoringReportRenderer.renderSummaryMarkdown(summary)
        val variantMarkdown = BenchmarkAwareScoringReportRenderer.renderVariantMarkdown(variantResult)

        assertTrue(summaryMarkdown.contains("# Benchmark-Aware Scoring Evaluation Report"))
        assertTrue(summaryMarkdown.contains("audio-fallback-1"))
        assertTrue(variantMarkdown.contains("# Benchmark-Aware Scoring Variant Report"))
        assertTrue(variantMarkdown.contains("default"))
    }

    @Test
    fun `summary markdown includes failure slices when scenarios miss expected winners`() {
        val summary = BenchmarkAwareEvaluationSummary(
            scenarioCount = 1,
            exactTop1Matches = 0,
            acceptableMatches = 0,
            pairwiseSatisfied = 0,
            pairwiseTotal = 1,
            top1Accuracy = 0.0,
            acceptableAccuracy = 0.0,
            pairwiseAccuracy = 0.0,
            failureSlices = listOf(
                BenchmarkAwareFailureSlice(
                    category = "wrong_winner",
                    count = 1,
                    scenarios = listOf("case-1")
                )
            ),
            scenarios = listOf(
                BenchmarkAwareScenarioEvaluation(
                    scenarioId = "case-1",
                    selectedStreamKey = "loser",
                    expectedWinnerStreamKey = "winner",
                    topMatch = false,
                    acceptableMatch = false,
                    pairwiseSatisfied = 0,
                    pairwiseTotal = 1,
                    winnerFinalScore = 10,
                    winnerProvider = "real_debrid",
                    winnerTransport = "optimized",
                    failureCategory = "wrong_winner"
                )
            )
        )

        val markdown = BenchmarkAwareScoringReportRenderer.renderSummaryMarkdown(summary)

        assertTrue(markdown.contains("## Failure Slices"))
        assertTrue(markdown.contains("wrong_winner"))
        assertTrue(markdown.contains("case-1"))
    }

    @Test
    fun `cli runner supports corpus dir with markdown report`() {
        val corpusDir = copySampleCorpusToTempDir()
        val outputFile = Files.createTempFile("benchmark_corpus_output", ".json")
        val reportFile = Files.createTempFile("benchmark_corpus_report", ".md")
        val defaultConfig = corpusDir.resolve("variants/default.json")

        val exitCode = BenchmarkAwareScoringCliRunner.run(
            listOf(
                "--corpus-dir", corpusDir.toString(),
                "--config", defaultConfig.toString(),
                "--output", outputFile.toString(),
                "--report-md", reportFile.toString()
            )
        )

        assertEquals(0, exitCode)
        assertTrue(String(Files.readAllBytes(outputFile)).contains("top1Accuracy"))
        assertTrue(String(Files.readAllBytes(reportFile)).contains("Benchmark-Aware Scoring Evaluation Report"))
    }

    @Test
    fun `cli runner uses corpus variants when no variant args are passed`() {
        val corpusDir = copySampleCorpusToTempDir()
        val outputFile = Files.createTempFile("benchmark_corpus_variants_output", ".json")
        val reportFile = Files.createTempFile("benchmark_corpus_variants_report", ".md")

        val exitCode = BenchmarkAwareScoringCliRunner.run(
            listOf(
                "--corpus-dir", corpusDir.toString(),
                "--output", outputFile.toString(),
                "--report-md", reportFile.toString()
            )
        )

        val output = String(Files.readAllBytes(outputFile))
        val report = String(Files.readAllBytes(reportFile))
        assertEquals(0, exitCode)
        assertTrue(output.contains("default.json"))
        assertTrue(output.contains("pcm-heavy.json"))
        assertTrue(report.contains("Benchmark-Aware Scoring Variant Report"))
    }

    @Test
    fun `expanded corpus variant sweep shows pcm heavy variant fails audio fallback case`() {
        val corpusDir = copySampleCorpusToTempDir()
        val corpus = BenchmarkAwareScoringCorpusLoader.load(corpusDir)

        val result = BenchmarkAwareScoringTuningHarness().evaluateVariants(
            dataset = corpus.dataset,
            variants = corpus.variants
        )
        val markdown = BenchmarkAwareScoringReportRenderer.renderVariantMarkdown(result)

        assertEquals("default.json", result.winner?.name)
        assertTrue(markdown.contains("## Variant: pcm-heavy.json"))
        assertTrue(markdown.contains("audio-fallback-1"))
    }

    @Test
    fun `parser backed lotr scenario evaluates to an acceptable winner`() {
        val corpusDir = copySampleCorpusToTempDir()
        val corpus = BenchmarkAwareScoringCorpusLoader.load(corpusDir)
        val lotr = corpus.dataset.scenarios.first { it.id == "lotr-return-of-the-king-movie" }

        val summary = BenchmarkAwareScoringEvaluator().evaluate(
            BenchmarkAwareScoringDataset(scenarios = listOf(lotr)),
            BenchmarkAwareStreamScorer()
        )

        val result = summary.scenarios.single()
        assertTrue(lotr.acceptableWinnerKeys.contains(result.selectedStreamKey))
        assertTrue(result.acceptableMatch)
    }

    @Test
    fun `parser backed tv and webdl movie scenarios evaluate to acceptable winners`() {
        val corpusDir = copySampleCorpusToTempDir()
        val corpus = BenchmarkAwareScoringCorpusLoader.load(corpusDir)
        val selectedScenarios = corpus.dataset.scenarios.filter {
            it.id == "tv-hevc-ddp-vs-av1-webdl" || it.id == "movie-webdl-non-remux-quality-pack"
        }

        val summary = BenchmarkAwareScoringEvaluator().evaluate(
            BenchmarkAwareScoringDataset(scenarios = selectedScenarios),
            BenchmarkAwareStreamScorer()
        )

        summary.scenarios.forEach { result ->
            val scenario = selectedScenarios.first { it.id == result.scenarioId }
            assertTrue(scenario.acceptableWinnerKeys.contains(result.selectedStreamKey))
            assertTrue(result.acceptableMatch)
        }
    }

    @Test
    fun `autoplay DV probe corpus scenarios resolve to expected post probe winners`() {
        val corpusDir = copySampleCorpusToTempDir()
        val corpus = BenchmarkAwareScoringCorpusLoader.load(corpusDir)
        val selectedScenarios = corpus.dataset.scenarios.filter {
            it.id == "dv-profile5-autoplay-fallback" ||
                it.id == "dv-profile7-autoplay-continue" ||
                it.id == "dv-probe-unknown-autoplay-fallback" ||
                it.id == "dv-remux-profile5-autoplay-continue"
        }

        val summary = BenchmarkAwareScoringEvaluator().evaluate(
            BenchmarkAwareScoringDataset(scenarios = selectedScenarios),
            BenchmarkAwareStreamScorer()
        )

        summary.scenarios.forEach { result ->
            val scenario = selectedScenarios.first { it.id == result.scenarioId }
            assertEquals(scenario.expectedResolvedWinnerStreamKey(), result.selectedStreamKey)
            assertTrue(result.acceptableMatch)
        }
    }

    private fun copySampleCorpusToTempDir() = Files.createTempDirectory("benchmark_scoring_corpus").also { tempDir ->
        val classLoader = javaClass.classLoader!!
        val root = classLoader.getResourceAsStream("benchmark_scoring_corpus/manifest.json")!!.readBytes()
        val dataset = classLoader.getResourceAsStream("benchmark_scoring_corpus/datasets/audio-fallback.json")!!.readBytes()
        val defaultVariant = classLoader.getResourceAsStream("benchmark_scoring_corpus/variants/default.json")!!.readBytes()
        val pcmHeavyVariant = classLoader.getResourceAsStream("benchmark_scoring_corpus/variants/pcm-heavy.json")!!.readBytes()
        val readme = classLoader.getResourceAsStream("benchmark_scoring_corpus/README.md")!!.readBytes()
        val defaultReport = classLoader.getResourceAsStream("benchmark_scoring_corpus/reports/default-summary.md")!!.readBytes()
        val variantsReport = classLoader.getResourceAsStream("benchmark_scoring_corpus/reports/variants.md")!!.readBytes()
        val template = classLoader.getResourceAsStream("benchmark_scoring_corpus/templates/template-scenario.json")!!.readBytes()
        Files.write(tempDir.resolve("manifest.json"), root)
        Files.createDirectories(tempDir.resolve("datasets"))
        Files.createDirectories(tempDir.resolve("variants"))
        Files.createDirectories(tempDir.resolve("templates"))
        Files.createDirectories(tempDir.resolve("reports"))
        Files.write(tempDir.resolve("datasets/audio-fallback.json"), dataset)
        listOf(
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
            val bytes = classLoader.getResourceAsStream("benchmark_scoring_corpus/datasets/$name")!!.readBytes()
            Files.write(tempDir.resolve("datasets/$name"), bytes)
        }
        Files.write(tempDir.resolve("variants/default.json"), defaultVariant)
        Files.write(tempDir.resolve("variants/pcm-heavy.json"), pcmHeavyVariant)
        Files.write(tempDir.resolve("templates/template-scenario.json"), template)
        Files.write(tempDir.resolve("README.md"), readme)
        Files.write(tempDir.resolve("reports/default-summary.md"), defaultReport)
        Files.write(tempDir.resolve("reports/variants.md"), variantsReport)
    }
}
