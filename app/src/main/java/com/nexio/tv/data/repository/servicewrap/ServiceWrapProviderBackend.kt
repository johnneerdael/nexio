package com.nexio.tv.data.repository.servicewrap

internal interface ServiceWrapProviderBackend {
    val provider: ServiceWrapProvider

    suspend fun isConfigured(): Boolean

    suspend fun resolve(
        candidate: WrapCandidate,
        requestContext: ServiceWrapRequestContext
    ): List<ResolvedServiceWrapStream>

    suspend fun resolveChunk(
        candidates: List<WrapCandidate>,
        requestContext: ServiceWrapRequestContext
    ): Map<String, List<ResolvedServiceWrapStream>> {
        val resolved = LinkedHashMap<String, List<ResolvedServiceWrapStream>>()
        candidates.forEach { candidate ->
            resolved[candidate.normalizedInfoHash] = resolve(candidate, requestContext)
        }
        return resolved
    }
}
