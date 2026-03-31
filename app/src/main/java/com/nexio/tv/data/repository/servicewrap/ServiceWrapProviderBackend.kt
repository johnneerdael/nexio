package com.nexio.tv.data.repository.servicewrap

internal interface ServiceWrapProviderBackend {
    val provider: ServiceWrapProvider

    suspend fun isConfigured(): Boolean

    suspend fun resolve(
        candidate: WrapCandidate,
        requestContext: ServiceWrapRequestContext
    ): List<ResolvedServiceWrapStream>
}
