package com.nexio.tv.data.repository.benchmark

import java.nio.file.Path
import kotlin.io.path.Path

object BenchmarkAwareScoringCliRunner {

    @JvmStatic
    fun main(args: Array<String>) {
        val exitCode = run(args.toList())
        if (exitCode != 0) {
            throw IllegalStateException("benchmark-aware scoring CLI failed with exit code $exitCode")
        }
    }

    fun run(rawArgs: List<String>): Int {
        val parsed = parseArgs(rawArgs) ?: return 1
        val evaluator = BenchmarkAwareScoringEvaluator()
        val baseConfig = parsed.configPath
            ?.let { BenchmarkAwareStreamScoringConfig.fromJson(Path(it).toFile().readText()) }
            ?: BenchmarkAwareStreamScoringConfig.default()
        val generatedVariantPaths = parsed.generatedVariantDir?.let {
            BenchmarkAwareScoringVariantGridGenerator.writeVariants(
                outputDirectory = Path(it),
                base = baseConfig
            ).map { path -> path.toString() }
        }.orEmpty()
        val corpus = parsed.corpusDir?.let { BenchmarkAwareScoringCorpusLoader.load(Path(it)) }
        val dataset = corpus?.dataset ?: BenchmarkAwareScoringDataset.fromPath(Path(parsed.datasetPath ?: return 1))
        val variantsFromArgs = parsed.variantPaths.map { variantPath ->
            val path = Path(variantPath)
            BenchmarkAwareNamedConfigVariant(
                name = path.fileName.toString(),
                config = BenchmarkAwareStreamScoringConfig.fromJson(path.toFile().readText())
            )
        } + generatedVariantPaths.map { variantPath ->
            val path = Path(variantPath)
            BenchmarkAwareNamedConfigVariant(
                name = path.fileName.toString(),
                config = BenchmarkAwareStreamScoringConfig.fromJson(path.toFile().readText())
            )
        }
        val variants =
            if (parsed.configPath != null && variantsFromArgs.isEmpty()) {
                emptyList()
            } else if (variantsFromArgs.isNotEmpty()) {
                variantsFromArgs
            } else {
                corpus?.variants.orEmpty()
            }

        return if (variants.isEmpty()) {
            val summary = evaluator.evaluate(dataset, BenchmarkAwareStreamScorer(baseConfig))
            val jsonOutput = summary.toJson()
            parsed.outputPath?.let { Path(it).toFile().writeText(jsonOutput) } ?: println(jsonOutput)
            parsed.reportMarkdownPath?.let {
                Path(it).toFile().writeText(BenchmarkAwareScoringReportRenderer.renderSummaryMarkdown(summary))
            }
            0
        } else {
            val result = BenchmarkAwareScoringTuningHarness(evaluator).evaluateVariants(dataset, variants)
            val jsonOutput = result.rankedVariants.joinToString(
                prefix = "[",
                postfix = "]"
            ) { variant ->
                "{\"name\":\"${variant.name}\",\"objectiveScore\":${variant.objectiveScore},\"top1Accuracy\":${variant.summary.top1Accuracy},\"pairwiseAccuracy\":${variant.summary.pairwiseAccuracy}}"
            }
            parsed.outputPath?.let { Path(it).toFile().writeText(jsonOutput) } ?: println(jsonOutput)
            parsed.reportMarkdownPath?.let {
                Path(it).toFile().writeText(BenchmarkAwareScoringReportRenderer.renderVariantMarkdown(result))
            }
            0
        }
    }

    internal data class ParsedArgs(
        val datasetPath: String?,
        val corpusDir: String?,
        val configPath: String?,
        val variantPaths: List<String>,
        val generatedVariantDir: String?,
        val outputPath: String?,
        val reportMarkdownPath: String?
    )

    internal fun parseArgs(rawArgs: List<String>): ParsedArgs? {
        var datasetPath: String? = null
        var corpusDir: String? = null
        var configPath: String? = null
        val variantPaths = mutableListOf<String>()
        var generatedVariantDir: String? = null
        var outputPath: String? = null
        var reportMarkdownPath: String? = null

        var index = 0
        while (index < rawArgs.size) {
            when (rawArgs[index]) {
                "--dataset" -> datasetPath = rawArgs.getOrNull(++index)
                "--corpus-dir" -> corpusDir = rawArgs.getOrNull(++index)
                "--config" -> configPath = rawArgs.getOrNull(++index)
                "--variant" -> rawArgs.getOrNull(++index)?.let(variantPaths::add)
                "--generate-variants" -> generatedVariantDir = rawArgs.getOrNull(++index)
                "--output" -> outputPath = rawArgs.getOrNull(++index)
                "--report-md" -> reportMarkdownPath = rawArgs.getOrNull(++index)
            }
            index += 1
        }

        if (datasetPath == null && corpusDir == null) return null
        return ParsedArgs(
            datasetPath = datasetPath,
            corpusDir = corpusDir,
            configPath = configPath,
            variantPaths = variantPaths,
            generatedVariantDir = generatedVariantDir,
            outputPath = outputPath,
            reportMarkdownPath = reportMarkdownPath
        )
    }
}
