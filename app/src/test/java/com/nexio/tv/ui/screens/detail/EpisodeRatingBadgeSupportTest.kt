package com.nexio.tv.ui.screens.detail

import com.nexio.tv.R
import com.nexio.tv.core.artwork.ArtworkAssetKey
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDisplayHints
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `episode rating for thumbnail overlay suppresses local rating when artwork embeds rating`() {
        val rating = EpisodeRating(value = 8.4, source = EpisodeRatingSource.IMDB)

        val resolved = episodeRatingForThumbnailOverlay(
            episode = episodeWithThumbnailOverlay(embedsRatingOverlay = true),
            rating = rating
        )

        assertNull(resolved)
    }

    @Test
    fun `episode rating for thumbnail overlay preserves local rating when artwork does not embed rating`() {
        val rating = EpisodeRating(value = 8.4, source = EpisodeRatingSource.IMDB)

        val resolved = episodeRatingForThumbnailOverlay(
            episode = episodeWithThumbnailOverlay(embedsRatingOverlay = false),
            rating = rating
        )

        assertEquals(rating, resolved)
    }

    private fun episodeWithThumbnailOverlay(embedsRatingOverlay: Boolean): Video = Video(
        id = "episode-1",
        title = "Episode 1",
        released = null,
        thumbnail = "legacy-thumbnail",
        season = 1,
        episode = 1,
        overview = null,
        thumbnailArtwork = ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey("thumbnail-decision"),
            assetKey = ArtworkAssetKey("thumbnail-asset"),
            imageType = ArtworkType.THUMBNAIL,
            selectedProvider = null,
            sourceRole = ArtworkSourceRole.PREMIUM,
            trace = ArtworkTrace.empty(),
            displayHints = ArtworkDisplayHints(embedsRatingOverlay = embedsRatingOverlay)
        )
    )
}
