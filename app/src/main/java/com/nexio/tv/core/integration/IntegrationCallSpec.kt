package com.nexio.tv.core.integration

data class IntegrationCallSpec<T>(
    val provider: IntegrationProvider,
    val apiShapeId: String,
    val operationKey: String,
    val headerPolicyId: String = IntegrationHeaderPolicies.defaultFor(provider, apiShapeId),
    val workClass: IntegrationWorkClass,
    val scope: IntegrationScope = IntegrationScope.GlobalContent,
    val profileContext: ProfileExecutionContext? = null,
    /**
     * Opt-in single-flight coalescing for non-cache call paths (F-A-02).
     * When true, concurrent identical calls (keyed on operationKey + scope.storageKey + apiShapeId)
     * are deduplicated through [IntegrationSingleFlight]. Default false preserves prior behavior
     * so mutations that must duplicate stay un-coalesced.
     */
    val coalesceConcurrent: Boolean = false,
    val call: suspend () -> IntegrationCallResult<T>
) {
    init {
        require(apiShapeId.isNotBlank()) { "IntegrationCallSpec.apiShapeId must not be blank" }
        require(operationKey.isNotBlank()) { "IntegrationCallSpec.operationKey must not be blank" }
        require(headerPolicyId.isNotBlank()) { "IntegrationCallSpec.headerPolicyId must not be blank" }
        ProfileBoundaryEnforcer.validateRequest(provider, scope, operationKey, profileContext)
    }
}
