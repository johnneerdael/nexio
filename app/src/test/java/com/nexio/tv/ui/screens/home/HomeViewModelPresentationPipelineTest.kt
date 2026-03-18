package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.data.repository.TraktCustomListCatalog
import com.nexio.tv.data.repository.TraktDiscoverySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HomeViewModelPresentationPipelineTest {

    @Test
    fun `consumeDeferredFocusedItemEnrichment keeps pending item while refresh is running`() {
        val pendingItem = testPreview("tt15940132", "War Machine")
        var replayedItem: MetaPreview? = null

        val remaining = consumeDeferredFocusedItemEnrichment(
            pendingItem = pendingItem,
            startupRefreshPending = true,
            catalogsLoadInProgress = false,
            traktDiscoveryRefreshInProgress = false,
            mdbListDiscoveryRefreshInProgress = false,
            onReady = { replayedItem = it }
        )

        assertEquals(pendingItem, remaining)
        assertNull(replayedItem)
    }

    @Test
    fun `consumeDeferredFocusedItemEnrichment replays pending item after refresh completes`() {
        val pendingItem = testPreview("tt15940132", "War Machine")
        var replayedItem: MetaPreview? = null

        val remaining = consumeDeferredFocusedItemEnrichment(
            pendingItem = pendingItem,
            startupRefreshPending = false,
            catalogsLoadInProgress = false,
            traktDiscoveryRefreshInProgress = false,
            mdbListDiscoveryRefreshInProgress = false,
            onReady = { replayedItem = it }
        )

        assertNull(remaining)
        assertEquals(pendingItem, replayedItem)
    }

    @Test
    fun `applyTomatoesToTraktSnapshot updates matching synthetic items`() {
        val untouched = testPreview("tt1", "Untouched")
        val target = testPreview("tt15940132", "War Machine")
        val snapshot = TraktDiscoverySnapshot(
            trendingMovieItems = listOf(untouched, target),
            customListCatalogs = listOf(
                TraktCustomListCatalog(
                    key = "custom",
                    catalogId = "custom_movies",
                    catalogName = "Custom Movies",
                    type = ContentType.MOVIE,
                    items = listOf(target)
                )
            )
        )

        val updated = applyTomatoesToTraktSnapshot(snapshot, "tt15940132", 71.0)

        assertNotSame(snapshot, updated)
        assertEquals(null, updated.trendingMovieItems.first().tomatoesRating)
        assertEquals(71.0, updated.trendingMovieItems.last().tomatoesRating ?: 0.0, 0.0)
        assertEquals(
            71.0,
            updated.customListCatalogs.first().items.first().tomatoesRating ?: 0.0,
            0.0
        )
    }

    @Test
    fun `applyTomatoesToTraktSnapshot returns same instance when item is absent`() {
        val snapshot = TraktDiscoverySnapshot(
            trendingMovieItems = listOf(testPreview("tt1", "Untouched"))
        )

        val updated = applyTomatoesToTraktSnapshot(snapshot, "tt15940132", 71.0)

        assertSame(snapshot, updated)
    }

    @Test
    fun `applyTomatoesOverridesToTraktSnapshot reapplies tomatoes after snapshot replacement`() {
        val snapshot = TraktDiscoverySnapshot(
            trendingMovieItems = listOf(testPreview("tt15940132", "War Machine"))
        )

        val updated = applyTomatoesOverridesToTraktSnapshot(
            snapshot = snapshot,
            tomatoesByItemId = mapOf("tt15940132" to 71.0)
        )

        assertEquals(71.0, updated.trendingMovieItems.first().tomatoesRating ?: 0.0, 0.0)
    }
    private fun testPreview(id: String, title: String): MetaPreview {
        return MetaPreview(
            id = id,
            type = ContentType.MOVIE,
            name = title,
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = "2026",
            runtime = null,
            imdbRating = 8.8f,
            tomatoesRating = null,
            genres = listOf("Action")
        )
    }
}
