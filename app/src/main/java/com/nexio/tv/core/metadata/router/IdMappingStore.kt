package com.nexio.tv.core.metadata.router

enum class IdMappingSource { FRIBB, LOCAL, ROUTER_OBSERVED, NEGATIVE }

data class IdMapping(
    val sourceId: ParsedMetadataId,
    val provider: MetadataPrimaryProvider,
    val providerId: String,
    val source: IdMappingSource,
    val evidence: String,
    val expiresAtEpochMs: Long? = null
)

object IdMappingTtlPolicy {
    const val NEGATIVE_TTL_MS: Long = 30L * 24L * 60L * 60L * 1_000L

    fun expiresAt(source: IdMappingSource, nowEpochMs: Long): Long? =
        if (source == IdMappingSource.NEGATIVE) nowEpochMs + NEGATIVE_TTL_MS else null

    fun comparePriority(incoming: IdMappingSource, existing: IdMappingSource): Int =
        priority(incoming) - priority(existing)

    private fun priority(source: IdMappingSource): Int =
        when (source) {
            IdMappingSource.LOCAL -> 0
            IdMappingSource.ROUTER_OBSERVED -> 1
            IdMappingSource.FRIBB -> 2
            IdMappingSource.NEGATIVE -> 3
        }
}

interface IdMappingStore {
    suspend fun lookupKitsu(sourceId: ParsedMetadataId): IdMapping?
    suspend fun persist(mapping: IdMapping)
}

fun ParsedMetadataId.mappingKey(): String = "${scheme.name.lowercase()}:$value"

class InMemoryIdMappingStore(
    initialMappings: List<IdMapping> = emptyList(),
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() }
) : IdMappingStore {
    private val mappings = mutableMapOf<String, IdMapping>()

    init {
        initialMappings.forEach { mapping ->
            mappings[mapping.sourceId.mappingKey()] = mapping.withPolicyExpiry()
        }
    }

    override suspend fun lookupKitsu(sourceId: ParsedMetadataId): IdMapping? {
        val mapping = mappings[sourceId.mappingKey()] ?: return null
        if (mapping.isExpired()) {
            mappings.remove(sourceId.mappingKey())
            return null
        }
        return mapping.takeIf { it.provider == MetadataPrimaryProvider.KITSU && it.source != IdMappingSource.NEGATIVE }
    }

    override suspend fun persist(mapping: IdMapping) {
        val key = mapping.sourceId.mappingKey()
        val incoming = mapping.withPolicyExpiry()
        val existing = mappings[key]
        if (existing == null || existing.isExpired() || IdMappingTtlPolicy.comparePriority(incoming.source, existing.source) <= 0) {
            mappings[key] = incoming
        }
    }

    private fun IdMapping.withPolicyExpiry(): IdMapping =
        if (expiresAtEpochMs == null) copy(expiresAtEpochMs = IdMappingTtlPolicy.expiresAt(source, nowEpochMs())) else this

    private fun IdMapping.isExpired(): Boolean =
        expiresAtEpochMs?.let { it <= nowEpochMs() } == true
}
