package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.FirstPaintSource
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.RailSource
import org.junit.Assert.assertEquals
import org.junit.Test

class HomePreviewHydrationPlannerTest {
    @Test
    fun `hydration targets focused visible and adjacent items without whole row fanout`() {
        val items = previews(1_000)

        val targets = HomePreviewHydrationPlanner.targetsForVisibleWindow(
            items = items,
            visibleRange = 10..14,
            focusedIndex = 12,
            adjacentCount = 2
        )

        assertEquals(items.slice(10..14), targets)
    }

    @Test
    fun `hydration targets visible range only when no item is focused`() {
        val items = previews(20)

        val targets = HomePreviewHydrationPlanner.targetsForVisibleWindow(
            items = items,
            visibleRange = 10..14,
            focusedIndex = null,
            adjacentCount = 2
        )

        assertEquals(items.slice(10..14), targets)
    }

    @Test
    fun `hydration targets visible range only when focused index is negative`() {
        val items = previews(20)

        val targets = HomePreviewHydrationPlanner.targetsForVisibleWindow(
            items = items,
            visibleRange = 10..14,
            focusedIndex = -1,
            adjacentCount = 2
        )

        assertEquals(items.slice(10..14), targets)
    }

    @Test
    fun `hydration targets visible range only when focused index is too large`() {
        val items = previews(20)

        val targets = HomePreviewHydrationPlanner.targetsForVisibleWindow(
            items = items,
            visibleRange = 10..14,
            focusedIndex = 20,
            adjacentCount = 2
        )

        assertEquals(items.slice(10..14), targets)
    }

    @Test
    fun `hydration targets clamp focused adjacent range at row boundaries`() {
        val items = previews(5)

        val startTargets = HomePreviewHydrationPlanner.targetsForVisibleWindow(
            items = items,
            visibleRange = 0..1,
            focusedIndex = 0,
            adjacentCount = 2
        )
        val endTargets = HomePreviewHydrationPlanner.targetsForVisibleWindow(
            items = items,
            visibleRange = 3..4,
            focusedIndex = 4,
            adjacentCount = 2
        )

        assertEquals(items.slice(0..2), startTargets)
        assertEquals(items.slice(2..4), endTargets)
    }

    @Test
    fun `hydration planner accepts addon and rail meta previews without source-specific filtering`() {
        val addon = metaPreview(id = "tt0903747")
        val rail = metaPreview(
            id = "tvdb:81189",
            firstPaintSource = FirstPaintSource.RAIL_PREVIEW,
            firstPaintSourceProvider = ProviderId.TRAKT,
            firstPaintStableIds = ProviderIds(trakt = "1", tvdb = "81189"),
            firstPaintRailSource = RailSource.BUILT_IN_TRAKT,
            firstPaintSourceItemId = "trakt:show:1"
        )
        val items = listOf(addon, rail)

        val targets = HomePreviewHydrationPlanner.targetsForVisibleWindow(
            items = items,
            visibleRange = 0..1,
            focusedIndex = null,
            adjacentCount = 0
        )

        assertEquals(items, targets)
    }

    @Test
    fun `negative adjacent count behaves like focused item only`() {
        val items = previews(5)

        val targets = HomePreviewHydrationPlanner.targetsForVisibleWindow(
            items = items,
            visibleRange = 0..0,
            focusedIndex = 3,
            adjacentCount = -5
        )

        assertEquals(listOf(items[0], items[3]), targets)
    }

    private fun previews(count: Int): List<MetaPreview> =
        (0 until count).map { index -> metaPreview(id = "movie:tmdb:$index") }

    private fun metaPreview(
        id: String,
        firstPaintSource: FirstPaintSource = FirstPaintSource.ADDON_META_PREVIEW,
        firstPaintSourceProvider: ProviderId? = null,
        firstPaintStableIds: ProviderIds = ProviderIds(),
        firstPaintRailSource: RailSource? = null,
        firstPaintSourceItemId: String? = null
    ): MetaPreview = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        name = id,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        firstPaintSource = firstPaintSource,
        firstPaintSourceProvider = firstPaintSourceProvider,
        firstPaintStableIds = firstPaintStableIds,
        firstPaintRailSource = firstPaintRailSource,
        firstPaintSourceItemId = firstPaintSourceItemId
    )
}
