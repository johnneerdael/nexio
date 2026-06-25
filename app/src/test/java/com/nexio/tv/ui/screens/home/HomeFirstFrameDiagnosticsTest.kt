package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkAssetKey
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDisplayHints
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.PlaceholderType
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeCatalogRail
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFirstFrameDiagnosticsTest {
    @Test
    fun `frame is final when all expected rails have hydrated poster assets`() {
        val rows = listOf(
            row("trakt_trending_movies", "tt1"),
            row("tmdb_popular_movies", "tt2")
        )

        val frame = analyzeHomeFrame(
            source = "snapshot",
            expectedOrderKeys = listOf("trakt_trending_movies", "tmdb_popular_movies"),
            displayRows = rows,
            fullRows = rows
        )

        assertTrue(frame.isFinal)
        assertEquals(0, frame.missingOrderKeys.size)
        assertEquals(0, frame.emptyRailKeys.size)
        assertEquals(0, frame.missingPosterItemCount)
        assertEquals(0, frame.placeholderPosterItemCount)
    }

    @Test
    fun `frame is not final when an expected rail is missing`() {
        val rows = listOf(row("trakt_trending_movies", "tt1"))

        val frame = analyzeHomeFrame(
            source = "producer_candidate",
            expectedOrderKeys = listOf("trakt_trending_movies", "tmdb_popular_movies"),
            displayRows = rows,
            fullRows = rows
        )

        assertFalse(frame.isFinal)
        assertEquals(listOf("tmdb_popular_movies"), frame.missingOrderKeys)
    }

    @Test
    fun `frame is final but reports when poster is placeholder or missing`() {
        val rows = listOf(
            row("trakt_trending_movies", "tt1", posterRef = placeholderPoster()),
            row("tmdb_popular_movies", "tt2", posterRef = null, legacyPoster = null)
        )

        val frame = analyzeHomeFrame(
            source = "snapshot",
            expectedOrderKeys = listOf("trakt_trending_movies", "tmdb_popular_movies"),
            displayRows = rows,
            fullRows = rows
        )

        assertTrue(frame.isFinal)
        assertEquals(1, frame.placeholderPosterItemCount)
        assertEquals(2, frame.missingPosterItemCount)
    }

    @Test
    fun `frame is final but reports when poster is missing without placeholder marker`() {
        val rows = listOf(row("tmdb_popular_movies", "tt2", posterRef = null, legacyPoster = null))

        val frame = analyzeHomeFrame(
            source = "snapshot",
            expectedOrderKeys = listOf("tmdb_popular_movies"),
            displayRows = rows,
            fullRows = rows
        )

        assertTrue(frame.isFinal)
        assertEquals(0, frame.placeholderPosterItemCount)
        assertEquals(1, frame.missingPosterItemCount)
    }

    @Test
    fun `frame is final but reports when only thumbnail artwork is present`() {
        val rows = listOf(
            row(
                "tmdb_popular_movies",
                "tt2",
                posterRef = null,
                thumbnailRef = runtimePoster("thumb-only"),
                legacyPoster = null
            )
        )

        val frame = analyzeHomeFrame(
            source = "snapshot",
            expectedOrderKeys = listOf("tmdb_popular_movies"),
            displayRows = rows,
            fullRows = rows
        )

        assertTrue(frame.isFinal)
        assertEquals(1, frame.missingPosterItemCount)
    }

    @Test
    fun `frame finality uses rendered display rows before full inventory rows`() {
        val displayRows = listOf(row("trakt_trending_movies", "tt1"))
        val fullRows = listOf(
            row("trakt_trending_movies", "tt1", posterRef = null, legacyPoster = null),
            row("hidden_inventory_row", "tt-hidden", posterRef = null, legacyPoster = null)
        )

        val frame = analyzeHomeFrame(
            source = "producer_candidate",
            expectedOrderKeys = listOf("trakt_trending_movies"),
            displayRows = displayRows,
            fullRows = fullRows
        )

        assertTrue(frame.isFinal)
        assertEquals(listOf("trakt_trending_movies"), frame.actualOrderKeys)
        assertEquals(1, frame.totalItemCount)
        assertEquals(0, frame.missingPosterItemCount)
    }

    @Test
    fun `expected frame keys come from configured rails when available`() {
        val rows = listOf(row("trakt_trending_movies", "tt1"))

        val keys = expectedHomeFrameOrderKeys(
            configuredRails = listOf(
                HomeCatalogRail(
                    key = "configured_one",
                    family = "trakt",
                    source = "provider_catalog",
                    title = "Configured One"
                ),
                HomeCatalogRail(
                    key = "disabled_one",
                    family = "trakt",
                    source = "provider_catalog",
                    title = "Disabled One",
                    enabled = false
                )
            ),
            displayRows = rows,
            fullRows = rows
        )

        assertEquals(listOf("configured_one"), keys)
    }

    @Test
    fun `hero item changes affect frame signature`() {
        val rows = listOf(row("trakt_trending_movies", "tt1"))

        val first = analyzeHomeFrame(
            source = "snapshot",
            expectedOrderKeys = listOf("trakt_trending_movies"),
            displayRows = rows,
            fullRows = rows,
            heroItems = listOf(preview("hero-1", runtimePoster("hero-1"), thumbnailRef = null, legacyPoster = null))
        )
        val second = analyzeHomeFrame(
            source = "producer",
            expectedOrderKeys = listOf("trakt_trending_movies"),
            displayRows = rows,
            fullRows = rows,
            heroItems = listOf(preview("hero-2", runtimePoster("hero-2"), thumbnailRef = null, legacyPoster = null))
        )

        assertTrue(first.isFinal)
        assertTrue(second.isFinal)
        assertFalse(first.signature == second.signature)
    }

    private fun row(
        catalogId: String,
        itemId: String,
        posterRef: ArtworkDisplayRef? = runtimePoster(itemId),
        thumbnailRef: ArtworkDisplayRef? = null,
        legacyPoster: String? = null
    ): CatalogRow = CatalogRow(
        addonId = catalogId.substringBefore('_'),
        addonName = "Test",
        addonBaseUrl = "https://example.test",
        catalogId = catalogId,
        catalogName = catalogId,
        type = ContentType.MOVIE,
        items = listOf(preview(itemId, posterRef, thumbnailRef, legacyPoster)),
        hasMore = false
    )

    private fun preview(
        id: String,
        posterRef: ArtworkDisplayRef?,
        thumbnailRef: ArtworkDisplayRef?,
        legacyPoster: String?
    ): MetaPreview = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        name = id,
        poster = legacyPoster,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        artwork = ArtworkBundle(poster = posterRef, thumbnail = thumbnailRef)
    )

    private fun runtimePoster(id: String): ArtworkDisplayRef.RuntimeAsset =
        ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey("decision-$id"),
            assetKey = ArtworkAssetKey("asset-$id"),
            imageType = ArtworkType.POSTER,
            selectedProvider = null,
            sourceRole = ArtworkSourceRole.PRIMARY,
            trace = ArtworkTrace.empty(),
            displayHints = ArtworkDisplayHints()
        )

    private fun placeholderPoster(): ArtworkDisplayRef.Placeholder =
        ArtworkDisplayRef.Placeholder(
            placeholderType = PlaceholderType.POSTER,
            imageType = ArtworkType.POSTER,
            trace = ArtworkTrace.empty()
        )
}
