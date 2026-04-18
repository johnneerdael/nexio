package com.nexio.tv.core.recommendations

import android.net.Uri
import androidx.tvprovider.media.tv.TvContractCompat
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidTvProgramPresentationTest {

    @Test
    fun `catalog feeds prefer portrait poster art over landscape backdrops`() {
        val presentation = AndroidTvProgramPresentation.from(
            item = preview(
                posterShape = PosterShape.LANDSCAPE,
                poster = "https://images.example/poster.jpg",
                background = "https://images.example/backdrop.jpg"
            ),
            feedKey = "trakt_movie_popular"
        )

        assertEquals(Uri.parse("https://images.example/poster.jpg"), presentation.posterArtUri)
        assertEquals(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3, presentation.posterArtAspectRatio)
        assertNull(presentation.thumbnailUri)
    }

    @Test
    fun `continue watching keeps landscape backdrop presentation`() {
        val presentation = AndroidTvProgramPresentation.from(
            item = preview(
                posterShape = PosterShape.LANDSCAPE,
                poster = "https://images.example/poster.jpg",
                background = "https://images.example/backdrop.jpg"
            ),
            feedKey = AndroidTvFeedCatalogService.CONTINUE_WATCHING_FEED_KEY
        )

        assertEquals(Uri.parse("https://images.example/backdrop.jpg"), presentation.posterArtUri)
        assertEquals(Uri.parse("https://images.example/backdrop.jpg"), presentation.thumbnailUri)
        assertEquals(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9, presentation.posterArtAspectRatio)
    }

    @Test
    fun `metadata includes genres and imdb rating as percentage review rating`() {
        val presentation = AndroidTvProgramPresentation.from(
            item = preview(
                imdbRating = 8.2f,
                genres = listOf("Action", "Drama")
            ),
            feedKey = "cinemeta_movie_popular"
        )

        assertEquals("Action, Drama", presentation.genre)
        assertEquals(TvContractCompat.Programs.REVIEW_RATING_STYLE_PERCENTAGE, presentation.reviewRatingStyle)
        assertEquals("82", presentation.reviewRating)
    }

    @Test
    fun `catalog feeds use local cached poster uri when available`() {
        val localPosterUri = Uri.parse("content://com.nexio.tv.channelart/poster/local.jpg")

        val presentation = AndroidTvProgramPresentation.from(
            item = preview(
                posterShape = PosterShape.LANDSCAPE,
                poster = "https://api.ratingposterdb.com/key/imdb/poster-default/tt123.jpg",
                background = "https://images.example/backdrop.jpg"
            ),
            feedKey = "trakt_movie_popular",
            localPosterArtUri = localPosterUri
        )

        assertEquals(localPosterUri, presentation.posterArtUri)
        assertEquals(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3, presentation.posterArtAspectRatio)
        assertNull(presentation.thumbnailUri)
    }

    @Test
    fun `continue watching keeps landscape artwork even when local poster is available`() {
        val localPosterUri = Uri.parse("content://com.nexio.tv.channelart/poster/local.jpg")

        val presentation = AndroidTvProgramPresentation.from(
            item = preview(
                posterShape = PosterShape.LANDSCAPE,
                poster = "https://api.ratingposterdb.com/key/imdb/poster-default/tt123.jpg",
                background = "https://images.example/backdrop.jpg"
            ),
            feedKey = AndroidTvFeedCatalogService.CONTINUE_WATCHING_FEED_KEY,
            localPosterArtUri = localPosterUri
        )

        assertEquals(Uri.parse("https://images.example/backdrop.jpg"), presentation.posterArtUri)
        assertEquals(Uri.parse("https://images.example/backdrop.jpg"), presentation.thumbnailUri)
        assertEquals(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9, presentation.posterArtAspectRatio)
    }

    private fun preview(
        posterShape: PosterShape = PosterShape.POSTER,
        poster: String? = "https://images.example/poster.jpg",
        background: String? = "https://images.example/backdrop.jpg",
        imdbRating: Float? = null,
        genres: List<String> = emptyList()
    ) = MetaPreview(
        id = "tt123",
        type = ContentType.MOVIE,
        name = "Example",
        poster = poster,
        posterShape = posterShape,
        background = background,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = imdbRating,
        genres = genres
    )
}
