package com.nexio.tv.core.search

import android.app.SearchManager
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidTvNativeSearchContractTest {

    @Test
    fun `Cinemeta result maps to cursor launch uri accepted by native search parser`() = runTest {
        val service = AndroidTvNativeSearchService(
            addonRepository = SingleAddonRepository(),
            catalogRepository = SingleCatalogRepository()
        )

        val suggestions = AndroidTvSearchSuggestionMapper.toSuggestions(service.search("matrix"))
        val cursor = AndroidTvSearchCursorFactory.fromSuggestions(suggestions)

        cursor.moveToFirst()
        val target = AndroidTvNativeSearchIntent.parseDetailUri(
            android.net.Uri.parse(cursor.getString(cursor.getColumnIndexOrThrow(SearchManager.SUGGEST_COLUMN_INTENT_DATA)))
        )

        assertNotNull(target)
        assertEquals("tt0133093", target?.itemId)
        assertEquals("movie", target?.itemType)
        assertEquals("https://v3-cinemeta.strem.io", target?.addonBaseUrl)
    }

    private class SingleAddonRepository : AddonRepository {
        override fun getInstalledAddons(): Flow<List<Addon>> = flowOf(listOf(addon()))
        override suspend fun getCachedInstalledAddons(): List<Addon> = listOf(addon())
        override suspend fun fetchAddon(baseUrl: String): NetworkResult<Addon> = NetworkResult.Success(addon())
        override suspend fun addAddon(url: String, parserPreset: AddonParserPreset, isAnime: Boolean) = Unit
        override suspend fun removeAddon(url: String) = Unit
        override suspend fun setAddonOrder(urls: List<String>) = Unit
        override suspend fun updateAddonParserPreset(url: String, parserPreset: AddonParserPreset) = Unit
        override suspend fun updateAddonIsAnime(url: String, isAnime: Boolean) = Unit
    }

    private class SingleCatalogRepository : CatalogRepository {
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
        ): Flow<NetworkResult<CatalogRow>> {
            val row = CatalogRow(
                addonId = addonId,
                addonName = addonName,
                addonBaseUrl = addonBaseUrl,
                catalogId = catalogId,
                catalogName = catalogName,
                type = ContentType.MOVIE,
                items = listOf(
                    MetaPreview(
                        id = "tt0133093",
                        type = ContentType.MOVIE,
                        name = "The Matrix",
                        poster = null,
                        posterShape = PosterShape.POSTER,
                        background = null,
                        logo = null,
                        description = null,
                        releaseInfo = "1999",
                        runtime = "136 min",
                        imdbRating = null,
                        genres = emptyList()
                    )
                )
            )
            return flowOf(NetworkResult.Success(row))
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

    private companion object {
        fun addon(): Addon {
            return Addon(
                id = "cinemeta",
                name = "Cinemeta",
                version = "1.0.0",
                description = null,
                logo = null,
                baseUrl = "https://v3-cinemeta.strem.io",
                catalogs = listOf(
                    CatalogDescriptor(
                        type = ContentType.MOVIE,
                        id = "search",
                        name = "Search",
                        extra = listOf(CatalogExtra(name = "search", isRequired = true))
                    )
                ),
                types = listOf(ContentType.MOVIE),
                resources = listOf(AddonResource(name = "catalog", types = listOf("movie"), idPrefixes = null))
            )
        }
    }
}
