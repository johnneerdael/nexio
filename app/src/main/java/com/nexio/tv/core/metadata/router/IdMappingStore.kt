package com.nexio.tv.core.metadata.router

enum class IdMappingSource {
    LOCAL,
    PROVIDER_LOOKUP,
    RAIL_PREVIEW,
    FRIBB,
    ROUTER_OBSERVED,
    NEGATIVE
}

data class IdMapping(
    val sourceId: ParsedMetadataId,
    val provider: MetadataPrimaryProvider,
    val providerId: String,
    val source: IdMappingSource,
    val evidence: String,
    val expiresAtEpochMs: Long? = null
)

object IdMappingTtlPolicy {
    const val POSITIVE_TTL_MS: Long = 7L * 24L * 60L * 60L * 1_000L
    const val STATIC_ANIME_TTL_MS: Long = 30L * 24L * 60L * 60L * 1_000L
    const val NEGATIVE_TTL_MS: Long = 24L * 60L * 60L * 1_000L

    fun expiresAt(source: IdMappingSource, nowEpochMs: Long): Long? =
        when (source) {
            IdMappingSource.LOCAL -> null
            IdMappingSource.PROVIDER_LOOKUP,
            IdMappingSource.RAIL_PREVIEW,
            IdMappingSource.ROUTER_OBSERVED -> nowEpochMs + POSITIVE_TTL_MS
            IdMappingSource.FRIBB -> nowEpochMs + STATIC_ANIME_TTL_MS
            IdMappingSource.NEGATIVE -> nowEpochMs + NEGATIVE_TTL_MS
        }

    fun comparePriority(incoming: IdMappingSource, existing: IdMappingSource): Int =
        priority(incoming) - priority(existing)

    private fun priority(source: IdMappingSource): Int =
        when (source) {
            IdMappingSource.LOCAL -> 0
            IdMappingSource.PROVIDER_LOOKUP -> 1
            IdMappingSource.RAIL_PREVIEW -> 2
            IdMappingSource.FRIBB -> 3
            IdMappingSource.ROUTER_OBSERVED -> 4
            IdMappingSource.NEGATIVE -> 5
        }
}

interface IdMappingStore {
    suspend fun lookup(provider: MetadataPrimaryProvider, sourceId: ParsedMetadataId): IdMapping?
    suspend fun lookupKitsu(sourceId: ParsedMetadataId): IdMapping?
    /** Returns the raw stored mapping including NEGATIVE entries (subject to TTL expiry). */
    suspend fun readRaw(provider: MetadataPrimaryProvider, sourceId: ParsedMetadataId): IdMapping?
    suspend fun persist(mapping: IdMapping)
}

fun ParsedMetadataId.mappingKey(): String = "${scheme.name.lowercase()}:${normalizeMetadataIdValue(scheme, value)}"

fun parseMetadataId(rawId: String): ParsedMetadataId? =
    MetadataIdParser.parse(rawId).takeUnless { it.scheme == AnimeIdScheme.UNKNOWN }

class InMemoryIdMappingStore(
    initialMappings: List<IdMapping> = emptyList(),
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() }
) : IdMappingStore {
    private val mappings = mutableMapOf<String, IdMapping>()

    init {
        initialMappings.forEach { mapping ->
            mappings[mapping.storeKey()] = mapping.withPolicyExpiry()
        }
    }

    override suspend fun lookup(provider: MetadataPrimaryProvider, sourceId: ParsedMetadataId): IdMapping? =
        readRaw(provider, sourceId)?.takeIf { it.source != IdMappingSource.NEGATIVE }

    override suspend fun lookupKitsu(sourceId: ParsedMetadataId): IdMapping? =
        lookup(MetadataPrimaryProvider.KITSU, sourceId)

    override suspend fun readRaw(provider: MetadataPrimaryProvider, sourceId: ParsedMetadataId): IdMapping? {
        val key = mappingStoreKey(provider, sourceId)
        val mapping = mappings[key] ?: return null
        if (mapping.isExpired()) {
            mappings.remove(key)
            return null
        }
        return mapping.takeIf { it.provider == provider }
    }

    override suspend fun persist(mapping: IdMapping) {
        val key = mapping.storeKey()
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

    private fun IdMapping.storeKey(): String =
        mappingStoreKey(provider, sourceId)

    private fun mappingStoreKey(provider: MetadataPrimaryProvider, sourceId: ParsedMetadataId): String =
        "${sourceId.mappingKey()}:${provider.name.lowercase()}"
}
