package com.nexio.tv.data.catalog.rails

import com.nexio.tv.core.catalog.rails.CatalogRailDescriptor
import com.nexio.tv.core.catalog.rails.CatalogRailProvider
import com.nexio.tv.core.catalog.rails.CatalogRailSource
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.data.local.MDBListDiscoverySnapshotStore
import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.data.repository.MDBListDiscoverySnapshot
import com.nexio.tv.domain.model.RailItemPreview
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Third conformant [CatalogRailSource] (Plan 4 of the catalog-rails uniformity series).
 *
 * Reads the per-profile [com.nexio.tv.data.repository.MDBListDiscoverySnapshot] (already
 * maintained by `MDBListDiscoveryService.ensureFresh(profileId)`) and exposes each
 * `MDBListCustomCatalog` as a rail. Each catalog already carries its pre-mapped
 * `itemRecords: List<RailItemPreview>`, so [fetchRail] is a snapshot lookup with no
 * additional mapping.
 *
 * **No codec.** `MDBListCustomCatalog.catalogId` is already a stable, unique identifier
 * for a user-selected list (e.g. `mdblist_list_<slug>_movies`). Plan 4 uses it directly
 * as the railId — no encode/decode layer needed.
 *
 * **API-key gate.** `MDBListSettingsDataStore.settings` is read on every call. If the
 * integration is disabled or the API key is blank, [availableRails] returns an empty
 * list and [fetchRail] returns [IntegrationFetchResult.Missing]. This matches the
 * existing `MDBListDiscoveryService.ensureFresh()` behavior — without auth, no rails.
 *
 * **Settings store caveat.** `MDBListSettingsDataStore` is currently a single global
 * store (NOT per-profile). The `apiKey` and `enabled` flags are shared across all
 * profiles. Plan 4 treats them as a profile-agnostic gate. Threading per-profile MDBList
 * settings is a separate concern best handled when the data store grows that surface.
 *
 * **Refresh ownership.** [fetchRail] does NOT trigger a network refresh — it only reads
 * the latest persisted snapshot. Triggering a refresh requires calling
 * `MDBListDiscoveryService.ensureFresh(profileId)` separately. A future plan can unify
 * these (fetch-on-demand) once the contract has explicit consumers.
 */
@Singleton
class MDBListCatalogRailSource @Inject constructor(
    private val snapshotStore: MDBListDiscoverySnapshotStore,
    private val settingsDataStore: MDBListSettingsDataStore
) : CatalogRailSource {

    override val providerId: CatalogRailProvider = CatalogRailProvider.MDBLIST

    override suspend fun availableRails(profileId: Int): List<CatalogRailDescriptor> {
        if (!isAuthorized()) return emptyList()
        val snapshot = snapshotStore.read(profileId = profileId) ?: return emptyList()
        return snapshot.customListCatalogs.mapIndexed { index, catalog ->
            CatalogRailDescriptor(
                railId = catalog.catalogId,
                providerId = CatalogRailProvider.MDBLIST,
                displayTitle = catalog.catalogName,
                sortOrder = index
            )
        }
    }

    override suspend fun fetchRail(
        profileId: Int,
        rail: CatalogRailDescriptor
    ): IntegrationFetchResult<List<RailItemPreview>> {
        if (!isAuthorized()) return IntegrationFetchResult.Missing
        val snapshot: MDBListDiscoverySnapshot =
            snapshotStore.read(profileId = profileId) ?: return IntegrationFetchResult.Missing
        val catalog = snapshot.customListCatalogs.firstOrNull { it.catalogId == rail.railId }
            ?: return IntegrationFetchResult.Missing
        return IntegrationFetchResult.Updated(catalog.itemRecords)
    }

    private suspend fun isAuthorized(): Boolean {
        val settings = settingsDataStore.settings.first()
        return settings.enabled && settings.apiKey.isNotBlank()
    }
}
