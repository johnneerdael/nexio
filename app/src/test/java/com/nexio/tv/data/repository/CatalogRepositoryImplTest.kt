package com.nexio.tv.data.repository

import com.nexio.tv.core.anime.AnimeIdMapAsset
import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.core.metadata.router.InMemoryIdMappingStore
import com.nexio.tv.core.metadata.router.StableIdBundleResolver
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.data.integration.addon.AddonCatalogIntegrationProvider
import com.nexio.tv.data.local.CatalogDiskCacheStore
import com.nexio.tv.data.local.HydratedHomeOverlayStore
import com.nexio.tv.data.mapper.CatalogItemCrossIdEnricher
import com.nexio.tv.data.remote.dto.CatalogResponseDto
import com.nexio.tv.data.remote.dto.MetaPreviewDto
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterRatingsProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class CatalogRepositoryImplTest {

    private fun noopEnricher(): CatalogItemCrossIdEnricher = CatalogItemCrossIdEnricher(
        idMappingStore = InMemoryIdMappingStore(),
        stableIdBundleResolver = StableIdBundleResolver(
            idMappingStore = InMemoryIdMappingStore(),
            lookup = object : StableIdBundleResolver.Lookup {
                override suspend fun tmdbMovieToImdb(tmdbId: String): String? = null
                override suspend fun imdbToTmdbMovie(imdbId: String): String? = null
                override suspend fun tmdbTvToTvdb(tmdbId: String): String? = null
                override suspend fun tmdbTvToImdb(tmdbId: String): String? = null
                override suspend fun imdbToTvdbSeries(imdbId: String): String? = null
                override suspend fun tvdbSeriesToImdb(tvdbId: String): String? = null
            }
        ),
        animeIdMappingService = AnimeIdMappingService { AnimeIdMapAsset(schemaVersion = 0) },
        overlayStore = mockk<HydratedHomeOverlayStore>(relaxed = true)
    )

    private fun repository(
        provider: AddonCatalogIntegrationProvider,
        posterResolver: PosterRatingsUrlResolver,
        diskCacheStore: CatalogDiskCacheStore,
        nowMsProvider: () -> Long = { 2_000_000_000L }
    ): CatalogRepositoryImpl = CatalogRepositoryImpl(
        addonCatalogIntegrationProvider = provider,
        posterRatingsUrlResolver = posterResolver,
        catalogDiskCacheStore = diskCacheStore,
        catalogItemCrossIdEnricher = noopEnricher(),
        nowMsProvider = nowMsProvider
    )

    private fun catalogRow(
        name: String,
        itemId: String = "tt-cached",
        addonId: String = "community.addon",
        catalogId: String = "trending",
        catalogName: String = "Trending",
        addonBaseUrl: String = "https://addon.example",
        type: ContentType = ContentType.MOVIE,
        rawType: String = "movie"
    ): CatalogRow = CatalogRow(
        addonId = addonId,
        addonName = "Community Addon",
        addonBaseUrl = addonBaseUrl,
        catalogId = catalogId,
        catalogName = catalogName,
        type = type,
        rawType = rawType,
        items = listOf(
            MetaPreview(
                id = itemId,
                name = name,
                type = type,
                rawType = rawType,
                poster = null,
                posterShape = com.nexio.tv.domain.model.PosterShape.POSTER,
                background = null,
                logo = null,
                description = null,
                releaseInfo = null,
                imdbRating = null,
                genres = emptyList()
            )
        ),
        isLoading = false,
        hasMore = false
    )

    private fun diskEntry(row: CatalogRow, updatedAtMs: Long): CatalogDiskCacheStore.Entry =
        CatalogDiskCacheStore.Entry(
            catalogRow = row,
            catalogVersionHash = "version-${row.items.single().id}",
            updatedAtMs = updatedAtMs
        )

    @Test
    fun `refreshCatalogToDisk returns fresh disk row without network or disk rewrite`() = runTest {
        val nowMs = 2_000_000_000L
        val cachedRow = catalogRow("Fresh Cached Row")
        val provider = mockk<AddonCatalogIntegrationProvider>()
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val diskCacheStore = mockk<CatalogDiskCacheStore>()
        val repository = repository(provider, posterResolver, diskCacheStore) { nowMs }

        coEvery { posterResolver.getActiveProvider() } returns null
        every { diskCacheStore.read(any()) } returns diskEntry(
            row = cachedRow,
            updatedAtMs = nowMs - TimeUnit.HOURS.toMillis(23)
        )

        val result = repository.refreshCatalogToDisk(
            addonBaseUrl = "https://addon.example",
            addonId = "community.addon",
            addonName = "Community Addon",
            catalogId = "trending",
            catalogName = "Trending",
            type = "movie",
            skip = 0,
            skipStep = 20,
            extraArgs = emptyMap(),
            supportsSkip = true
        )

        assertTrue(result.isSuccess)
        assertEquals(cachedRow, result.getOrThrow())
        coVerify(exactly = 0) { provider.getCatalog(any(), any()) }
        verify(exactly = 0) { diskCacheStore.write(any(), any(), any()) }
    }

    @Test
    fun `refreshCatalogToDisk refreshes stale disk row and writes refreshed row`() = runTest {
        val nowMs = 2_000_000_000L
        val staleRow = catalogRow("Stale Cached Row")
        val provider = mockk<AddonCatalogIntegrationProvider>()
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val diskCacheStore = mockk<CatalogDiskCacheStore>()
        val writtenRow = slot<CatalogRow>()
        val repository = repository(provider, posterResolver, diskCacheStore) { nowMs }

        coEvery { posterResolver.getActiveProvider() } returns null
        every { diskCacheStore.read(any()) } returns diskEntry(
            row = staleRow,
            updatedAtMs = nowMs - TimeUnit.HOURS.toMillis(24)
        )
        every { diskCacheStore.write(any(), capture(writtenRow), any()) } returns Unit
        coEvery { provider.getCatalog(any(), any()) } returns NetworkResult.Success(
            CatalogResponseDto(
                metas = listOf(
                    MetaPreviewDto(
                        id = "tt-fresh",
                        type = "movie",
                        name = "Fresh Network Row"
                    )
                )
            )
        )

        val result = repository.refreshCatalogToDisk(
            addonBaseUrl = "https://addon.example",
            addonId = "community.addon",
            addonName = "Community Addon",
            catalogId = "trending",
            catalogName = "Trending",
            type = "movie",
            skip = 0,
            skipStep = 20,
            extraArgs = emptyMap(),
            supportsSkip = true
        )

        assertTrue(result.isSuccess)
        assertEquals("Fresh Network Row", result.getOrThrow().items.single().name)
        assertEquals("Fresh Network Row", writtenRow.captured.items.single().name)
        coVerify(exactly = 1) { provider.getCatalog(any(), any()) }
        verify(exactly = 1) { diskCacheStore.write(any(), any(), any()) }
    }

    @Test
    fun `refreshCatalogToDisk returns stale disk row when stale refresh fails`() = runTest {
        val nowMs = 2_000_000_000L
        val staleRow = catalogRow("Stale Cached Row")
        val provider = mockk<AddonCatalogIntegrationProvider>()
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val diskCacheStore = mockk<CatalogDiskCacheStore>()
        val repository = repository(provider, posterResolver, diskCacheStore) { nowMs }

        coEvery { posterResolver.getActiveProvider() } returns null
        every { diskCacheStore.read(any()) } returns diskEntry(
            row = staleRow,
            updatedAtMs = nowMs - TimeUnit.DAYS.toMillis(2)
        )
        coEvery { provider.getCatalog(any(), any()) } returns NetworkResult.Error("provider unavailable", 503)

        val result = repository.refreshCatalogToDisk(
            addonBaseUrl = "https://addon.example",
            addonId = "community.addon",
            addonName = "Community Addon",
            catalogId = "trending",
            catalogName = "Trending",
            type = "movie",
            skip = 0,
            skipStep = 20,
            extraArgs = emptyMap(),
            supportsSkip = true
        )

        assertTrue(result.isSuccess)
        assertEquals(staleRow, result.getOrThrow())
        coVerify(exactly = 1) { provider.getCatalog(any(), any()) }
        verify(exactly = 0) { diskCacheStore.write(any(), any(), any()) }
    }

    @Test
    fun `refreshCatalogToDisk returns failure when network fails and no cache exists`() = runTest {
        val provider = mockk<AddonCatalogIntegrationProvider>()
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val diskCacheStore = mockk<CatalogDiskCacheStore>()
        val repository = repository(provider, posterResolver, diskCacheStore)

        coEvery { posterResolver.getActiveProvider() } returns null
        every { diskCacheStore.read(any()) } returns null
        coEvery { provider.getCatalog(any(), any()) } returns NetworkResult.Error("boom", 503)

        val result = repository.refreshCatalogToDisk(
            addonBaseUrl = "https://addon.example",
            addonId = "community.addon",
            addonName = "Community Addon",
            catalogId = "trending",
            catalogName = "Trending",
            type = "movie",
            skip = 0,
            skipStep = 20,
            extraArgs = emptyMap(),
            supportsSkip = true
        )

        assertTrue(result.isFailure)
        assertEquals("boom", result.exceptionOrNull()?.message)
        verify(exactly = 0) { diskCacheStore.write(any(), any(), any()) }
    }

    @Test
    fun `refreshCatalogToDisk includes poster provider token in freshness cache key`() = runTest {
        val provider = mockk<AddonCatalogIntegrationProvider>()
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val diskCacheStore = mockk<CatalogDiskCacheStore>()
        val readKey = slot<String>()
        val repository = repository(provider, posterResolver, diskCacheStore)

        coEvery { posterResolver.getActiveProvider() } returns PosterRatingsUrlResolver.ActiveProvider(
            provider = PosterRatingsProvider.TOP_POSTERS,
            apiKey = "secret"
        )
        every { diskCacheStore.read(capture(readKey)) } returns null
        every { diskCacheStore.write(any(), any(), any()) } returns Unit
        coEvery { provider.getCatalog(any(), any()) } returns NetworkResult.Success(
            CatalogResponseDto(
                metas = listOf(
                    MetaPreviewDto(
                        id = "tt-fresh",
                        type = "movie",
                        name = "Fresh Network Row"
                    )
                )
            )
        )

        val result = repository.refreshCatalogToDisk(
            addonBaseUrl = "https://addon.example",
            addonId = "community.addon",
            addonName = "Community Addon",
            catalogId = "trending",
            catalogName = "Trending",
            type = "movie",
            skip = 0,
            skipStep = 20,
            extraArgs = emptyMap(),
            supportsSkip = true
        )

        assertTrue(result.isSuccess)
        assertTrue(readKey.captured.contains("TOP_POSTERS"))
        assertTrue(readKey.captured.contains("community.addon"))
        assertTrue(readKey.captured.contains("movie_trending_0_20"))
        coVerify(exactly = 1) { provider.getCatalog(any(), any()) }
    }

    @Test
    fun `catalog refresh does not apply premium poster resolver to preview rows`() = runTest {
        val provider = mockk<AddonCatalogIntegrationProvider>()
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val diskCacheStore = mockk<CatalogDiskCacheStore>()
        val rowSlot = slot<CatalogRow>()
        val repository = CatalogRepositoryImpl(
            addonCatalogIntegrationProvider = provider,
            posterRatingsUrlResolver = posterResolver,
            catalogDiskCacheStore = diskCacheStore,
            catalogItemCrossIdEnricher = noopEnricher()
        )

        every { diskCacheStore.write(any(), capture(rowSlot), any()) } returns Unit
        every { diskCacheStore.read(any()) } returns null
        coEvery { posterResolver.getActiveProvider() } returns PosterRatingsUrlResolver.ActiveProvider(
            provider = PosterRatingsProvider.TOP_POSTERS,
            apiKey = "secret"
        )
        every {
            posterResolver.apply(any<MetaPreview>(), any())
        } throws AssertionError("Catalog preview rows must not use premium poster resolver")
        coEvery {
            provider.getCatalog(
                addonId = "top-streaming",
                catalogUrl = any()
            )
        } returns NetworkResult.Success(
            CatalogResponseDto(
                metas = listOf(
                    MetaPreviewDto(
                        id = "tt12042730",
                        type = "movie",
                        name = "Project Hail Mary",
                        poster = "https://image.tmdb.org/t/p/w500/original.jpg",
                        releaseInfo = "2026"
                    )
                )
            )
        )

        val result = repository.refreshCatalogToDisk(
            addonBaseUrl = "https://top-streaming.example/manifest.json",
            addonId = "top-streaming",
            addonName = "Top Streaming",
            catalogId = "top",
            catalogName = "Top Streaming",
            type = "movie",
            skip = 0,
            skipStep = 100,
            extraArgs = emptyMap(),
            supportsSkip = true
        )

        assertTrue(result.isSuccess)
        assertTrue(rowSlot.isCaptured)
        assertEquals("https://image.tmdb.org/t/p/w500/original.jpg", rowSlot.captured.items.single().poster)
        verify(exactly = 0) { posterResolver.apply(any<MetaPreview>(), any()) }
    }

    @Test
    fun `series route preserves mixed addon item types from metas payload`() = runTest {
        val provider = mockk<AddonCatalogIntegrationProvider>()
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val diskCacheStore = mockk<CatalogDiskCacheStore>()
        val rowSlot = slot<CatalogRow>()
        val repository = CatalogRepositoryImpl(
            addonCatalogIntegrationProvider = provider,
            posterRatingsUrlResolver = posterResolver,
            catalogDiskCacheStore = diskCacheStore,
            catalogItemCrossIdEnricher = noopEnricher()
        )

        every { diskCacheStore.read(any()) } returns null
        every { diskCacheStore.write(any(), capture(rowSlot), any()) } returns Unit
        coEvery { posterResolver.getActiveProvider() } returns null
        every { posterResolver.apply(any<MetaPreview>(), null) } answers { firstArg() }
        coEvery {
            provider.getCatalog(
                addonId = "top-streaming",
                catalogUrl = any()
            )
        } returns NetworkResult.Success(
            CatalogResponseDto(
                metas = listOf(
                    MetaPreviewDto(
                        id = "tt12042730",
                        type = "movie",
                        name = "Project Hail Mary",
                        releaseInfo = "2026"
                    ),
                    MetaPreviewDto(
                        id = "tt0903747",
                        type = "series",
                        name = "Breaking Bad",
                        releaseInfo = "2008"
                    )
                )
            )
        )

        val emissions = repository.getCatalog(
            addonBaseUrl = "https://top-streaming.example/manifest.json",
            addonId = "top-streaming",
            addonName = "Top Streaming",
            catalogId = "top",
            catalogName = "Top Streaming",
            type = "series"
        ).toList()
        val row = (emissions.last() as NetworkResult.Success).data

        assertEquals(ContentType.SERIES, row.type)
        assertEquals("series", row.apiType)
        assertEquals(listOf("movie", "series"), row.items.map { it.apiType })
        assertEquals(listOf(ContentType.MOVIE, ContentType.SERIES), row.items.map { it.type })
        assertTrue(rowSlot.isCaptured)
        assertEquals(listOf("movie", "series"), rowSlot.captured.items.map { it.apiType })
        assertEquals(listOf(ContentType.MOVIE, ContentType.SERIES), rowSlot.captured.items.map { it.type })
    }
}
