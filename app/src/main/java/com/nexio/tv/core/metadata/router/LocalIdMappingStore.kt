package com.nexio.tv.core.metadata.router

import com.nexio.tv.data.local.integration.ExternalIdEntity
import com.nexio.tv.data.local.integration.MediaIdentityDao
import com.nexio.tv.data.local.integration.MediaIdentityEntity
import javax.inject.Inject

class LocalIdMappingStore @Inject constructor(
    private val dao: MediaIdentityDao
) : IdMappingStore {
    override suspend fun lookupKitsu(sourceId: ParsedMetadataId): IdMapping? {
        val now = System.currentTimeMillis()
        return dao.externalIdsForMedia(sourceId.mappingKey())
            .firstOrNull { entity ->
                entity.provider.equals(MetadataPrimaryProvider.KITSU.name, ignoreCase = true) &&
                    entity.expiresAtEpochMs?.let { it <= now } != true
            }
            ?.let { entity ->
                IdMapping(
                    sourceId = sourceId,
                    provider = MetadataPrimaryProvider.KITSU,
                    providerId = entity.externalId,
                    source = entity.mappingSource?.let(IdMappingSource::valueOf) ?: IdMappingSource.LOCAL,
                    evidence = entity.evidence ?: "local external id",
                    expiresAtEpochMs = entity.expiresAtEpochMs
                )
            }
    }

    override suspend fun persist(mapping: IdMapping) {
        val mediaKey = mapping.sourceId.mappingKey()
        val existing = lookupKitsu(mapping.sourceId)
        if (existing != null && IdMappingTtlPolicy.comparePriority(mapping.source, existing.source) > 0) {
            return
        }

        val expiresAt = mapping.expiresAtEpochMs ?: IdMappingTtlPolicy.expiresAt(mapping.source, System.currentTimeMillis())
        dao.upsertMediaIdentity(
            MediaIdentityEntity(
                mediaKey = mediaKey,
                mediaType = mapping.sourceId.scheme.name,
                title = null,
                year = null,
                updatedAtEpochMs = System.currentTimeMillis()
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
}
