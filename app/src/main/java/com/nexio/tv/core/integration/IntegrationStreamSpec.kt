package com.nexio.tv.core.integration

data class IntegrationStreamSpec<T>(
    val provider: IntegrationProvider,
    val apiShapeId: String,
    val operationKey: String,
    val headerPolicyId: String = IntegrationHeaderPolicies.defaultFor(provider, apiShapeId),
    val workClass: IntegrationWorkClass,
    val scope: IntegrationScope = IntegrationScope.Global,
    val open: suspend () -> IntegrationStreamHandle<T>
) {
    init {
        require(apiShapeId.isNotBlank()) { "IntegrationStreamSpec.apiShapeId must not be blank" }
        require(operationKey.isNotBlank()) { "IntegrationStreamSpec.operationKey must not be blank" }
        require(headerPolicyId.isNotBlank()) { "IntegrationStreamSpec.headerPolicyId must not be blank" }
    }
}
