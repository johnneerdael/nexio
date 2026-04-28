package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.tvdb.TvProvider
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.RailDisplaySeed
import com.nexio.tv.domain.model.RailItemPreview
import com.nexio.tv.domain.model.RailSource
import com.nexio.tv.domain.model.SourcePayloadQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `best routing id prefers kitsu for kitsu rail items`() {
        val preview = railItemPreview(
            railSource = RailSource.BUILT_IN_KITSU,
            sourceProvider = ProviderId.KITSU,
            itemType = ContentType.SERIES,
            stableIds = ProviderIds(kitsu = "42", tvdb = "11"),
            sourceItemId = "kitsu:anime:42"
        )

        assertEquals("kitsu:42", preview.bestRoutingId())
    }

    @Test
    fun `best routing id prefers tvdb over kitsu for non kitsu series items`() {
        val preview = railItemPreview(
            railSource = RailSource.BUILT_IN_TRAKT,
            sourceProvider = ProviderId.TRAKT,
            itemType = ContentType.SERIES,
            stableIds = ProviderIds(kitsu = "42", tvdb = "11"),
            sourceItemId = "trakt:show:1"
        )

        assertEquals("tvdb:11", preview.bestRoutingId())
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
    fun `best routing id falls back to source item id when only imdb is available`() {
        val preview = railItemPreview(
            itemType = ContentType.SERIES,
            stableIds = ProviderIds(imdb = "tt99"),
            sourceItemId = "source:series:1"
        )

        assertEquals("source:series:1", preview.bestRoutingId())
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
    fun `tmdb tv rail visible item without tvdb identity is not routeable`() {
        val preview = railItemPreview(
            railSource = RailSource.BUILT_IN_TMDB,
            sourceProvider = ProviderId.TMDB,
            itemType = ContentType.SERIES,
            stableIds = ProviderIds(tmdb = "1399"),
            sourceItemId = "tmdb:1399"
        )
        val routingId = preview.bestRoutingId()

        assertEquals("tmdb:1399", routingId)
        assertNull(RailPreviewHydrationCoordinator.providerForVisibleHydration(routingId, preview.itemType))
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

        assertVisibleHydrationSelection(preview, "tvdb:81189", TvProvider.TVDB)
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

        assertVisibleHydrationSelection(preview, "tmdb:550", TvProvider.TMDB)
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

        assertVisibleHydrationSelection(preview, "tmdb:603", TvProvider.TMDB)
    }

    @Test
    fun tmdb_tv_rail_visible_item_with_explicit_tvdb_identity_hydrates_tvdb() {
        val preview = railItemPreview(
            railSource = RailSource.BUILT_IN_TMDB,
            sourceProvider = ProviderId.TMDB,
            itemType = ContentType.SERIES,
            stableIds = ProviderIds(tmdb = "1399", tvdb = "121361"),
            sourceItemId = "tmdb:tv:1399"
        )

        assertVisibleHydrationSelection(preview, "tvdb:121361", TvProvider.TVDB)
    }

    @Test
    fun sparse_imdb_and_source_native_visible_items_are_not_routeable() {
        assertNull(RailPreviewHydrationCoordinator.providerForVisibleHydration("tt99", ContentType.SERIES))
        assertNull(
            RailPreviewHydrationCoordinator.providerForVisibleHydration(
                "source:series:1",
                ContentType.SERIES
            )
        )
    }

    @Test
    fun movie_visible_item_with_imdb_but_without_tmdb_falls_back_to_non_routeable_source_id() {
        val preview = railItemPreview(
            railSource = RailSource.BUILT_IN_TRAKT,
            sourceProvider = ProviderId.TRAKT,
            itemType = ContentType.MOVIE,
            stableIds = ProviderIds(trakt = "20", imdb = "tt0137523"),
            sourceItemId = "trakt:movie:20"
        )
        val routingId = preview.bestRoutingId()

        assertEquals("trakt:movie:20", routingId)
        assertNull(RailPreviewHydrationCoordinator.providerForVisibleHydration(routingId, preview.itemType))
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

        assertVisibleHydrationSelection(preview, "kitsu:1", TvProvider.KITSU)
    }

    @Test
    fun non_kitsu_series_visible_item_with_tvdb_and_kitsu_hydrates_tvdb() {
        val preview = railItemPreview(
            railSource = RailSource.BUILT_IN_TRAKT,
            sourceProvider = ProviderId.TRAKT,
            itemType = ContentType.SERIES,
            stableIds = ProviderIds(kitsu = "1", tvdb = "78874"),
            sourceItemId = "trakt:show:1"
        )

        assertVisibleHydrationSelection(preview, "tvdb:78874", TvProvider.TVDB)
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

        assertVisibleHydrationSelection(preview, "tmdb:27205", TvProvider.TMDB)
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

        assertVisibleHydrationSelection(preview, "tvdb:305288", TvProvider.TVDB)
    }

    private fun assertVisibleHydrationSelection(
        preview: RailItemPreview,
        expectedRoutingId: String,
        expectedProvider: TvProvider
    ) {
        val routingId = preview.bestRoutingId()

        assertEquals(expectedRoutingId, routingId)
        assertEquals(
            expectedProvider,
            RailPreviewHydrationCoordinator.providerForVisibleHydration(routingId, preview.itemType)
        )
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
