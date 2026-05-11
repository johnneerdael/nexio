package com.nexio.tv.data.repository

import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.data.integration.railpreview.KitsuRailFranchiseGrouper
import com.nexio.tv.data.integration.railpreview.KitsuRailPreviewMapper
import com.nexio.tv.data.integration.kitsu.KitsuDiscoveryIntegrationProvider
import com.nexio.tv.data.local.KitsuCatalogPreferences
import com.nexio.tv.data.remote.api.KitsuAnimeResource
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.RailItemPreview
import com.nexio.tv.domain.model.RailPreviewCatalogRowRecord
import com.nexio.tv.domain.model.toMetaPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

typealias RetrofitKitsuDiscoveryClient = KitsuDiscoveryIntegrationProvider

@Singleton
class KitsuDiscoveryService @Inject constructor(
    private val client: KitsuDiscoveryClient,
    private val grouper: KitsuRailFranchiseGrouper,
    animeIdMappingService: AnimeIdMappingService
) {
    private val snapshot = MutableStateFlow(KitsuDiscoverySnapshot())
    private val railPreviewMapper = KitsuRailPreviewMapper(animeIdMappingService)

    fun observeSnapshot(): Flow<KitsuDiscoverySnapshot> = snapshot

    suspend fun refreshCatalogs(
        preferences: KitsuCatalogPreferences,
        force: Boolean,
        catalogIds: Set<String>? = null
    ): KitsuDiscoverySnapshot {
        val sanitized = preferences.sanitized()
        val requestedCatalogIds = catalogIds
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
        val previous = snapshot.value
        val previousCurrentRows = previous.currentRowsFor(sanitized)
        val previousCurrentRowRecords = previous.rowRecordsByCatalog.filterKeys { key ->
            key in previousCurrentRows
        }
        if (!force && requestedCatalogIds != null && requestedCatalogIds.all { it in previousCurrentRows }) {
            return previous
        }

        val enabledCatalogs = sanitized.catalogOrder
            .filter { it in sanitized.enabledCatalogs }
            .filter { requestedCatalogIds == null || it in requestedCatalogIds }

        val refreshedRows = enabledCatalogs.associateWith { catalogId ->
            fetchCatalogRow(catalogId, sanitized)
        }.filterValues { it != null }
            .mapValues { it.value!! }

        val rows = if (catalogIds == null) {
            refreshedRows
        } else {
            previousCurrentRowRecords - requestedCatalogIds.orEmpty() + refreshedRows
        }
        val currentPreferenceCatalogIds = sanitized.enabledCatalogIds()
        val catalogIdsWithCurrentPreferences = if (catalogIds == null) {
            currentPreferenceCatalogIds
        } else {
            (previous.catalogIdsWithCurrentPreferences.intersect(currentPreferenceCatalogIds) - requestedCatalogIds.orEmpty()) +
                enabledCatalogs.toSet()
        }

        return KitsuDiscoverySnapshot(
            rowRecordsByCatalog = rows,
            updatedAtMs = System.currentTimeMillis(),
            catalogIdsWithCurrentPreferences = catalogIdsWithCurrentPreferences
        ).also { snapshot.value = it }
    }

    private suspend fun fetchCatalogRow(
        catalogId: String,
        preferences: KitsuCatalogPreferences
    ): RailPreviewCatalogRowRecord? {
        val title = kitsuCatalogTitle(catalogId) ?: return null
        val results = runCatching { client.fetchCatalog(catalogId, preferences) }
            .getOrDefault(emptyList())
        val items = mapCatalogResults(
            railId = catalogId,
            results = results,
            generatedAtMs = System.currentTimeMillis()
        )
        if (items.isEmpty()) return null
        return RailPreviewCatalogRowRecord(
            addonId = ADDON_ID,
            addonName = ADDON_NAME,
            addonBaseUrl = ADDON_BASE_URL,
            catalogId = catalogId,
            catalogName = title,
            type = ContentType.SERIES,
            rawType = ContentType.SERIES.toApiString("catalog"),
            previews = items
        )
    }

    private fun mapCatalogResults(
        railId: String,
        results: List<KitsuAnimeResource>,
        generatedAtMs: Long
    ): List<RailItemPreview> {
        val mapped = results.take(MAX_ITEMS_PER_SOURCE)
            .mapIndexedNotNull { index, result ->
                railPreviewMapper.mapAnime(
                    railId = railId,
                    anime = result,
                    position = index,
                    generatedAtMs = generatedAtMs
                )
            }
        return grouper.group(mapped)
    }

    companion object {
        private const val ADDON_ID = "kitsu"
        private const val ADDON_NAME = "Kitsu"
        private const val ADDON_BASE_URL = "https://kitsu.io/api/edge"
        private const val MAX_ITEMS_PER_SOURCE = 20
    }
}
