package com.nexio.tv.data.repository.benchmark

import com.google.gson.Gson
import java.nio.file.Files
import java.nio.file.Path

data class BenchmarkAwareScenarioEvaluation(
    val scenarioId: String,
    val selectedStreamKey: String?,
    val expectedWinnerStreamKey: String?,
    val topMatch: Boolean,
    val acceptableMatch: Boolean,
    val pairwiseSatisfied: Int,
    val pairwiseTotal: Int,
    val winnerFinalScore: Int?,
    val winnerProvider: String?,
    val winnerTransport: String?,
    val failureCategory: String?
)

data class BenchmarkAwareFailureSlice(
    val category: String,
    val count: Int,
    val scenarios: List<String>
)

data class BenchmarkAwareEvaluationSummary(
    val scenarioCount: Int,
    val exactTop1Matches: Int,
    val acceptableMatches: Int,
    val pairwiseSatisfied: Int,
    val pairwiseTotal: Int,
    val top1Accuracy: Double,
    val acceptableAccuracy: Double,
    val pairwiseAccuracy: Double,
    val failureSlices: List<BenchmarkAwareFailureSlice>,
    val scenarios: List<BenchmarkAwareScenarioEvaluation>
) {
    fun toJson(gson: Gson = Gson()): String = gson.toJson(this)

    fun writeTo(path: Path, gson: Gson = Gson()) {
        Files.write(path, toJson(gson).toByteArray())
    }
}

data class BenchmarkAwareNamedConfigVariant(
    val name: String,
    val config: BenchmarkAwareStreamScoringConfig
)

data class BenchmarkAwareVariantEvaluation(
    val name: String,
    val summary: BenchmarkAwareEvaluationSummary,
    val objectiveScore: Double
)

data class BenchmarkAwareTuningHarnessResult(
    val rankedVariants: List<BenchmarkAwareVariantEvaluation>
) {
    val winner: BenchmarkAwareVariantEvaluation?
        get() = rankedVariants.firstOrNull()
}

class BenchmarkAwareScoringEvaluator {

    fun evaluate(
        dataset: BenchmarkAwareScoringDataset,
        scorer: BenchmarkAwareStreamScorer
    ): BenchmarkAwareEvaluationSummary {
        val scenarioResults = dataset.scenarios.map { scenario ->
            val event = scorer.score(
                request = scenario.toShadowRequestContext(),
                streams = scenario.toStreamCards(),
                benchmarkSessions = scenario.toBenchmarkSessionMap()
            )
            val selectedKey = scenario.resolveAutoPlayWinnerStreamKey(
                selected = event.selected,
                selectedNonDolbyVisionFallback = event.selectedNonDolbyVisionFallback,
                winners = event.winners
            )
            val expectedWinnerStreamKey = scenario.expectedResolvedWinnerStreamKey()
            val acceptableWinnerKeys = scenario.acceptableResolvedWinnerKeys().ifEmpty {
                expectedWinnerStreamKey?.let(::listOf).orEmpty()
            }
            val exactTopMatch =
                expectedWinnerStreamKey != null &&
                    streamKeyMatches(selectedKey, expectedWinnerStreamKey)
            val acceptableMatch =
                selectedKey != null && acceptableWinnerKeys.any { streamKeyMatches(selectedKey, it) }
            val pairwiseSatisfied = scenario.preferredPairs.count { pair ->
                val preferred = event.winners.indexOfFirst { streamKeyMatches(it.streamKey, pair.preferredStreamKey) }
                val other = event.winners.indexOfFirst { streamKeyMatches(it.streamKey, pair.otherStreamKey) }
                preferred >= 0 && other >= 0 && preferred < other
            }
            val failureCategory = when {
                exactTopMatch -> null
                !acceptableMatch && selectedKey == null -> "no_selection"
                !acceptableMatch -> "wrong_winner"
                pairwiseSatisfied < scenario.preferredPairs.size -> "pairwise_mismatch"
                else -> null
            }
            BenchmarkAwareScenarioEvaluation(
                scenarioId = scenario.id,
                selectedStreamKey = selectedKey,
                expectedWinnerStreamKey = expectedWinnerStreamKey,
                topMatch = exactTopMatch,
                acceptableMatch = acceptableMatch,
                pairwiseSatisfied = pairwiseSatisfied,
                pairwiseTotal = scenario.preferredPairs.size,
                winnerFinalScore = event.selected?.finalScore,
                winnerProvider = event.selected?.provider?.storageKey,
                winnerTransport = event.selected?.transport?.wireKey,
                failureCategory = failureCategory
            )
        }

        val exactMatches = scenarioResults.count { it.topMatch }
        val acceptableMatches = scenarioResults.count { it.acceptableMatch }
        val pairwiseSatisfied = scenarioResults.sumOf { it.pairwiseSatisfied }
        val pairwiseTotal = scenarioResults.sumOf { it.pairwiseTotal }
        val scenarioCount = scenarioResults.size.coerceAtLeast(1)
        val failureSlices = scenarioResults
            .mapNotNull { result ->
                result.failureCategory?.let { category -> category to result.scenarioId }
            }
            .groupBy(
                keySelector = { it.first },
                valueTransform = { it.second }
            )
            .map { (category, scenarios) ->
                BenchmarkAwareFailureSlice(
                    category = category,
                    count = scenarios.size,
                    scenarios = scenarios.sorted()
                )
            }
            .sortedByDescending { it.count }

        return BenchmarkAwareEvaluationSummary(
            scenarioCount = scenarioResults.size,
            exactTop1Matches = exactMatches,
            acceptableMatches = acceptableMatches,
            pairwiseSatisfied = pairwiseSatisfied,
            pairwiseTotal = pairwiseTotal,
            top1Accuracy = exactMatches.toDouble() / scenarioCount,
            acceptableAccuracy = acceptableMatches.toDouble() / scenarioCount,
            pairwiseAccuracy = if (pairwiseTotal == 0) 1.0 else pairwiseSatisfied.toDouble() / pairwiseTotal,
            failureSlices = failureSlices,
            scenarios = scenarioResults
        )
    }
}

class BenchmarkAwareScoringTuningHarness(
    private val evaluator: BenchmarkAwareScoringEvaluator = BenchmarkAwareScoringEvaluator()
) {

    fun evaluateVariants(
        dataset: BenchmarkAwareScoringDataset,
        variants: List<BenchmarkAwareNamedConfigVariant>
    ): BenchmarkAwareTuningHarnessResult {
        val ranked = variants.map { variant ->
            val summary = evaluator.evaluate(dataset, BenchmarkAwareStreamScorer(variant.config))
            BenchmarkAwareVariantEvaluation(
                name = variant.name,
                summary = summary,
                objectiveScore = objectiveScore(summary)
            )
        }.sortedByDescending { it.objectiveScore }
        return BenchmarkAwareTuningHarnessResult(rankedVariants = ranked)
    }

    private fun objectiveScore(summary: BenchmarkAwareEvaluationSummary): Double {
        return summary.top1Accuracy * 4.0 +
            summary.acceptableAccuracy * 2.0 +
            summary.pairwiseAccuracy * 2.0
    }
}

private fun streamKeyMatches(actual: String?, expected: String?): Boolean {
    if (actual == null || expected == null) return actual == expected
    if (actual == expected) return true
    val baseActual = actual.substringBefore('|')
    val baseExpected = expected.substringBefore('|')
    return baseActual == baseExpected
}
