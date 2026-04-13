package com.nexio.tv.data.repository.servicewrap

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val SERVICE_WRAP_RESOLVED_STREAM_CACHE_TTL_MS = 10L * 60L * 1000L
private const val SERVICE_WRAP_RESOLVED_STREAM_CACHE_MAX_ENTRIES = 500

@Singleton
class ServiceWrapResolvedStreamCache @Inject constructor() {
    private data class Entry(
        val createdAtMs: Long,
        val streams: List<ResolvedServiceWrapStream>
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun get(
        provider: ServiceWrapProvider,
        candidate: WrapCandidate,
        requestContext: ServiceWrapRequestContext
    ): List<ResolvedServiceWrapStream>? {
        val key = key(provider, candidate, requestContext)
        val entry = entries[key] ?: return null
        val ageMs = System.currentTimeMillis() - entry.createdAtMs
        if (ageMs > SERVICE_WRAP_RESOLVED_STREAM_CACHE_TTL_MS) {
            entries.remove(key)
            return null
        }
        return entry.streams
    }

    fun put(
        provider: ServiceWrapProvider,
        candidate: WrapCandidate,
        requestContext: ServiceWrapRequestContext,
        streams: List<ResolvedServiceWrapStream>
    ) {
        if (entries.size >= SERVICE_WRAP_RESOLVED_STREAM_CACHE_MAX_ENTRIES) {
            val oldestKey = entries.minByOrNull { it.value.createdAtMs }?.key
            if (oldestKey != null) entries.remove(oldestKey)
        }
        entries[key(provider, candidate, requestContext)] = Entry(
            createdAtMs = System.currentTimeMillis(),
            streams = streams
        )
    }

    private fun key(
        provider: ServiceWrapProvider,
        candidate: WrapCandidate,
        requestContext: ServiceWrapRequestContext
    ): String {
        return buildString {
            append(provider.providerId)
            append('|')
            append(candidate.normalizedInfoHash)
            append('|')
            append(requestContext.contentType.lowercase())
            append('|')
            append(requestContext.season ?: -1)
            append('|')
            append(requestContext.episode ?: -1)
            append('|')
            append(candidate.sourceStreamKey)
        }
    }
}
