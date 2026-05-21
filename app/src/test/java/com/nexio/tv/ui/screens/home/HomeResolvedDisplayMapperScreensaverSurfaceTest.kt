package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.metadata.router.resolver.TrailerAvailability
import com.nexio.tv.core.metadata.router.resolver.TrailerPlaybackRef
import com.nexio.tv.core.metadata.router.resolver.TrailerResolution
import com.nexio.tv.core.metadata.router.resolver.TrailerResolveRequest
import com.nexio.tv.core.metadata.router.resolver.TrailerSurface
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.FirstPaintSource
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.TitleRatingSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plan: Bug A — Task A1.
 *
 * Verifies that `HomeResolvedDisplayMapper.toResolvedDisplayItems(...)` plumbs
 * an explicit `surface: TrailerSurface` parameter all the way into the
 * `TrailerResolveRequest` passed to the `resolveTrailer` lambda.
 *
 * Previously the mapper hardcoded `surface = TrailerSurface.HOME` regardless
 * of caller, which mislabeled screensaver-surface trailer resolutions in trace
 * events and made the source of the empty-trailer-state bug invisible in
 * diagnostics.
 */
class HomeResolvedDisplayMapperScreensaverSurfaceTest {

    @Test
    fun `mapper threads SCREENSAVER surface into TrailerResolveRequest`() {
        var capturedSurface: TrailerSurface? = null
        val resolveTrailer: (TrailerResolveRequest) -> TrailerResolution = { request ->
            capturedSurface = request.surface
            TrailerResolution(
                availability = TrailerAvailability(available = false, reason = "test"),
                candidates = emptyList(),
                selected = null,
                trace = emptyList()
            )
        }
        HomeResolvedDisplayMapper.clearCacheForTest()

        HomeResolvedDisplayMapper.toResolvedDisplayItems(
            rows = listOf(row(samplePreview())),
            overlaysByItemKey = emptyMap(),
            nowMs = 10_000L,
            resolveTrailer = resolveTrailer,
            surface = TrailerSurface.SCREENSAVER
        )

        assertEquals(TrailerSurface.SCREENSAVER, capturedSurface)
    }

    @Test
    fun `mapper defaults to HOME surface for backward compat with existing call sites`() {
        var capturedSurface: TrailerSurface? = null
        val resolveTrailer: (TrailerResolveRequest) -> TrailerResolution = { request ->
            capturedSurface = request.surface
            TrailerResolution(
                availability = TrailerAvailability(available = false, reason = "test"),
                candidates = emptyList(),
                selected = null,
                trace = emptyList()
            )
        }
        HomeResolvedDisplayMapper.clearCacheForTest()

        HomeResolvedDisplayMapper.toResolvedDisplayItems(
            rows = listOf(row(samplePreview())),
            overlaysByItemKey = emptyMap(),
            nowMs = 10_000L,
            resolveTrailer = resolveTrailer
            // surface omitted — defaults to HOME
        )

        assertEquals(TrailerSurface.HOME, capturedSurface)
    }

    @Test
    fun `selected playback ref surface label matches the requested surface`() {
        val resolveTrailer: (TrailerResolveRequest) -> TrailerResolution = { _ ->
            TrailerResolution(
                availability = TrailerAvailability(available = true, reason = "fallback"),
                candidates = listOf(TrailerPlaybackRef.YouTubeId("abc123")),
                selected = TrailerPlaybackRef.YouTubeId("abc123"),
                trace = emptyList()
            )
        }
        HomeResolvedDisplayMapper.clearCacheForTest()

        val items = HomeResolvedDisplayMapper.toResolvedDisplayItems(
            rows = listOf(row(samplePreview())),
            overlaysByItemKey = emptyMap(),
            nowMs = 10_000L,
            resolveTrailer = resolveTrailer,
            surface = TrailerSurface.SCREENSAVER
        )

        assertEquals(1, items.size)
        assertEquals("screensaver", items.single().trailer.surface)
    }

    // ---- test fixtures (mirrored from HomeResolvedDisplayMapperTest's private helpers) ----

    private fun samplePreview(
        id: String = "tmdb:550",
        title: String = "Fight Club",
        contentType: ContentType = ContentType.MOVIE
    ): MetaPreview = MetaPreview(
        id = id,
        type = contentType,
        rawType = contentType.toApiString(),
        name = title,
        poster = "legacy-poster",
        posterShape = PosterShape.POSTER,
        background = "legacy-backdrop",
        logo = null,
        description = "sample",
        releaseInfo = "1999",
        runtime = "139m",
        imdbRating = 8.8f,
        ratingSource = TitleRatingSource.IMDB,
        genres = listOf("Drama"),
        trailerYtIds = emptyList(),
        artwork = ArtworkBundle(),
        firstPaintSource = FirstPaintSource.RAIL_PREVIEW,
        firstPaintStableIds = ProviderIds(imdb = "tt0137523", tmdb = "550")
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
