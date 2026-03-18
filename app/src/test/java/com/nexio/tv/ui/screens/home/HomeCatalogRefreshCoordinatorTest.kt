package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCatalogRefreshCoordinatorTest {

    @Test
    fun `diffCatalogItems marks new and changed entries as addedOrChanged`() {
        val oldItems = listOf(
            preview(id = "a", poster = "posterA"),
            preview(id = "b", poster = "posterB")
        )
        val newItems = listOf(
            preview(id = "a", poster = "posterA_v2"),
            preview(id = "b", poster = "posterB"),
            preview(id = "c", poster = "posterC")
        )

        val diff = diffCatalogItems(oldItems, newItems)

        val changedIds = diff.addedOrChanged.map { it.id }.toSet()
        assertEquals(setOf("a", "c"), changedIds)
        assertTrue(diff.removed.isEmpty())
    }

    @Test
    fun `diffCatalogItems marks removed entries`() {
        val oldItems = listOf(
            preview(id = "a", poster = "posterA"),
            preview(id = "b", poster = "posterB")
        )
        val newItems = listOf(
            preview(id = "a", poster = "posterA")
        )

        val diff = diffCatalogItems(oldItems, newItems)

        assertEquals(0, diff.addedOrChanged.size)
        assertEquals(1, diff.removed.size)
        assertEquals("b", diff.removed.first().id)
    }

    @Test
    fun `diffCatalogItems marks metadata only changes as addedOrChanged`() {
        val oldItems = listOf(
            preview(id = "a", poster = "posterA").copy(description = "old", runtime = "90m")
        )
        val newItems = listOf(
            preview(id = "a", poster = "posterA").copy(description = "new", runtime = "95m")
        )

        val diff = diffCatalogItems(oldItems, newItems)

        assertEquals(1, diff.addedOrChanged.size)
        assertEquals("a", diff.addedOrChanged.first().id)
    }

    @Test
    fun `shouldReusePersistedHomeItem only reuses unchanged rows with tomatoes persisted`() {
        assertTrue(
            shouldReusePersistedHomeItem(
                itemChanged = false,
                persistedFallback = preview(id = "a", poster = "posterA").copy(tomatoesRating = 71.0)
            )
        )
        assertEquals(
            false,
            shouldReusePersistedHomeItem(
                itemChanged = false,
                persistedFallback = preview(id = "a", poster = "posterA")
            )
        )
        assertEquals(
            false,
            shouldReusePersistedHomeItem(
                itemChanged = true,
                persistedFallback = preview(id = "a", poster = "posterA").copy(tomatoesRating = 71.0)
            )
        )
    }

    private fun preview(id: String, poster: String?): MetaPreview {
        return MetaPreview(
            id = id,
            type = ContentType.MOVIE,
            rawType = "movie",
            name = "Item $id",
            poster = poster,
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
