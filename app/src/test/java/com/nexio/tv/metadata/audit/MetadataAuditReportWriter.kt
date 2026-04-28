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
            appendLine("  \"schemaVersion\": ${report.schemaVersion},")
            appendLine("  \"gitSha\": ${jsonString(report.provenance.gitSha)},")
            appendLine("  \"gitWorktree\": ${gitWorktreeJson(report.provenance.gitWorktree)},")
            appendLine("  \"artifactRole\": \"SMOKE_DEBUG_ONLY\",")
            appendLine("  \"fixtureName\": ${jsonString(report.fixtureName)},")
            appendLine("  \"scenario\": ${jsonString(report.scenario.name)},")
            appendLine("  \"verdict\": ${jsonString(report.verdict.name)},")
            appendLine("  \"totalItems\": ${report.summaries.totalItems},")
            appendLine("  \"routedItems\": ${report.summaries.routedItems},")
            appendLine("  \"networkCalls\": ${report.summaries.networkCalls},")
            appendLine("  \"items\": [")
            report.items.forEachIndexed { index, item ->
                appendLine("    {")
                appendLine("      \"itemId\": ${jsonString(item.itemId)},")
                appendLine("      \"itemType\": ${jsonString(item.itemType)},")
                appendLine("      \"provider\": ${jsonString(item.routing?.provider?.name.orEmpty())},")
                appendLine("      \"railSource\": ${nullableStringJson(item.railSource)},")
                appendLine("      \"sourceProvider\": ${nullableStringJson(item.sourceProvider)},")
                appendLine("      \"sourcePayloadFieldsUsed\": ${stringArrayJson(item.sourcePayloadFieldsUsed)},")
                appendLine("      \"routingAfterVisible\": ${routeJson(item.routingAfterVisible, indent = "      ")},")
                appendLine("      \"selectedFieldsBeforeHydration\": [${item.selectedFieldsBeforeHydration.joinToString { selectedFieldJson(it) }}],")
                appendLine("      \"selectedFieldsAfterHydration\": [${item.selectedFieldsAfterHydration.joinToString { selectedFieldJson(it) }}],")
                appendLine("      \"identityMappingsHarvested\": ${stringMapJson(item.identityMappingsHarvested)},")
                appendLine("      \"apiShapes\": ${stringArrayJson(item.runtimeCalls.map { it.apiShapeId })}")
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
            appendLine("  \"schemaVersion\": ${bundle.schemaVersion},")
            appendLine("  \"gitSha\": ${jsonString(bundle.provenance.gitSha)},")
            appendLine("  \"gitWorktree\": ${gitWorktreeJson(bundle.provenance.gitWorktree)},")
            appendLine("  \"artifactRole\": \"SIGN_OFF_AGGREGATE\",")
            appendLine("  \"verdict\": ${jsonString(bundle.verdict.name)},")
            appendLine("  \"generatedAtEpochMs\": ${bundle.generatedAtEpochMs},")
            appendLine("  \"summary\": ${summaryJson(bundle.summaries, indent = "  ")},")
            appendLine("  \"reports\": [")
            bundle.reports.forEachIndexed { reportIndex, report ->
                appendLine("    {")
                appendLine("      \"fixtureName\": ${jsonString(report.fixtureName)},")
                appendLine("      \"scenario\": ${jsonString(report.scenario.name)},")
                appendLine("      \"verdict\": ${jsonString(report.verdict.name)},")
                appendLine("      \"items\": [")
                report.items.forEachIndexed { itemIndex, item ->
                    appendLine("        {")
                    appendLine("          \"itemId\": ${jsonString(item.itemId)},")
                    appendLine("          \"itemType\": ${jsonString(item.itemType)},")
                    appendLine("          \"firstPaint\": {")
                    appendLine("            \"source\": ${jsonString(item.firstPaint.source)},")
                    appendLine("            \"routerExecuted\": ${item.firstPaint.routerExecuted},")
                    appendLine("            \"networkExecuted\": ${item.firstPaint.networkExecuted}")
                    appendLine("          },")
                    appendLine("          \"routing\": ${routeJson(item.routing, indent = "          ")},")
                    appendLine("          \"railSource\": ${nullableStringJson(item.railSource)},")
                    appendLine("          \"sourceProvider\": ${nullableStringJson(item.sourceProvider)},")
                    appendLine("          \"sourcePayloadFieldsUsed\": ${stringArrayJson(item.sourcePayloadFieldsUsed)},")
                    appendLine("          \"routingAfterVisible\": ${routeJson(item.routingAfterVisible, indent = "          ")},")
                    appendLine("          \"selectedFieldsBeforeHydration\": [${item.selectedFieldsBeforeHydration.joinToString { selectedFieldJson(it) }}],")
                    appendLine("          \"selectedFieldsAfterHydration\": [${item.selectedFieldsAfterHydration.joinToString { selectedFieldJson(it) }}],")
                    appendLine("          \"identityMappingsHarvested\": ${stringMapJson(item.identityMappingsHarvested)},")
                    appendLine("          \"providerPlan\": ${providerPlanJson(item.providerPlan, indent = "          ")},")
                    appendLine("          \"runtimeCalls\": [${item.runtimeCalls.joinToString { runtimeCallJson(it) }}],")
                    appendLine("          \"cacheDecisions\": [${item.cacheDecisions.joinToString { cacheDecisionJson(it) }}],")
                    appendLine("          \"resolverSchedule\": ${resolverScheduleJson(item.resolverSchedule, indent = "          ")},")
                    appendLine("          \"selectedFields\": [${item.selectedFields.joinToString { selectedFieldJson(it) }}],")
                    appendLine("          \"forbiddenOverwrites\": [${item.forbiddenOverwrites.joinToString { forbiddenOverwriteJson(it) }}],")
                    appendLine("          \"continueWatchingSnapshot\": ${continueWatchingJson(item.continueWatchingSnapshot, indent = "          ")},")
                    appendLine("          \"identityResolution\": ${identityResolutionJson(item.identityResolution)},")
                    appendLine("          \"localization\": ${localizationJson(item.localization)},")
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
            appendLine("> Smoke/debug artifact only. Use `metadata-execution-report.md` for production sign-off.")
            appendLine()
            appendLine("**Fixture:** `${report.fixtureName}`")
            appendLine("**Scenario:** `${report.scenario.name}`")
            appendLine("**Verdict:** `${report.verdict}`")
            appendLine("**Schema version:** `${report.schemaVersion}`")
            appendLine("**Git SHA:** `${report.provenance.gitSha}`")
            appendLine("**Git worktree:** `${report.provenance.gitWorktree.state}` (${report.provenance.gitWorktree.dirtyFileCount} changed, ${report.provenance.gitWorktree.untrackedFileCount} untracked)")
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
                appendRailPreview(item)

                item.routing?.let { route ->
                    appendLine("### Routing")
                    appendLine("| Field | Value |")
                    appendLine("|---|---|")
                    appendLine("| Parent ID | `${route.parentId}` |")
                    appendLine("| Provider | `${route.provider}` |")
                    appendLine("| Media kind | `${route.mediaKind}` |")
                    appendLine("| Reason | `${route.reason}` |")
                    appendLine("| Pre-resolution identity required | `${route.preResolutionTargetIdRequiresIdentityResolution}` |")
                    appendLine("| Execution identity resolved | `${!route.targetIdRequiresIdentityResolution}` |")
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
                        appendLine("| `${it.field}` | `${it.selectedProvider}` | `${it.sourceRole}` | `${it.valuePreview.orEmpty()}` | `${it.ownershipRule}` | `${it.rejectedCandidates.joinToString { rejected -> rejectedCandidateMarkdown(rejected) }}` |")
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
            appendLine("**Schema version:** `${bundle.schemaVersion}`")
            appendLine("**Git SHA:** `${bundle.provenance.gitSha}`")
            appendLine("**Git worktree:** `${bundle.provenance.gitWorktree.state}` (${bundle.provenance.gitWorktree.dirtyFileCount} changed, ${bundle.provenance.gitWorktree.untrackedFileCount} untracked)")
            appendLine("**Artifact role:** `SIGN_OFF_AGGREGATE`")
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
                    appendRailPreview(item)
                    item.routing?.let { route ->
                        appendLine("#### Routing")
                        appendLine("| Provider | Media kind | Reason | Used inputs | Ignored inputs | Pre-resolution identity required | Execution identity resolved |")
                        appendLine("|---|---|---|---|---|---:|---:|")
                        appendLine("| `${route.provider}` | `${route.mediaKind}` | `${route.reason}` | `${route.usedInputs.joinToString()}` | `${route.ignoredInputs.joinToString()}` | `${route.preResolutionTargetIdRequiresIdentityResolution}` | `${!route.targetIdRequiresIdentityResolution}` |")
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
                            appendLine("| `${it.field}` | `${it.selectedProvider}` | `${it.sourceRole}` | `${it.valuePreview.orEmpty()}` | `${it.ownershipRule}` | `${it.rejectedCandidates.joinToString { rejected -> rejectedCandidateMarkdown(rejected) }}` |")
                        }
                        appendLine()
                    }
                    appendLocalization(item.localization)
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

    private fun StringBuilder.appendRailPreview(item: ItemExecutionReport) {
        if (item.railSource == null && item.sourceProvider == null && item.sourcePayloadFieldsUsed.isEmpty()) return
        appendLine("#### Rail preview")
        appendLine("| Rail source | Source provider | Payload fields used | Routing after visible | Identity mappings harvested |")
        appendLine("|---|---|---|---|---|")
        appendLine("| `${item.railSource.orEmpty()}` | `${item.sourceProvider.orEmpty()}` | `${item.sourcePayloadFieldsUsed.joinToString()}` | `${item.routingAfterVisible?.provider ?: ""}` | `${item.identityMappingsHarvested.entries.joinToString { "${it.key}:${it.value}" }}` |")
        appendLine()
        if (item.selectedFieldsBeforeHydration.isNotEmpty()) {
            appendLine("##### Fields before hydration")
            appendLine("| Field | Provider | Value |")
            appendLine("|---|---|---|")
            item.selectedFieldsBeforeHydration.forEach {
                appendLine("| `${it.field}` | `${it.selectedProvider}` | `${it.valuePreview.orEmpty()}` |")
            }
            appendLine()
        }
        if (item.selectedFieldsAfterHydration.isNotEmpty()) {
            appendLine("##### Fields after hydration")
            appendLine("| Field | Provider | Value |")
            appendLine("|---|---|---|")
            item.selectedFieldsAfterHydration.forEach {
                appendLine("| `${it.field}` | `${it.selectedProvider}` | `${it.valuePreview.orEmpty()}` |")
            }
            appendLine()
        }
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

    private fun StringBuilder.appendLocalization(event: LocalizationEvent?) {
        event ?: return
        appendLine("#### Localization")
        appendLine("| Provider | Requested | Fallback | Policy | Provider fallback used | Per-episode fallbacks |")
        appendLine("|---|---|---|---:|---:|---:|")
        appendLine("| `${event.provider}` | `${event.requestedLanguage}` | `${event.fallbackLanguage}` | `${event.policyVersion}` | `${event.providerFallbackUsed}` | `${event.perEpisodeTranslationFallbacksAttempted}/${event.maxPerEpisodeTranslationFallbacksAllowed}` |")
        appendLine()
        appendLine("| API shape | Language | Role | Cache decision | Network | Source | Cache key |")
        appendLine("|---|---|---|---|---:|---|---|")
        event.payloads.forEach {
            appendLine("| `${it.apiShapeId}` | `${it.language}` | `${it.fallbackRole}` | `${it.cacheDecision}` | `${it.executedNetwork}` | `${it.source}` | `${it.cacheKey}` |")
        }
        appendLine()
    }

    private fun summaryJson(summary: AuditSummaries, indent: String): String =
        """{"totalItems":${summary.totalItems},"routedItems":${summary.routedItems},"networkCalls":${summary.networkCalls},"cacheHits":${summary.cacheHits},"cacheMisses":${summary.cacheMisses},"staleHits":${summary.staleHits},"forbiddenOverwrites":${summary.forbiddenOverwrites},"policyViolations":${summary.policyViolations}}"""

    private fun routeJson(route: RouteEvent?, indent: String): String =
        route?.let {
            """{"parentId":${jsonString(it.parentId)},"provider":${jsonString(it.provider.name)},"mediaKind":${jsonString(it.mediaKind.name)},"reason":${jsonString(it.reason.name)},"preResolutionTargetIdRequiresIdentityResolution":${it.preResolutionTargetIdRequiresIdentityResolution},"executionTargetIdRequiresIdentityResolution":${it.targetIdRequiresIdentityResolution},"executionIdentityResolved":${!it.targetIdRequiresIdentityResolution},"usedInputs":${stringArrayJson(it.usedInputs)},"ignoredInputs":${stringArrayJson(it.ignoredInputs)}}"""
        } ?: "null"

    private fun providerPlanJson(plan: ProviderPlanEvent?, indent: String): String =
        plan?.let {
            """{"provider":${jsonString(it.provider.name)},"mediaKind":${jsonString(it.mediaKind.name)},"depth":${jsonString(it.depth.name)},"steps":[${it.steps.joinToString { step -> "{\"stepId\":${jsonString(step.stepId)},\"provider\":${jsonString(step.provider.name)},\"apiShapeId\":${jsonString(step.apiShapeId)},\"workClass\":${jsonString(step.workClass)},\"cachePolicy\":${jsonString(step.cachePolicy)},\"requiresIdentityResolution\":${step.requiresIdentityResolution}}" }}]}"""
        } ?: "null"

    private fun resolverScheduleJson(schedule: ResolverScheduleEvent?, indent: String): String =
        schedule?.let {
            """{"depth":${jsonString(it.depth.name)},"resolversScheduled":${stringArrayJson(it.resolversScheduled.map { resolver -> resolver.name })},"resolversSkipped":${stringMapJson(it.resolversSkipped)}}"""
        } ?: "null"

    private fun runtimeCallJson(call: RuntimeCallEvent): String =
        """{"provider":${jsonString(call.provider)},"apiShapeId":${jsonString(call.apiShapeId)},"operationKey":${jsonString(call.operationKey)},"cacheKey":${jsonString(call.cacheKey)},"workClass":${jsonString(call.workClass)},"executedNetwork":${call.executedNetwork}}"""

    private fun cacheDecisionJson(decision: CacheDecisionEvent): String =
        """{"provider":${jsonString(decision.provider)},"apiShapeId":${jsonString(decision.apiShapeId)},"cacheKey":${jsonString(decision.cacheKey)},"decision":${jsonString(decision.decision.name)},"ttlMs":${decision.ttlMs},"staleWindowMs":${decision.staleWindowMs},"reason":${jsonString(decision.reason)}}"""

    private fun selectedFieldJson(field: FieldSelectedEvent): String =
        """{"field":${jsonString(field.field)},"selectedProvider":${jsonString(field.selectedProvider)},"sourceRole":${jsonString(field.sourceRole)},"valuePreview":${jsonString(field.valuePreview.orEmpty())},"ownershipRule":${jsonString(field.ownershipRule)},"rejectedCandidates":[${field.rejectedCandidates.joinToString { rejected -> rejectedCandidateJson(rejected) }}]}"""

    private fun rejectedCandidateJson(rejected: RejectedCandidateReport): String =
        buildString {
            append("""{"provider":${jsonString(rejected.provider)}""")
            rejected.sourceRole?.let { sourceRole ->
                append(""","sourceRole":${jsonString(sourceRole)}""")
            }
            append(""","reason":${jsonString(rejected.reason)}}""")
        }

    private fun rejectedCandidateMarkdown(rejected: RejectedCandidateReport): String {
        val source = rejected.sourceRole?.let { sourceRole -> "${rejected.provider}/$sourceRole" } ?: rejected.provider
        return "$source: ${rejected.reason}"
    }

    private fun forbiddenOverwriteJson(overwrite: ForbiddenOverwriteEvent): String =
        """{"field":${jsonString(overwrite.field)},"primaryProvider":${jsonString(overwrite.primaryProvider)},"rejectedProvider":${jsonString(overwrite.rejectedProvider)},"reason":${jsonString(overwrite.reason)}}"""

    private fun continueWatchingJson(snapshot: ContinueWatchingSnapshotEvent?, indent: String): String =
        snapshot?.let {
            """{"contentId":${jsonString(it.contentId)},"parentId":${jsonString(it.parentId)},"provider":${nullableStringJson(it.provider?.name)},"routingVersion":${it.routingVersion},"hasClickTimeMetadata":${it.hasClickTimeMetadata},"reroutedDueToVersionMismatch":${it.reroutedDueToVersionMismatch}}"""
        } ?: "null"

    private fun identityResolutionJson(event: IdentityResolutionEvent?): String =
        event?.let {
            """{"required":${it.required},"sourceId":${jsonString(it.sourceId)},"targetProvider":${jsonString(it.targetProvider.name)},"resolver":${jsonString(it.resolver)},"apiShapeId":${nullableStringJson(it.apiShapeId)},"resultId":${nullableStringJson(it.resultId)},"success":${it.success}}"""
        } ?: "null"

    private fun productionCallerOwnershipJson(event: ProductionCallerOwnershipEvent): String =
        """{"pathName":${jsonString(event.pathName)},"entrypoint":${jsonString(event.entrypoint)},"facadeOrRepositoryCalled":${event.facadeOrRepositoryCalled},"providerPlanRunnerExpected":${event.providerPlanRunnerExpected},"fieldResolverExpected":${event.fieldResolverExpected},"legacyRouterUsedAfterFacade":${event.legacyRouterUsedAfterFacade}}"""

    private fun localizationJson(event: LocalizationEvent?): String =
        event?.let {
            """{"provider":${jsonString(it.provider.name)},"requestedLanguage":${jsonString(it.requestedLanguage)},"fallbackLanguage":${jsonString(it.fallbackLanguage)},"policyVersion":${it.policyVersion},"providerFallbackAllowedForMissingLocalizedFields":${it.providerFallbackAllowedForMissingLocalizedFields},"providerFallbackUsed":${it.providerFallbackUsed},"perEpisodeTranslationFallbacksAttempted":${it.perEpisodeTranslationFallbacksAttempted},"maxPerEpisodeTranslationFallbacksAllowed":${it.maxPerEpisodeTranslationFallbacksAllowed},"payloads":[${it.payloads.joinToString { payload -> localizationPayloadJson(payload) }}]}"""
        } ?: "null"

    private fun localizationPayloadJson(payload: LocalizationPayloadReport): String =
        """{"apiShapeId":${jsonString(payload.apiShapeId)},"language":${jsonString(payload.language)},"fallbackRole":${jsonString(payload.fallbackRole)},"cacheKey":${jsonString(payload.cacheKey)},"cacheDecision":${nullableStringJson(payload.cacheDecision?.name)},"executedNetwork":${payload.executedNetwork},"source":${jsonString(payload.source)}}"""

    private fun gitWorktreeJson(state: GitWorktreeState): String =
        """{"state":${jsonString(state.state)},"dirtyFileCount":${state.dirtyFileCount},"untrackedFileCount":${state.untrackedFileCount}}"""

    private fun nullableStringJson(value: String?): String =
        value?.let(::jsonString) ?: "null"

    private fun stringArrayJson(values: Iterable<String>): String =
        "[${values.joinToString { jsonString(it) }}]"

    private fun stringMapJson(values: Map<String, String>): String =
        "{${values.entries.joinToString { "${jsonString(it.key)}:${jsonString(it.value)}" }}}"

    private fun jsonString(value: String): String =
        buildString {
            append('"')
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (char < ' ') {
                            append("\\u")
                            append(char.code.toString(16).padStart(4, '0'))
                        } else {
                            append(char)
                        }
                    }
                }
            }
            append('"')
        }
}
