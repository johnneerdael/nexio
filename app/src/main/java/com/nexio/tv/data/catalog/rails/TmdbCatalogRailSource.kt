package com.nexio.tv.data.catalog.rails

import com.nexio.tv.core.catalog.rails.CatalogRailDescriptor
import com.nexio.tv.core.catalog.rails.CatalogRailProvider
import com.nexio.tv.core.catalog.rails.CatalogRailSource
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.data.repository.TmdbDiscoveryService
import com.nexio.tv.domain.model.RailItemPreview
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Fifth conformant [CatalogRailSource] (Plan 6 of the catalog-rails uniformity series).
 *
 * Reads the in-memory `MutableStateFlow<TmdbDiscoverySnapshot>` exposed by
 * [TmdbDiscoveryService.observeSnapshot] (already maintained by
 * `TmdbDiscoveryService.refreshCatalogs(...)`) and exposes each entry in
 * `rowRecordsByCatalog` as a rail. Each `RailPreviewCatalogRowRecord` already carries
 * its `previews: List<RailItemPreview>` and `catalogName: String`, so [fetchRail] is a
 * snapshot lookup with no additional mapping and [availableRails] uses `catalogName`
 * directly for display titles.
 *
 * **No codec.** `TmdbDiscoverySnapshot.rowRecordsByCatalog` keys are the stable catalog
 * IDs (e.g. `tmdb_trending_movies`, `tmdb_popular_series`) — used directly as railIds.
 *
 * **No auth gate.** Built-in TMDB rails are public TMDB API content; the underlying
 * `TmdbDiscoveryService.refreshCatalogs()` does not gate on authentication state. Empty
 * snapshot is the only "rails unavailable" condition.
 *
 * **Profile-isolation gap (known, not addressed by this plan):** Unlike Plans 4/5 which
 * read disk-backed per-profile snapshot stores, `TmdbDiscoveryService` keeps a single
 * **global** in-memory `MutableStateFlow<TmdbDiscoverySnapshot>` shared across all
 * profiles. Profile A's preferences (include adult, enabled catalogs, etc.) bleed into
 * Profile B's view of the snapshot until Profile B's `refreshCatalogs()` overwrites it.
 * This bug exists today in the underlying service. A follow-up plan should add per-profile
 * keying to the snapshot, orthogonal to this contract migration.
 *
 * **Refresh ownership.** [fetchRail] does NOT trigger a network refresh — it only reads
 * the latest snapshot. Triggering a refresh requires calling
 * `TmdbDiscoveryService.refreshCatalogs(preferences, force, catalogIds)` separately
 * (already done by `HomeRailHydrationExecutor`).
 */
@Singleton
class TmdbCatalogRailSource @Inject constructor(
    private val discoveryService: TmdbDiscoveryService
) : CatalogRailSource {

    override val providerId: CatalogRailProvider = CatalogRailProvider.TMDB_BUILTIN

    override suspend fun availableRails(profileId: Int): List<CatalogRailDescriptor> {
        val snapshot = discoveryService.observeSnapshot().first()
        return snapshot.rowRecordsByCatalog.entries.mapIndexed { index, (catalogId, record) ->
            CatalogRailDescriptor(
                railId = catalogId,
                providerId = CatalogRailProvider.TMDB_BUILTIN,
                displayTitle = record.catalogName,
                sortOrder = index
            )
        }
    }

    override suspend fun fetchRail(
        profileId: Int,
        rail: CatalogRailDescriptor
    ): IntegrationFetchResult<List<RailItemPreview>> {
        val snapshot = discoveryService.observeSnapshot().first()
        val record = snapshot.rowRecordsByCatalog[rail.railId]
            ?: return IntegrationFetchResult.Missing
        return IntegrationFetchResult.Updated(record.previews)
    }
}
