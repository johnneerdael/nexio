package com.nexio.tv.core.integration

data class IntegrationSpec<T>(
    val provider: IntegrationProvider,
    val cacheKey: String,
    val codec: IntegrationCodec<T>,
    val cachePolicy: IntegrationCachePolicy = IntegrationCachePolicy.Disabled,
    val workClass: IntegrationWorkClass,
    val scope: IntegrationScope = IntegrationScope.Global,
    val load: suspend () -> IntegrationLoadResult<T>
)
