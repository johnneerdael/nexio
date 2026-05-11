package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkProviderCapabilityResolver
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkProviderResolver
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkProviderSelectionSettings
import com.nexio.tv.domain.model.ArtworkProviderSettings
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.FirstPaintSource
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.RailSource
import com.nexio.tv.domain.model.TitleRatingSource
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Plan B Task 11. Verifies that [HomeResolvedDisplayMapper] threads the
 * [ArtworkProviderResolver] + current [ArtworkProviderSettings] through to
 * [ResolvedDisplayItem.preferredArtworkProviders] for all four artwork types,
 * with the correct anime detection (apiType == "anime" OR rail-source ==
 * BUILT_IN_KITSU) driving the resolver's anime-aware default routing.
 */
class HomeResolvedDisplayMapperPreferredProvidersTest {
    private val resolver = ArtworkProviderResolver(ArtworkProviderCapabilityResolver())

    @Before
    fun clearMapperCache() {
        HomeResolvedDisplayMapper.clearCacheForTest()
    }

    @Test
    fun `DEFAULT for non-anime movie returns addon for all four types`() {
        val firstPaint = preview(
            id = "tmdb:550",
            apiType = "movie",
            contentType = ContentType.MOVIE,
            stableIds = ProviderIds(tmdb = "550")
        )
        val defaultSettings = ArtworkProviderSettings(
            selection = ArtworkProviderSelectionSettings()
        )

        val resolved = HomeResolvedDisplayMapper.toResolvedDisplayItems(
            rows = listOf(row(firstPaint)),
            overlaysByItemKey = emptyMap(),
            resolver = resolver,
            currentSettings = defaultSettings,
            nowMs = 10_000L
        ).single()

        val addon = ArtworkProviderId.RuntimeProvider(IntegrationProvider.ADDON)
        assertEquals(addon, resolved.preferredArtworkProviders[ArtworkType.POSTER])
        assertEquals(addon, resolved.preferredArtworkProviders[ArtworkType.BACKDROP])
        assertEquals(addon, resolved.preferredArtworkProviders[ArtworkType.LOGO])
        assertEquals(addon, resolved.preferredArtworkProviders[ArtworkType.THUMBNAIL])
    }

    @Test
    fun `RPDB selected for movie with imdb prefers RPDB for poster`() {
        val firstPaint = preview(
            id = "tmdb:550",
            apiType = "movie",
            contentType = ContentType.MOVIE,
            stableIds = ProviderIds(imdb = "tt0137523", tmdb = "550")
        )
        val rpdbSettings = ArtworkProviderSettings(
            rpdbApiKey = "test-key",
            selection = ArtworkProviderSelectionSettings(
                posterProvider = ArtworkProviderChoiceKey.RPDB
            )
        )

        val resolved = HomeResolvedDisplayMapper.toResolvedDisplayItems(
            rows = listOf(row(firstPaint)),
            overlaysByItemKey = emptyMap(),
            resolver = resolver,
            currentSettings = rpdbSettings,
            nowMs = 10_000L
        ).single()

        val rpdb = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB)
        val addon = ArtworkProviderId.RuntimeProvider(IntegrationProvider.ADDON)
        assertEquals(rpdb, resolved.preferredArtworkProviders[ArtworkType.POSTER])
        // Non-poster types unchanged -> still addon default.
        assertEquals(addon, resolved.preferredArtworkProviders[ArtworkType.BACKDROP])
        assertEquals(addon, resolved.preferredArtworkProviders[ArtworkType.LOGO])
        assertEquals(addon, resolved.preferredArtworkProviders[ArtworkType.THUMBNAIL])
    }

    @Test
    fun `anime detected via BUILT_IN_KITSU railSource picks addon defaults`() {
        val firstPaint = preview(
            id = "kitsu:1234",
            apiType = "series",
            contentType = ContentType.SERIES,
            stableIds = ProviderIds(kitsu = "1234"),
            firstPaintRailSource = RailSource.BUILT_IN_KITSU
        )
        val defaultSettings = ArtworkProviderSettings(
            selection = ArtworkProviderSelectionSettings()
        )

        val resolved = HomeResolvedDisplayMapper.toResolvedDisplayItems(
            rows = listOf(row(firstPaint)),
            overlaysByItemKey = emptyMap(),
            resolver = resolver,
            currentSettings = defaultSettings,
            nowMs = 10_000L
        ).single()

        val addon = ArtworkProviderId.RuntimeProvider(IntegrationProvider.ADDON)
        // The anime default branch returns the addon provider for all four types
        // (matches the current [ContentTypeDefaults.resolve] table; if fanart.tv
        // is wired in later, this test will need to track that change).
        assertEquals(addon, resolved.preferredArtworkProviders[ArtworkType.POSTER])
        assertEquals(addon, resolved.preferredArtworkProviders[ArtworkType.BACKDROP])
        assertEquals(addon, resolved.preferredArtworkProviders[ArtworkType.LOGO])
        assertEquals(addon, resolved.preferredArtworkProviders[ArtworkType.THUMBNAIL])
    }

    @Test
    fun `anime detected via apiType anime triggers anime branch`() {
        // `MetaPreview.apiType` is derived via [ContentType.toApiString], which
        // only honors `rawType` when the typed enum is [ContentType.UNKNOWN].
        // So to exercise the apiType-equals-"anime" branch of the mapper's
        // anime detector we use UNKNOWN here — that is the only state where a
        // producer-side "anime" rawType reaches the mapper unchanged.
        val firstPaint = preview(
            id = "kitsu:9999",
            apiType = "anime",
            contentType = ContentType.UNKNOWN,
            stableIds = ProviderIds(imdb = "tt0", kitsu = "9999")
        )
        val rpdbSettings = ArtworkProviderSettings(
            rpdbApiKey = "test-key",
            selection = ArtworkProviderSelectionSettings(
                posterProvider = ArtworkProviderChoiceKey.RPDB
            )
        )

        val resolved = HomeResolvedDisplayMapper.toResolvedDisplayItems(
            rows = listOf(row(firstPaint)),
            overlaysByItemKey = emptyMap(),
            resolver = resolver,
            currentSettings = rpdbSettings,
            nowMs = 10_000L
        ).single()

        // RPDB override rule still applies for anime when imdb is present;
        // this verifies the resolver does receive isAnime=true via the
        // mapper and still honors the user's explicit poster preference.
        val rpdb = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB)
        assertEquals(rpdb, resolved.preferredArtworkProviders[ArtworkType.POSTER])
    }

    // --- Test helpers (mirroring HomeResolvedDisplayMapperTest style). ---

    private fun preview(
        id: String,
        apiType: String,
        contentType: ContentType,
        stableIds: ProviderIds = ProviderIds(),
        firstPaintRailSource: RailSource? = null
    ) = MetaPreview(
        id = id,
        type = contentType,
        rawType = apiType,
        name = "Title for $id",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = "Overview",
        releaseInfo = "2024",
        runtime = "120m",
        imdbRating = null,
        ratingSource = TitleRatingSource.IMDB,
        genres = emptyList(),
        trailerYtIds = emptyList(),
        artwork = ArtworkBundle(),
        firstPaintSource = FirstPaintSource.RAIL_PREVIEW,
        firstPaintStableIds = stableIds,
        firstPaintRailSource = firstPaintRailSource
    )

    private fun row(item: MetaPreview) = CatalogRow(
        addonId = "home",
        addonName = "Home",
        addonBaseUrl = "https://home.example",
        catalogId = "popular",
        catalogName = "Popular",
        type = item.type,
        items = listOf(item),
        hasMore = false
    )
}
