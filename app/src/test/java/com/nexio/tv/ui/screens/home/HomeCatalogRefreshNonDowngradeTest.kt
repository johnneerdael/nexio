package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDisplayHints
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.FirstPaintSource
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class HomeCatalogRefreshNonDowngradeTest {
    @Test
    fun `refresh does not overwrite persisted durable poster with raw rail URL`() {
        val rawAddonItem = MetaPreview(
            id = "tmdb:550",
            type = ContentType.MOVIE,
            name = "Fight Club (rail)",
            poster = "https://image.tmdb.org/raw.jpg",
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            genres = emptyList(),
            releaseInfo = null,
            runtime = null,
            imdbRating = null,
            firstPaintSource = FirstPaintSource.RAIL_PREVIEW,
            firstPaintSourceProvider = null,
            firstPaintStableIds = ProviderIds(tmdb = "550"),
            firstPaintRailSource = null,
            firstPaintSourceItemId = "tmdb:550",
            artwork = null
        )
        val resolvedPoster = ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey("artwork-decision:poster:imdb:tt0137523"),
            assetKey = null,
            imageType = ArtworkType.POSTER,
            selectedProvider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
            sourceRole = ArtworkSourceRole.PREMIUM,
            trace = ArtworkTrace.empty(),
            displayHints = ArtworkDisplayHints()
        )
        val persistedFallback = rawAddonItem.copy(
            poster = "nexio-artwork://decision/artwork-decision:poster:imdb:tt0137523",
            posterProviderTag = "rpdb",
            artwork = ArtworkBundle(poster = resolvedPoster)
        )

        val merged = HomeCatalogRefreshCoordinator.projectRailRowAgainstPersistedForTest(
            rawRailItem = rawAddonItem,
            persistedFallback = persistedFallback,
            externalMeta = null
        )

        assertEquals(true, merged.poster?.startsWith("nexio-artwork://"))
        assertNotNull(merged.artwork?.poster)
        assertEquals("rpdb", merged.posterProviderTag)
    }
}
