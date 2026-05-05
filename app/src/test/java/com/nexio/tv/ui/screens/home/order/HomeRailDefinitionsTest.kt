package com.nexio.tv.ui.screens.home.order

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
}
