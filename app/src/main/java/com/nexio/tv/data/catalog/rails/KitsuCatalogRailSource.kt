package com.nexio.tv.data.catalog.rails

import com.nexio.tv.core.catalog.rails.CatalogRailDescriptor
import com.nexio.tv.core.catalog.rails.CatalogRailProvider
import com.nexio.tv.core.catalog.rails.CatalogRailSource
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.data.repository.KitsuDiscoveryService
import com.nexio.tv.domain.model.RailItemPreview
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Sixth conformant [CatalogRailSource] (Plan 7 of the catalog-rails uniformity series — last
 * provider migration before the audit can flip from advisory to strict in Plan 8).
 *
 * Reads the in-memory `MutableStateFlow<KitsuDiscoverySnapshot>` exposed by
 * [KitsuDiscoveryService.observeSnapshot] (already maintained by
 * `KitsuDiscoveryService.refreshCatalogs(...)`) and exposes each entry in
 * `rowRecordsByCatalog` as a rail. Each `RailPreviewCatalogRowRecord` already carries
 * its `previews: List<RailItemPreview>` and `catalogName: String`, so [fetchRail] is a
 * snapshot lookup with no additional mapping and [availableRails] uses `catalogName`
 * directly for display titles.
 *
 * **No codec.** `KitsuDiscoverySnapshot.rowRecordsByCatalog` keys are stable catalog IDs
 * (e.g. `kitsu_trending_anime`, `kitsu_highest_rated_anime`) — used directly as railIds.
 *
 * **No auth gate.** Built-in Kitsu rails are public Kitsu API content; the underlying
 * `KitsuDiscoveryService.refreshCatalogs()` does not gate on authentication state. Empty
 * snapshot is the only "rails unavailable" condition.
 *
 * **Profile-isolation gap (known, not addressed by this plan):** Like TMDB (Plan 6),
 * `KitsuDiscoveryService` keeps a single **global** in-memory `MutableStateFlow<KitsuDiscoverySnapshot>`
 * shared across all profiles. Profile-specific preferences may briefly bleed across profiles
 * until refresh overwrites the snapshot. This bug exists today in the underlying service.
 * A follow-up plan should add per-profile keying to the snapshot, orthogonal to this
 * contract migration.
 *
 * **Refresh ownership.** [fetchRail] does NOT trigger a network refresh — it only reads
 * the latest snapshot. Triggering a refresh requires calling
 * `KitsuDiscoveryService.refreshCatalogs(preferences, force, catalogIds)` separately
 * (already done by `HomeRailHydrationExecutor`).
 */
@Singleton
class KitsuCatalogRailSource @Inject constructor(
    private val discoveryService: KitsuDiscoveryService
) : CatalogRailSource {

    override val providerId: CatalogRailProvider = CatalogRailProvider.KITSU_BUILTIN

    override suspend fun availableRails(profileId: Int): List<CatalogRailDescriptor> {
        val snapshot = discoveryService.observeSnapshot().first()
        return snapshot.rowRecordsByCatalog.entries.mapIndexed { index, (catalogId, record) ->
            CatalogRailDescriptor(
                railId = catalogId,
                providerId = CatalogRailProvider.KITSU_BUILTIN,
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
