package com.nexio.tv.ui.screens.home.order

import org.junit.Assert.assertEquals
import org.junit.Test

private fun publicDef(
    key: String,
    family: RailFamily,
    intra: Int = 0,
    enabled: Boolean = true,
): HomeRailDefinition = HomeRailDefinition(
    key = HomeRailKey(key),
    family = family,
    source = RailSource.PROVIDER_PUBLIC,
    title = key,
    enabled = enabled,
    defaultSortKey = DefaultSortKey(family.familyRank, intra),
    publishPolicy = RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY,
)

class HomeRailOrderReconcilerTest {
    private val reconciler = HomeRailOrderReconciler()

    @Test
    fun `saved order wins for known enabled keys`() {
        val saved = listOf(
            HomeRailKey("trakt:popular"),
            HomeRailKey("tmdb:popular"),
            HomeRailKey("simkl:trending"),
        )
        val live = listOf(
            publicDef("trakt:popular", RailFamily.TRAKT),
            publicDef("tmdb:popular", RailFamily.TMDB),
            publicDef("simkl:trending", RailFamily.SIMKL),
        )
        val effective = reconciler.reconcile(
            savedGlobalOrder = saved,
            disabledKeys = emptySet(),
            liveDefinitions = live,
        )
        assertEquals(saved, effective.visibleKeys)
    }
}
