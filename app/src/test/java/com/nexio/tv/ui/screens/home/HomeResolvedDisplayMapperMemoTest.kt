package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.FirstPaintSource
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HydratedHomeFieldTrace
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.hydratedHomeDisplayHash
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class HomeResolvedDisplayMapperMemoTest {

    @Before
    fun setUp() {
        HomeResolvedDisplayMapper.clearCacheForTest()
    }

    @Test
    fun `same content returns the same instance across calls`() {
        val item = preview(id = "tmdb:550", title = "Fight Club")
        val overlay = overlay(itemKey = "movie:tmdb:550", title = "Fight Club Overlay")
        val rows = listOf(row(item))
        val overlays = mapOf("movie:tmdb:550" to overlay)

        val first = HomeResolvedDisplayMapper.toResolvedDisplayItems(
            rows = rows,
            overlaysByItemKey = overlays,
            nowMs = 10_000L
        ).single()
        val second = HomeResolvedDisplayMapper.toResolvedDisplayItems(
            rows = rows,
            overlaysByItemKey = overlays,
            nowMs = 99_999_999L
        ).single()

        assertSame(first, second)
    }

    @Test
    fun `different MetaPreview content returns a different instance`() {
        val original = preview(id = "tmdb:550", title = "Fight Club")
        val updated = preview(id = "tmdb:550", title = "Fight Club Updated")
        val overlays = emptyMap<String, HydratedHomeOverlay>()

        val first = HomeResolvedDisplayMapper.toResolvedDisplayItems(
            rows = listOf(row(original)),
            overlaysByItemKey = overlays,
            nowMs = 10_000L
        ).single()
        val second = HomeResolvedDisplayMapper.toResolvedDisplayItems(
            rows = listOf(row(updated)),
            overlaysByItemKey = overlays,
            nowMs = 10_000L
        ).single()

        assertNotSame(first, second)
    }

    @Test
    fun `different overlay returns a different instance`() {
        val item = preview(id = "tmdb:550", title = "Fight Club")
        val overlayA = overlay(itemKey = "movie:tmdb:550", title = "Overlay A")
        val overlayB = overlay(itemKey = "movie:tmdb:550", title = "Overlay B")

        val first = HomeResolvedDisplayMapper.toResolvedDisplayItems(
            rows = listOf(row(item)),
            overlaysByItemKey = mapOf("movie:tmdb:550" to overlayA),
            nowMs = 10_000L
        ).single()
        val second = HomeResolvedDisplayMapper.toResolvedDisplayItems(
            rows = listOf(row(item)),
            overlaysByItemKey = mapOf("movie:tmdb:550" to overlayB),
            nowMs = 10_000L
        ).single()

        assertNotSame(first, second)
    }

    @Test
    fun `entries not in active set are evicted across calls`() {
        val item = preview(id = "tmdb:550", title = "Fight Club")
        val overlays = emptyMap<String, HydratedHomeOverlay>()

        val first = HomeResolvedDisplayMapper.toResolvedDisplayItems(
            rows = listOf(row(item)),
            overlaysByItemKey = overlays,
            nowMs = 10_000L
        ).single()
        // Empty rows triggers retainAll(emptySet) — clears cache.
        HomeResolvedDisplayMapper.toResolvedDisplayItems(
            rows = emptyList(),
            overlaysByItemKey = overlays,
            nowMs = 10_000L
        )
        val third = HomeResolvedDisplayMapper.toResolvedDisplayItems(
            rows = listOf(row(item)),
            overlaysByItemKey = overlays,
            nowMs = 10_000L
        ).single()

        assertNotSame(first, third)
    }

    private fun preview(
        id: String,
        title: String
    ) = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        rawType = ContentType.MOVIE.toApiString(),
        name = title,
        poster = "legacy-poster",
        posterShape = PosterShape.POSTER,
        background = "legacy-backdrop",
        logo = null,
        description = "Overview",
        releaseInfo = "1999",
        runtime = "139m",
        imdbRating = null,
        ratingSource = TitleRatingSource.IMDB,
        genres = listOf("Drama"),
        trailerYtIds = emptyList(),
        artwork = ArtworkBundle(),
        firstPaintSource = FirstPaintSource.RAIL_PREVIEW,
        firstPaintStableIds = ProviderIds()
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

    private fun overlay(
        itemKey: String,
        title: String
    ): HydratedHomeOverlay {
        val fields = HomeDisplayMetadata(title = title)
        return HydratedHomeOverlay(
            overlayKey = "canonical:TMDB:550:type:MOVIE:lang:en:policy:1",
            itemKey = itemKey,
            canonicalProvider = ProviderId.TMDB,
            canonicalId = "550",
            imdbId = "tt0137523",
            contentType = ContentType.MOVIE,
            languageTag = "en",
            fields = fields,
            fieldTrace = listOf(HydratedHomeFieldTrace("POSTER", "TOP_POSTERS", "ARTWORK")),
            displayHash = fields.hydratedHomeDisplayHash(),
            updatedAtMs = 9_000L,
            staleAtMs = 20_000L,
            expiresAtMs = 30_000L
        )
    }
}
