package com.nexio.tv.ui.screens.home.order

import com.nexio.tv.domain.model.HomeCatalogRail
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeCatalogRailContractTest {
    @Test
    fun `visible keys follow rails order and skip unknown unavailable and disabled definitions`() {
        val definitions = listOf(
            definition("tmdb_trending_movies", "TMDB Trending", enabled = true),
            definition("kitsu_trending_anime", "Kitsu Trending", enabled = true),
            definition("trakt_up_next", "Up Next", enabled = false)
        )
        val rails = listOf(
            rail("kitsu_trending_anime"),
            rail("unknown_key"),
            rail("trakt_up_next"),
            rail("tmdb_trending_movies")
        )

        assertEquals(
            listOf(HomeRailKey("kitsu_trending_anime"), HomeRailKey("tmdb_trending_movies")),
            visibleHomeRailKeysFromRails(rails, definitions)
        )
    }

    @Test
    fun `migration preserves effective visible order and titles from definitions`() {
        val definitions = listOf(
            definition("tmdb_trending_movies", "TMDB Trending", enabled = true),
            definition("kitsu_trending_anime", "Kitsu Trending", enabled = true)
        )
        val effective = EffectiveHomeRailOrder.Empty.copy(
            visibleKeys = listOf(HomeRailKey("kitsu_trending_anime"), HomeRailKey("tmdb_trending_movies")),
            disabledKeys = emptySet()
        )

        val rails = migrateHomeCatalogRailsFromEffectiveOrder(effective, definitions, nowMs = 1778544000000L)

        assertEquals(listOf("kitsu_trending_anime", "tmdb_trending_movies"), rails.map { it.key })
        assertEquals(listOf("Kitsu Trending", "TMDB Trending"), rails.map { it.title })
        assertEquals(listOf(1778544000000L, 1778544000000L), rails.map { it.addedAtMs })
    }

    private fun rail(key: String) = HomeCatalogRail(
        key = key,
        family = "",
        source = "",
        title = "",
        enabled = true
    )

    private fun definition(key: String, title: String, enabled: Boolean) = HomeRailDefinition(
        key = HomeRailKey(key),
        family = RailFamily.fromOrderKey(key),
        source = if (RailFamily.fromOrderKey(key) == RailFamily.ADDON) {
            RailSource.ADDON_CATALOG
        } else {
            RailSource.PROVIDER_PUBLIC
        },
        title = title,
        enabled = enabled,
        defaultSortKey = DefaultSortKey(RailFamily.fromOrderKey(key).familyRank, 0),
        publishPolicy = RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY
    )
}
