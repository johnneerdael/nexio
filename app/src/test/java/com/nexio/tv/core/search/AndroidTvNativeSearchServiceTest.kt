package com.nexio.tv.core.search

import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.AddonResource
import com.nexio.tv.domain.model.CatalogDescriptor
import com.nexio.tv.domain.model.CatalogExtra
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.CatalogRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidTvNativeSearchServiceTest {

    @Test
    fun `search returns movie and series results from Cinemeta only`() = runTest {
        val cinemeta = addon(baseUrl = CINEMETA_BASE_URL)
        val privateAddon = addon(
            id = "private",
            name = "Private",
            baseUrl = "https://private.example",
            catalogs = listOf(searchCatalog(ContentType.MOVIE))
        )
        val catalogRepository = FakeCatalogRepository(
            rows = mapOf(
                requestKey(CINEMETA_BASE_URL, "movie") to catalogRow(
                    type = ContentType.MOVIE,
                    items = listOf(preview("tt0133093", ContentType.MOVIE, "The Matrix"))
                ),
                requestKey(CINEMETA_BASE_URL, "series") to catalogRow(
                    type = ContentType.SERIES,
                    items = listOf(preview("tt0108778", ContentType.SERIES, "The Matrix: Animated"))
                ),
                requestKey("https://private.example", "movie") to catalogRow(
                    addonBaseUrl = "https://private.example",
                    type = ContentType.MOVIE,
                    items = listOf(preview("private-1", ContentType.MOVIE, "Private Result"))
                )
            )
        )
        val service = service(
            addonRepository = FakeAddonRepository(installed = listOf(privateAddon, cinemeta)),
            catalogRepository = catalogRepository
        )

        val results = service.search("matrix", limit = 10)

        assertEquals(listOf("The Matrix", "The Matrix: Animated"), results.map { it.title })
        assertEquals(setOf(CINEMETA_BASE_URL), catalogRepository.requests.map { it.addonBaseUrl }.toSet())
        assertEquals(setOf("movie", "series"), catalogRepository.requests.map { it.type }.toSet())
    }

    @Test
    fun `search fetches stock Cinemeta when no installed Cinemeta is cached`() = runTest {
        val catalogRepository = FakeCatalogRepository(
            rows = mapOf(
                requestKey(CINEMETA_BASE_URL, "movie") to catalogRow(
                    type = ContentType.MOVIE,
                    items = listOf(preview("tt0111161", ContentType.MOVIE, "The Shawshank Redemption"))
                )
            )
        )
        val service = service(
            addonRepository = FakeAddonRepository(
                installed = emptyList(),
                fetched = NetworkResult.Success(addon(baseUrl = CINEMETA_BASE_URL))
            ),
            catalogRepository = catalogRepository
        )

        val results = service.search("shawshank", limit = 10)

        assertEquals(listOf("The Shawshank Redemption"), results.map { it.title })
        assertEquals(listOf(CINEMETA_BASE_URL), catalogRepository.requests.map { it.addonBaseUrl }.distinct())
    }

    @Test
    fun `blank and one character queries return empty without catalog requests`() = runTest {
        val catalogRepository = FakeCatalogRepository()
        val service = service(
            addonRepository = FakeAddonRepository(installed = listOf(addon(baseUrl = CINEMETA_BASE_URL))),
            catalogRepository = catalogRepository
        )

        assertTrue(service.search(" ", limit = 10).isEmpty())
        assertTrue(service.search("a", limit = 10).isEmpty())
        assertTrue(catalogRepository.requests.isEmpty())
    }

    @Test
    fun `search returns successful catalog when the other content type fails`() = runTest {
        val catalogRepository = FakeCatalogRepository(
            rows = mapOf(
                requestKey(CINEMETA_BASE_URL, "movie") to catalogRow(
                    type = ContentType.MOVIE,
                    items = listOf(preview("tt0068646", ContentType.MOVIE, "The Godfather"))
                )
            ),
            errors = mapOf(
                requestKey(CINEMETA_BASE_URL, "series") to NetworkResult.Error("series failed")
            )
        )
        val service = service(
            addonRepository = FakeAddonRepository(installed = listOf(addon(baseUrl = CINEMETA_BASE_URL))),
            catalogRepository = catalogRepository
        )

        val results = service.search("godfather", limit = 10)

        assertEquals(listOf("The Godfather"), results.map { it.title })
    }

    @Test
    fun `search returns empty on timeout and does not throw`() = runTest {
        val catalogRepository = FakeCatalogRepository(delayMs = 100)
        val service = service(
            addonRepository = FakeAddonRepository(installed = listOf(addon(baseUrl = CINEMETA_BASE_URL))),
            catalogRepository = catalogRepository,
            timeoutMs = 10
        )

        val results = service.search("slow", limit = 10)

        assertTrue(results.isEmpty())
    }

    @Test
    fun `search deduplicates by api type and id`() = runTest {
        val duplicate = preview("tt123", ContentType.MOVIE, "Duplicate")
        val catalogRepository = FakeCatalogRepository(
            rows = mapOf(
                requestKey(CINEMETA_BASE_URL, "movie") to catalogRow(
                    type = ContentType.MOVIE,
                    items = listOf(duplicate, duplicate.copy(name = "Duplicate Again"))
                )
            )
        )
        val service = service(
            addonRepository = FakeAddonRepository(installed = listOf(addon(baseUrl = CINEMETA_BASE_URL))),
            catalogRepository = catalogRepository
        )

        val results = service.search("duplicate", limit = 10)

        assertEquals(1, results.size)
        assertEquals("Duplicate", results.single().title)
    }

    private fun service(
        addonRepository: AddonRepository,
        catalogRepository: CatalogRepository,
        timeoutMs: Long = 750
    ): AndroidTvNativeSearchService {
        return AndroidTvNativeSearchService(
            addonRepository = addonRepository,
            catalogRepository = catalogRepository,
            timeoutMs = timeoutMs
        )
    }

    private class FakeAddonRepository(
        private val installed: List<Addon>,
        private val fetched: NetworkResult<Addon> = NetworkResult.Error("not found")
    ) : AddonRepository {
        override fun getInstalledAddons(): Flow<List<Addon>> = flowOf(installed)
        override suspend fun getCachedInstalledAddons(): List<Addon> = installed
        override suspend fun fetchAddon(baseUrl: String): NetworkResult<Addon> = fetched
        override suspend fun addAddon(url: String, parserPreset: AddonParserPreset) = Unit
        override suspend fun removeAddon(url: String) = Unit
        override suspend fun setAddonOrder(urls: List<String>) = Unit
        override suspend fun updateAddonParserPreset(url: String, parserPreset: AddonParserPreset) = Unit
    }

    private class FakeCatalogRepository(
        private val rows: Map<String, CatalogRow> = emptyMap(),
        private val errors: Map<String, NetworkResult.Error> = emptyMap(),
        private val delayMs: Long = 0
    ) : CatalogRepository {
        val requests = mutableListOf<CatalogRequest>()

        override fun getCatalog(
            addonBaseUrl: String,
            addonId: String,
            addonName: String,
            catalogId: String,
            catalogName: String,
            type: String,
            skip: Int,
            skipStep: Int,
            extraArgs: Map<String, String>,
            supportsSkip: Boolean
        ): Flow<NetworkResult<CatalogRow>> = flow {
            requests += CatalogRequest(addonBaseUrl = addonBaseUrl, type = type, extraArgs = extraArgs)
            if (delayMs > 0) delay(delayMs)
            val key = "${addonBaseUrl.trimEnd('/')}:$type"
            when {
                key in errors -> emit(errors.getValue(key))
                key in rows -> emit(NetworkResult.Success(rows.getValue(key)))
                else -> emit(NetworkResult.Error("missing row"))
            }
        }

        override fun getCatalogCachedFirst(
            addonBaseUrl: String,
            addonId: String,
            addonName: String,
            catalogId: String,
            catalogName: String,
            type: String,
            skip: Int,
            skipStep: Int,
            extraArgs: Map<String, String>,
            supportsSkip: Boolean,
            allowNetworkRefresh: Boolean
        ): Flow<NetworkResult<CatalogRow>> = getCatalog(
            addonBaseUrl = addonBaseUrl,
            addonId = addonId,
            addonName = addonName,
            catalogId = catalogId,
            catalogName = catalogName,
            type = type,
            skip = skip,
            skipStep = skipStep,
            extraArgs = extraArgs,
            supportsSkip = supportsSkip
        )

        override suspend fun refreshCatalogToDisk(
            addonBaseUrl: String,
            addonId: String,
            addonName: String,
            catalogId: String,
            catalogName: String,
            type: String,
            skip: Int,
            skipStep: Int,
            extraArgs: Map<String, String>,
            supportsSkip: Boolean
        ): Result<CatalogRow> = Result.failure(UnsupportedOperationException("not used"))

        override fun clearCache() = Unit
    }

    private data class CatalogRequest(
        val addonBaseUrl: String,
        val type: String,
        val extraArgs: Map<String, String>
    )

    private fun addon(
        id: String = "cinemeta",
        name: String = "Cinemeta",
        baseUrl: String,
        catalogs: List<CatalogDescriptor> = listOf(
            searchCatalog(ContentType.MOVIE),
            searchCatalog(ContentType.SERIES)
        )
    ): Addon {
        return Addon(
            id = id,
            name = name,
            displayName = name,
            version = "1.0.0",
            description = null,
            logo = null,
            baseUrl = baseUrl,
            catalogs = catalogs,
            types = listOf(ContentType.MOVIE, ContentType.SERIES),
            resources = listOf(AddonResource(name = "catalog", types = listOf("movie", "series"), idPrefixes = null))
        )
    }

    private fun searchCatalog(type: ContentType): CatalogDescriptor {
        return CatalogDescriptor(
            type = type,
            id = "search",
            name = "Search",
            extra = listOf(CatalogExtra(name = "search", isRequired = true))
        )
    }

    private fun catalogRow(
        addonBaseUrl: String = CINEMETA_BASE_URL,
        type: ContentType,
        items: List<MetaPreview>
    ): CatalogRow {
        return CatalogRow(
            addonId = "cinemeta",
            addonName = "Cinemeta",
            addonBaseUrl = addonBaseUrl,
            catalogId = "search",
            catalogName = "Search",
            type = type,
            items = items
        )
    }

    private fun preview(
        id: String,
        type: ContentType,
        name: String
    ): MetaPreview {
        return MetaPreview(
            id = id,
            type = type,
            name = name,
            poster = "https://images.example/$id.jpg",
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = "$name description",
            releaseInfo = "1999",
            runtime = "120 min",
            imdbRating = null,
            genres = emptyList()
        )
    }

    private fun requestKey(baseUrl: String, type: String): String = "${baseUrl.trimEnd('/')}:$type"

    private companion object {
        const val CINEMETA_BASE_URL = "https://v3-cinemeta.strem.io"
    }
}
