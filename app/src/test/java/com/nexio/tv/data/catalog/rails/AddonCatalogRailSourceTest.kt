package com.nexio.tv.data.catalog.rails

import com.nexio.tv.core.catalog.rails.CatalogRailDescriptor
import com.nexio.tv.core.catalog.rails.CatalogRailProvider
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.AddonResource
import com.nexio.tv.domain.model.CatalogDescriptor as AddonCatalogDescriptor
import com.nexio.tv.domain.model.CatalogExtra
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.CatalogRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonCatalogRailSourceTest {

    private val addonRepository = mockk<AddonRepository>()
    private val catalogRepository = mockk<CatalogRepository>()
    private val codec = AddonCatalogRailIdCodec()
    private val source = AddonCatalogRailSource(
        addonRepository = addonRepository,
        catalogRepository = catalogRepository,
        railIdCodec = codec
    )

    private fun addon(
        id: String,
        name: String = id,
        baseUrl: String = "https://$id.example/manifest.json",
        catalogs: List<AddonCatalogDescriptor>
    ) = Addon(
        id = id,
        name = name,
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = baseUrl,
        catalogs = catalogs,
        types = listOf(ContentType.MOVIE),
        resources = listOf(
            AddonResource(name = "catalog", types = listOf("movie"), idPrefixes = null)
        ),
        parserPreset = AddonParserPreset.GENERIC
    )

    private fun catalog(
        type: ContentType = ContentType.MOVIE,
        id: String,
        name: String = id,
        extra: List<CatalogExtra> = emptyList()
    ) = AddonCatalogDescriptor(
        type = type,
        rawType = type.toApiString(),
        id = id,
        name = name,
        extra = extra
    )

    private fun preview(id: String) = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        rawType = "movie",
        name = "Title $id",
        poster = "https://example/poster/$id.jpg",
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = "2025",
        runtime = null,
        imdbRating = null,
        genres = emptyList(),
        trailerYtIds = emptyList(),
        language = null,
        firstPaintStableIds = ProviderIds()
    )

    @Test
    fun `providerId is ADDON`() {
        assertEquals(CatalogRailProvider.ADDON, source.providerId)
    }

    @Test
    fun `availableRails returns one descriptor per non-search catalog across installed addons`() = runBlocking {
        coEvery { addonRepository.getCachedInstalledAddons() } returns listOf(
            addon(id = "addon.alpha", catalogs = listOf(
                catalog(id = "top", name = "Top Movies"),
                catalog(id = "trending", name = "Trending Movies")
            )),
            addon(id = "addon.beta", catalogs = listOf(
                catalog(id = "popular", name = "Popular")
            ))
        )

        val rails = source.availableRails(profileId = 1)

        assertEquals(3, rails.size)
        assertEquals(
            CatalogRailDescriptor(
                railId = "addon::addon.alpha::movie::top",
                providerId = CatalogRailProvider.ADDON,
                displayTitle = "Top Movies",
                sortOrder = 0
            ),
            rails[0]
        )
        assertEquals(
            CatalogRailDescriptor(
                railId = "addon::addon.alpha::movie::trending",
                providerId = CatalogRailProvider.ADDON,
                displayTitle = "Trending Movies",
                sortOrder = 1
            ),
            rails[1]
        )
        assertEquals(
            CatalogRailDescriptor(
                railId = "addon::addon.beta::movie::popular",
                providerId = CatalogRailProvider.ADDON,
                displayTitle = "Popular",
                sortOrder = 2
            ),
            rails[2]
        )
    }

    @Test
    fun `availableRails skips search-only catalogs`() = runBlocking {
        val searchOnly = catalog(
            id = "search",
            name = "Search",
            extra = listOf(CatalogExtra(name = "search", isRequired = true))
        )
        coEvery { addonRepository.getCachedInstalledAddons() } returns listOf(
            addon(id = "addon.alpha", catalogs = listOf(
                catalog(id = "top", name = "Top"),
                searchOnly
            ))
        )

        val rails = source.availableRails(profileId = 1)

        assertEquals(1, rails.size)
        assertEquals("addon::addon.alpha::movie::top", rails[0].railId)
    }

    @Test
    fun `availableRails returns empty list when no addons installed`() = runBlocking {
        coEvery { addonRepository.getCachedInstalledAddons() } returns emptyList()
        assertEquals(emptyList<CatalogRailDescriptor>(), source.availableRails(profileId = 1))
    }

    @Test
    fun `fetchRail returns Updated with mapped RailItemPreviews on success`() = runBlocking {
        coEvery { addonRepository.getCachedInstalledAddons() } returns listOf(
            addon(id = "addon.alpha", catalogs = listOf(catalog(id = "top", name = "Top")))
        )
        val row = CatalogRow(
            addonId = "addon.alpha",
            addonName = "addon.alpha",
            addonBaseUrl = "https://addon.alpha.example/manifest.json",
            catalogId = "top",
            catalogName = "Top",
            type = ContentType.MOVIE,
            rawType = "movie",
            items = listOf(preview("a"), preview("b")),
            isLoading = false,
            hasMore = false,
            currentPage = 0,
            supportsSkip = false,
            skipStep = 100
        )
        coEvery {
            catalogRepository.refreshCatalogToDisk(
                addonBaseUrl = "https://addon.alpha.example/manifest.json",
                addonId = "addon.alpha",
                addonName = "addon.alpha",
                catalogId = "top",
                catalogName = "Top",
                type = "movie"
            )
        } returns Result.success(row)

        val result = source.fetchRail(
            profileId = 1,
            rail = CatalogRailDescriptor(
                railId = "addon::addon.alpha::movie::top",
                providerId = CatalogRailProvider.ADDON,
                displayTitle = "Top",
                sortOrder = 0
            )
        )

        assertTrue("expected Updated, got $result", result is IntegrationFetchResult.Updated)
        val items = (result as IntegrationFetchResult.Updated).value
        assertEquals(2, items.size)
        assertEquals("addon::addon.alpha::movie::top", items[0].railId)
        assertEquals("addon::addon.alpha::movie::top", items[1].railId)
    }

    @Test
    fun `fetchRail returns Missing when railId cannot be decoded`() = runBlocking {
        val result = source.fetchRail(
            profileId = 1,
            rail = CatalogRailDescriptor(
                railId = "trakt::foo::movie::top",
                providerId = CatalogRailProvider.ADDON,
                displayTitle = "x",
                sortOrder = 0
            )
        )
        assertEquals(IntegrationFetchResult.Missing, result)
    }

    @Test
    fun `fetchRail returns Missing when addon is not installed`() = runBlocking {
        coEvery { addonRepository.getCachedInstalledAddons() } returns emptyList()
        val result = source.fetchRail(
            profileId = 1,
            rail = CatalogRailDescriptor(
                railId = "addon::addon.gone::movie::top",
                providerId = CatalogRailProvider.ADDON,
                displayTitle = "x",
                sortOrder = 0
            )
        )
        assertEquals(IntegrationFetchResult.Missing, result)
    }

    @Test
    fun `fetchRail returns Missing when repository returns failure`() = runBlocking {
        coEvery { addonRepository.getCachedInstalledAddons() } returns listOf(
            addon(id = "addon.alpha", catalogs = listOf(catalog(id = "top", name = "Top")))
        )
        coEvery {
            catalogRepository.refreshCatalogToDisk(
                addonBaseUrl = any(),
                addonId = any(),
                addonName = any(),
                catalogId = any(),
                catalogName = any(),
                type = any()
            )
        } returns Result.failure(RuntimeException("boom"))

        val result = source.fetchRail(
            profileId = 1,
            rail = CatalogRailDescriptor(
                railId = "addon::addon.alpha::movie::top",
                providerId = CatalogRailProvider.ADDON,
                displayTitle = "Top",
                sortOrder = 0
            )
        )

        assertEquals(IntegrationFetchResult.Missing, result)
    }
}
