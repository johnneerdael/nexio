package com.nexio.tv.core.integration

data class IntegrationSpec<T>(
    val provider: IntegrationProvider,
    val apiShapeId: String,
    val operationKey: String,
    val headerPolicyId: String = IntegrationHeaderPolicies.defaultFor(provider, apiShapeId),
    val cacheKey: String?,
    val codec: IntegrationCodec<T>,
    val cachePolicy: IntegrationCachePolicy = IntegrationCachePolicy.Disabled,
    val ownership: IntegrationCacheOwnership = IntegrationCacheOwnership.None,
    val workClass: IntegrationWorkClass,
    val scope: IntegrationScope = IntegrationScope.GlobalContent,
    val profileContext: ProfileExecutionContext? = null,
    val load: suspend () -> IntegrationLoadResult<T>
) {
    init {
        require(apiShapeId.isNotBlank()) { "IntegrationSpec.apiShapeId must not be blank" }
        require(operationKey.isNotBlank()) { "IntegrationSpec.operationKey must not be blank" }
        require(headerPolicyId.isNotBlank()) { "IntegrationSpec.headerPolicyId must not be blank" }
        if (cachePolicy is IntegrationCachePolicy.CacheFirst) {
            require(!cacheKey.isNullOrBlank()) { "CacheFirst IntegrationSpec.cacheKey must not be blank" }
        }
        ProfileBoundaryEnforcer.validateRequest(provider, scope, cacheKey, profileContext)
    }

    val requiredCacheKey: String
        get() = requireNotNull(cacheKey) { "IntegrationSpec.cacheKey is required for $apiShapeId" }
}
