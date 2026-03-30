package com.nexio.tv.data.repository.servicewrap

interface ServiceWrapResolver {
    suspend fun resolve(
        candidate: WrapCandidate,
        requestContext: ServiceWrapRequestContext
    ): List<ResolvedServiceWrapStream>
}
