package com.nexio.tv.ui.screens.home

import com.nexio.tv.data.local.MDBListCatalogPreferences
import com.nexio.tv.data.local.SimklCatalogIds
import com.nexio.tv.data.local.SimklCatalogPreferences
import com.nexio.tv.data.local.SyntheticHomeCatalogStore
import com.nexio.tv.data.local.TmdbCatalogPreferences
import com.nexio.tv.data.local.TraktCatalogIds
import com.nexio.tv.data.local.TraktCatalogPreferences
import com.nexio.tv.data.local.PersistedSyntheticCatalogGroup
import com.nexio.tv.data.repository.MDBListCustomCatalog
import com.nexio.tv.data.repository.MDBListDiscoverySnapshot
import com.nexio.tv.data.repository.SimklDiscoverySnapshot
import com.nexio.tv.data.repository.TmdbDiscoverySnapshot
import com.nexio.tv.data.repository.TraktCustomListCatalog
import com.nexio.tv.data.repository.TraktDiscoverySnapshot
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.MetaPreview

internal data class CatalogPlan(
    val expectedOrderKeys: List<String>,
    val publishableOrderKeys: List<String>,
    val descriptors: List<ConfiguredHomeCatalogDescriptor>,
    val rails: List<PlannedCatalogRail>
)

internal data class PlannedCatalogRail(
    val orderKey: String,
    val descriptor: ConfiguredHomeCatalogDescriptor,
    private val populatedRows: List<CatalogRow>
) {
    fun toLoadingCatalogRow(): CatalogRow = descriptor.toLoadingCatalogRow()

    fun toPopulatedRows(): List<CatalogRow> = populatedRows
}

internal fun CatalogPlan.toPersistedSyntheticCatalogGroups(): List<PersistedSyntheticCatalogGroup> {
    return rails.mapNotNull { rail ->
        val rows = rail.toPopulatedRows()
        if (rows.isEmpty()) {
            null
        } else {
            PersistedSyntheticCatalogGroup(
                orderKey = rail.orderKey,
                rows = rows
            )
        }
    }
}

internal fun SyntheticHomeCatalogStore.Snapshot.tmdbGroupsMatchingPreferences(
    prefs: TmdbCatalogPreferences,
    preferencesObserved: Boolean = true
): List<PersistedSyntheticCatalogGroup> {
    return tmdbGroupsMatchPreferences(
        groups = tmdbGroups,
        includeAdult = tmdbIncludeAdult,
        hideUnreleasedDigital = tmdbHideUnreleasedDigital,
        prefs = prefs,
        preferencesObserved = preferencesObserved
    )
}

internal fun tmdbGroupsMatchPreferences(
    groups: List<PersistedSyntheticCatalogGroup>,
    includeAdult: Boolean?,
    hideUnreleasedDigital: Boolean?,
    prefs: TmdbCatalogPreferences,
    preferencesObserved: Boolean = true
): List<PersistedSyntheticCatalogGroup> {
    if (!preferencesObserved) return emptyList()
    val sanitized = prefs.sanitized()
    return if (
        includeAdult == sanitized.includeAdult &&
        hideUnreleasedDigital == sanitized.hideUnreleasedDigital
    ) {
        groups.filterTmdbGroupsEnabledUnder(sanitized)
    } else {
        emptyList()
    }
}

internal fun resolveEffectiveTmdbSyntheticGroups(
    renewedTmdbGroups: List<PersistedSyntheticCatalogGroup>,
    existingSnapshot: SyntheticHomeCatalogStore.Snapshot,
    prefs: TmdbCatalogPreferences,
    snapshot: TmdbDiscoverySnapshot
): List<PersistedSyntheticCatalogGroup> {
    val sanitized = prefs.sanitized()
    val currentRenewedGroups = renewedTmdbGroups.filterTmdbGroupsEnabledUnder(sanitized)
    if (currentRenewedGroups.isNotEmpty()) return currentRenewedGroups
    val existingCurrentGroups = existingSnapshot.tmdbGroupsMatchingPreferences(prefs)
    if (existingCurrentGroups.isEmpty()) return emptyList()
    return if (shouldPreserveExistingTmdbGroupsDuringRefresh(sanitized, snapshot)) {
        existingCurrentGroups
    } else {
        emptyList()
    }
}

internal fun List<PersistedSyntheticCatalogGroup>.filterTmdbGroupsEnabledUnder(
    prefs: TmdbCatalogPreferences
): List<PersistedSyntheticCatalogGroup> {
    val enabledCatalogIds = prefs.enabledCatalogIds()
    return mapNotNull { group ->
        if (group.orderKey !in enabledCatalogIds) return@mapNotNull null
        val enabledRows = group.rows.filter { row -> row.catalogId in enabledCatalogIds }
        if (enabledRows.isEmpty()) null else group.copy(rows = enabledRows)
    }
}

internal fun shouldPreserveExistingTmdbGroupsDuringRefresh(
    prefs: TmdbCatalogPreferences,
    snapshot: TmdbDiscoverySnapshot
): Boolean {
    val expectedKeys = buildExpectedConfiguredTmdbOrderKeys(prefs)
    if (expectedKeys.isEmpty()) return false
    val snapshotHasCurrentPreferenceProvenance = snapshot.updatedAtMs > 0L &&
        snapshot.matchesPreferences(prefs)
    if (!snapshotHasCurrentPreferenceProvenance) return true
    return expectedKeys.any { key -> key !in snapshot.catalogIdsWithCurrentPreferences }
}

internal fun buildConfiguredCatalogPlan(
    addons: List<Addon>,
    disabledHomeCatalogKeys: Set<String>,
    availableAddonOrderKeys: Set<String>,
    traktPrefs: TraktCatalogPreferences,
    traktSnapshot: TraktDiscoverySnapshot,
    hasTraktUpNextItems: Boolean,
    traktUpNextItems: List<MetaPreview> = emptyList(),
    simklPrefs: SimklCatalogPreferences,
    simklSnapshot: SimklDiscoverySnapshot,
    mdbPrefs: MDBListCatalogPreferences,
    mdbSnapshot: MDBListDiscoverySnapshot,
    tmdbPrefs: TmdbCatalogPreferences = TmdbCatalogPreferences(enabledCatalogs = emptySet(), catalogOrder = emptyList()),
    tmdbSnapshot: TmdbDiscoverySnapshot = TmdbDiscoverySnapshot(),
    existingRowsByOrderKey: Map<String, CatalogRow> = emptyMap()
): CatalogPlan {
    val currentTmdbRows = tmdbSnapshot.currentRowsFor(tmdbPrefs)
    val expectedOrderKeys = buildExpectedConfiguredHomeOrderKeys(
        addons = addons,
        disabledHomeCatalogKeys = disabledHomeCatalogKeys,
        traktPrefs = traktPrefs,
        simklPrefs = simklPrefs,
        mdbPrefs = mdbPrefs,
        mdbSnapshot = mdbSnapshot,
        tmdbPrefs = tmdbPrefs
    )
    val publishableOrderKeys = buildPublishableConfiguredHomeOrderKeys(
        addons = addons,
        disabledHomeCatalogKeys = disabledHomeCatalogKeys,
        availableAddonOrderKeys = availableAddonOrderKeys,
        traktPrefs = traktPrefs,
        traktSnapshot = traktSnapshot,
        hasTraktUpNextItems = hasTraktUpNextItems,
        simklPrefs = simklPrefs,
        simklSnapshot = simklSnapshot,
        mdbPrefs = mdbPrefs,
        mdbSnapshot = mdbSnapshot,
        tmdbPrefs = tmdbPrefs,
        tmdbSnapshot = tmdbSnapshot
    )
    val descriptors = buildConfiguredHomeCatalogDescriptors(
        addons = addons,
        disabledHomeCatalogKeys = disabledHomeCatalogKeys,
        traktPrefs = traktPrefs,
        traktSnapshot = traktSnapshot,
        hasTraktUpNextItems = hasTraktUpNextItems,
        simklPrefs = simklPrefs,
        mdbPrefs = mdbPrefs,
        mdbSnapshot = mdbSnapshot,
        tmdbPrefs = tmdbPrefs,
        tmdbSnapshot = tmdbSnapshot,
        existingRowsByOrderKey = existingRowsByOrderKey
    )
    val descriptorByKey = descriptors.associateBy { it.orderKey }
    val rails = publishableOrderKeys.mapNotNull { key ->
        val descriptor = descriptorByKey[key] ?: return@mapNotNull null
        val rows = when (descriptor.addonId) {
            TRAKT_HOME_ADDON_ID -> populatedTraktRowsForKey(
                key = key,
                descriptor = descriptor,
                snapshot = traktSnapshot,
                upNextItems = traktUpNextItems
            )
            SIMKL_HOME_ADDON_ID -> populatedRowsForItems(
                descriptor = descriptor,
                items = simklItemsForKey(key, simklSnapshot)
            )
            MDBLIST_HOME_ADDON_ID -> populatedMDBListRowsForKey(
                key = key,
                snapshot = mdbSnapshot
            )
            TMDB_HOME_ADDON_ID -> currentTmdbRows[key]
                ?.takeIf { row -> row.items.isNotEmpty() }
                ?.let(::listOf)
                .orEmpty()
            else -> emptyList()
        }
        PlannedCatalogRail(
            orderKey = key,
            descriptor = descriptor,
            populatedRows = rows
        )
    }

    return CatalogPlan(
        expectedOrderKeys = expectedOrderKeys,
        publishableOrderKeys = publishableOrderKeys,
        descriptors = descriptors,
        rails = rails
    )
}

private fun populatedTraktRowsForKey(
    key: String,
    descriptor: ConfiguredHomeCatalogDescriptor,
    snapshot: TraktDiscoverySnapshot,
    upNextItems: List<MetaPreview>
): List<CatalogRow> {
    val builtInItems = when (key) {
        TraktCatalogIds.UP_NEXT -> upNextItems
        TraktCatalogIds.TRENDING_MOVIES -> snapshot.trendingMovieItems
        TraktCatalogIds.TRENDING_SHOWS -> snapshot.trendingShowItems
        TraktCatalogIds.POPULAR_MOVIES -> snapshot.popularMovieItems
        TraktCatalogIds.POPULAR_SHOWS -> snapshot.popularShowItems
        TraktCatalogIds.RECOMMENDED_MOVIES -> snapshot.recommendationMovieItems
        TraktCatalogIds.RECOMMENDED_SHOWS -> snapshot.recommendationShowItems
        TraktCatalogIds.CALENDAR -> snapshot.calendarItems
        else -> emptyList()
    }
    if (builtInItems.isNotEmpty()) {
        return populatedRowsForItems(descriptor = descriptor, items = builtInItems)
    }

    return snapshot.customListCatalogs
        .filter { catalog -> catalog.key == key }
        .mapNotNull(::traktCustomCatalogRow)
}

private fun simklItemsForKey(
    key: String,
    snapshot: SimklDiscoverySnapshot
): List<MetaPreview> {
    return when (key) {
        SimklCatalogIds.TV_TRENDING_TODAY,
        SimklCatalogIds.TV_TRENDING_WEEK,
        SimklCatalogIds.TV_TRENDING_MONTH,
        SimklCatalogIds.ANIME_TRENDING_TODAY,
        SimklCatalogIds.ANIME_TRENDING_WEEK,
        SimklCatalogIds.ANIME_TRENDING_MONTH,
        SimklCatalogIds.MOVIE_TRENDING_TODAY,
        SimklCatalogIds.MOVIE_TRENDING_WEEK,
        SimklCatalogIds.MOVIE_TRENDING_MONTH,
        SimklCatalogIds.DVD_RELEASES -> snapshot.itemsByCatalog[key].orEmpty()
        else -> emptyList()
    }
}

private fun populatedMDBListRowsForKey(
    key: String,
    snapshot: MDBListDiscoverySnapshot
): List<CatalogRow> {
    return snapshot.customListCatalogs
        .filter { catalog -> catalog.key == key }
        .mapNotNull(::mdbListCustomCatalogRow)
}

private fun populatedRowsForItems(
    descriptor: ConfiguredHomeCatalogDescriptor,
    items: List<MetaPreview>
): List<CatalogRow> {
    if (items.isEmpty()) return emptyList()
    return listOf(
        CatalogRow(
            addonId = descriptor.addonId,
            addonName = descriptor.addonName,
            addonBaseUrl = descriptor.addonBaseUrl,
            catalogId = descriptor.catalogId,
            catalogName = descriptor.catalogName,
            type = descriptor.type,
            rawType = descriptor.rawType,
            items = items,
            isLoading = false,
            hasMore = false,
            supportsSkip = false
        )
    )
}

private fun traktCustomCatalogRow(catalog: TraktCustomListCatalog): CatalogRow? {
    if (catalog.items.isEmpty()) return null
    return CatalogRow(
        addonId = TRAKT_HOME_ADDON_ID,
        addonName = "Trakt",
        addonBaseUrl = "https://api.trakt.tv",
        catalogId = catalog.catalogId,
        catalogName = catalog.catalogName,
        type = catalog.type,
        rawType = catalog.type.toApiString("catalog"),
        items = catalog.items,
        isLoading = false,
        hasMore = false,
        supportsSkip = false
    )
}

private fun mdbListCustomCatalogRow(catalog: MDBListCustomCatalog): CatalogRow? {
    if (catalog.items.isEmpty()) return null
    return CatalogRow(
        addonId = MDBLIST_HOME_ADDON_ID,
        addonName = "MDBList",
        addonBaseUrl = "https://api.mdblist.com",
        catalogId = catalog.catalogId,
        catalogName = catalog.catalogName,
        type = catalog.type,
        rawType = catalog.type.toApiString("catalog"),
        items = catalog.items,
        isLoading = false,
        hasMore = false,
        supportsSkip = false
    )
}
