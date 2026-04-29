package com.nexio.tv.data.repository

import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.data.integration.addon.AddonCatalogIntegrationProvider
import com.nexio.tv.data.local.CatalogDiskCacheStore
import com.nexio.tv.data.remote.dto.CatalogResponseDto
import com.nexio.tv.data.remote.dto.MetaPreviewDto
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogRepositoryImplTest {
    @Test
    fun `series route preserves mixed addon item types from metas payload`() = runTest {
        val provider = mockk<AddonCatalogIntegrationProvider>()
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val diskCacheStore = mockk<CatalogDiskCacheStore>()
        val rowSlot = slot<CatalogRow>()
        val repository = CatalogRepositoryImpl(
            addonCatalogIntegrationProvider = provider,
            posterRatingsUrlResolver = posterResolver,
            catalogDiskCacheStore = diskCacheStore
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
        assertEquals(listOf("movie", "series"), rowSlot.captured.items.map { it.apiType })
    }
}
