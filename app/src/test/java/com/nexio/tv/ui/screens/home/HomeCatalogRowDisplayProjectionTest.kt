package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeLayout
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
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

        assertSame(row, projected)
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

    private fun row(
        addonId: String,
        addonBaseUrl: String,
        catalogId: String,
        itemCount: Int
    ): CatalogRow {
        return CatalogRow(
            addonId = addonId,
            addonName = "Addon",
            addonBaseUrl = addonBaseUrl,
            catalogId = catalogId,
            catalogName = "Catalog",
            type = ContentType.MOVIE,
            rawType = "movie",
            items = (0 until itemCount).map { index -> item(index) },
            supportsSkip = false
        )
    }

    private fun item(index: Int): MetaPreview {
        return MetaPreview(
            id = "tekenfilms:$index",
            type = ContentType.MOVIE,
            rawType = "movie",
            name = "Movie $index",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList()
        )
    }
}
