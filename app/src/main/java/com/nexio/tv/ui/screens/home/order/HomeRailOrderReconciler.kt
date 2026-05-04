package com.nexio.tv.ui.screens.home.order

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeRailOrderReconciler @Inject constructor() {
    fun reconcile(
        savedGlobalOrder: List<HomeRailKey>,
        disabledKeys: Set<HomeRailKey>,
        liveDefinitions: List<HomeRailDefinition>,
    ): EffectiveHomeRailOrder {
        val liveByKey = liveDefinitions.associateBy { it.key }
        val enabledLive = liveDefinitions.filter { it.enabled && it.key !in disabledKeys }
        val enabledKeys = enabledLive.map { it.key }.toSet()

        val savedKnownEnabled = savedGlobalOrder.filter { it in enabledKeys }
        val missingEnabled = enabledLive
            .filter { it.key !in savedKnownEnabled }
            .sortedWith(
                compareBy(
                    { it.defaultSortKey.familyRank },
                    { it.defaultSortKey.intraFamilyRank },
                )
            )
            .map { it.key }

        val liveKeysSet = liveByKey.keys
        val unknownSaved = savedGlobalOrder.filter { it !in liveKeysSet }
        val pruned = savedGlobalOrder.filter { it in liveKeysSet && it !in enabledKeys }

        return EffectiveHomeRailOrder(
            visibleKeys = savedKnownEnabled + missingEnabled,
            disabledKeys = disabledKeys,
            unknownSavedKeys = unknownSaved,
            newlyDiscoveredKeys = missingEnabled,
            prunedKeys = pruned,
        )
    }
}
