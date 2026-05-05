package com.nexio.tv.ui.screens.home.order

import org.junit.Assert.assertEquals
import org.junit.Test

class RailOrderSpliceTest {
    private fun key(value: String) = HomeRailKey(value)
    private fun def(k: String, family: RailFamily, intra: Int = 0) =
        HomeRailDefinition(
            key = key(k),
            family = family,
            source = RailSource.PROVIDER_PUBLIC,
            title = k,
            enabled = true,
            defaultSortKey = DefaultSortKey(family.familyRank, intra),
            publishPolicy = RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY,
        )

    @Test
    fun `simple replace preserves cross-provider positions`() {
        val current = listOf("trakt:popular", "tmdb:popular", "simkl:trending", "tmdb:top-rated").map(::key)
        val live = listOf(
            def("trakt:popular", RailFamily.TRAKT),
            def("tmdb:popular", RailFamily.TMDB, 0),
            def("simkl:trending", RailFamily.SIMKL),
            def("tmdb:top-rated", RailFamily.TMDB, 1),
        )
        val result = spliceProviderKeys(
            current = current,
            family = RailFamily.TMDB,
            providerOrder = listOf(key("tmdb:top-rated"), key("tmdb:popular")),
            liveDefinitions = live,
        )
        assertEquals(
            listOf("trakt:popular", "tmdb:top-rated", "simkl:trending", "tmdb:popular").map(::key),
            result,
        )
    }

    @Test
    fun `new family key is appended after last existing family slot`() {
        val current = listOf("trakt:A", "tmdb:A", "simkl:B").map(::key)
        val live = listOf(
            def("trakt:A", RailFamily.TRAKT),
            def("tmdb:A", RailFamily.TMDB, 0),
            def("tmdb:B", RailFamily.TMDB, 1),
            def("tmdb:C", RailFamily.TMDB, 2),
            def("simkl:B", RailFamily.SIMKL),
        )
        val result = spliceProviderKeys(
            current = current,
            family = RailFamily.TMDB,
            providerOrder = listOf(key("tmdb:C"), key("tmdb:B"), key("tmdb:A")),
            liveDefinitions = live,
        )
        assertEquals(
            listOf("trakt:A", "tmdb:C", "simkl:B", "tmdb:B", "tmdb:A").map(::key),
            result,
        )
    }

    @Test
    fun `omitted existing family key is preserved as stable tail`() {
        val current = listOf("tmdb:A", "simkl:X", "tmdb:B", "trakt:Y", "tmdb:C").map(::key)
        val live = listOf(
            def("tmdb:A", RailFamily.TMDB, 0),
            def("simkl:X", RailFamily.SIMKL),
            def("tmdb:B", RailFamily.TMDB, 1),
            def("trakt:Y", RailFamily.TRAKT),
            def("tmdb:C", RailFamily.TMDB, 2),
        )
        val result = spliceProviderKeys(
            current = current,
            family = RailFamily.TMDB,
            providerOrder = listOf(key("tmdb:C"), key("tmdb:A")),
            liveDefinitions = live,
        )
        assertEquals(
            listOf("tmdb:C", "simkl:X", "tmdb:A", "trakt:Y", "tmdb:B").map(::key),
            result,
        )
    }

    @Test
    fun `family with no current slot is inserted at family-rank position`() {
        val current = listOf("trakt:A", "simkl:B").map(::key)
        val live = listOf(
            def("trakt:A", RailFamily.TRAKT),
            def("simkl:B", RailFamily.SIMKL),
            def("kitsu:trending", RailFamily.KITSU, 0),
            def("kitsu:popular", RailFamily.KITSU, 1),
        )
        val result = spliceProviderKeys(
            current = current,
            family = RailFamily.KITSU,
            providerOrder = listOf(key("kitsu:trending"), key("kitsu:popular")),
            liveDefinitions = live,
        )
        assertEquals(
            listOf("trakt:A", "simkl:B", "kitsu:trending", "kitsu:popular").map(::key),
            result,
        )
    }
}
