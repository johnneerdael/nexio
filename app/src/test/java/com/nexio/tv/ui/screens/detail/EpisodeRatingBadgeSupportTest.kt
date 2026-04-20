package com.nexio.tv.ui.screens.detail

import com.nexio.tv.R
import com.nexio.tv.domain.model.TitleRatingSource
import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeRatingBadgeSupportTest {

    @Test
    fun `episode badge uses imdb branding for custom imdb ratings`() {
        val badge = episodeRatingBadge(EpisodeRatingSource.IMDB)

        assertEquals(R.raw.imdb_logo_2016, badge.logoRes)
        assertEquals("IMDb", badge.contentDescription)
    }

    @Test
    fun `episode badge uses imdb branding for omdb ratings`() {
        val badge = episodeRatingBadge(EpisodeRatingSource.OMDB)

        assertEquals(R.raw.imdb_logo_2016, badge.logoRes)
        assertEquals("IMDb", badge.contentDescription)
    }

    @Test
    fun `episode badge uses tmdb branding for tmdb fallback ratings`() {
        val badge = episodeRatingBadge(EpisodeRatingSource.TMDB)

        assertEquals(R.raw.mdblist_tmdb, badge.logoRes)
        assertEquals("TMDB", badge.contentDescription)
    }

    @Test
    fun `title rating badge uses tmdb branding for tmdb source`() {
        val badge = titleRatingBadge(TitleRatingSource.TMDB)

        assertEquals(R.raw.mdblist_tmdb, badge.logoRes)
        assertEquals("TMDB", badge.contentDescription)
    }

    @Test
    fun `title rating badge uses imdb branding for imdb source`() {
        val badge = titleRatingBadge(TitleRatingSource.IMDB)

        assertEquals(R.raw.imdb_logo_2016, badge.logoRes)
        assertEquals("IMDb", badge.contentDescription)
    }
}
