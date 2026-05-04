package com.nexio.tv.ui.screens.home.order

data class EffectiveHomeRailOrder(
    val visibleKeys: List<HomeRailKey>,
    val disabledKeys: Set<HomeRailKey>,
    val unknownSavedKeys: List<HomeRailKey>,
    val newlyDiscoveredKeys: List<HomeRailKey>,
    val prunedKeys: List<HomeRailKey>,
) {
    companion object {
        val Empty = EffectiveHomeRailOrder(
            visibleKeys = emptyList(),
            disabledKeys = emptySet(),
            unknownSavedKeys = emptyList(),
            newlyDiscoveredKeys = emptyList(),
            prunedKeys = emptyList(),
        )
    }
}
