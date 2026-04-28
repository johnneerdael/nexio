package com.nexio.tv.core.integration

import com.nexio.tv.core.metadata.router.AnimeIdScheme
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
        val sourceIds = preview.stableIds.parsedSourceIds()
        val targetIds = preview.stableIds.targetProviderIds()
        val facts = buildList {
            sourceIds.forEach { sourceId ->
                targetIds.forEach { (provider, providerId) ->
                    if (sourceId.scheme.toPrimaryProvider() != provider) {
                        add(
                            IdMapping(
                                sourceId = sourceId,
                                provider = provider,
                                providerId = providerId,
                                source = IdMappingSource.ROUTER_OBSERVED,
                                evidence = "rail preview ${preview.railId} explicit stableIds"
                            )
                        )
                    }
                }
            }
        }.distinctBy { mapping ->
            "${mapping.sourceId.scheme}:${mapping.sourceId.value}:${mapping.provider}:${mapping.providerId}"
        }

        facts.forEach { mapping -> idMappingStore.persist(mapping) }
        return facts
    }

    private fun ProviderIds.parsedSourceIds(): List<ParsedMetadataId> =
        listOfNotNull(
            imdb?.let { parseMetadataId(it) ?: parseMetadataId("imdb:$it") },
            tmdb?.let { parseMetadataId("tmdb:$it") },
            tvdb?.let { parseMetadataId("tvdb:$it") },
            kitsu?.let { parseMetadataId("kitsu:$it") },
            mal?.let { parseMetadataId("mal:$it") },
            anilist?.let { parseMetadataId("anilist:$it") },
            anidb?.let { parseMetadataId("anidb:$it") }
        )

    private fun ProviderIds.targetProviderIds(): List<Pair<MetadataPrimaryProvider, String>> =
        listOfNotNull(
            tmdb.cleanId()?.let { MetadataPrimaryProvider.TMDB to it },
            tvdb.cleanId()?.let { MetadataPrimaryProvider.TVDB to it },
            kitsu.cleanId()?.let { MetadataPrimaryProvider.KITSU to it }
        )

    private fun String?.cleanId(): String? =
        this?.trim()?.takeIf { it.isNotEmpty() }

    private fun AnimeIdScheme.toPrimaryProvider(): MetadataPrimaryProvider? =
        when (this) {
            AnimeIdScheme.TMDB -> MetadataPrimaryProvider.TMDB
            AnimeIdScheme.TVDB -> MetadataPrimaryProvider.TVDB
            AnimeIdScheme.KITSU -> MetadataPrimaryProvider.KITSU
            AnimeIdScheme.MAL,
            AnimeIdScheme.ANILIST,
            AnimeIdScheme.ANIDB,
            AnimeIdScheme.IMDB,
            AnimeIdScheme.UNKNOWN -> null
        }
}
