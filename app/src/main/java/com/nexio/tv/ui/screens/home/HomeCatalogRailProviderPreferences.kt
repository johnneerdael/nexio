package com.nexio.tv.ui.screens.home

import com.nexio.tv.data.local.KitsuCatalogIds
import com.nexio.tv.data.local.KitsuCatalogPreferences
import com.nexio.tv.data.local.TmdbCatalogIds
import com.nexio.tv.data.local.TmdbCatalogPreferences
import com.nexio.tv.domain.model.HomeCatalogRail
import com.nexio.tv.domain.model.sanitizeHomeCatalogRails

internal fun TmdbCatalogPreferences.includingHomeCatalogRails(
    rails: List<HomeCatalogRail>
): TmdbCatalogPreferences {
    val railKeys = sanitizeHomeCatalogRails(rails)
        .asSequence()
        .filter { it.enabled }
        .map { it.key }
        .filter { it in TmdbCatalogIds.BUILT_IN_ORDER }
        .toSet()
    val sanitized = sanitized()
    if (railKeys.isEmpty()) return sanitized
    return sanitized.copy(
        enabledCatalogs = railKeys,
        catalogOrder = railKeys.toList() + sanitized.catalogOrder.filterNot { it in railKeys }
    ).sanitized()
}

internal fun KitsuCatalogPreferences.includingHomeCatalogRails(
    rails: List<HomeCatalogRail>
): KitsuCatalogPreferences {
    val railKeys = sanitizeHomeCatalogRails(rails)
        .asSequence()
        .filter { it.enabled }
        .map { it.key }
        .filter { it in KitsuCatalogIds.BUILT_IN_ORDER }
        .toSet()
    val sanitized = sanitized()
    if (railKeys.isEmpty()) return sanitized
    return sanitized.copy(
        enabledCatalogs = railKeys,
        catalogOrder = railKeys.toList() + sanitized.catalogOrder.filterNot { it in railKeys }
    ).sanitized()
}
