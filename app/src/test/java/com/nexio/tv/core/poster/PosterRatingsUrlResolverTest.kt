package com.nexio.tv.core.poster

import com.nexio.tv.core.image.PosterIntegrationRequest
import com.nexio.tv.core.integration.IntegrationProvider
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

        val request = PosterIntegrationRequest.fromModel(resolved!!)
        assertEquals(IntegrationProvider.RPDB, request?.provider)
        assertEquals("rpdb-key", request?.apiKey)
        assertEquals("imdb/poster-default/tt15940132.jpg", request?.path)
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

        val request = PosterIntegrationRequest.fromModel(resolved!!)
        assertEquals(IntegrationProvider.TOP_POSTERS, request?.provider)
        assertEquals("top-key", request?.apiKey)
        assertEquals("tmdb/poster/series-123.jpg", request?.path)
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

        val request = PosterIntegrationRequest.fromModel(resolved!!)
        assertEquals(IntegrationProvider.TOP_POSTERS, request?.provider)
        assertEquals("top-key", request?.apiKey)
        assertEquals("tvdb/poster/121361.jpg", request?.path)
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

        val request = PosterIntegrationRequest.fromModel(resolved!!)
        assertEquals(IntegrationProvider.RPDB, request?.provider)
        assertEquals("rpdb-key", request?.apiKey)
        assertEquals("tvdb/poster-default/121361.jpg", request?.path)
    }
}
