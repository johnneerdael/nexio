package com.nexio.tv.core.poster

import com.nexio.tv.data.local.PosterRatingsSettingsDataStore
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.PosterRatingsProvider
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PosterRatingsUrlResolverTest {

    private val resolver = PosterRatingsUrlResolver(
        settingsDataStore = mockk<PosterRatingsSettingsDataStore>()
    )

    @Test
    fun `rpdb poster url is built from imdb id when source poster is blank`() {
        val resolved = resolver.resolvePosterUrl(
            originalPosterUrl = null,
            contentId = "tt15940132",
            contentType = ContentType.MOVIE,
            activeProvider = PosterRatingsUrlResolver.ActiveProvider(
                provider = PosterRatingsProvider.RPDB,
                apiKey = "rpdb-key"
            )
        )

        assertEquals(
            "https://api.ratingposterdb.com/rpdb-key/imdb/poster-default/tt15940132.jpg",
            resolved
        )
    }

    @Test
    fun `top posters url is built from tmdb id when source poster is blank`() {
        val resolved = resolver.resolvePosterUrl(
            originalPosterUrl = "",
            contentId = "tmdb:123",
            contentType = ContentType.SERIES,
            activeProvider = PosterRatingsUrlResolver.ActiveProvider(
                provider = PosterRatingsProvider.TOP_POSTERS,
                apiKey = "top-key"
            )
        )

        assertEquals(
            "https://api.top-posters.com/top-key/tmdb/poster/series-123.jpg",
            resolved
        )
    }

    @Test
    fun `top posters url is built from tvdb id when source poster is blank`() {
        val resolved = resolver.resolvePosterUrl(
            originalPosterUrl = null,
            contentId = "tvdb:121361",
            contentType = ContentType.SERIES,
            activeProvider = PosterRatingsUrlResolver.ActiveProvider(
                provider = PosterRatingsProvider.TOP_POSTERS,
                apiKey = "top-key"
            )
        )

        assertEquals(
            "https://api.top-posters.com/top-key/tvdb/poster/121361.jpg",
            resolved
        )
    }

    @Test
    fun `rpdb poster url is built from tvdb id`() {
        val resolved = resolver.resolvePosterUrl(
            originalPosterUrl = "https://tvdb.example/poster.jpg",
            contentId = "tvdb:121361",
            contentType = ContentType.SERIES,
            activeProvider = PosterRatingsUrlResolver.ActiveProvider(
                provider = PosterRatingsProvider.RPDB,
                apiKey = "rpdb-key"
            )
        )

        assertEquals(
            "https://api.ratingposterdb.com/rpdb-key/tvdb/poster-default/121361.jpg",
            resolved
        )
    }
}
