package com.nexio.tv.core.integration

data class IntegrationCallSpec<T>(
    val provider: IntegrationProvider,
    val workClass: IntegrationWorkClass,
    val scope: IntegrationScope = IntegrationScope.Global,
    val call: suspend () -> IntegrationCallResult<T>
)
