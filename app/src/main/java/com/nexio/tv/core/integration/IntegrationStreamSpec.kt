package com.nexio.tv.core.integration

data class IntegrationStreamSpec<T>(
    val provider: IntegrationProvider,
    val workClass: IntegrationWorkClass,
    val scope: IntegrationScope = IntegrationScope.Global,
    val open: suspend () -> IntegrationStreamHandle<T>
)
