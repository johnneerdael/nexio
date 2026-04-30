package com.nexio.tv.data.catalog.rails

import com.nexio.tv.core.catalog.rails.CatalogRailDescriptor
import com.nexio.tv.core.catalog.rails.CatalogRailProvider
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.data.local.MDBListDiscoverySnapshotStore
import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.data.repository.MDBListCustomCatalog
import com.nexio.tv.data.repository.MDBListDiscoverySnapshot
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MDBListSettings
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MDBListCatalogRailSourceTest {

    private val snapshotStore = mockk<MDBListDiscoverySnapshotStore>()
    private val settingsDataStore = mockk<MDBListSettingsDataStore>()
    private val source = MDBListCatalogRailSource(
        snapshotStore = snapshotStore,
        settingsDataStore = settingsDataStore
    )

    private fun settings(enabled: Boolean = true, apiKey: String = "key-123") =
        MDBListSettings(enabled = enabled, apiKey = apiKey)

    private fun item(railId: String, sourceItemId: String) = RailItemPreview(
        railId = railId,
        railSource = RailSource.BUILT_IN_MDBLIST,
        sourceProvider = ProviderId.MDBLIST,
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

    private fun catalog(
        key: String,
        catalogId: String,
        catalogName: String = "Catalog $key",
        type: ContentType = ContentType.MOVIE,
        items: List<RailItemPreview> = emptyList()
    ) = MDBListCustomCatalog(
        key = key,
        catalogId = catalogId,
        catalogName = catalogName,
        type = type,
        itemRecords = items
    )

    @Test
    fun `providerId is MDBLIST`() {
        assertEquals(CatalogRailProvider.MDBLIST, source.providerId)
    }

    @Test
    fun `availableRails returns one descriptor per custom catalog in the per-profile snapshot`() = runBlocking {
        every { settingsDataStore.settings } returns flowOf(settings())
        every { snapshotStore.read(profileId = 1) } returns MDBListDiscoverySnapshot(
            customListCatalogs = listOf(
                catalog(key = "alpha", catalogId = "mdblist_list_alpha_movies", catalogName = "Alpha Movies"),
                catalog(key = "beta", catalogId = "mdblist_list_beta_shows", catalogName = "Beta Shows", type = ContentType.SERIES)
            )
        )

        val rails = source.availableRails(profileId = 1)

        assertEquals(2, rails.size)
        assertEquals(
            CatalogRailDescriptor(
                railId = "mdblist_list_alpha_movies",
                providerId = CatalogRailProvider.MDBLIST,
                displayTitle = "Alpha Movies",
                sortOrder = 0
            ),
            rails[0]
        )
        assertEquals(
            CatalogRailDescriptor(
                railId = "mdblist_list_beta_shows",
                providerId = CatalogRailProvider.MDBLIST,
                displayTitle = "Beta Shows",
                sortOrder = 1
            ),
            rails[1]
        )
    }

    @Test
    fun `availableRails returns empty list when MDBList integration is disabled`() = runBlocking {
        every { settingsDataStore.settings } returns flowOf(settings(enabled = false))
        every { snapshotStore.read(profileId = 1) } returns MDBListDiscoverySnapshot(
            customListCatalogs = listOf(catalog(key = "alpha", catalogId = "x"))
        )

        assertEquals(emptyList<CatalogRailDescriptor>(), source.availableRails(profileId = 1))
    }

    @Test
    fun `availableRails returns empty list when API key is blank`() = runBlocking {
        every { settingsDataStore.settings } returns flowOf(settings(apiKey = ""))
        every { snapshotStore.read(profileId = 1) } returns MDBListDiscoverySnapshot(
            customListCatalogs = listOf(catalog(key = "alpha", catalogId = "x"))
        )

        assertEquals(emptyList<CatalogRailDescriptor>(), source.availableRails(profileId = 1))
    }

    @Test
    fun `availableRails returns empty list when snapshot is absent for profile`() = runBlocking {
        every { settingsDataStore.settings } returns flowOf(settings())
        every { snapshotStore.read(profileId = 5) } returns null

        assertEquals(emptyList<CatalogRailDescriptor>(), source.availableRails(profileId = 5))
    }

    @Test
    fun `availableRails reads the snapshot for the requested profileId`() = runBlocking {
        every { settingsDataStore.settings } returns flowOf(settings())
        every { snapshotStore.read(profileId = 1) } returns MDBListDiscoverySnapshot(
            customListCatalogs = listOf(catalog(key = "alpha", catalogId = "alpha_id"))
        )
        every { snapshotStore.read(profileId = 2) } returns MDBListDiscoverySnapshot(
            customListCatalogs = listOf(catalog(key = "beta", catalogId = "beta_id"))
        )

        val railsP1 = source.availableRails(profileId = 1)
        val railsP2 = source.availableRails(profileId = 2)

        assertEquals(1, railsP1.size)
        assertEquals("alpha_id", railsP1[0].railId)
        assertEquals(1, railsP2.size)
        assertEquals("beta_id", railsP2[0].railId)
    }

    @Test
    fun `fetchRail returns Updated with the snapshot's pre-built itemRecords`() = runBlocking {
        every { settingsDataStore.settings } returns flowOf(settings())
        val items = listOf(
            item(railId = "mdblist_list_alpha_movies", sourceItemId = "tt001"),
            item(railId = "mdblist_list_alpha_movies", sourceItemId = "tt002")
        )
        every { snapshotStore.read(profileId = 1) } returns MDBListDiscoverySnapshot(
            customListCatalogs = listOf(
                catalog(key = "alpha", catalogId = "mdblist_list_alpha_movies", items = items)
            )
        )

        val result = source.fetchRail(
            profileId = 1,
            rail = CatalogRailDescriptor(
                railId = "mdblist_list_alpha_movies",
                providerId = CatalogRailProvider.MDBLIST,
                displayTitle = "Alpha Movies",
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
    fun `fetchRail returns Missing when integration is disabled`() = runBlocking {
        every { settingsDataStore.settings } returns flowOf(settings(enabled = false))
        every { snapshotStore.read(profileId = 1) } returns MDBListDiscoverySnapshot(
            customListCatalogs = listOf(catalog(key = "alpha", catalogId = "x"))
        )

        val result = source.fetchRail(
            profileId = 1,
            rail = CatalogRailDescriptor(
                railId = "x",
                providerId = CatalogRailProvider.MDBLIST,
                displayTitle = "x",
                sortOrder = 0
            )
        )

        assertEquals(IntegrationFetchResult.Missing, result)
    }

    @Test
    fun `fetchRail returns Missing when railId is not in the per-profile snapshot`() = runBlocking {
        every { settingsDataStore.settings } returns flowOf(settings())
        every { snapshotStore.read(profileId = 1) } returns MDBListDiscoverySnapshot(
            customListCatalogs = listOf(catalog(key = "alpha", catalogId = "alpha_id"))
        )

        val result = source.fetchRail(
            profileId = 1,
            rail = CatalogRailDescriptor(
                railId = "missing_rail",
                providerId = CatalogRailProvider.MDBLIST,
                displayTitle = "x",
                sortOrder = 0
            )
        )

        assertEquals(IntegrationFetchResult.Missing, result)
    }

    @Test
    fun `fetchRail returns Missing when snapshot is absent`() = runBlocking {
        every { settingsDataStore.settings } returns flowOf(settings())
        every { snapshotStore.read(profileId = 9) } returns null

        val result = source.fetchRail(
            profileId = 9,
            rail = CatalogRailDescriptor(
                railId = "anything",
                providerId = CatalogRailProvider.MDBLIST,
                displayTitle = "x",
                sortOrder = 0
            )
        )

        assertEquals(IntegrationFetchResult.Missing, result)
    }
}
