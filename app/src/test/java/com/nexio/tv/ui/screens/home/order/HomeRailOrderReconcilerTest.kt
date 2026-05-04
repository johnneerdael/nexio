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

    @Test
    fun `new live keys are appended by family-rank then intra-family-rank`() {
        val saved = listOf(HomeRailKey("tmdb:popular"))
        val live = listOf(
            publicDef("tmdb:popular", RailFamily.TMDB, intra = 0),
            publicDef("simkl:trending", RailFamily.SIMKL, intra = 0),
            publicDef("tmdb:top-rated", RailFamily.TMDB, intra = 1),
        )
        val effective = reconciler.reconcile(
            savedGlobalOrder = saved,
            disabledKeys = emptySet(),
            liveDefinitions = live,
        )
        assertEquals(
            listOf(
                HomeRailKey("tmdb:popular"),       // saved
                HomeRailKey("simkl:trending"),     // SIMKL family rank 1 < TMDB rank 3
                HomeRailKey("tmdb:top-rated"),     // TMDB family rank 3 with higher intra rank
            ),
            effective.visibleKeys,
        )
        assertEquals(
            listOf(HomeRailKey("simkl:trending"), HomeRailKey("tmdb:top-rated")),
            effective.newlyDiscoveredKeys,
        )
    }
}
