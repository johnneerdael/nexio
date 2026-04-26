package com.nexio.tv.metadata.audit

import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.ResolverType

enum class AuditVerdict {
    PASS,
    PASS_WITH_WARNINGS,
    FAIL
}

enum class CacheDecision {
    HIT,
    MISS_THEN_NETWORK,
    MISS_NETWORK_SUPPRESSED,
    STALE_HIT,
    BYPASS,
    OBSERVE_ONLY,
    WRITE
}

enum class Severity {
    LOW,
    MEDIUM,
    HIGH,
    BLOCKER
}

sealed interface AuditEvent {
    data class FirstPaint(val event: FirstPaintEvent) : AuditEvent
    data class Route(val event: RouteEvent) : AuditEvent
    data class ProviderPlan(val event: ProviderPlanEvent) : AuditEvent
    data class RuntimeCall(val event: RuntimeCallEvent) : AuditEvent
    data class CacheDecisionEventRecord(val event: CacheDecisionEvent) : AuditEvent
    data class ResolverSchedule(val event: ResolverScheduleEvent) : AuditEvent
    data class FieldSelected(val event: FieldSelectedEvent) : AuditEvent
    data class ForbiddenOverwrite(val event: ForbiddenOverwriteEvent) : AuditEvent
    data class ContinueWatching(val event: ContinueWatchingSnapshotEvent) : AuditEvent
    data class IdentityResolution(val event: IdentityResolutionEvent) : AuditEvent
    data class ProductionCallerOwnership(val event: ProductionCallerOwnershipEvent) : AuditEvent
    data class PolicyViolation(val event: PolicyViolationEvent) : AuditEvent
}

data class FirstPaintEvent(
    val itemId: String,
    val itemType: String,
    val source: String = "ADDON_META_PREVIEW",
    val fieldsUsed: Set<String>,
    val routerExecuted: Boolean,
    val networkExecuted: Boolean
)

data class RouteEvent(
    val itemId: String,
    val parentId: String,
    val itemType: String,
    val provider: MetadataPrimaryProvider,
    val mediaKind: MetadataMediaKind,
    val reason: MetadataDecisionReason,
    val targetIds: Map<MetadataPrimaryProvider, String>,
    val targetIdRequiresIdentityResolution: Boolean,
    val usedInputs: Set<String>,
    val ignoredInputs: Set<String>
)

data class ProviderPlanEvent(
    val itemId: String,
    val provider: MetadataPrimaryProvider,
    val mediaKind: MetadataMediaKind,
    val depth: MetadataDepth,
    val steps: List<ProviderPlanStepReport>
)

data class ProviderPlanStepReport(
    val stepId: String,
    val provider: MetadataPrimaryProvider,
    val apiShapeId: String,
    val workClass: String,
    val cachePolicy: String,
    val requiresIdentityResolution: Boolean
)

data class RuntimeCallEvent(
    val itemId: String,
    val provider: String,
    val apiShapeId: String,
    val operationKey: String,
    val cacheKey: String,
    val workClass: String,
    val executedNetwork: Boolean
)

data class CacheDecisionEvent(
    val itemId: String,
    val provider: String,
    val apiShapeId: String,
    val cacheKey: String,
    val decision: CacheDecision,
    val ttlMs: Long?,
    val staleWindowMs: Long?,
    val reason: String
)

data class ResolverScheduleEvent(
    val itemId: String,
    val depth: MetadataDepth,
    val resolversScheduled: List<ResolverType>,
    val resolversSkipped: Map<String, String>
)

data class FieldSelectedEvent(
    val itemId: String,
    val field: String,
    val selectedProvider: String,
    val sourceRole: String,
    val valuePreview: String?,
    val rejectedCandidates: List<RejectedCandidateReport>,
    val ownershipRule: String
)

data class RejectedCandidateReport(
    val provider: String,
    val reason: String
)

data class ForbiddenOverwriteEvent(
    val itemId: String,
    val field: String,
    val primaryProvider: String,
    val rejectedProvider: String,
    val reason: String
)

data class PolicyViolationEvent(
    val severity: Severity,
    val code: String,
    val message: String,
    val itemId: String? = null,
    val evidence: String? = null
)

data class ContinueWatchingSnapshotEvent(
    val contentId: String,
    val parentId: String,
    val provider: MetadataPrimaryProvider?,
    val routingVersion: Int?,
    val hasClickTimeMetadata: Boolean,
    val reroutedDueToVersionMismatch: Boolean
)

data class IdentityResolutionEvent(
    val itemId: String,
    val required: Boolean,
    val sourceId: String,
    val targetProvider: MetadataPrimaryProvider,
    val resolver: String,
    val apiShapeId: String?,
    val resultId: String?,
    val success: Boolean
)

data class ProductionCallerOwnershipEvent(
    val pathName: String,
    val entrypoint: String,
    val facadeOrRepositoryCalled: Boolean,
    val providerPlanRunnerExpected: Boolean,
    val fieldResolverExpected: Boolean,
    val legacyRouterUsedAfterFacade: Boolean
)

data class MetadataExecutionReport(
    val verdict: AuditVerdict,
    val scenario: MetadataAuditScenario,
    val fixtureName: String,
    val generatedAtEpochMs: Long,
    val items: List<ItemExecutionReport>,
    val summaries: AuditSummaries,
    val policyViolations: List<PolicyViolationEvent>
)

data class MetadataExecutionReportBundle(
    val verdict: AuditVerdict,
    val generatedAtEpochMs: Long,
    val reports: List<MetadataExecutionReport>,
    val summaries: AuditSummaries,
    val policyViolations: List<PolicyViolationEvent>
)

data class ItemExecutionReport(
    val itemId: String,
    val itemType: String,
    val addonFields: Map<String, String?>,
    val firstPaint: FirstPaintEvent,
    val routing: RouteEvent?,
    val providerPlan: ProviderPlanEvent?,
    val runtimeCalls: List<RuntimeCallEvent>,
    val cacheDecisions: List<CacheDecisionEvent>,
    val resolverSchedule: ResolverScheduleEvent?,
    val selectedFields: List<FieldSelectedEvent>,
    val forbiddenOverwrites: List<ForbiddenOverwriteEvent>,
    val continueWatchingSnapshot: ContinueWatchingSnapshotEvent?,
    val identityResolution: IdentityResolutionEvent?,
    val productionCallerOwnership: List<ProductionCallerOwnershipEvent>,
    val violations: List<PolicyViolationEvent>,
    val events: List<AuditEvent>
)

data class AuditSummaries(
    val totalItems: Int,
    val routedItems: Int,
    val networkCalls: Int,
    val cacheHits: Int,
    val cacheMisses: Int,
    val staleHits: Int,
    val forbiddenOverwrites: Int,
    val policyViolations: Int,
    val providersUsed: Map<String, Int>,
    val apiShapesUsed: Map<String, Int>
)

fun MetadataExecutionReport.allEvents(): List<AuditEvent> = items.flatMap { it.events }
