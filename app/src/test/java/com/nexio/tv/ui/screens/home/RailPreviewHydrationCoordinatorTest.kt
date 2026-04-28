package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.RailDisplaySeed
import com.nexio.tv.domain.model.RailItemPreview
import com.nexio.tv.domain.model.RailSource
import com.nexio.tv.domain.model.SourcePayloadQuality
import org.junit.Assert.assertEquals
import org.junit.Test

class RailPreviewHydrationCoordinatorTest {
    @Test
    fun `hydration targets focused visible and adjacent items without whole row fanout`() {
        val targets = RailPreviewHydrationCoordinator.targetsForVisibleWindow(
            itemKeys = (0 until 1000).map { "movie:tmdb:$it" },
            visibleRange = 10..14,
            focusedIndex = 12,
            adjacentCount = 2
        )

        assertEquals((8..16).map { "movie:tmdb:$it" }, targets)
    }

    @Test
    fun `hydration targets visible range with adjacent margins when no item is focused`() {
        val targets = RailPreviewHydrationCoordinator.targetsForVisibleWindow(
            itemKeys = (0 until 20).map { "movie:tmdb:$it" },
            visibleRange = 10..14,
            focusedIndex = null,
            adjacentCount = 2
        )

        assertEquals((8..16).map { "movie:tmdb:$it" }, targets)
    }

    @Test
    fun `hydration targets clamp focused adjacent range at row boundaries`() {
        val itemKeys = (0 until 5).map { "movie:tmdb:$it" }

        val startTargets = RailPreviewHydrationCoordinator.targetsForVisibleWindow(
            itemKeys = itemKeys,
            visibleRange = 0..1,
            focusedIndex = 0,
            adjacentCount = 2
        )
        val endTargets = RailPreviewHydrationCoordinator.targetsForVisibleWindow(
            itemKeys = itemKeys,
            visibleRange = 3..4,
            focusedIndex = 4,
            adjacentCount = 2
        )

        assertEquals((0..3).map { "movie:tmdb:$it" }, startTargets)
        assertEquals((1..4).map { "movie:tmdb:$it" }, endTargets)
    }

    @Test
    fun `best routing id prefers kitsu over other stable ids`() {
        val preview = railItemPreview(
            itemType = ContentType.MOVIE,
            stableIds = ProviderIds(kitsu = "42", tmdb = "99", imdb = "tt99"),
            sourceItemId = "source:movie:1"
        )

        assertEquals("kitsu:42", preview.bestRoutingId())
    }

    @Test
    fun `best routing id uses movie tmdb id`() {
        val preview = railItemPreview(
            itemType = ContentType.MOVIE,
            stableIds = ProviderIds(tmdb = "99", tvdb = "11", imdb = "tt99"),
            sourceItemId = "source:movie:1"
        )

        assertEquals("tmdb:99", preview.bestRoutingId())
    }

    @Test
    fun `best routing id uses series tvdb id`() {
        val preview = railItemPreview(
            itemType = ContentType.SERIES,
            stableIds = ProviderIds(tmdb = "99", tvdb = "11", imdb = "tt99"),
            sourceItemId = "source:series:1"
        )

        assertEquals("tvdb:11", preview.bestRoutingId())
    }

    @Test
    fun `best routing id falls back to imdb id`() {
        val preview = railItemPreview(
            itemType = ContentType.SERIES,
            stableIds = ProviderIds(imdb = "tt99"),
            sourceItemId = "source:series:1"
        )

        assertEquals("tt99", preview.bestRoutingId())
    }

    @Test
    fun `best routing id falls back to source item id`() {
        val preview = railItemPreview(
            itemType = ContentType.SERIES,
            stableIds = ProviderIds(),
            sourceItemId = "source:series:1"
        )

        assertEquals("source:series:1", preview.bestRoutingId())
    }

    private fun railItemPreview(
        itemType: ContentType,
        stableIds: ProviderIds,
        sourceItemId: String
    ): RailItemPreview {
        return RailItemPreview(
            railId = "rail",
            railSource = RailSource.ADDON_CATALOG,
            sourceProvider = ProviderId.ADDON,
            sourceItemId = sourceItemId,
            itemType = itemType,
            stableIds = stableIds,
            display = RailDisplaySeed(title = "Title"),
            sourcePayloadQuality = SourcePayloadQuality.SPARSE_IDENTITY,
            sourcePayloadHash = "hash",
            generatedAtMs = 1_000L
        )
    }
}
