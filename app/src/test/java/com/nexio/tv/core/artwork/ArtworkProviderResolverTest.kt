package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkProviderSelectionSettings
import com.nexio.tv.domain.model.ArtworkProviderSettings
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkProviderResolverTest {
    private val resolver = ArtworkProviderResolver(ArtworkProviderCapabilityResolver())

    private val rpdbSettings = ArtworkProviderSettings(
        rpdbApiKey = "test-key",
        selection = ArtworkProviderSelectionSettings(
            posterProvider = ArtworkProviderChoiceKey.RPDB
        )
    )
    private val defaultSettings = ArtworkProviderSettings(
        selection = ArtworkProviderSelectionSettings()
    )

    @Test fun `explicit RPDB with imdb returns RPDB`() {
        val result = resolver.resolve(
            artworkType = ArtworkType.POSTER,
            contentType = ContentType.MOVIE,
            isAnime = false,
            availableIds = ProviderIds(imdb = "tt0137523"),
            settings = rpdbSettings
        )
        assertEquals(
            ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
            result
        )
    }

    @Test fun `explicit RPDB without imdb falls through to addon default`() {
        val result = resolver.resolve(
            artworkType = ArtworkType.POSTER,
            contentType = ContentType.MOVIE,
            isAnime = false,
            availableIds = ProviderIds(),
            settings = rpdbSettings
        )
        assertEquals(
            ArtworkProviderId.RuntimeProvider(IntegrationProvider.ADDON),
            result
        )
    }

    @Test fun `DEFAULT for non-anime poster returns addon`() {
        val result = resolver.resolve(
            artworkType = ArtworkType.POSTER,
            contentType = ContentType.MOVIE,
            isAnime = false,
            availableIds = ProviderIds(tmdb = "550"),
            settings = defaultSettings
        )
        assertEquals(
            ArtworkProviderId.RuntimeProvider(IntegrationProvider.ADDON),
            result
        )
    }

    @Test fun `DEFAULT for anime poster returns addon`() {
        val result = resolver.resolve(
            artworkType = ArtworkType.POSTER,
            contentType = ContentType.SERIES,
            isAnime = true,
            availableIds = ProviderIds(kitsu = "1234"),
            settings = defaultSettings
        )
        assertEquals(
            ArtworkProviderId.RuntimeProvider(IntegrationProvider.ADDON),
            result
        )
    }

    @Test fun `RPDB for anime with imdb wins over default (override rule)`() {
        val result = resolver.resolve(
            artworkType = ArtworkType.POSTER,
            contentType = ContentType.SERIES,
            isAnime = true,
            availableIds = ProviderIds(imdb = "tt1", kitsu = "1234"),
            settings = rpdbSettings
        )
        assertEquals(
            ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
            result
        )
    }

    @Test fun `thumbnail always returns addon`() {
        val result = resolver.resolve(
            artworkType = ArtworkType.THUMBNAIL,
            contentType = ContentType.SERIES,
            isAnime = false,
            availableIds = ProviderIds(tmdb = "550"),
            settings = defaultSettings
        )
        assertEquals(
            ArtworkProviderId.RuntimeProvider(IntegrationProvider.ADDON),
            result
        )
    }
}
