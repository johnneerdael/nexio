package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRowMemoTest {

    @Test
    fun `intern returns same instance for content-equal rows`() {
        val memo = CatalogRowMemo()
        val first = listOf(testRow(catalogId = "trending", items = listOf(testPreview("a"), testPreview("b"))))
        val second = listOf(testRow(catalogId = "trending", items = listOf(testPreview("a"), testPreview("b"))))

        // Sanity: the two CatalogRow instances are content-equal but reference-distinct.
        assertEquals(first[0], second[0])
        assertNotSame(first[0], second[0])

        val internedFirst = memo.intern(first)
        val internedSecond = memo.intern(second)

        assertSame(internedFirst[0], internedSecond[0])
    }

    @Test
    fun `intern returns new instance when items list differs`() {
        val memo = CatalogRowMemo()
        val rowA = listOf(testRow(catalogId = "trending", items = listOf(testPreview("a"))))
        val rowB = listOf(testRow(catalogId = "trending", items = listOf(testPreview("a"), testPreview("b"))))

        val internedA = memo.intern(rowA)
        val internedB = memo.intern(rowB)

        assertNotSame(internedA[0], internedB[0])
        assertEquals(2, internedB[0].items.size)
    }

    @Test
    fun `intern returns new instance when catalogId differs`() {
        val memo = CatalogRowMemo()
        val items = listOf(testPreview("a"))
        val rowA = listOf(testRow(catalogId = "trending", items = items))
        val rowB = listOf(testRow(catalogId = "popular", items = items))

        val internedA = memo.intern(rowA)
        val internedB = memo.intern(rowB)

        // catalogId differs → different signature → both stored, but distinct references.
        assertNotSame(internedA[0], internedB[0])
        assertEquals("trending", internedA[0].catalogId)
        assertEquals("popular", internedB[0].catalogId)
    }

    @Test
    fun `intern evicts entries not in active set`() {
        val memo = CatalogRowMemo()
        val rowA1 = listOf(testRow(catalogId = "trending", items = listOf(testPreview("a"))))
        val rowB = listOf(testRow(catalogId = "popular", items = listOf(testPreview("b"))))
        val rowA2 = listOf(testRow(catalogId = "trending", items = listOf(testPreview("a"))))

        val internedA1 = memo.intern(rowA1)
        memo.intern(rowB) // displaces "trending" from the active set
        val internedA2 = memo.intern(rowA2)

        // After eviction the second "trending" call should produce a fresh interned reference,
        // not the original cached A1 instance.
        assertNotSame(internedA1[0], internedA2[0])
        assertEquals(internedA1[0], internedA2[0])
    }

    @Test
    fun `intern handles empty list`() {
        val memo = CatalogRowMemo()
        val result = memo.intern(emptyList())
        assertTrue(result.isEmpty())
    }

    private fun testPreview(id: String): MetaPreview {
        return MetaPreview(
            id = id,
            type = ContentType.MOVIE,
            name = "Title-$id",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = "2026",
            runtime = null,
            imdbRating = 8.0f,
            tomatoesRating = null,
            genres = listOf("Action")
        )
    }

    private fun testRow(catalogId: String, items: List<MetaPreview>): CatalogRow {
        return CatalogRow(
            addonId = "addon",
            addonName = "Addon",
            addonBaseUrl = "https://addon.example",
            catalogId = catalogId,
            catalogName = "Catalog",
            type = ContentType.MOVIE,
            items = items
        )
    }
}
