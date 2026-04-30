package com.nexio.tv.data.catalog.rails

import com.nexio.tv.core.catalog.rails.CatalogRailDescriptor
import com.nexio.tv.core.catalog.rails.CatalogRailProvider
import com.nexio.tv.core.catalog.rails.CatalogRailSource
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.data.local.SimklDiscoverySnapshotStore
import com.nexio.tv.domain.model.RailItemPreview
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fourth conformant [CatalogRailSource] (Plan 5 of the catalog-rails uniformity series).
 *
 * Reads the per-profile [com.nexio.tv.data.repository.SimklDiscoverySnapshot] (already
 * maintained by `SimklDiscoveryService.ensureFresh(profileId)`) and exposes each entry
 * in `itemRecordsByCatalog` as a rail. The snapshot already carries pre-mapped
 * `RailItemPreview` lists per catalogId, so [fetchRail] is a snapshot lookup with no
 * additional mapping.
 *
 * **No codec.** SIMKL discovery uses stable catalog IDs (e.g. `simkl_tv_trending_today`)
 * as railIds directly — no encode/decode layer needed.
 *
 * **No auth gate.** SIMKL discovery rails are public CDN endpoints; the underlying
 * `SimklDiscoveryService.ensureFresh()` does not check authentication. An empty snapshot
 * (no rails enabled in user settings → `ensureFresh` writes nothing) is the only
 * "rails unavailable" condition.
 *
 * **Display titles** are hardcoded per known catalogId. `SimklDiscoverySnapshot` only
 * stores items, not human-readable names. Unknown catalog IDs fall back to the railId as
 * the display title (defensive — covers future catalogs added to `SimklDiscoveryService`
 * before this map is updated).
 *
 * **Refresh ownership.** [fetchRail] does NOT trigger a network refresh — it only reads
 * the latest persisted snapshot. Triggering a refresh requires calling
 * `SimklDiscoveryService.ensureFresh(profileId)` separately. A future plan can unify
 * these (fetch-on-demand) once the contract has explicit consumers.
 */
@Singleton
class SimklCatalogRailSource @Inject constructor(
    private val snapshotStore: SimklDiscoverySnapshotStore
) : CatalogRailSource {

    override val providerId: CatalogRailProvider = CatalogRailProvider.SIMKL

    override suspend fun availableRails(profileId: Int): List<CatalogRailDescriptor> {
        val snapshot = snapshotStore.read(profileId = profileId) ?: return emptyList()
        return snapshot.itemRecordsByCatalog.entries.mapIndexed { index, (catalogId, _) ->
            CatalogRailDescriptor(
                railId = catalogId,
                providerId = CatalogRailProvider.SIMKL,
                displayTitle = DISPLAY_TITLES[catalogId] ?: catalogId,
                sortOrder = index
            )
        }
    }

    override suspend fun fetchRail(
        profileId: Int,
        rail: CatalogRailDescriptor
    ): IntegrationFetchResult<List<RailItemPreview>> {
        val snapshot = snapshotStore.read(profileId = profileId) ?: return IntegrationFetchResult.Missing
        val items = snapshot.itemRecordsByCatalog[rail.railId] ?: return IntegrationFetchResult.Missing
        return IntegrationFetchResult.Updated(items)
    }

    private companion object {
        private val DISPLAY_TITLES: Map<String, String> = mapOf(
            "simkl_tv_trending_today" to "Simkl — TV Trending Today",
            "simkl_tv_trending_week" to "Simkl — TV Trending This Week",
            "simkl_tv_trending_month" to "Simkl — TV Trending This Month",
            "simkl_anime_trending_today" to "Simkl — Anime Trending Today",
            "simkl_anime_trending_week" to "Simkl — Anime Trending This Week",
            "simkl_anime_trending_month" to "Simkl — Anime Trending This Month",
            "simkl_movie_trending_today" to "Simkl — Movies Trending Today",
            "simkl_movie_trending_week" to "Simkl — Movies Trending This Week",
            "simkl_movie_trending_month" to "Simkl — Movies Trending This Month",
            "simkl_dvd_releases" to "Simkl — DVD Releases"
        )
    }
}
