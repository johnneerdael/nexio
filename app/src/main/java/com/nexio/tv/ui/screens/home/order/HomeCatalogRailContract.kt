package com.nexio.tv.ui.screens.home.order

import com.nexio.tv.domain.model.HomeCatalogRail
import com.nexio.tv.domain.model.homeCatalogRailFamilyForKey
import com.nexio.tv.domain.model.homeCatalogRailSourceForFamily
import com.nexio.tv.domain.model.sanitizeHomeCatalogRails

internal fun visibleHomeRailKeysFromRails(
    rails: List<HomeCatalogRail>,
    liveDefinitions: List<HomeRailDefinition>
): List<HomeRailKey> {
    if (rails.isEmpty()) return emptyList()
    val enabledLiveByKey = liveDefinitions
        .asSequence()
        .filter { it.enabled }
        .associateBy { it.key.value }
    return sanitizeHomeCatalogRails(rails)
        .asSequence()
        .filter { it.enabled }
        .mapNotNull { rail -> enabledLiveByKey[rail.key]?.key }
        .toList()
}

internal fun migrateHomeCatalogRailsFromEffectiveOrder(
    effectiveOrder: EffectiveHomeRailOrder,
    liveDefinitions: List<HomeRailDefinition>,
    nowMs: Long
): List<HomeCatalogRail> {
    val definitionsByKey = liveDefinitions.associateBy { it.key }
    val migrated = effectiveOrder.visibleKeys.mapNotNull { key ->
        val definition = definitionsByKey[key]?.takeIf { it.enabled } ?: return@mapNotNull null
        homeCatalogRailFromDefinition(definition, nowMs)
    }
    return sanitizeHomeCatalogRails(migrated)
}

internal fun homeCatalogRailFromDefinition(
    definition: HomeRailDefinition,
    nowMs: Long? = null
): HomeCatalogRail {
    val key = definition.key.value
    val family = homeCatalogRailFamilyForKey(key)
    return HomeCatalogRail(
        key = key,
        family = family,
        source = homeCatalogRailSourceForFamily(family),
        title = definition.title,
        enabled = true,
        addedAtMs = nowMs
    )
}
