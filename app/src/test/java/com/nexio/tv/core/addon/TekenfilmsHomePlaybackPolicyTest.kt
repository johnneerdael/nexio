package com.nexio.tv.core.addon

import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TekenfilmsHomePlaybackPolicyTest {

    @Test
    fun `matches exact tekenfilms row and item`() {
        val row = tekenfilmsRow()
        val item = preview("tekenfilms:aladdin-1992")

        assertTrue(TekenfilmsHomePlaybackPolicy.isTekenfilmsRow(row))
        assertTrue(TekenfilmsHomePlaybackPolicy.isTekenfilmsItem(row, item))
    }

    @Test
    fun `normalizes manifest url and trailing slash`() {
        val row = tekenfilmsRow(
            addonBaseUrl = "https://tekenfilms.nexioapp.org/manifest.json/"
        )

        assertTrue(TekenfilmsHomePlaybackPolicy.isTekenfilmsRow(row))
    }

    @Test
    fun `rejects url impostor with wrong addon id`() {
        val row = tekenfilmsRow(addonId = "org.other.addon")

        assertFalse(TekenfilmsHomePlaybackPolicy.isTekenfilmsRow(row))
        assertFalse(TekenfilmsHomePlaybackPolicy.isTekenfilmsItem(row, preview("tekenfilms:aladdin-1992")))
    }

    @Test
    fun `rejects addon id impostor with wrong url`() {
        val row = tekenfilmsRow(addonBaseUrl = "https://other.example")

        assertFalse(TekenfilmsHomePlaybackPolicy.isTekenfilmsRow(row))
    }

    @Test
    fun `rejects wrong catalog type catalog id and item prefix`() {
        assertFalse(TekenfilmsHomePlaybackPolicy.isTekenfilmsRow(tekenfilmsRow(catalogId = "other")))
        assertFalse(TekenfilmsHomePlaybackPolicy.isTekenfilmsRow(tekenfilmsRow(rawType = "series")))
        assertFalse(TekenfilmsHomePlaybackPolicy.isTekenfilmsItem(tekenfilmsRow(), preview("tt123")))
    }

    private fun tekenfilmsRow(
        addonId: String = "org.nexio.tekenfilms",
        addonBaseUrl: String = "https://tekenfilms.nexioapp.org",
        catalogId: String = "tekenfilms_nl",
        rawType: String = "movie"
    ) = CatalogRow(
        addonId = addonId,
        addonName = "Tekenfilms",
        addonBaseUrl = addonBaseUrl,
        catalogId = catalogId,
        catalogName = "Tekenfilms (Nederlands)",
        type = ContentType.fromString(rawType),
        rawType = rawType,
        items = emptyList(),
        isLoading = false,
        hasMore = false
    )

    private fun preview(id: String) = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        rawType = "movie",
        name = "Title",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        runtime = null,
        imdbRating = null,
        genres = emptyList(),
        trailerYtIds = emptyList(),
        language = null
    )
}
