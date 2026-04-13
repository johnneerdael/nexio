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
        candidates.forEach { candidate ->
            var emittedTerminalBatch = false
            resolveProgressively(candidate, requestContext).collect { resolution ->
                if (resolution.isTerminal) emittedTerminalBatch = true
                emit(
                    ServiceWrapResolutionChunkBatch(
                        streamsByHash = mapOf(candidate.normalizedInfoHash to resolution.streams),
                        isTerminal = resolution.isTerminal
                    )
                )
            }
            if (!emittedTerminalBatch) {
                emit(
                    ServiceWrapResolutionChunkBatch(
                        streamsByHash = mapOf(candidate.normalizedInfoHash to emptyList()),
                        isTerminal = true
                    )
                )
            }
        }
    }
}
