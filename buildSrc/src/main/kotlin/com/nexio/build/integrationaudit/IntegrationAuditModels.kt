package com.nexio.build.integrationaudit

data class IntegrationAuditSpecRow(
    val callId: String,
    val provider: String,
    val specType: String,
    val adapterClass: String,
    val adapterMethod: String,
    val sourceFile: String,
    val line: Int,
    val apiShapeId: String?,
    val headerPolicyId: String?,
    val expectedHeaderPolicyId: String?,
    val operationKey: String?,
    val cacheKeyTemplate: String?,
    val workClass: String?,
    val cachePolicy: String?,
    val codec: String?,
    val scope: String?,
    val rawClient: String?,
    val verdict: String,
    val issues: List<String>
)

data class IntegrationAuditProviderRow(
    val provider: String,
    val adapterClasses: List<String>,
    val hasPolicy: Boolean,
    val runtimeCoveredCalls: Int,
    val directBypassCalls: Int,
    val missingEndpointShapeIds: Int,
    val cachePolicyModes: List<String>,
    val verdict: String
)

data class IntegrationAuditViolation(
    val offender: String,
    val forbiddenDependency: String,
    val file: String,
    val whyForbidden: String,
    val requiredFix: String
)

data class IntegrationAuditExemption(
    val path: String,
    val hostProvider: String,
    val reason: String,
    val owningFile: String,
    val reviewStatus: String
)

data class IntegrationAuditHeaderPolicyRow(
    val apiShapeId: String,
    val provider: String,
    val headerPolicyId: String?,
    val requiredHeaders: String,
    val optionalHeaders: String,
    val forbiddenHeaders: String,
    val credentialLocation: String,
    val userAgentPolicy: String,
    val responseHeadersCaptured: String,
    val cacheVary: String,
    val verdict: String,
    val issues: List<String>
)

data class IntegrationProviderContractProvenanceRow(
    val provider: String,
    val lifecycleStatus: String,
    val sourceFile: String,
    val sourceType: String,
    val reviewedAt: String,
    val reviewer: String,
    val usedShapes: Int,
    val activeRequiredShapes: Int,
    val verdict: String
)

data class IntegrationCachePolicyContractRow(
    val apiShapeId: String,
    val provider: String,
    val expectedCacheContract: String,
    val expectedCachePolicy: String,
    val actualCachePolicy: String?,
    val ttl: String,
    val staleAfterExpiry: String,
    val scope: String,
    val codec: String?,
    val cacheKeyTemplate: String?,
    val verdict: String,
    val issues: List<String>
)

data class IntegrationCacheKeyVaryRow(
    val apiShapeId: String,
    val include: String,
    val forbidden: String,
    val actualTemplate: String?,
    val verdict: String,
    val issues: List<String>
)

data class IntegrationBestPracticeRow(
    val apiShapeId: String,
    val provider: String,
    val rule: String,
    val verdict: String,
    val evidence: String
)

enum class BestPracticeRuleSupport {
    EXECUTABLE,
    DECLARED_ONLY
}

data class BestPracticeValidationInput(
    val shapeId: String,
    val shape: Map<String, String>,
    val spec: IntegrationAuditSpecRow?
)

data class BestPracticeValidationResult(
    val verdict: String,
    val evidence: String
)

data class IntegrationEndpointReadinessRow(
    val shapeId: String,
    val provider: String,
    val status: String,
    val metadataRouterRequired: Boolean,
    val actualAdapterMethod: String?,
    val actualSource: String?,
    val requiredAction: String
)

data class GitWorktreeState(
    val state: String,
    val dirtyFileCount: Int,
    val untrackedFileCount: Int,
    val sample: List<String>
)
