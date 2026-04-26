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

    fun writeBundleJson(bundle: MetadataExecutionReportBundle, outputFile: File) {
        outputFile.parentFile?.mkdirs()
        outputFile.writeText(toBundleJson(bundle))
    }

    fun writeBundleMarkdown(bundle: MetadataExecutionReportBundle, outputFile: File) {
        outputFile.parentFile?.mkdirs()
        outputFile.writeText(toBundleMarkdown(bundle))
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

    private fun toBundleJson(bundle: MetadataExecutionReportBundle): String =
        buildString {
            appendLine("{")
            appendLine("  \"verdict\": \"${bundle.verdict}\",")
            appendLine("  \"generatedAtEpochMs\": ${bundle.generatedAtEpochMs},")
            appendLine("  \"summary\": ${summaryJson(bundle.summaries, indent = "  ")},")
            appendLine("  \"reports\": [")
            bundle.reports.forEachIndexed { reportIndex, report ->
                appendLine("    {")
                appendLine("      \"fixtureName\": \"${report.fixtureName}\",")
                appendLine("      \"scenario\": \"${report.scenario.name}\",")
                appendLine("      \"verdict\": \"${report.verdict}\",")
                appendLine("      \"items\": [")
                report.items.forEachIndexed { itemIndex, item ->
                    appendLine("        {")
                    appendLine("          \"itemId\": \"${item.itemId}\",")
                    appendLine("          \"itemType\": \"${item.itemType}\",")
                    appendLine("          \"firstPaint\": {")
                    appendLine("            \"source\": \"${item.firstPaint.source}\",")
                    appendLine("            \"routerExecuted\": ${item.firstPaint.routerExecuted},")
                    appendLine("            \"networkExecuted\": ${item.firstPaint.networkExecuted}")
                    appendLine("          },")
                    appendLine("          \"routing\": ${routeJson(item.routing, indent = "          ")},")
                    appendLine("          \"providerPlan\": ${providerPlanJson(item.providerPlan, indent = "          ")},")
                    appendLine("          \"runtimeCalls\": [${item.runtimeCalls.joinToString { runtimeCallJson(it) }}],")
                    appendLine("          \"cacheDecisions\": [${item.cacheDecisions.joinToString { cacheDecisionJson(it) }}],")
                    appendLine("          \"resolverSchedule\": ${resolverScheduleJson(item.resolverSchedule, indent = "          ")},")
                    appendLine("          \"selectedFields\": [${item.selectedFields.joinToString { selectedFieldJson(it) }}],")
                    appendLine("          \"forbiddenOverwrites\": [${item.forbiddenOverwrites.joinToString { forbiddenOverwriteJson(it) }}],")
                    appendLine("          \"continueWatchingSnapshot\": ${continueWatchingJson(item.continueWatchingSnapshot, indent = "          ")},")
                    appendLine("          \"identityResolution\": ${identityResolutionJson(item.identityResolution)},")
                    appendLine("          \"productionCallerOwnership\": [${item.productionCallerOwnership.joinToString { productionCallerOwnershipJson(it) }}]")
                    append("        }")
                    if (itemIndex != report.items.lastIndex) append(",")
                    appendLine()
                }
                appendLine("      ]")
                append("    }")
                if (reportIndex != bundle.reports.lastIndex) append(",")
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
                    appendLine("| Field | Source provider | Role | Value | Ownership rule | Rejected candidates |")
                    appendLine("|---|---|---|---|---|---|")
                    item.selectedFields.forEach {
                        appendLine("| `${it.field}` | `${it.selectedProvider}` | `${it.sourceRole}` | `${it.valuePreview.orEmpty()}` | `${it.ownershipRule}` | `${it.rejectedCandidates.joinToString { rejected -> "${rejected.provider}:${rejected.reason}" }}` |")
                    }
                    appendLine()
                }

                appendContinueWatching(item.continueWatchingSnapshot)
            }
        }

    private fun toBundleMarkdown(bundle: MetadataExecutionReportBundle): String =
        buildString {
            appendLine("# Metadata Execution Audit Bundle")
            appendLine()
            appendLine("**Verdict:** `${bundle.verdict}`")
            appendLine("**Generated:** `${bundle.generatedAtEpochMs}`")
            appendLine()
            appendSummary(bundle.summaries)
            bundle.reports.forEach { report ->
                appendLine("## Scenario `${report.scenario.name}`")
                appendLine()
                appendLine("**Fixture:** `${report.fixtureName}`")
                appendLine("**Verdict:** `${report.verdict}`")
                appendLine()
                report.items.forEach { item ->
                    appendLine("### ${item.itemId} / ${item.itemType}")
                    appendLine()
                    appendLine("| First paint source | Router executed | Network executed |")
                    appendLine("|---|---:|---:|")
                    appendLine("| `${item.firstPaint.source}` | `${item.firstPaint.routerExecuted}` | `${item.firstPaint.networkExecuted}` |")
                    appendLine()
                    item.routing?.let { route ->
                        appendLine("#### Routing")
                        appendLine("| Provider | Media kind | Reason | Used inputs | Ignored inputs | Requires identity resolution |")
                        appendLine("|---|---|---|---|---|---:|")
                        appendLine("| `${route.provider}` | `${route.mediaKind}` | `${route.reason}` | `${route.usedInputs.joinToString()}` | `${route.ignoredInputs.joinToString()}` | `${route.targetIdRequiresIdentityResolution}` |")
                        appendLine()
                    }
                    item.providerPlan?.let { plan ->
                        appendLine("#### Provider plan")
                        appendLine("| Step | Provider | API shape | Work class | Cache policy |")
                        appendLine("|---|---|---|---|---|")
                        plan.steps.forEach {
                            appendLine("| `${it.stepId}` | `${it.provider}` | `${it.apiShapeId}` | `${it.workClass}` | `${it.cachePolicy}` |")
                        }
                        appendLine()
                    }
                    if (item.cacheDecisions.isNotEmpty()) {
                        appendLine("#### Cache decisions")
                        appendLine("| Provider | API shape | Decision | TTL | Stale | Reason |")
                        appendLine("|---|---|---|---:|---:|---|")
                        item.cacheDecisions.forEach {
                            appendLine("| `${it.provider}` | `${it.apiShapeId}` | `${it.decision}` | `${it.ttlMs}` | `${it.staleWindowMs}` | `${it.reason}` |")
                        }
                        appendLine()
                    }
                    if (item.selectedFields.isNotEmpty()) {
                        appendLine("#### Final fields")
                        appendLine("| Field | Selected provider | Role | Value | Ownership rule | Rejected candidates |")
                        appendLine("|---|---|---|---|---|---|")
                        item.selectedFields.forEach {
                            appendLine("| `${it.field}` | `${it.selectedProvider}` | `${it.sourceRole}` | `${it.valuePreview.orEmpty()}` | `${it.ownershipRule}` | `${it.rejectedCandidates.joinToString { rejected -> "${rejected.provider}:${rejected.reason}" }}` |")
                        }
                        appendLine()
                    }
                    if (item.forbiddenOverwrites.isNotEmpty()) {
                        appendLine("#### Forbidden overwrites")
                        item.forbiddenOverwrites.forEach {
                            appendLine("- `${it.field}` rejected `${it.rejectedProvider}` because ${it.reason}")
                        }
                        appendLine()
                    }
                    appendIdentityResolution(item.identityResolution)
                    appendProductionCallerOwnership(item.productionCallerOwnership)
                    appendContinueWatching(item.continueWatchingSnapshot)
                }
            }
        }

    private fun StringBuilder.appendSummary(summary: AuditSummaries) {
        appendLine("## Summary")
        appendLine("| Metric | Value |")
        appendLine("|---|---:|")
        appendLine("| Items | ${summary.totalItems} |")
        appendLine("| Routed items | ${summary.routedItems} |")
        appendLine("| Network calls | ${summary.networkCalls} |")
        appendLine("| Cache hits | ${summary.cacheHits} |")
        appendLine("| Cache misses | ${summary.cacheMisses} |")
        appendLine("| Stale hits | ${summary.staleHits} |")
        appendLine("| Forbidden overwrites | ${summary.forbiddenOverwrites} |")
        appendLine("| Policy violations | ${summary.policyViolations} |")
        appendLine()
    }

    private fun StringBuilder.appendContinueWatching(snapshot: ContinueWatchingSnapshotEvent?) {
        snapshot ?: return
        appendLine("### Continue Watching")
        appendLine("| Parent ID | Provider | Routing version | Click-time metadata | Rerouted due to version mismatch |")
        appendLine("|---|---|---:|---:|---:|")
        appendLine("| `${snapshot.parentId}` | `${snapshot.provider}` | `${snapshot.routingVersion}` | `${snapshot.hasClickTimeMetadata}` | `${snapshot.reroutedDueToVersionMismatch}` |")
        appendLine()
    }

    private fun StringBuilder.appendIdentityResolution(event: IdentityResolutionEvent?) {
        event ?: return
        appendLine("#### Identity resolution")
        appendLine("| Required | Source ID | Target provider | Resolver | API shape | Result | Success |")
        appendLine("|---:|---|---|---|---|---|---:|")
        appendLine("| `${event.required}` | `${event.sourceId}` | `${event.targetProvider}` | `${event.resolver}` | `${event.apiShapeId}` | `${event.resultId}` | `${event.success}` |")
        appendLine()
    }

    private fun StringBuilder.appendProductionCallerOwnership(events: List<ProductionCallerOwnershipEvent>) {
        if (events.isEmpty()) return
        appendLine("#### Production caller ownership")
        appendLine("| Path | Entrypoint | Facade/repository | Plan runner | FieldResolver | Legacy after facade |")
        appendLine("|---|---|---:|---:|---:|---:|")
        events.forEach {
            appendLine("| `${it.pathName}` | `${it.entrypoint}` | `${it.facadeOrRepositoryCalled}` | `${it.providerPlanRunnerExpected}` | `${it.fieldResolverExpected}` | `${it.legacyRouterUsedAfterFacade}` |")
        }
        appendLine()
    }

    private fun summaryJson(summary: AuditSummaries, indent: String): String =
        """{"totalItems":${summary.totalItems},"routedItems":${summary.routedItems},"networkCalls":${summary.networkCalls},"cacheHits":${summary.cacheHits},"cacheMisses":${summary.cacheMisses},"staleHits":${summary.staleHits},"forbiddenOverwrites":${summary.forbiddenOverwrites},"policyViolations":${summary.policyViolations}}"""

    private fun routeJson(route: RouteEvent?, indent: String): String =
        route?.let {
            """{"parentId":"${it.parentId}","provider":"${it.provider}","mediaKind":"${it.mediaKind}","reason":"${it.reason}","targetIdRequiresIdentityResolution":${it.targetIdRequiresIdentityResolution},"usedInputs":[${it.usedInputs.joinToString { input -> "\"$input\"" }}],"ignoredInputs":[${it.ignoredInputs.joinToString { input -> "\"$input\"" }}]}"""
        } ?: "null"

    private fun providerPlanJson(plan: ProviderPlanEvent?, indent: String): String =
        plan?.let {
            """{"provider":"${it.provider}","mediaKind":"${it.mediaKind}","depth":"${it.depth}","steps":[${it.steps.joinToString { step -> "{\"stepId\":\"${step.stepId}\",\"provider\":\"${step.provider}\",\"apiShapeId\":\"${step.apiShapeId}\",\"workClass\":\"${step.workClass}\",\"cachePolicy\":\"${step.cachePolicy}\",\"requiresIdentityResolution\":${step.requiresIdentityResolution}}" }}]}"""
        } ?: "null"

    private fun resolverScheduleJson(schedule: ResolverScheduleEvent?, indent: String): String =
        schedule?.let {
            """{"depth":"${it.depth}","resolversScheduled":[${it.resolversScheduled.joinToString { resolver -> "\"$resolver\"" }}],"resolversSkipped":${it.resolversSkipped}}"""
        } ?: "null"

    private fun runtimeCallJson(call: RuntimeCallEvent): String =
        """{"provider":"${call.provider}","apiShapeId":"${call.apiShapeId}","operationKey":"${call.operationKey}","cacheKey":"${call.cacheKey}","workClass":"${call.workClass}","executedNetwork":${call.executedNetwork}}"""

    private fun cacheDecisionJson(decision: CacheDecisionEvent): String =
        """{"provider":"${decision.provider}","apiShapeId":"${decision.apiShapeId}","cacheKey":"${decision.cacheKey}","decision":"${decision.decision}","ttlMs":${decision.ttlMs},"staleWindowMs":${decision.staleWindowMs},"reason":"${decision.reason}"}"""

    private fun selectedFieldJson(field: FieldSelectedEvent): String =
        """{"field":"${field.field}","selectedProvider":"${field.selectedProvider}","sourceRole":"${field.sourceRole}","valuePreview":"${field.valuePreview.orEmpty()}","ownershipRule":"${field.ownershipRule}","rejectedCandidates":[${field.rejectedCandidates.joinToString { rejected -> "{\"provider\":\"${rejected.provider}\",\"reason\":\"${rejected.reason}\"}" }}]}"""

    private fun forbiddenOverwriteJson(overwrite: ForbiddenOverwriteEvent): String =
        """{"field":"${overwrite.field}","primaryProvider":"${overwrite.primaryProvider}","rejectedProvider":"${overwrite.rejectedProvider}","reason":"${overwrite.reason}"}"""

    private fun continueWatchingJson(snapshot: ContinueWatchingSnapshotEvent?, indent: String): String =
        snapshot?.let {
            """{"contentId":"${it.contentId}","parentId":"${it.parentId}","provider":"${it.provider}","routingVersion":${it.routingVersion},"hasClickTimeMetadata":${it.hasClickTimeMetadata},"reroutedDueToVersionMismatch":${it.reroutedDueToVersionMismatch}}"""
        } ?: "null"

    private fun identityResolutionJson(event: IdentityResolutionEvent?): String =
        event?.let {
            """{"required":${it.required},"sourceId":"${it.sourceId}","targetProvider":"${it.targetProvider}","resolver":"${it.resolver}","apiShapeId":"${it.apiShapeId}","resultId":"${it.resultId}","success":${it.success}}"""
        } ?: "null"

    private fun productionCallerOwnershipJson(event: ProductionCallerOwnershipEvent): String =
        """{"pathName":"${event.pathName}","entrypoint":"${event.entrypoint}","facadeOrRepositoryCalled":${event.facadeOrRepositoryCalled},"providerPlanRunnerExpected":${event.providerPlanRunnerExpected},"fieldResolverExpected":${event.fieldResolverExpected},"legacyRouterUsedAfterFacade":${event.legacyRouterUsedAfterFacade}}"""
}
