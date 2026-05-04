package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkProviderCapabilityResolverTest {
    private val resolver = ArtworkProviderCapabilityResolver()

    @Test
    fun `rpdb does not support raw kitsu ids`() {
        assertFalse(
            resolver.supports(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
                imageType = ArtworkType.POSTER,
                ids = ProviderIds(kitsu = "7442"),
                mediaKind = MetadataMediaKind.ANIME
            )
        )
    }

    @Test
    fun `top posters supports imdb poster candidates`() {
        assertTrue(
            resolver.supports(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
                imageType = ArtworkType.POSTER,
                ids = ProviderIds(imdb = "tt0137523"),
                mediaKind = MetadataMediaKind.MOVIE
            )
        )
    }
}
