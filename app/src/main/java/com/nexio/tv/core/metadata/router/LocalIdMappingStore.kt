package com.nexio.tv.core.metadata.router

import com.nexio.tv.data.local.integration.ExternalIdEntity
import com.nexio.tv.data.local.integration.MediaIdentityDao
import com.nexio.tv.data.local.integration.MediaIdentityEntity
import javax.inject.Inject

class LocalIdMappingStore @Inject constructor(
    private val dao: MediaIdentityDao
) : IdMappingStore {
    override suspend fun lookup(provider: MetadataPrimaryProvider, sourceId: ParsedMetadataId): IdMapping? =
        bestDurableMapping(provider, sourceId, includeNegative = false)

    override suspend fun lookupKitsu(sourceId: ParsedMetadataId): IdMapping? =
        lookup(MetadataPrimaryProvider.KITSU, sourceId)

    override suspend fun readRaw(provider: MetadataPrimaryProvider, sourceId: ParsedMetadataId): IdMapping? =
        bestDurableMapping(provider, sourceId, includeNegative = true)

    override suspend fun persist(mapping: IdMapping) {
        val mediaKey = mapping.sourceId.mappingKey()
        val existing = bestDurableMapping(mapping.provider, mapping.sourceId, includeNegative = true)
        if (existing != null && IdMappingTtlPolicy.comparePriority(mapping.source, existing.source) > 0) {
            return
        }

        val now = System.currentTimeMillis()
        val expiresAt = mapping.expiresAtEpochMs ?: IdMappingTtlPolicy.expiresAt(mapping.source, now)
        dao.upsertMediaIdentity(
            MediaIdentityEntity(
                mediaKey = mediaKey,
                mediaType = mapping.sourceId.scheme.name,
                title = null,
                year = null,
                updatedAtEpochMs = now
            )
        )
        dao.upsertExternalIds(
            listOf(
                ExternalIdEntity(
                    key = "$mediaKey:${mapping.provider.name.lowercase()}",
                    mediaKey = mediaKey,
                    provider = mapping.provider.name,
                    externalId = mapping.providerId,
                    idType = mapping.sourceId.scheme.name,
                    mappingSource = mapping.source.name,
                    evidence = mapping.evidence,
                    expiresAtEpochMs = expiresAt
                )
            )
        )
    }

    private suspend fun bestDurableMapping(
        provider: MetadataPrimaryProvider,
        sourceId: ParsedMetadataId,
        includeNegative: Boolean
    ): IdMapping? {
        val now = System.currentTimeMillis()
        return dao.externalIdsForMedia(sourceId.mappingKey())
            .asSequence()
            .filter { entity -> entity.provider.equals(provider.name, ignoreCase = true) }
            .filterNot { entity -> entity.expiresAtEpochMs?.let { it <= now } == true }
            .map { entity -> entity.toIdMapping(sourceId) }
            .filter { mapping -> includeNegative || mapping.source != IdMappingSource.NEGATIVE }
            .minWithOrNull { left, right -> IdMappingTtlPolicy.comparePriority(left.source, right.source) }
    }

    private fun ExternalIdEntity.toIdMapping(sourceId: ParsedMetadataId): IdMapping =
        IdMapping(
            sourceId = sourceId,
            provider = provider.toMetadataPrimaryProvider(),
            providerId = externalId,
            source = mappingSource(),
            evidence = evidence ?: "local external id",
            expiresAtEpochMs = expiresAtEpochMs
        )

    private fun String.toMetadataPrimaryProvider(): MetadataPrimaryProvider =
        MetadataPrimaryProvider.entries.firstOrNull { it.name.equals(this, ignoreCase = true) }
            ?: MetadataPrimaryProvider.KITSU

    private fun ExternalIdEntity.mappingSource(): IdMappingSource =
        mappingSource.toIdMappingSource()
            ?: idType.toIdMappingSource()
            ?: IdMappingSource.LOCAL

    private fun String?.toIdMappingSource(): IdMappingSource? =
        this
            ?.trim()
            ?.uppercase()
            ?.let { value -> IdMappingSource.entries.firstOrNull { it.name == value } }
}
