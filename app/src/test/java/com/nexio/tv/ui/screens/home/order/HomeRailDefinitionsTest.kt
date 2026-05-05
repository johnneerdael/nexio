package com.nexio.tv.ui.screens.home.order

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.ui.screens.home.CatalogPlan
import com.nexio.tv.ui.screens.home.ConfiguredHomeCatalogDescriptor
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRailDefinitionsTest {
    @Test
    fun `fromOrderKey resolves provider prefix to the correct family`() {
        assertEquals(RailFamily.TRAKT,   RailFamily.fromOrderKey("trakt_popular_movies"))
        assertEquals(RailFamily.SIMKL,   RailFamily.fromOrderKey("simkl_tv_trending_week"))
        assertEquals(RailFamily.MDBLIST, RailFamily.fromOrderKey("mdblist_pending_xyz"))
        assertEquals(RailFamily.TMDB,    RailFamily.fromOrderKey("tmdb_popular_movies"))
        assertEquals(RailFamily.KITSU,   RailFamily.fromOrderKey("kitsu_trending_anime"))
        assertEquals(RailFamily.ADDON,   RailFamily.fromOrderKey("cinemeta_movie_top"))
        assertEquals(RailFamily.ADDON,   RailFamily.fromOrderKey("torrentio_series_top"))
    }

    @Test
    fun `fromOrderKey defaults unknown prefixes to ADDON`() {
        assertEquals(RailFamily.ADDON, RailFamily.fromOrderKey("unknown_something"))
        assertEquals(RailFamily.ADDON, RailFamily.fromOrderKey("randomtext"))
    }

    @Test
    fun `toHomeRailDefinitions reflects descriptor enabled flag`() {
        // Build two ConfiguredHomeCatalogDescriptor instances — one enabled, one disabled —
        // and confirm toHomeRailDefinitions threads the value through to HomeRailDefinition.
        val enabledDescriptor = ConfiguredHomeCatalogDescriptor(
            orderKey = "tmdb_popular_movies",
            addonId = "tmdb",
            addonName = "TMDB",
            addonBaseUrl = "https://api.themoviedb.org",
            catalogId = "popular",
            catalogName = "Popular Movies",
            type = ContentType.MOVIE,
            enabled = true,
        )
        val disabledDescriptor = ConfiguredHomeCatalogDescriptor(
            orderKey = "tmdb_top_rated_movies",
            addonId = "tmdb",
            addonName = "TMDB",
            addonBaseUrl = "https://api.themoviedb.org",
            catalogId = "top_rated",
            catalogName = "Top Rated Movies",
            type = ContentType.MOVIE,
            enabled = false,
        )

        val plan = CatalogPlan(
            expectedOrderKeys = listOf("tmdb_popular_movies", "tmdb_top_rated_movies"),
            publishableOrderKeys = listOf("tmdb_popular_movies", "tmdb_top_rated_movies"),
            descriptors = listOf(enabledDescriptor, disabledDescriptor),
            rails = emptyList(),
        )

        val defs = plan.toHomeRailDefinitions()
        assertEquals(2, defs.size)
        assertEquals(true, defs[0].enabled)
        assertEquals(false, defs[1].enabled)
    }
}
