package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.metadata.router.AnimeIdScheme
import com.nexio.tv.core.metadata.router.IdMappingStore
import com.nexio.tv.core.metadata.router.MetadataIdParser
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.ParsedMetadataId
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.homeDisplayItemKey
import java.util.Locale

internal object HomeArtworkOverlayKeys {
    fun aliasesFor(
        rowItemKey: String,
        contentId: String,
        itemType: String,
        providerIds: ProviderIds,
        canonicalProvider: ProviderId?,
        canonicalId: String?
    ): Set<String> = buildSet {
        rowItemKey.trim().takeIf { it.isNotBlank() }?.let(::add)
        contentId.trim().takeIf { it.isNotBlank() }?.let { id ->
            add(homeDisplayItemKey(itemType, id))
            addTypedProviderAlias(itemType, id)
        }
        addStableAliases(itemType, providerIds)
        if (canonicalProvider != null && !canonicalId.isNullOrBlank()) {
            addStableAliases(
                itemType,
                when (canonicalProvider) {
                    ProviderId.TMDB -> ProviderIds(tmdb = canonicalId)
                    ProviderId.TVDB -> ProviderIds(tvdb = canonicalId)
                    ProviderId.IMDB -> ProviderIds(imdb = canonicalId)
                    ProviderId.TRAKT -> ProviderIds(trakt = canonicalId)
                    ProviderId.KITSU -> ProviderIds(kitsu = canonicalId)
                    else -> ProviderIds()
                }
            )
        }
    }

    /**
     * Cross-id-aware variant of [aliasesFor]. Before computing the alias set,
     * looks up [IdMappingStore] to enrich [providerIds] with any imdb (or other
     * cross-provider id) cached for a tmdb/tvdb-prefixed [contentId]. This lets
     * a TMDB-rail row consume an overlay that was originally hydrated under
     * the imdb-form alias by another rail (Trakt, etc.).
     *
     * Cache-miss path: returns the same alias set as the non-enriched
     * [aliasesFor] would. No network call.
     */
    suspend fun aliasesForEnriched(
        rowItemKey: String,
        contentId: String,
        itemType: String,
        providerIds: ProviderIds,
        canonicalProvider: ProviderId?,
        canonicalId: String?,
        idMappingStore: IdMappingStore
    ): Set<String> {
        val enriched = enrichProviderIdsFromStore(itemType, contentId, providerIds, idMappingStore)
        return aliasesFor(
            rowItemKey = rowItemKey,
            contentId = contentId,
            itemType = itemType,
            providerIds = enriched,
            canonicalProvider = canonicalProvider,
            canonicalId = canonicalId
        )
    }

    private suspend fun enrichProviderIdsFromStore(
        itemType: String,
        contentId: String,
        providerIds: ProviderIds,
        idMappingStore: IdMappingStore
    ): ProviderIds {
        if (!providerIds.imdb.isNullOrBlank()) return providerIds
        val parsed = MetadataIdParser.parse(contentId)
        val sourceId = when (parsed.scheme) {
            AnimeIdScheme.TMDB -> {
                val mediaPrefix = if (itemType.equals("movie", ignoreCase = true)) "movie" else "tv"
                ParsedMetadataId(AnimeIdScheme.TMDB, "$mediaPrefix:${parsed.value}", parsed.raw)
            }
            AnimeIdScheme.TVDB -> ParsedMetadataId(AnimeIdScheme.TVDB, parsed.value, parsed.raw)
            else -> return providerIds
        }
        val imdb = idMappingStore.lookup(MetadataPrimaryProvider.IMDB, sourceId)?.providerId
            ?.takeIf { it.isNotBlank() } ?: return providerIds
        return providerIds.copy(imdb = imdb)
    }

    private fun MutableSet<String>.addTypedProviderAlias(itemType: String, contentId: String) {
        val parts = contentId.trim().split(':').filter { it.isNotBlank() }
        if (parts.size < 2) return
        val provider = parts[0].lowercase(Locale.US)
        val id = when {
            parts.size >= 3 && parts[1].equals("tv", ignoreCase = true) -> parts[2]
            parts.size >= 3 && parts[1].equals("movie", ignoreCase = true) -> parts[2]
            else -> parts[1]
        }
        normalizedTypes(itemType).forEach { type ->
            add("$type:$provider:$id")
        }
    }

    private fun MutableSet<String>.addStableAliases(type: String, ids: ProviderIds) {
        normalizedTypes(type).forEach { normalizedType ->
            ids.imdb?.takeIf { it.isNotBlank() }?.let { add("$normalizedType:imdb:$it") }
            ids.tmdb?.takeIf { it.isNotBlank() }?.let { add("$normalizedType:tmdb:$it") }
            ids.tvdb?.takeIf { it.isNotBlank() }?.let { add("$normalizedType:tvdb:$it") }
            ids.trakt?.takeIf { it.isNotBlank() }?.let { add("$normalizedType:trakt:$it") }
            ids.kitsu?.takeIf { it.isNotBlank() }?.let { add("$normalizedType:kitsu:$it") }
        }
    }

    private fun normalizedTypes(type: String): Set<String> =
        when (type.lowercase(Locale.US)) {
            "series", "tv", "show" -> setOf("series", "tv")
            "movie" -> setOf("movie")
            else -> setOf(type.lowercase(Locale.US))
        }
}
