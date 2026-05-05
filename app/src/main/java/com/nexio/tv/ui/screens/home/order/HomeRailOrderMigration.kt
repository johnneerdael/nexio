package com.nexio.tv.ui.screens.home.order

internal fun migrateHomeRailOrderState(
    current: HomeRailOrderState,
    legacyOrder: List<HomeRailKey>,
    legacyDisabled: List<HomeRailKey>,
    liveDefinitions: List<HomeRailDefinition>,
    persistedSyntheticOrder: List<HomeRailKey>,
    nowMs: Long,
): HomeRailOrderState {
    val disabledMerged = if (current.disabledKeys.isEmpty() && legacyDisabled.isNotEmpty()) {
        legacyDisabled.toSet()
    } else {
        current.disabledKeys
    }

    if (current.orderedKeys.isNotEmpty()) {
        return current.copy(disabledKeys = disabledMerged)
    }

    return when {
        legacyOrder.isNotEmpty() -> current.copy(
            orderedKeys = legacyOrder,
            disabledKeys = disabledMerged,
            version = current.version + 1,
            updatedAtMs = nowMs,
            lastMutationSource = RailOrderMutationSource.MIGRATION,
        )
        liveDefinitions.isNotEmpty() -> current.copy(
            orderedKeys = liveDefinitions
                .sortedWith(compareBy({ it.defaultSortKey.familyRank }, { it.defaultSortKey.intraFamilyRank }))
                .map { it.key },
            disabledKeys = disabledMerged,
            version = current.version + 1,
            updatedAtMs = nowMs,
            lastMutationSource = RailOrderMutationSource.MIGRATION,
        )
        else -> current.copy(
            orderedKeys = persistedSyntheticOrder,
            disabledKeys = disabledMerged,
            version = current.version + 1,
            updatedAtMs = nowMs,
            lastMutationSource = RailOrderMutationSource.MIGRATION_SYNTHETIC_FALLBACK,
        )
    }
}

internal fun finalizeSyntheticFallback(
    current: HomeRailOrderState,
    liveDefinitions: List<HomeRailDefinition>,
    nowMs: Long,
): HomeRailOrderState {
    if (current.lastMutationSource != RailOrderMutationSource.MIGRATION_SYNTHETIC_FALLBACK) {
        return current
    }
    if (liveDefinitions.isEmpty()) return current
    return current.copy(
        orderedKeys = liveDefinitions
            .sortedWith(compareBy({ it.defaultSortKey.familyRank }, { it.defaultSortKey.intraFamilyRank }))
            .map { it.key },
        version = current.version + 1,
        updatedAtMs = nowMs,
        lastMutationSource = RailOrderMutationSource.MIGRATION,
    )
}
