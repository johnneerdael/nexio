package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.HomeCatalogRail
import com.nexio.tv.ui.screens.home.order.DefaultSortKey
import com.nexio.tv.ui.screens.home.order.EffectiveHomeRailOrder
import com.nexio.tv.ui.screens.home.order.HomeRailDefinition
import com.nexio.tv.ui.screens.home.order.HomeRailKey
import com.nexio.tv.ui.screens.home.order.RailFamily
import com.nexio.tv.ui.screens.home.order.RailPublishPolicy
import com.nexio.tv.ui.screens.home.order.RailSource
import com.nexio.tv.ui.screens.home.order.fromOrderKey
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeCatalogRailsProjectionTest {
    @Test
    fun `home rails override legacy effective order when present`() {
        val liveDefinitions = listOf(
            definition("tmdb_trending_movies", "TMDB Trending"),
            definition("kitsu_trending_anime", "Kitsu Trending")
        )
        val legacyEffective = EffectiveHomeRailOrder.Empty.copy(
            visibleKeys = listOf(HomeRailKey("tmdb_trending_movies"), HomeRailKey("kitsu_trending_anime"))
        )
        val rails = listOf(
            HomeCatalogRail(
                key = "kitsu_trending_anime",
                family = "kitsu",
                source = "provider_catalog",
                title = "Kitsu Trending"
            ),
            HomeCatalogRail(
                key = "tmdb_trending_movies",
                family = "tmdb",
                source = "provider_catalog",
                title = "TMDB Trending"
            )
        )

        val result = resolveEffectiveHomeOrderForCatalogRails(
            configuredRails = rails,
            liveDefinitions = liveDefinitions,
            legacyEffectiveOrder = legacyEffective
        )

        assertEquals(
            listOf(HomeRailKey("kitsu_trending_anime"), HomeRailKey("tmdb_trending_movies")),
            result.visibleKeys
        )
    }

    @Test
    fun `empty home rails clear legacy effective order`() {
        val liveDefinitions = listOf(
            definition("tmdb_trending_movies", "TMDB Trending"),
            definition("kitsu_trending_anime", "Kitsu Trending")
        )
        val legacyEffective = EffectiveHomeRailOrder.Empty.copy(
            visibleKeys = listOf(HomeRailKey("tmdb_trending_movies"), HomeRailKey("kitsu_trending_anime"))
        )

        val result = resolveEffectiveHomeOrderForCatalogRails(
            configuredRails = emptyList(),
            liveDefinitions = liveDefinitions,
            legacyEffectiveOrder = legacyEffective
        )

        assertEquals(emptyList<HomeRailKey>(), result.visibleKeys)
        assertEquals(
            listOf(HomeRailKey("tmdb_trending_movies"), HomeRailKey("kitsu_trending_anime")),
            result.prunedKeys
        )
    }

    private fun definition(key: String, title: String) = HomeRailDefinition(
        key = HomeRailKey(key),
        family = RailFamily.fromOrderKey(key),
        source = RailSource.PROVIDER_PUBLIC,
        title = title,
        enabled = true,
        defaultSortKey = DefaultSortKey(RailFamily.fromOrderKey(key).familyRank, 0),
        publishPolicy = RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY
    )
}
