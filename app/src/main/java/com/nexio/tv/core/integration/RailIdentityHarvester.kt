package com.nexio.tv.core.integration

import com.nexio.tv.core.metadata.router.IdMapping
import com.nexio.tv.core.metadata.router.IdMappingSource
import com.nexio.tv.core.metadata.router.IdMappingStore
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.ParsedMetadataId
import com.nexio.tv.core.metadata.router.parseMetadataId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.RailItemPreview
import javax.inject.Inject

class RailIdentityHarvester @Inject constructor(
    private val idMappingStore: IdMappingStore
) {
    suspend fun harvest(preview: RailItemPreview): List<IdMapping> {
        val facts = preview.stableIds.directMappings(preview.railId).distinctBy { mapping ->
            "${mapping.sourceId.scheme}:${mapping.sourceId.value}:${mapping.provider}:${mapping.providerId}"
        }

        facts.forEach { mapping -> idMappingStore.persist(mapping) }
        return facts
    }

    private fun ProviderIds.directMappings(railId: String): List<IdMapping> {
        val imdbSource = imdb?.let { parseMetadataId(it) ?: parseMetadataId("imdb:$it") }
        val animeSources = listOfNotNull(
            mal?.let { parseMetadataId("mal:$it") },
            anilist?.let { parseMetadataId("anilist:$it") },
            anidb?.let { parseMetadataId("anidb:$it") }
        )
        val kitsuTarget = kitsu.cleanId()

        return buildList {
            imdbSource?.let { sourceId ->
                tmdb.cleanId()?.let { add(sourceId.mappingTo(MetadataPrimaryProvider.TMDB, it, railId)) }
                tvdb.cleanId()?.let { add(sourceId.mappingTo(MetadataPrimaryProvider.TVDB, it, railId)) }
                kitsuTarget?.let { add(sourceId.mappingTo(MetadataPrimaryProvider.KITSU, it, railId)) }
            }
            kitsuTarget?.let { providerId ->
                animeSources.forEach { sourceId ->
                    add(sourceId.mappingTo(MetadataPrimaryProvider.KITSU, providerId, railId))
                }
            }
        }
    }

    private fun ParsedMetadataId.mappingTo(
        provider: MetadataPrimaryProvider,
        providerId: String,
        railId: String
    ): IdMapping =
        IdMapping(
            sourceId = this,
            provider = provider,
            providerId = providerId,
            source = IdMappingSource.ROUTER_OBSERVED,
            evidence = "rail preview $railId explicit stableIds"
        )

    private fun String?.cleanId(): String? =
        this?.trim()?.takeIf { it.isNotEmpty() }
}
