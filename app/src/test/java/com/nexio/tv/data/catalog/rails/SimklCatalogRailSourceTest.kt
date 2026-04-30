package com.nexio.tv.data.catalog.rails

import com.nexio.tv.core.catalog.rails.CatalogRailDescriptor
import com.nexio.tv.core.catalog.rails.CatalogRailProvider
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.data.local.SimklDiscoverySnapshotStore
import com.nexio.tv.data.repository.SimklDiscoverySnapshot
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.RailDisplaySeed
import com.nexio.tv.domain.model.RailHydrationState
import com.nexio.tv.domain.model.RailItemPreview
import com.nexio.tv.domain.model.RailSource
import com.nexio.tv.domain.model.SourcePayloadQuality
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimklCatalogRailSourceTest {

    private val snapshotStore = mockk<SimklDiscoverySnapshotStore>()
    private val source = SimklCatalogRailSource(snapshotStore = snapshotStore)

    private fun item(railId: String, sourceItemId: String) = RailItemPreview(
        railId = railId,
        railSource = RailSource.BUILT_IN_SIMKL_DISCOVERY,
        sourceProvider = ProviderId.SIMKL,
        sourceItemId = sourceItemId,
        itemType = ContentType.MOVIE,
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

    @Test
    fun `providerId is SIMKL`() {
        assertEquals(CatalogRailProvider.SIMKL, source.providerId)
    }

    @Test
    fun `availableRails returns one descriptor per catalogId in the per-profile snapshot`() = runBlocking {
        every { snapshotStore.read(profileId = 1) } returns SimklDiscoverySnapshot(
            itemRecordsByCatalog = linkedMapOf(
                "simkl_tv_trending_today" to listOf(item("simkl_tv_trending_today", "simkl-1")),
                "simkl_movie_trending_week" to listOf(item("simkl_movie_trending_week", "simkl-2"))
            )
        )

        val rails = source.availableRails(profileId = 1)

        assertEquals(2, rails.size)
        assertEquals(
            CatalogRailDescriptor(
                railId = "simkl_tv_trending_today",
                providerId = CatalogRailProvider.SIMKL,
                displayTitle = "Simkl — TV Trending Today",
                sortOrder = 0
            ),
            rails[0]
        )
        assertEquals(
            CatalogRailDescriptor(
                railId = "simkl_movie_trending_week",
                providerId = CatalogRailProvider.SIMKL,
                displayTitle = "Simkl — Movies Trending This Week",
                sortOrder = 1
            ),
            rails[1]
        )
    }

    @Test
    fun `availableRails uses railId as displayTitle for unknown catalog IDs`() = runBlocking {
        every { snapshotStore.read(profileId = 1) } returns SimklDiscoverySnapshot(
            itemRecordsByCatalog = mapOf(
                "simkl_future_rail_we_havent_named" to listOf(item("simkl_future_rail_we_havent_named", "x"))
            )
        )

        val rails = source.availableRails(profileId = 1)

        assertEquals(1, rails.size)
        assertEquals("simkl_future_rail_we_havent_named", rails[0].displayTitle)
    }

    @Test
    fun `availableRails returns empty list when snapshot is absent for profile`() = runBlocking {
        every { snapshotStore.read(profileId = 5) } returns null

        assertEquals(emptyList<CatalogRailDescriptor>(), source.availableRails(profileId = 5))
    }

    @Test
    fun `availableRails returns empty list when snapshot has no catalogs`() = runBlocking {
        every { snapshotStore.read(profileId = 1) } returns SimklDiscoverySnapshot(
            itemRecordsByCatalog = emptyMap()
        )

        assertEquals(emptyList<CatalogRailDescriptor>(), source.availableRails(profileId = 1))
    }

    @Test
    fun `availableRails reads the snapshot for the requested profileId`() = runBlocking {
        every { snapshotStore.read(profileId = 1) } returns SimklDiscoverySnapshot(
            itemRecordsByCatalog = mapOf("simkl_tv_trending_today" to listOf(item("simkl_tv_trending_today", "p1")))
        )
        every { snapshotStore.read(profileId = 2) } returns SimklDiscoverySnapshot(
            itemRecordsByCatalog = mapOf("simkl_anime_trending_today" to listOf(item("simkl_anime_trending_today", "p2")))
        )

        val railsP1 = source.availableRails(profileId = 1)
        val railsP2 = source.availableRails(profileId = 2)

        assertEquals(1, railsP1.size)
        assertEquals("simkl_tv_trending_today", railsP1[0].railId)
        assertEquals(1, railsP2.size)
        assertEquals("simkl_anime_trending_today", railsP2[0].railId)
    }

    @Test
    fun `fetchRail returns Updated with the snapshot's pre-built itemRecords`() = runBlocking {
        val items = listOf(
            item(railId = "simkl_tv_trending_today", sourceItemId = "tt001"),
            item(railId = "simkl_tv_trending_today", sourceItemId = "tt002")
        )
        every { snapshotStore.read(profileId = 1) } returns SimklDiscoverySnapshot(
            itemRecordsByCatalog = mapOf("simkl_tv_trending_today" to items)
        )

        val result = source.fetchRail(
            profileId = 1,
            rail = CatalogRailDescriptor(
                railId = "simkl_tv_trending_today",
                providerId = CatalogRailProvider.SIMKL,
                displayTitle = "Simkl — TV Trending Today",
                sortOrder = 0
            )
        )

        assertTrue("expected Updated, got $result", result is IntegrationFetchResult.Updated)
        val returned = (result as IntegrationFetchResult.Updated<List<RailItemPreview>>).value
        assertEquals(2, returned.size)
        assertEquals("tt001", returned[0].sourceItemId)
        assertEquals("tt002", returned[1].sourceItemId)
    }

    @Test
    fun `fetchRail returns Missing when railId is not in the per-profile snapshot`() = runBlocking {
        every { snapshotStore.read(profileId = 1) } returns SimklDiscoverySnapshot(
            itemRecordsByCatalog = mapOf("simkl_tv_trending_today" to listOf(item("simkl_tv_trending_today", "x")))
        )

        val result = source.fetchRail(
            profileId = 1,
            rail = CatalogRailDescriptor(
                railId = "simkl_movie_trending_week",
                providerId = CatalogRailProvider.SIMKL,
                displayTitle = "x",
                sortOrder = 0
            )
        )

        assertEquals(IntegrationFetchResult.Missing, result)
    }

    @Test
    fun `fetchRail returns Missing when snapshot is absent`() = runBlocking {
        every { snapshotStore.read(profileId = 9) } returns null

        val result = source.fetchRail(
            profileId = 9,
            rail = CatalogRailDescriptor(
                railId = "simkl_tv_trending_today",
                providerId = CatalogRailProvider.SIMKL,
                displayTitle = "x",
                sortOrder = 0
            )
        )

        assertEquals(IntegrationFetchResult.Missing, result)
    }
}
