package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.FirstPaintSource
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HydratedHomeFieldTrace
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.hydratedHomeDisplayHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class HomeResolvedDisplayMapperTest {
    @Test
    fun `mapper uses final home item and overlay trace without applying overlays again`() {
        val finalItem = preview(
            id = "tmdb:550",
            title = "Final Home Title",
            overview = "Final Home Overview",
            rating = 8.8f,
            artwork = ArtworkBundle(backdrop = artworkRef("backdrop-550"))
        )
        val overlay = overlay(
            itemKey = "movie:tmdb:550",
            fields = HomeDisplayMetadata(
                title = "Overlay Title That Must Not Be Reapplied",
                description = "Overlay Overview That Must Not Be Reapplied",
                imdbRating = 8.8f,
                ratingSource = TitleRatingSource.IMDB,
                posterProviderTag = "top_posters",
                artwork = ArtworkBundle(backdrop = artworkRef("backdrop-550"))
            )
        )

        val resolved = HomeResolvedDisplayMapper.toResolvedDisplayItems(
            rows = listOf(row(finalItem)),
            overlaysByItemKey = mapOf("movie:tmdb:550" to overlay),
            nowMs = 10_000L
        ).single()

        assertEquals("Final Home Title", resolved.display.title)
        assertEquals("Final Home Overview", resolved.display.overview)
        assertEquals("tt0137523", resolved.imdbId)
        assertEquals("550", resolved.stableIds.tmdb)
        assertEquals(8.8, resolved.rating?.value ?: 0.0, 0.0)
        assertNotNull(resolved.artwork.backdrop)
        assertEquals("top_posters", resolved.artwork.backdrop?.trace?.selectedProvider)
        assertEquals(HydrationState.CANONICAL_READY, resolved.hydrationState)
        assertEquals("POSTER", resolved.sourceTrace.single().field)
    }

    private fun preview(
        id: String,
        title: String,
        overview: String,
        rating: Float?,
        artwork: ArtworkBundle
    ) = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        rawType = "movie",
        name = title,
        poster = "legacy-poster",
        posterShape = PosterShape.POSTER,
        background = "legacy-backdrop",
        logo = null,
        description = overview,
        releaseInfo = "1999",
        runtime = "139m",
        imdbRating = rating,
        ratingSource = TitleRatingSource.IMDB,
        genres = listOf("Drama"),
        artwork = artwork,
        firstPaintSource = FirstPaintSource.RAIL_PREVIEW
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
        fields: HomeDisplayMetadata
    ) = HydratedHomeOverlay(
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

    private fun artworkRef(key: String) = ArtworkDisplayRef.RuntimeAsset(
        decisionKey = ArtworkDecisionKey(key),
        assetKey = null,
        imageType = ArtworkType.BACKDROP,
        selectedProvider = null,
        sourceRole = ArtworkSourceRole.PREMIUM,
        trace = ArtworkTrace(selectedProvider = "top_posters", sourceRole = "ARTWORK")
    )
}
