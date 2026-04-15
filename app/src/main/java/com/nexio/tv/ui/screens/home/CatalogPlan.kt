package com.nexio.tv.ui.screens.home

import com.nexio.tv.data.local.MDBListCatalogPreferences
import com.nexio.tv.data.local.SimklCatalogPreferences
import com.nexio.tv.data.local.TraktCatalogPreferences
import com.nexio.tv.data.repository.MDBListDiscoverySnapshot
import com.nexio.tv.data.repository.SimklDiscoverySnapshot
import com.nexio.tv.data.repository.TraktDiscoverySnapshot
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.CatalogRow

internal data class CatalogPlan(
    val expectedOrderKeys: List<String>,
    val publishableOrderKeys: List<String>,
    val descriptors: List<ConfiguredHomeCatalogDescriptor>
)

internal fun buildConfiguredCatalogPlan(
    addons: List<Addon>,
    disabledHomeCatalogKeys: Set<String>,
    availableAddonOrderKeys: Set<String>,
    traktPrefs: TraktCatalogPreferences,
    traktSnapshot: TraktDiscoverySnapshot,
    hasTraktUpNextItems: Boolean,
    simklPrefs: SimklCatalogPreferences,
    simklSnapshot: SimklDiscoverySnapshot,
    mdbPrefs: MDBListCatalogPreferences,
    mdbSnapshot: MDBListDiscoverySnapshot,
    existingRowsByOrderKey: Map<String, CatalogRow> = emptyMap()
): CatalogPlan {
    val expectedOrderKeys = buildExpectedConfiguredHomeOrderKeys(
        addons = addons,
        disabledHomeCatalogKeys = disabledHomeCatalogKeys,
        traktPrefs = traktPrefs,
        simklPrefs = simklPrefs,
        mdbPrefs = mdbPrefs,
        mdbSnapshot = mdbSnapshot
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
        mdbSnapshot = mdbSnapshot
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
        existingRowsByOrderKey = existingRowsByOrderKey
    )

    return CatalogPlan(
        expectedOrderKeys = expectedOrderKeys,
        publishableOrderKeys = publishableOrderKeys,
        descriptors = descriptors
    )
}
