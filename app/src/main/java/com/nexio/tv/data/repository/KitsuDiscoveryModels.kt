package com.nexio.tv.data.repository

import com.nexio.tv.data.local.KitsuCatalogIds
import com.nexio.tv.data.local.KitsuCatalogPreferences
import com.nexio.tv.data.remote.api.KitsuAnimeResource
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.RailPreviewCatalogRowRecord
import com.nexio.tv.domain.model.toLegacyRailItemPreviews

data class KitsuDiscoverySnapshot(
    val rowRecordsByCatalog: Map<String, RailPreviewCatalogRowRecord> = emptyMap(),
    val updatedAtMs: Long = 0L,
    val catalogIdsWithCurrentPreferences: Set<String> = emptySet()
) {
    constructor(
        rowsByCatalog: Map<String, CatalogRow>,
        updatedAtMs: Long = 0L,
        catalogIdsWithCurrentPreferences: Set<String> = emptySet(),
        fromLegacyRows: Boolean = true
    ) : this(
        rowRecordsByCatalog = rowsByCatalog.mapValues { (_, row) -> row.toRailPreviewCatalogRowRecord() },
        updatedAtMs = updatedAtMs,
        catalogIdsWithCurrentPreferences = catalogIdsWithCurrentPreferences
    )

    val rowsByCatalog get() = rowRecordsByCatalog.mapValues { (_, row) -> row.toCatalogRow() }

    fun currentRowsFor(preferences: KitsuCatalogPreferences): Map<String, CatalogRow> {
        val enabledCatalogIds = preferences.enabledCatalogIds()
        return rowRecordsByCatalog.filterKeys { key ->
            key in catalogIdsWithCurrentPreferences && key in enabledCatalogIds
        }.mapValues { (_, row) -> row.toCatalogRow() }
    }
}

private fun CatalogRow.toRailPreviewCatalogRowRecord(): RailPreviewCatalogRowRecord {
    return RailPreviewCatalogRowRecord(
        addonId = addonId,
        addonName = addonName,
        addonBaseUrl = addonBaseUrl,
        catalogId = catalogId,
        catalogName = catalogName,
        type = type,
        rawType = rawType,
        previews = items.toLegacyRailItemPreviews(railId = catalogId)
    )
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
