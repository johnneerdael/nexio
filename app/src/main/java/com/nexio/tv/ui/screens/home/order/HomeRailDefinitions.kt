package com.nexio.tv.ui.screens.home.order

import com.nexio.tv.ui.screens.home.CatalogPlan
import com.nexio.tv.ui.screens.home.ConfiguredHomeCatalogDescriptor

internal fun RailFamily.Companion.fromOrderKey(orderKey: String): RailFamily = when {
    orderKey.startsWith("trakt_")   -> RailFamily.TRAKT
    orderKey.startsWith("simkl_")   -> RailFamily.SIMKL
    orderKey.startsWith("mdblist_") -> RailFamily.MDBLIST
    orderKey.startsWith("tmdb_")    -> RailFamily.TMDB
    orderKey.startsWith("kitsu_")   -> RailFamily.KITSU
    else                            -> RailFamily.ADDON
}

internal fun CatalogPlan.toHomeRailDefinitions(): List<HomeRailDefinition> {
    val perFamilyIndex = mutableMapOf<RailFamily, Int>()
    return descriptors.map { descriptor ->
        val family = RailFamily.fromOrderKey(descriptor.orderKey)
        val intra = perFamilyIndex.getOrDefault(family, 0)
        perFamilyIndex[family] = intra + 1
        HomeRailDefinition(
            key = HomeRailKey(descriptor.orderKey),
            family = family,
            source = inferSource(family),
            title = descriptor.catalogName,
            enabled = descriptor.enabled,
            defaultSortKey = DefaultSortKey(family.familyRank, intra),
            publishPolicy = RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY,
        )
    }
}

private fun inferSource(family: RailFamily): RailSource =
    if (family == RailFamily.ADDON) RailSource.ADDON_CATALOG else RailSource.PROVIDER_PUBLIC
