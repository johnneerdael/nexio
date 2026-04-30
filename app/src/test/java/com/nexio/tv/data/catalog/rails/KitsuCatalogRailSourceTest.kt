package com.nexio.tv.data.catalog.rails

import com.nexio.tv.core.catalog.rails.CatalogRailDescriptor
import com.nexio.tv.core.catalog.rails.CatalogRailProvider
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.data.repository.KitsuDiscoveryService
import com.nexio.tv.data.repository.KitsuDiscoverySnapshot
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.RailDisplaySeed
import com.nexio.tv.domain.model.RailHydrationState
import com.nexio.tv.domain.model.RailItemPreview
import com.nexio.tv.domain.model.RailPreviewCatalogRowRecord
import com.nexio.tv.domain.model.RailSource
import com.nexio.tv.domain.model.SourcePayloadQuality
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KitsuCatalogRailSourceTest {

    private val discoveryService = mockk<KitsuDiscoveryService>()
    private val source = KitsuCatalogRailSource(discoveryService = discoveryService)

    private fun item(railId: String, sourceItemId: String) = RailItemPreview(
        railId = railId,
        railSource = RailSource.BUILT_IN_KITSU,
        sourceProvider = ProviderId.KITSU,
        sourceItemId = sourceItemId,
        itemType = ContentType.SERIES,
        stableIds = ProviderIds(),
        display = RailDisplaySeed(
            title = "Title $sourceItemId",
            releaseDate = null,
            overview = null,
            runtimeText = null,
            genres = emptyList(),
            posterUrl = null,
            posterShape = PosterShape.POSTER,
            backdropUrl = null,
            logoUrl = null,
            rating = null,
            ratingText = null,
            trailerHint = null
        ),
        ranking = null,
        sourcePayloadQuality = SourcePayloadQuality.RICH_PREVIEW,
        sourcePayloadHash = "hash-$sourceItemId",
        generatedAtMs = 0L,
        hydrationState = RailHydrationState.PREVIEW_ONLY
    )

    private fun record(
        catalogId: String,
        catalogName: String,
        previews: List<RailItemPreview> = emptyList()
    ) = RailPreviewCatalogRowRecord(
        addonId = "kitsu",
        addonName = "Kitsu",
        addonBaseUrl = "kitsu://builtin",
        catalogId = catalogId,
        catalogName = catalogName,
        type = ContentType.SERIES,
        previews = previews
    )

    @Test
    fun `providerId is KITSU_BUILTIN`() {
        assertEquals(CatalogRailProvider.KITSU_BUILTIN, source.providerId)
    }

    @Test
    fun `availableRails returns one descriptor per catalogId in the snapshot using catalogName for display`() = runBlocking {
        every { discoveryService.observeSnapshot() } returns flowOf(
            KitsuDiscoverySnapshot(
                rowRecordsByCatalog = linkedMapOf(
                    "kitsu_trending_anime" to record("kitsu_trending_anime", "Kitsu Trending Anime"),
                    "kitsu_highest_rated_anime" to record("kitsu_highest_rated_anime", "Kitsu Highest Rated Anime")
                )
            )
        )

        val rails = source.availableRails(profileId = 1)

        assertEquals(2, rails.size)
        assertEquals(
            CatalogRailDescriptor(
                railId = "kitsu_trending_anime",
                providerId = CatalogRailProvider.KITSU_BUILTIN,
                displayTitle = "Kitsu Trending Anime",
                sortOrder = 0
            ),
            rails[0]
        )
        assertEquals(
            CatalogRailDescriptor(
                railId = "kitsu_highest_rated_anime",
                providerId = CatalogRailProvider.KITSU_BUILTIN,
                displayTitle = "Kitsu Highest Rated Anime",
                sortOrder = 1
            ),
            rails[1]
        )
    }

    @Test
    fun `availableRails returns empty list when snapshot has no catalogs`() = runBlocking {
        every { discoveryService.observeSnapshot() } returns flowOf(
            KitsuDiscoverySnapshot(rowRecordsByCatalog = emptyMap())
        )

        assertEquals(emptyList<CatalogRailDescriptor>(), source.availableRails(profileId = 1))
    }

    @Test
    fun `availableRails uses the same global snapshot for any profileId`() = runBlocking {
        every { discoveryService.observeSnapshot() } returns flowOf(
            KitsuDiscoverySnapshot(
                rowRecordsByCatalog = mapOf(
                    "kitsu_trending_anime" to record("kitsu_trending_anime", "Kitsu Trending Anime")
                )
            )
        )

        val railsP1 = source.availableRails(profileId = 1)
        val railsP2 = source.availableRails(profileId = 2)

        assertEquals(railsP1, railsP2)
        assertEquals(1, railsP1.size)
        assertEquals("kitsu_trending_anime", railsP1[0].railId)
    }

    @Test
    fun `fetchRail returns Updated with the snapshot's pre-built previews`() = runBlocking {
        val previews = listOf(
            item(railId = "kitsu_trending_anime", sourceItemId = "kitsu-1"),
            item(railId = "kitsu_trending_anime", sourceItemId = "kitsu-2")
        )
        every { discoveryService.observeSnapshot() } returns flowOf(
            KitsuDiscoverySnapshot(
                rowRecordsByCatalog = mapOf(
                    "kitsu_trending_anime" to record("kitsu_trending_anime", "Kitsu Trending Anime", previews = previews)
                )
            )
        )

        val result = source.fetchRail(
            profileId = 1,
            rail = CatalogRailDescriptor(
                railId = "kitsu_trending_anime",
                providerId = CatalogRailProvider.KITSU_BUILTIN,
                displayTitle = "Kitsu Trending Anime",
                sortOrder = 0
            )
        )

        assertTrue("expected Updated, got $result", result is IntegrationFetchResult.Updated)
        val returned = (result as IntegrationFetchResult.Updated<List<RailItemPreview>>).value
        assertEquals(2, returned.size)
        assertEquals("kitsu-1", returned[0].sourceItemId)
        assertEquals("kitsu-2", returned[1].sourceItemId)
    }

    @Test
    fun `fetchRail returns Missing when railId is not in the snapshot`() = runBlocking {
        every { discoveryService.observeSnapshot() } returns flowOf(
            KitsuDiscoverySnapshot(
                rowRecordsByCatalog = mapOf(
                    "kitsu_trending_anime" to record("kitsu_trending_anime", "Kitsu Trending Anime")
                )
            )
        )

        val result = source.fetchRail(
            profileId = 1,
            rail = CatalogRailDescriptor(
                railId = "kitsu_unknown_rail",
                providerId = CatalogRailProvider.KITSU_BUILTIN,
                displayTitle = "x",
                sortOrder = 0
            )
        )

        assertEquals(IntegrationFetchResult.Missing, result)
    }

    @Test
    fun `fetchRail returns Missing when snapshot is empty`() = runBlocking {
        every { discoveryService.observeSnapshot() } returns flowOf(
            KitsuDiscoverySnapshot(rowRecordsByCatalog = emptyMap())
        )

        val result = source.fetchRail(
            profileId = 1,
            rail = CatalogRailDescriptor(
                railId = "kitsu_trending_anime",
                providerId = CatalogRailProvider.KITSU_BUILTIN,
                displayTitle = "x",
                sortOrder = 0
            )
        )

        assertEquals(IntegrationFetchResult.Missing, result)
    }
}
