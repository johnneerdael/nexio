package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkAssetKey
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDisplayHints
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeLayout
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HomeCatalogRowDisplayProjectionTest {
    @Test
    fun `tekenfilms modern home row keeps every item`() {
        val row = row(
            addonId = "org.nexio.tekenfilms",
            addonBaseUrl = "https://tekenfilms.nexioapp.org",
            catalogId = "tekenfilms_nl",
            itemCount = 40
        )

        val projected = projectCatalogRowForHomeDisplay(row, HomeLayout.MODERN)

        assertEquals(40, projected.items.size)
    }

    @Test
    fun `ordinary modern home row still truncates after twenty five items`() {
        val row = row(
            addonId = "other",
            addonBaseUrl = "https://example.test",
            catalogId = "movies",
            itemCount = 40
        )

        val projected = projectCatalogRowForHomeDisplay(row, HomeLayout.MODERN)

        assertEquals(25, projected.items.size)
    }

    @Test
    fun `home row projection drops posterless cards before rendering`() {
        val row = row(
            addonId = "other",
            addonBaseUrl = "https://example.test",
            catalogId = "movies",
            itemCount = 3,
            renderPoster = false
        ).copy(
            items = listOf(
                item("ordinary", 0, renderPoster = false),
                item("ordinary", 1, renderPoster = true),
                item("ordinary", 2, renderPoster = false)
            )
        )

        val projected = projectCatalogRowForHomeDisplay(row, HomeLayout.MODERN)

        assertEquals(listOf("ordinary:1"), projected.items.map { it.id })
    }

    @Test
    fun `home row projection drops cards with only decision artwork refs`() {
        val row = row(
            addonId = "other",
            addonBaseUrl = "https://example.test",
            catalogId = "movies",
            itemCount = 2,
            renderPoster = false
        ).copy(
            items = listOf(
                item("ordinary", 0, renderPoster = false).copy(
                    artwork = ArtworkBundle(poster = runtimeDecisionRef("poster-decision"))
                ),
                item("ordinary", 1, renderPoster = true)
            )
        )

        val projected = projectCatalogRowForHomeDisplay(row, HomeLayout.MODERN)

        assertEquals(listOf("ordinary:1"), projected.items.map { it.id })
    }

    @Test
    fun `home row projection keeps cards with asset backed artwork refs`() {
        val row = row(
            addonId = "other",
            addonBaseUrl = "https://example.test",
            catalogId = "movies",
            itemCount = 1,
            renderPoster = false
        ).copy(
            items = listOf(
                item("ordinary", 0, renderPoster = false).copy(
                    artwork = ArtworkBundle(poster = runtimeAsset("poster-asset"))
                )
            )
        )

        val projected = projectCatalogRowForHomeDisplay(row, HomeLayout.MODERN)

        assertEquals(listOf("ordinary:0"), projected.items.map { it.id })
    }

    @Test
    fun `profile switch provider fallback keeps cached posterless cards`() {
        val row = row(
            addonId = "trakt",
            addonBaseUrl = "https://api.trakt.tv",
            catalogId = "trakt_trending_movies",
            itemCount = 2,
            itemIdPrefix = "trakt",
            renderPoster = false
        )

        val projected = projectCatalogRowForHomeDisplay(
            row = row,
            currentLayout = HomeLayout.MODERN,
            allowUnresolvedProviderFallback = true
        )

        assertEquals(listOf("trakt:0", "trakt:1"), projected.items.map { it.id })
    }

    @Test
    fun `home row projection keeps posterless cards backed by resolved authority`() {
        val row = row(
            addonId = "trakt",
            addonBaseUrl = "https://api.trakt.tv",
            catalogId = "trakt_trending_movies",
            itemCount = 1,
            itemIdPrefix = "trakt",
            renderPoster = false
        ).copy(
            items = listOf(
                item("trakt", 123, renderPoster = false).copy(
                    firstPaintStableIds = ProviderIds(trakt = "123")
                )
            )
        )

        val projected = projectCatalogRowForHomeDisplay(
            row = row,
            currentLayout = HomeLayout.MODERN,
            homeAuthorityAliasKeys = setOf("movie:trakt:123")
        )

        assertEquals(listOf("trakt:123"), projected.items.map { it.id })
    }

    @Test
    fun `home row projection drops cards with only thumbnail artwork`() {
        val row = row(
            addonId = "other",
            addonBaseUrl = "https://example.test",
            catalogId = "movies",
            itemCount = 2,
            renderPoster = false
        ).copy(
            items = listOf(
                item("ordinary", 0, renderPoster = false).copy(
                    artwork = ArtworkBundle(thumbnail = runtimeAsset("thumb-only"))
                ),
                item("ordinary", 1, renderPoster = true)
            )
        )

        val projected = projectCatalogRowForHomeDisplay(row, HomeLayout.MODERN)

        assertEquals(listOf("ordinary:1"), projected.items.map { it.id })
    }

    @Test
    fun `tekenfilms row only keeps every item on modern home`() {
        val row = row(
            addonId = "org.nexio.tekenfilms",
            addonBaseUrl = "https://tekenfilms.nexioapp.org",
            catalogId = "tekenfilms_nl",
            itemCount = 40
        )

        val projected = projectCatalogRowForHomeDisplay(row, HomeLayout.CLASSIC)

        assertEquals(25, projected.items.size)
    }

    @Test
    fun `visible home hydration candidates exclude only tekenfilms row items`() {
        val tekenfilmsRow = row(
            addonId = "org.nexio.tekenfilms",
            addonBaseUrl = "https://tekenfilms.nexioapp.org",
            catalogId = "tekenfilms_nl",
            itemCount = 1,
            itemIdPrefix = "tekenfilms"
        )
        val ordinaryRow = row(
            addonId = "other",
            addonBaseUrl = "https://example.test",
            catalogId = "movies",
            itemCount = 1,
            itemIdPrefix = "ordinary"
        )

        val filtered = filterVisibleHomeHydrationCandidates(
            items = tekenfilmsRow.items + ordinaryRow.items,
            rows = listOf(tekenfilmsRow, ordinaryRow)
        )

        assertEquals(listOf(ordinaryRow.items.single()), filtered)
    }

    @Test
    fun `visible home hydration candidates stay unchanged without tekenfilms row`() {
        val ordinaryRow = row(
            addonId = "other",
            addonBaseUrl = "https://example.test",
            catalogId = "movies",
            itemCount = 2,
            itemIdPrefix = "ordinary"
        )

        val filtered = filterVisibleHomeHydrationCandidates(
            items = ordinaryRow.items,
            rows = listOf(ordinaryRow)
        )

        assertSame(ordinaryRow.items, filtered)
    }

    private fun row(
        addonId: String,
        addonBaseUrl: String,
        catalogId: String,
        itemCount: Int,
        itemIdPrefix: String = "tekenfilms",
        renderPoster: Boolean = true
    ): CatalogRow {
        return CatalogRow(
            addonId = addonId,
            addonName = "Addon",
            addonBaseUrl = addonBaseUrl,
            catalogId = catalogId,
            catalogName = "Catalog",
            type = ContentType.MOVIE,
            rawType = "movie",
            items = (0 until itemCount).map { index -> item(itemIdPrefix, index, renderPoster) },
            supportsSkip = false
        )
    }

    private fun item(idPrefix: String, index: Int, renderPoster: Boolean = true): MetaPreview {
        return MetaPreview(
            id = "$idPrefix:$index",
            type = ContentType.MOVIE,
            rawType = "movie",
            name = "Movie $index",
            poster = if (renderPoster) "https://image.test/$idPrefix/$index.jpg" else null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList()
        )
    }

    private fun runtimeAsset(id: String): ArtworkDisplayRef.RuntimeAsset =
        ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey("decision-$id"),
            assetKey = ArtworkAssetKey("asset-$id"),
            imageType = ArtworkType.POSTER,
            selectedProvider = null,
            sourceRole = ArtworkSourceRole.RAIL_PREVIEW,
            trace = ArtworkTrace.empty(),
            displayHints = ArtworkDisplayHints()
        )

    private fun runtimeDecisionRef(id: String): ArtworkDisplayRef.RuntimeAsset =
        ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey("decision-$id"),
            assetKey = null,
            imageType = ArtworkType.POSTER,
            selectedProvider = null,
            sourceRole = ArtworkSourceRole.RAIL_PREVIEW,
            trace = ArtworkTrace.empty(),
            displayHints = ArtworkDisplayHints()
        )
}
