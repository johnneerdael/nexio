package com.nexio.tv.core.integration

data class IntegrationCallSpec<T>(
    val provider: IntegrationProvider,
    val apiShapeId: String,
    val operationKey: String,
    val headerPolicyId: String = IntegrationHeaderPolicies.defaultFor(provider, apiShapeId),
    val workClass: IntegrationWorkClass,
    val scope: IntegrationScope = IntegrationScope.Global,
    val call: suspend () -> IntegrationCallResult<T>
) {
    init {
        require(apiShapeId.isNotBlank()) { "IntegrationCallSpec.apiShapeId must not be blank" }
        require(operationKey.isNotBlank()) { "IntegrationCallSpec.operationKey must not be blank" }
        require(headerPolicyId.isNotBlank()) { "IntegrationCallSpec.headerPolicyId must not be blank" }
    }
}
