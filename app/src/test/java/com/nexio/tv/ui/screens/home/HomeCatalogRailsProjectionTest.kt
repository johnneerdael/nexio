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

    @Test
    fun `unobserved home rails keep legacy effective order`() {
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
            legacyEffectiveOrder = legacyEffective,
            configuredRailsObserved = false
        )

        assertEquals(legacyEffective, result)
    }

    @Test
    fun `restore expected keys use configured visible rails over extra publishable keys`() {
        val liveDefinitions = listOf(
            definition("trakt_trending_movies", "Trakt Trending Movies"),
            definition("org.nexio.tekenfilms_movie_tekenfilms_disney_classics_4k", "Disney Classics (4K)"),
            definition("org.nexio.tekenfilms_movie_tekenfilms_pixar", "Pixar"),
            definition("app.torbox.stremio_movie_user-movies", "User Movies")
        )
        val configuredRails = listOf(
            HomeCatalogRail(
                key = "trakt_trending_movies",
                family = "trakt",
                source = "provider_catalog",
                title = "Trakt Trending Movies"
            ),
            HomeCatalogRail(
                key = "org.nexio.tekenfilms_movie_tekenfilms_disney_classics_4k",
                family = "addon",
                source = "addon_catalog",
                title = "Disney Classics (4K)"
            )
        )

        val expected = expectedHomeSnapshotOrderKeysForRestore(
            configuredRails = configuredRails,
            liveDefinitions = liveDefinitions,
            publishableOrderKeys = listOf(
                "trakt_trending_movies",
                "org.nexio.tekenfilms_movie_tekenfilms_disney_classics_4k",
                "org.nexio.tekenfilms_movie_tekenfilms_pixar",
                "app.torbox.stremio_movie_user-movies"
            )
        )

        assertEquals(
            listOf(
                "trakt_trending_movies",
                "org.nexio.tekenfilms_movie_tekenfilms_disney_classics_4k"
            ),
            expected
        )
    }

    @Test
    fun `restore expected keys ignore configured rails that are not publishable yet`() {
        val liveDefinitions = listOf(
            definition("trakt_trending_movies", "Trakt Trending Movies"),
            definition("trakt_trending_shows", "Trakt Trending Shows"),
            definition("tmdb_trending_movies", "TMDB Trending Movies"),
            definition("tmdb_popular_movies", "TMDB Popular Movies")
        )
        val configuredRails = listOf(
            HomeCatalogRail(
                key = "trakt_trending_movies",
                family = "trakt",
                source = "provider_catalog",
                title = "Trakt Trending Movies"
            ),
            HomeCatalogRail(
                key = "trakt_trending_shows",
                family = "trakt",
                source = "provider_catalog",
                title = "Trakt Trending Shows"
            ),
            HomeCatalogRail(
                key = "tmdb_trending_movies",
                family = "tmdb",
                source = "provider_catalog",
                title = "TMDB Trending Movies"
            ),
            HomeCatalogRail(
                key = "tmdb_popular_movies",
                family = "tmdb",
                source = "provider_catalog",
                title = "TMDB Popular Movies"
            )
        )

        val expected = expectedHomeSnapshotOrderKeysForRestore(
            configuredRails = configuredRails,
            liveDefinitions = liveDefinitions,
            publishableOrderKeys = listOf("tmdb_trending_movies", "tmdb_popular_movies")
        )

        assertEquals(listOf("tmdb_trending_movies", "tmdb_popular_movies"), expected)
    }

    @Test
    fun `restore expected keys fall back to publishable keys before home rails are observed`() {
        val expected = expectedHomeSnapshotOrderKeysForRestore(
            configuredRails = emptyList(),
            liveDefinitions = listOf(definition("trakt_trending_movies", "Trakt Trending Movies")),
            publishableOrderKeys = listOf("trakt_trending_movies")
        )

        assertEquals(listOf("trakt_trending_movies"), expected)
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
