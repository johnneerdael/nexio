package com.nexio.tv.data.repository

import com.nexio.tv.data.local.KitsuCatalogIds
import com.nexio.tv.data.local.KitsuCatalogPreferences
import com.nexio.tv.data.remote.api.KitsuAnimeResource
import com.nexio.tv.domain.model.CatalogRow

data class KitsuDiscoverySnapshot(
    val rowsByCatalog: Map<String, CatalogRow> = emptyMap(),
    val updatedAtMs: Long = 0L,
    val catalogIdsWithCurrentPreferences: Set<String> = emptySet()
) {
    fun currentRowsFor(preferences: KitsuCatalogPreferences): Map<String, CatalogRow> {
        val enabledCatalogIds = preferences.enabledCatalogIds()
        return rowsByCatalog.filterKeys { key ->
            key in catalogIdsWithCurrentPreferences && key in enabledCatalogIds
        }
    }
}

fun kitsuCatalogTitle(catalogId: String): String? {
    return when (catalogId) {
        KitsuCatalogIds.TRENDING_ANIME -> "Kitsu Trending Anime"
        KitsuCatalogIds.HIGHEST_RATED_ANIME -> "Kitsu Highest Rated Anime"
        KitsuCatalogIds.POPULAR_ANIME -> "Kitsu Popular Anime"
        KitsuCatalogIds.POPULAR_ACTION_ANIME -> "Kitsu Popular Action Anime"
        KitsuCatalogIds.POPULAR_DRAMA_ANIME -> "Kitsu Popular Drama Anime"
        KitsuCatalogIds.POPULAR_COMEDY_ANIME -> "Kitsu Popular Comedy Anime"
        KitsuCatalogIds.POPULAR_FANTASY_ANIME -> "Kitsu Popular Fantasy Anime"
        KitsuCatalogIds.POPULAR_ROMANCE_ANIME -> "Kitsu Popular Romance Anime"
        KitsuCatalogIds.POPULAR_ADVENTURE_ANIME -> "Kitsu Popular Adventure Anime"
        else -> null
    }
}

interface KitsuDiscoveryClient {
    suspend fun fetchCatalog(
        catalogId: String,
        preferences: KitsuCatalogPreferences
    ): List<KitsuAnimeResource>
}
