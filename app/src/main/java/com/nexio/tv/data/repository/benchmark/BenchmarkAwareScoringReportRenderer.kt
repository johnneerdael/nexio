package com.nexio.tv.data.repository.benchmark

import java.util.Locale

object BenchmarkAwareScoringReportRenderer {

    fun renderSummaryMarkdown(summary: BenchmarkAwareEvaluationSummary): String {
        return buildString {
            appendLine("# Benchmark-Aware Scoring Evaluation Report")
            appendLine()
            appendLine("- Scenarios: ${summary.scenarioCount}")
            appendLine("- Top-1 accuracy: ${formatPercent(summary.top1Accuracy)}")
            appendLine("- Acceptable accuracy: ${formatPercent(summary.acceptableAccuracy)}")
            appendLine("- Pairwise accuracy: ${formatPercent(summary.pairwiseAccuracy)}")
            if (summary.failureSlices.isNotEmpty()) {
                appendLine()
                appendLine("## Failure Slices")
                appendLine()
                appendLine("| Category | Count | Scenarios |")
                appendLine("|---|---:|---|")
                summary.failureSlices.forEach { slice ->
                    appendLine(
                        "| ${slice.category} | ${slice.count} | ${slice.scenarios.joinToString(", ")} |"
                    )
                }
            }
            appendLine()
            appendLine("## Scenario Results")
            appendLine()
            appendLine("| Scenario | Selected | Expected | Top match | Acceptable | Pairwise | Failure |")
            appendLine("|---|---|---|---:|---:|---:|---|")
            summary.scenarios.forEach { scenario ->
                appendLine(
                    "| ${scenario.scenarioId} | ${scenario.selectedStreamKey ?: "none"} | ${scenario.expectedWinnerStreamKey ?: "n/a"} | ${yesNo(scenario.topMatch)} | ${yesNo(scenario.acceptableMatch)} | ${scenario.pairwiseSatisfied}/${scenario.pairwiseTotal} | ${scenario.failureCategory ?: "none"} |"
                )
            }
        }
    }

    fun renderVariantMarkdown(result: BenchmarkAwareTuningHarnessResult): String {
        return buildString {
            appendLine("# Benchmark-Aware Scoring Variant Report")
            appendLine()
            appendLine("| Variant | Objective | Top-1 | Acceptable | Pairwise |")
            appendLine("|---|---:|---:|---:|---:|")
            result.rankedVariants.forEach { variant ->
                appendLine(
                    "| ${variant.name} | ${"%.3f".format(variant.objectiveScore)} | ${formatPercent(variant.summary.top1Accuracy)} | ${formatPercent(variant.summary.acceptableAccuracy)} | ${formatPercent(variant.summary.pairwiseAccuracy)} |"
                )
            }

            result.rankedVariants.forEach { variant ->
                val failedScenarios = variant.summary.scenarios.filterNot { it.acceptableMatch }
                appendLine()
                appendLine("## Variant: ${variant.name}")
                appendLine()
                if (failedScenarios.isEmpty()) {
                    appendLine("- No failed scenarios")
                } else {
                    appendLine("### Failed Scenarios")
                    appendLine()
                    appendLine("| Scenario | Selected | Expected | Failure |")
                    appendLine("|---|---|---|---|")
                    failedScenarios.forEach { scenario ->
                        appendLine(
                            "| ${scenario.scenarioId} | ${scenario.selectedStreamKey ?: "none"} | ${scenario.expectedWinnerStreamKey ?: "n/a"} | ${scenario.failureCategory ?: "unknown"} |"
                        )
                    }
                }
                if (variant.summary.failureSlices.isNotEmpty()) {
                    appendLine()
                    appendLine("### Failure Slices")
                    appendLine()
                    appendLine("| Category | Count | Scenarios |")
                    appendLine("|---|---:|---|")
                    variant.summary.failureSlices.forEach { slice ->
                        appendLine(
                            "| ${slice.category} | ${slice.count} | ${slice.scenarios.joinToString(", ")} |"
                        )
                    }
                }
            }
        }
    }

    private fun formatPercent(value: Double): String = String.format(Locale.US, "%.1f%%", value * 100.0)

    private fun yesNo(value: Boolean): String = if (value) "yes" else "no"
}
