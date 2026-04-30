package com.nexio.tv.data.catalog.rails

import com.nexio.tv.core.catalog.rails.CatalogRailDescriptor
import com.nexio.tv.core.catalog.rails.CatalogRailProvider
import com.nexio.tv.core.catalog.rails.CatalogRailSource
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.domain.model.CatalogDescriptor as AddonCatalogDescriptor
import com.nexio.tv.domain.model.RailItemPreview
import com.nexio.tv.domain.model.toLegacyRailItemPreviews
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.CatalogRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * First conformant [CatalogRailSource] (Plan 2 of the catalog-rails uniformity series).
 *
 * Discovers addon catalogs via [AddonRepository] and fetches each rail through the existing
 * [CatalogRepository] (which routes through the integration runtime via
 * `AddonCatalogIntegrationProvider`). Maps the resulting [com.nexio.tv.domain.model.MetaPreview]
 * items to [RailItemPreview] using the shared `toLegacyRailItemPreviews(railId)` extension so
 * the canonical Home first-paint boundary fires uniformly across all rail providers.
 *
 * **Profile parameter caveat:** [availableRails] accepts a `profileId` for interface compliance,
 * but [AddonRepository.getCachedInstalledAddons] returns the *currently active* profile's
 * addons (profile-bound by the underlying preferences store, not by parameter). This matches
 * the existing `HomeViewModelCatalogPipeline` behavior — a future plan will thread `profileId`
 * explicitly through `AddonRepository`.
 */
@Singleton
class AddonCatalogRailSource @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val railIdCodec: AddonCatalogRailIdCodec
) : CatalogRailSource {

    override val providerId: CatalogRailProvider = CatalogRailProvider.ADDON

    override suspend fun availableRails(profileId: Int): List<CatalogRailDescriptor> {
        val addons = addonRepository.getCachedInstalledAddons()
        var sortOrder = 0
        val descriptors = mutableListOf<CatalogRailDescriptor>()
        for (addon in addons) {
            for (catalog in addon.catalogs) {
                if (catalog.isSearchOnlyCatalog()) continue
                descriptors += CatalogRailDescriptor(
                    railId = railIdCodec.encode(
                        addonId = addon.id,
                        type = catalog.rawType,
                        catalogId = catalog.id
                    ),
                    providerId = CatalogRailProvider.ADDON,
                    displayTitle = catalog.name,
                    sortOrder = sortOrder++
                )
            }
        }
        return descriptors
    }

    override suspend fun fetchRail(
        profileId: Int,
        rail: CatalogRailDescriptor
    ): IntegrationFetchResult<List<RailItemPreview>> {
        val parts = railIdCodec.decode(rail.railId) ?: return IntegrationFetchResult.Missing
        val addons = addonRepository.getCachedInstalledAddons()
        val addon = addons.firstOrNull { it.id == parts.addonId }
            ?: return IntegrationFetchResult.Missing
        val catalog = addon.catalogs.firstOrNull {
            it.id == parts.catalogId && it.rawType == parts.type
        } ?: return IntegrationFetchResult.Missing

        val result = runCatching {
            catalogRepository.refreshCatalogToDisk(
                addonBaseUrl = addon.baseUrl,
                addonId = addon.id,
                addonName = addon.name,
                catalogId = catalog.id,
                catalogName = catalog.name,
                type = catalog.rawType
            )
        }.getOrNull() ?: return IntegrationFetchResult.Missing

        val row = result.getOrNull() ?: return IntegrationFetchResult.Missing
        val items = row.items.toLegacyRailItemPreviews(railId = rail.railId)
        return IntegrationFetchResult.Updated(items)
    }

    /**
     * Mirrors `CatalogDescriptor.isSearchOnlyCatalog()` from
     * `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogUtils.kt:748` —
     * inlined here because the original is `internal` to the UI module and we don't want
     * to widen its visibility just for this consumer.
     */
    private fun AddonCatalogDescriptor.isSearchOnlyCatalog(): Boolean =
        extra.any { it.name.equals("search", ignoreCase = true) && it.isRequired }
}
