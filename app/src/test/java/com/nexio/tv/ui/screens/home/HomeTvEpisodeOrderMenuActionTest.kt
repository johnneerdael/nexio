package com.nexio.tv.ui.screens.home

import com.nexio.tv.R
import com.nexio.tv.data.repository.TvEpisodeOrderProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeTvEpisodeOrderMenuActionTest {

    @Test
    fun `resolveHomeTvEpisodeOrderMenuAction returns tvdb label for tmdb default`() {
        val action = resolveHomeTvEpisodeOrderMenuAction(
            provider = TvEpisodeOrderProvider.TMDB_DEFAULT,
            tmdbTvOrderKey = "tmdb:tv:90802"
        )

        assertEquals(R.string.detail_use_tvdb_season_numbering, action?.labelRes)
        assertEquals("tmdb:tv:90802", action?.tmdbTvOrderKey)
    }

    @Test
    fun `resolveHomeTvEpisodeOrderMenuAction returns tmdb label for tvdb override`() {
        val action = resolveHomeTvEpisodeOrderMenuAction(
            provider = TvEpisodeOrderProvider.TVDB_DEFAULT,
            tmdbTvOrderKey = "90802"
        )

        assertEquals(R.string.detail_use_tmdb_season_numbering, action?.labelRes)
        assertEquals("tmdb:tv:90802", action?.tmdbTvOrderKey)
    }

    @Test
    fun `resolveHomeTvEpisodeOrderMenuAction is unavailable only without tmdb tv id`() {
        assertNull(
            resolveHomeTvEpisodeOrderMenuAction(
                provider = TvEpisodeOrderProvider.TMDB_DEFAULT,
                tmdbTvOrderKey = null
            )
        )
    }
}
