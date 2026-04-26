package com.nexio.tv.metadata.audit

import java.io.File

class MetadataAuditReportWriter {
    fun writeJson(report: MetadataExecutionReport, outputFile: File) {
        outputFile.parentFile?.mkdirs()
        outputFile.writeText(toJson(report))
    }

    fun writeMarkdown(report: MetadataExecutionReport, outputFile: File) {
        outputFile.parentFile?.mkdirs()
        outputFile.writeText(toMarkdown(report))
    }

    private fun toJson(report: MetadataExecutionReport): String =
        buildString {
            appendLine("{")
            appendLine("  \"fixtureName\": \"${report.fixtureName}\",")
            appendLine("  \"scenario\": \"${report.scenario.name}\",")
            appendLine("  \"verdict\": \"${report.verdict}\",")
            appendLine("  \"totalItems\": ${report.summaries.totalItems},")
            appendLine("  \"routedItems\": ${report.summaries.routedItems},")
            appendLine("  \"networkCalls\": ${report.summaries.networkCalls},")
            appendLine("  \"items\": [")
            report.items.forEachIndexed { index, item ->
                appendLine("    {")
                appendLine("      \"itemId\": \"${item.itemId}\",")
                appendLine("      \"itemType\": \"${item.itemType}\",")
                appendLine("      \"provider\": \"${item.routing?.provider ?: ""}\",")
                appendLine("      \"apiShapes\": [${item.runtimeCalls.joinToString { "\"${it.apiShapeId}\"" }}]")
                append("    }")
                if (index != report.items.lastIndex) append(",")
                appendLine()
            }
            appendLine("  ]")
            appendLine("}")
        }

    private fun toMarkdown(report: MetadataExecutionReport): String =
        buildString {
            appendLine("# Metadata Execution Audit")
            appendLine()
            appendLine("**Fixture:** `${report.fixtureName}`")
            appendLine("**Scenario:** `${report.scenario.name}`")
            appendLine("**Verdict:** `${report.verdict}`")
            appendLine()
            appendLine("## Summary")
            appendLine("| Metric | Value |")
            appendLine("|---|---:|")
            appendLine("| Items | ${report.summaries.totalItems} |")
            appendLine("| Routed items | ${report.summaries.routedItems} |")
            appendLine("| Network calls | ${report.summaries.networkCalls} |")
            appendLine("| Cache hits | ${report.summaries.cacheHits} |")
            appendLine("| Cache misses | ${report.summaries.cacheMisses} |")
            appendLine("| Forbidden overwrites | ${report.summaries.forbiddenOverwrites} |")
            appendLine("| Policy violations | ${report.summaries.policyViolations} |")
            appendLine()

            report.items.forEach { item ->
                appendLine("## ${item.itemId} / ${item.itemType}")
                appendLine()
                appendLine("### First paint")
                appendLine("- Source: `${item.firstPaint.source}`")
                appendLine("- Router executed: `${item.firstPaint.routerExecuted}`")
                appendLine("- Network executed: `${item.firstPaint.networkExecuted}`")
                appendLine()

                item.routing?.let { route ->
                    appendLine("### Routing")
                    appendLine("| Field | Value |")
                    appendLine("|---|---|")
                    appendLine("| Parent ID | `${route.parentId}` |")
                    appendLine("| Provider | `${route.provider}` |")
                    appendLine("| Media kind | `${route.mediaKind}` |")
                    appendLine("| Reason | `${route.reason}` |")
                    appendLine("| Requires identity resolution | `${route.targetIdRequiresIdentityResolution}` |")
                    appendLine()
                }

                item.providerPlan?.let { plan ->
                    appendLine("### Provider plan")
                    appendLine("| Step | Provider | API shape | Work class | Cache policy |")
                    appendLine("|---|---|---|---|---|")
                    plan.steps.forEach {
                        appendLine("| `${it.stepId}` | `${it.provider}` | `${it.apiShapeId}` | `${it.workClass}` | `${it.cachePolicy}` |")
                    }
                    appendLine()
                }

                if (item.cacheDecisions.isNotEmpty()) {
                    appendLine("### Cache decisions")
                    appendLine("| Provider | API shape | Decision | TTL | Stale |")
                    appendLine("|---|---|---|---:|---:|")
                    item.cacheDecisions.forEach {
                        appendLine("| `${it.provider}` | `${it.apiShapeId}` | `${it.decision}` | `${it.ttlMs}` | `${it.staleWindowMs}` |")
                    }
                    appendLine()
                }

                if (item.selectedFields.isNotEmpty()) {
                    appendLine("### Final fields")
                    appendLine("| Field | Source provider | Role | Value |")
                    appendLine("|---|---|---|---|")
                    item.selectedFields.forEach {
                        appendLine("| `${it.field}` | `${it.selectedProvider}` | `${it.sourceRole}` | `${it.valuePreview.orEmpty()}` |")
                    }
                    appendLine()
                }
            }
        }
}
