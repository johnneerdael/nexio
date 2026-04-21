package com.nexio.tv.core.integration

interface IntegrationRuntime {
    suspend fun <T> get(
        spec: IntegrationSpec<T>,
        options: IntegrationFetchOptions = IntegrationFetchOptions()
    ): IntegrationFetchResult<T>
}
