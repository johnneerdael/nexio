package com.nexio.tv.ui.screens.home.order

data class HomeRailOrderState(
    val orderedKeys: List<HomeRailKey>,
    val disabledKeys: Set<HomeRailKey>,
    val version: Long,
    val updatedAtMs: Long,
    val lastMutationSource: RailOrderMutationSource,
) {
    companion object {
        val Empty = HomeRailOrderState(
            orderedKeys = emptyList(),
            disabledKeys = emptySet(),
            version = 0L,
            updatedAtMs = 0L,
            lastMutationSource = RailOrderMutationSource.DEFAULT_BOOTSTRAP,
        )
    }
}
