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
        val facts = preview.stableIds.directMappings(preview.railId).distinctBy { mapping ->
            "${mapping.sourceId.scheme}:${mapping.sourceId.value}:${mapping.provider}:${mapping.providerId}"
        }

        facts.forEach { mapping -> idMappingStore.persist(mapping) }
        return facts
    }

    private fun ProviderIds.directMappings(railId: String): List<IdMapping> {
        val explicitIds = directIds()

        return buildList {
            explicitIds.forEach { source ->
                explicitIds.forEach { target ->
                    val provider = target.provider
                    if (
                        provider != null &&
                        provider.isCanonicalOrSidecarTarget() &&
                        source.id != target.id
                    ) {
                        add(source.id.mappingTo(provider, target.id.value, railId))
                    }
                }
            }
        }
    }

    private fun MetadataPrimaryProvider.isCanonicalOrSidecarTarget(): Boolean =
        when (this) {
            MetadataPrimaryProvider.TMDB,
            MetadataPrimaryProvider.TVDB,
            MetadataPrimaryProvider.KITSU,
            MetadataPrimaryProvider.IMDB -> true
            MetadataPrimaryProvider.TRAKT,
            MetadataPrimaryProvider.SIMKL,
            MetadataPrimaryProvider.RPDB,
            MetadataPrimaryProvider.TOP_POSTERS -> false
        }

    private fun ProviderIds.directIds(): List<DirectStableId> =
        listOfNotNull(
            imdb.directId(AnimeIdScheme.IMDB, MetadataPrimaryProvider.IMDB, "imdb"),
            tmdb.directId(AnimeIdScheme.TMDB, MetadataPrimaryProvider.TMDB, "tmdb"),
            tvdb.directId(AnimeIdScheme.TVDB, MetadataPrimaryProvider.TVDB, "tvdb"),
            trakt.directId(AnimeIdScheme.TRAKT, MetadataPrimaryProvider.TRAKT, "trakt"),
            simkl.directId(AnimeIdScheme.SIMKL, MetadataPrimaryProvider.SIMKL, "simkl"),
            kitsu.directId(AnimeIdScheme.KITSU, MetadataPrimaryProvider.KITSU, "kitsu"),
            mal.directId(AnimeIdScheme.MAL, provider = null, prefix = "mal"),
            anilist.directId(AnimeIdScheme.ANILIST, provider = null, prefix = "anilist"),
            anidb.directId(AnimeIdScheme.ANIDB, provider = null, prefix = "anidb")
        )

    private fun String?.directId(
        scheme: AnimeIdScheme,
        provider: MetadataPrimaryProvider?,
        prefix: String
    ): DirectStableId? {
        val clean = cleanId() ?: return null
        val parsed = parseMetadataId(clean)?.takeIf { it.scheme == scheme }
            ?: parseMetadataId("$prefix:$clean")?.takeIf { it.scheme == scheme }
            ?: return null
        return DirectStableId(id = parsed, provider = provider)
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
            source = IdMappingSource.RAIL_PREVIEW,
            evidence = "rail preview $railId explicit stableIds"
        )

    private fun String?.cleanId(): String? =
        this?.trim()?.takeIf { it.isNotEmpty() }

    private data class DirectStableId(
        val id: ParsedMetadataId,
        val provider: MetadataPrimaryProvider?
    )
}
