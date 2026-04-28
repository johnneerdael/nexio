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

        assertEquals((10..14).map { "movie:tmdb:$it" }, targets)
    }

    @Test
    fun `hydration targets visible range only when no item is focused`() {
        val targets = RailPreviewHydrationCoordinator.targetsForVisibleWindow(
            itemKeys = (0 until 20).map { "movie:tmdb:$it" },
            visibleRange = 10..14,
            focusedIndex = null,
            adjacentCount = 2
        )

        assertEquals((10..14).map { "movie:tmdb:$it" }, targets)
    }

    @Test
    fun `hydration targets visible range only when focused index is negative`() {
        val targets = RailPreviewHydrationCoordinator.targetsForVisibleWindow(
            itemKeys = (0 until 20).map { "movie:tmdb:$it" },
            visibleRange = 10..14,
            focusedIndex = -1,
            adjacentCount = 2
        )

        assertEquals((10..14).map { "movie:tmdb:$it" }, targets)
    }

    @Test
    fun `hydration targets visible range only when focused index is too large`() {
        val targets = RailPreviewHydrationCoordinator.targetsForVisibleWindow(
            itemKeys = (0 until 20).map { "movie:tmdb:$it" },
            visibleRange = 10..14,
            focusedIndex = 20,
            adjacentCount = 2
        )

        assertEquals((10..14).map { "movie:tmdb:$it" }, targets)
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

        assertEquals((0..2).map { "movie:tmdb:$it" }, startTargets)
        assertEquals((2..4).map { "movie:tmdb:$it" }, endTargets)
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

    @Test
    fun trakt_tv_rail_visible_item_hydrates_tvdb() {
        val preview = railItemPreview(
            railSource = RailSource.BUILT_IN_TRAKT,
            sourceProvider = ProviderId.TRAKT,
            itemType = ContentType.SERIES,
            stableIds = ProviderIds(trakt = "10", tvdb = "81189", tmdb = "1396"),
            sourceItemId = "trakt:show:10"
        )

        assertEquals("tvdb:81189", preview.bestRoutingId())
    }

    @Test
    fun trakt_movie_rail_visible_item_hydrates_tmdb() {
        val preview = railItemPreview(
            railSource = RailSource.BUILT_IN_TRAKT,
            sourceProvider = ProviderId.TRAKT,
            itemType = ContentType.MOVIE,
            stableIds = ProviderIds(trakt = "20", tmdb = "550", imdb = "tt0137523"),
            sourceItemId = "trakt:movie:20"
        )

        assertEquals("tmdb:550", preview.bestRoutingId())
    }

    @Test
    fun mdblist_movie_rail_visible_item_hydrates_tmdb() {
        val preview = railItemPreview(
            railSource = RailSource.BUILT_IN_MDBLIST,
            sourceProvider = ProviderId.MDBLIST,
            itemType = ContentType.MOVIE,
            stableIds = ProviderIds(tmdb = "603", imdb = "tt0133093"),
            sourceItemId = "mdblist:movie:603"
        )

        assertEquals("tmdb:603", preview.bestRoutingId())
    }

    @Test
    fun tmdb_tv_rail_visible_item_resolves_tvdb_primary() {
        val preview = railItemPreview(
            railSource = RailSource.BUILT_IN_TMDB,
            sourceProvider = ProviderId.TMDB,
            itemType = ContentType.SERIES,
            stableIds = ProviderIds(tmdb = "1399", tvdb = "121361"),
            sourceItemId = "tmdb:tv:1399"
        )

        assertEquals("tvdb:121361", preview.bestRoutingId())
    }

    @Test
    fun kitsu_rail_visible_item_hydrates_kitsu() {
        val preview = railItemPreview(
            railSource = RailSource.BUILT_IN_KITSU,
            sourceProvider = ProviderId.KITSU,
            itemType = ContentType.SERIES,
            stableIds = ProviderIds(kitsu = "1", tmdb = "37854", tvdb = "78874"),
            sourceItemId = "kitsu:anime:1"
        )

        assertEquals("kitsu:1", preview.bestRoutingId())
    }

    @Test
    fun simkl_movie_rail_visible_item_hydrates_tmdb() {
        val preview = railItemPreview(
            railSource = RailSource.BUILT_IN_SIMKL_DISCOVERY,
            sourceProvider = ProviderId.SIMKL,
            itemType = ContentType.MOVIE,
            stableIds = ProviderIds(simkl = "30", tmdb = "27205", imdb = "tt1375666"),
            sourceItemId = "simkl:movie:30"
        )

        assertEquals("tmdb:27205", preview.bestRoutingId())
    }

    @Test
    fun simkl_tv_rail_visible_item_hydrates_tvdb() {
        val preview = railItemPreview(
            railSource = RailSource.BUILT_IN_SIMKL_DISCOVERY,
            sourceProvider = ProviderId.SIMKL,
            itemType = ContentType.SERIES,
            stableIds = ProviderIds(simkl = "40", tmdb = "66732", tvdb = "305288"),
            sourceItemId = "simkl:show:40"
        )

        assertEquals("tvdb:305288", preview.bestRoutingId())
    }

    private fun railItemPreview(
        railSource: RailSource = RailSource.ADDON_CATALOG,
        sourceProvider: ProviderId = ProviderId.ADDON,
        itemType: ContentType,
        stableIds: ProviderIds,
        sourceItemId: String
    ): RailItemPreview {
        return RailItemPreview(
            railId = "rail",
            railSource = railSource,
            sourceProvider = sourceProvider,
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
