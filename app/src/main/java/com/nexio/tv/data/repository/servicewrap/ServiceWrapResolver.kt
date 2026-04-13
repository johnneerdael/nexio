package com.nexio.tv.data.repository.servicewrap

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface ServiceWrapResolver {
    suspend fun resolve(
        candidate: WrapCandidate,
        requestContext: ServiceWrapRequestContext
    ): List<ResolvedServiceWrapStream>

    fun resolveProgressively(
        candidate: WrapCandidate,
        requestContext: ServiceWrapRequestContext
    ): Flow<ServiceWrapResolutionBatch> = flow {
        emit(
            ServiceWrapResolutionBatch(
                streams = resolve(candidate, requestContext),
                isTerminal = true
            )
        )
    }

    fun resolveChunkProgressively(
        candidates: List<WrapCandidate>,
        requestContext: ServiceWrapRequestContext
    ): Flow<ServiceWrapResolutionChunkBatch> = flow {
        val resultsByHash = LinkedHashMap<String, List<ResolvedServiceWrapStream>>()
        candidates.forEach { candidate ->
            resultsByHash[candidate.normalizedInfoHash] = resolve(candidate, requestContext)
        }
        emit(
            ServiceWrapResolutionChunkBatch(
                streamsByHash = resultsByHash,
                isTerminal = true
            )
        )
    }
}
