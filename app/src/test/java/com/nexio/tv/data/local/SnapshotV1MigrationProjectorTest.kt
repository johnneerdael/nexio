package com.nexio.tv.data.local

import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.DisplaySourceRank
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotV1MigrationProjectorTest {

    private fun sampleMetaPreview(id: String, name: String): MetaPreview = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        name = name,
        poster = "https://example.com/$id.jpg",
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        runtime = null,
        imdbRating = null,
        genres = emptyList()
    )

    private fun sampleCatalogRow(catalogId: String, items: List<MetaPreview>): CatalogRow = CatalogRow(
        addonId = "test-addon",
        addonName = "Test Addon",
        addonBaseUrl = "https://test.example",
        catalogId = catalogId,
        catalogName = "Catalog $catalogId",
        type = ContentType.MOVIE,
        items = items
    )

    @Test
    fun `projects v1 catalogRows to v5 displayRails preserving structure`() {
        val metas = listOf(
            sampleMetaPreview("tt1", "Movie One"),
            sampleMetaPreview("tt2", "Movie Two"),
        )
        val v1Rows = listOf(sampleCatalogRow("c1", metas))

        val result = SnapshotV1MigrationProjector.projectLegacySnapshot(
            catalogRows = v1Rows,
            fullCatalogRows = v1Rows,
            heroItems = emptyList(),
            orderedGroupKeys = listOf("g1"),
            nowMs = 1_700_000_000_000L
        )

        assertEquals(1, result.snapshot.displayRails.size)
        assertEquals("c1", result.snapshot.displayRails[0].catalogId)
        assertEquals(2, result.snapshot.displayRails[0].items.size)
        assertEquals("tt1", result.snapshot.displayRails[0].items[0].contentId)
        assertEquals("tt2", result.snapshot.displayRails[0].items[1].contentId)
    }

    @Test
    fun `projects v1 heroItems to v5 heroItemKeys`() {
        val heroMetas = listOf(
            sampleMetaPreview("tt10", "Hero One"),
            sampleMetaPreview("tt11", "Hero Two"),
        )
        val result = SnapshotV1MigrationProjector.projectLegacySnapshot(
            catalogRows = emptyList(),
            fullCatalogRows = emptyList(),
            heroItems = heroMetas,
            orderedGroupKeys = emptyList(),
            nowMs = 1_700_000_000_000L
        )

        assertEquals(listOf("tt10", "tt11"), result.snapshot.heroItemKeys.map { it.contentId })
    }

    @Test
    fun `projects MetaPreview content to ResolvedDisplayItem at FIRST_PAINT rank`() {
        val meta = sampleMetaPreview("tt1", "Movie One")
        val v1Rows = listOf(sampleCatalogRow("c1", listOf(meta)))

        val result = SnapshotV1MigrationProjector.projectLegacySnapshot(
            catalogRows = v1Rows,
            fullCatalogRows = v1Rows,
            heroItems = emptyList(),
            orderedGroupKeys = emptyList(),
            nowMs = 1_700_000_000_000L
        )

        assertEquals(1, result.typedCache.size)
        val item = result.typedCache.values.first()
        assertEquals("Movie One", item.display.title)
        assertEquals(HydrationState.PREVIEW_ONLY, item.hydrationState)
        val slots = item.slots
        assertTrue("title slot expected at FIRST_PAINT", slots?.title?.rank == DisplaySourceRank.FIRST_PAINT)
    }

    @Test
    fun `de-duplicates typed cache by itemKey when meta appears in multiple rows`() {
        val meta = sampleMetaPreview("tt1", "Movie One")
        val v1Rows = listOf(
            sampleCatalogRow("c1", listOf(meta)),
            sampleCatalogRow("c2", listOf(meta))
        )

        val result = SnapshotV1MigrationProjector.projectLegacySnapshot(
            catalogRows = v1Rows,
            fullCatalogRows = v1Rows,
            heroItems = emptyList(),
            orderedGroupKeys = emptyList(),
            nowMs = 1_700_000_000_000L
        )

        assertEquals("typed cache de-dups by itemKey", 1, result.typedCache.size)
        assertEquals(2, result.snapshot.displayRails.size)
    }
}
