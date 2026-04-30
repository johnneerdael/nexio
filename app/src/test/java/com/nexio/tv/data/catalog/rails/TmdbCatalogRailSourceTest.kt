package com.nexio.tv.data.catalog.rails

import com.nexio.tv.core.catalog.rails.CatalogRailDescriptor
import com.nexio.tv.core.catalog.rails.CatalogRailProvider
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.data.repository.TmdbDiscoveryService
import com.nexio.tv.data.repository.TmdbDiscoverySnapshot
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

class TmdbCatalogRailSourceTest {

    private val discoveryService = mockk<TmdbDiscoveryService>()
    private val source = TmdbCatalogRailSource(discoveryService = discoveryService)

    private fun item(railId: String, sourceItemId: String) = RailItemPreview(
        railId = railId,
        railSource = RailSource.BUILT_IN_TMDB,
        sourceProvider = ProviderId.TMDB,
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

    private fun record(
        catalogId: String,
        catalogName: String,
        type: ContentType = ContentType.MOVIE,
        previews: List<RailItemPreview> = emptyList()
    ) = RailPreviewCatalogRowRecord(
        addonId = "tmdb",
        addonName = "TMDB",
        addonBaseUrl = "tmdb://builtin",
        catalogId = catalogId,
        catalogName = catalogName,
        type = type,
        previews = previews
    )

    @Test
    fun `providerId is TMDB_BUILTIN`() {
        assertEquals(CatalogRailProvider.TMDB_BUILTIN, source.providerId)
    }

    @Test
    fun `availableRails returns one descriptor per catalogId in the snapshot using catalogName for display`() = runBlocking {
        every { discoveryService.observeSnapshot() } returns flowOf(
            TmdbDiscoverySnapshot(
                rowRecordsByCatalog = linkedMapOf(
                    "tmdb_trending_movies" to record("tmdb_trending_movies", "TMDB — Trending Movies"),
                    "tmdb_popular_series" to record("tmdb_popular_series", "TMDB — Popular Series", type = ContentType.SERIES)
                )
            )
        )

        val rails = source.availableRails(profileId = 1)

        assertEquals(2, rails.size)
        assertEquals(
            CatalogRailDescriptor(
                railId = "tmdb_trending_movies",
                providerId = CatalogRailProvider.TMDB_BUILTIN,
                displayTitle = "TMDB — Trending Movies",
                sortOrder = 0
            ),
            rails[0]
        )
        assertEquals(
            CatalogRailDescriptor(
                railId = "tmdb_popular_series",
                providerId = CatalogRailProvider.TMDB_BUILTIN,
                displayTitle = "TMDB — Popular Series",
                sortOrder = 1
            ),
            rails[1]
        )
    }

    @Test
    fun `availableRails returns empty list when snapshot has no catalogs`() = runBlocking {
        every { discoveryService.observeSnapshot() } returns flowOf(
            TmdbDiscoverySnapshot(rowRecordsByCatalog = emptyMap())
        )

        assertEquals(emptyList<CatalogRailDescriptor>(), source.availableRails(profileId = 1))
    }

    @Test
    fun `availableRails uses the same global snapshot for any profileId`() = runBlocking {
        every { discoveryService.observeSnapshot() } returns flowOf(
            TmdbDiscoverySnapshot(
                rowRecordsByCatalog = mapOf("tmdb_trending_movies" to record("tmdb_trending_movies", "TMDB — Trending Movies"))
            )
        )

        val railsP1 = source.availableRails(profileId = 1)
        val railsP2 = source.availableRails(profileId = 2)

        assertEquals(railsP1, railsP2)
        assertEquals(1, railsP1.size)
        assertEquals("tmdb_trending_movies", railsP1[0].railId)
    }

    @Test
    fun `fetchRail returns Updated with the snapshot's pre-built previews`() = runBlocking {
        val previews = listOf(
            item(railId = "tmdb_trending_movies", sourceItemId = "tt001"),
            item(railId = "tmdb_trending_movies", sourceItemId = "tt002")
        )
        every { discoveryService.observeSnapshot() } returns flowOf(
            TmdbDiscoverySnapshot(
                rowRecordsByCatalog = mapOf(
                    "tmdb_trending_movies" to record("tmdb_trending_movies", "TMDB — Trending Movies", previews = previews)
                )
            )
        )

        val result = source.fetchRail(
            profileId = 1,
            rail = CatalogRailDescriptor(
                railId = "tmdb_trending_movies",
                providerId = CatalogRailProvider.TMDB_BUILTIN,
                displayTitle = "TMDB — Trending Movies",
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
    fun `fetchRail returns Missing when railId is not in the snapshot`() = runBlocking {
        every { discoveryService.observeSnapshot() } returns flowOf(
            TmdbDiscoverySnapshot(
                rowRecordsByCatalog = mapOf("tmdb_trending_movies" to record("tmdb_trending_movies", "TMDB — Trending Movies"))
            )
        )

        val result = source.fetchRail(
            profileId = 1,
            rail = CatalogRailDescriptor(
                railId = "tmdb_unknown_rail",
                providerId = CatalogRailProvider.TMDB_BUILTIN,
                displayTitle = "x",
                sortOrder = 0
            )
        )

        assertEquals(IntegrationFetchResult.Missing, result)
    }

    @Test
    fun `fetchRail returns Missing when snapshot is empty`() = runBlocking {
        every { discoveryService.observeSnapshot() } returns flowOf(
            TmdbDiscoverySnapshot(rowRecordsByCatalog = emptyMap())
        )

        val result = source.fetchRail(
            profileId = 1,
            rail = CatalogRailDescriptor(
                railId = "tmdb_trending_movies",
                providerId = CatalogRailProvider.TMDB_BUILTIN,
                displayTitle = "x",
                sortOrder = 0
            )
        )

        assertEquals(IntegrationFetchResult.Missing, result)
    }
}
