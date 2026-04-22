package com.nexio.tv.core.integration

interface IntegrationRuntime {
    suspend fun <T> get(
        spec: IntegrationSpec<T>,
        options: IntegrationFetchOptions = IntegrationFetchOptions()
    ): IntegrationFetchResult<T>

    suspend fun <T> call(spec: IntegrationCallSpec<T>): IntegrationCallResult<T>

    suspend fun <T> open(spec: IntegrationStreamSpec<T>): IntegrationStreamHandle<T>?
}
