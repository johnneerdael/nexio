package com.nexio.tv.ui.screens.home.order

data class DefaultSortKey(
    val familyRank: Int,
    val intraFamilyRank: Int,
)

data class HomeRailDefinition(
    val key: HomeRailKey,
    val family: RailFamily,
    val source: RailSource,
    val title: String,
    val enabled: Boolean,
    val defaultSortKey: DefaultSortKey,
    val publishPolicy: RailPublishPolicy,
)
