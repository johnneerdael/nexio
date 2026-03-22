package com.nexio.tv.core.tmdb

import com.nexio.tv.data.remote.api.TmdbApi
import com.nexio.tv.data.remote.api.TmdbCompanyDetailsResponse
import com.nexio.tv.data.remote.api.TmdbDiscoverResponse
import com.nexio.tv.data.remote.api.TmdbDiscoverResult
import com.nexio.tv.data.remote.api.TmdbNetworkDetailsResponse
import com.nexio.tv.domain.model.MetaCompanyKind
import com.nexio.tv.domain.model.OrganizationDiscoverType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

class TmdbOrganizationServiceTest {

    @Test
    fun `company detail uses company details plus movie discover`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery { api.getCompanyDetails(1, "test-api-key") } returns Response.success(
            TmdbCompanyDetailsResponse(
                id = 1,
                name = "Lucasfilm Ltd.",
                description = "",
                headquarters = "San Francisco, California",
                homepage = "https://www.lucasfilm.com",
                originCountry = "US"
            )
        )
        coEvery {
            api.discoverMoviesByCompany(
                apiKey = "test-api-key",
                language = "en-US",
                companyIds = "1",
                page = 1
            )
        } returns Response.success(
            TmdbDiscoverResponse(
                totalResults = 1,
                results = listOf(
                    TmdbDiscoverResult(id = 11, title = "Star Wars", releaseDate = "1977-05-25")
                )
            )
        )

        val service = TmdbOrganizationService(api, MutableStateFlow("test-api-key"))

        val detail = service.fetchOrganizationDetail(
            entityId = 1,
            kind = MetaCompanyKind.COMPANY,
            discoverType = OrganizationDiscoverType.MOVIE_COMPANY
        )

        assertEquals("Lucasfilm Ltd.", detail?.name)
        assertEquals("San Francisco, California", detail?.headquarters)
        assertEquals("https://www.lucasfilm.com", detail?.homepage)
        assertEquals(1, detail?.titles?.size)
        assertEquals("tmdb:11", detail?.titles?.single()?.id)
        coVerify(exactly = 1) { api.discoverMoviesByCompany("test-api-key", "en-US", "1", 1) }
    }

    @Test
    fun `tv network detail uses with_networks discover`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery { api.getNetworkDetails(49, "test-api-key") } returns Response.success(
            TmdbNetworkDetailsResponse(
                id = 49,
                name = "HBO",
                headquarters = "New York City, New York",
                homepage = "https://www.hbo.com",
                originCountry = "US"
            )
        )
        coEvery {
            api.discoverTvByNetwork(
                apiKey = "test-api-key",
                language = "en-US",
                networkId = 49,
                page = 1
            )
        } returns Response.success(
            TmdbDiscoverResponse(
                totalResults = 1,
                results = listOf(
                    TmdbDiscoverResult(id = 1399, name = "Game of Thrones", firstAirDate = "2011-04-17")
                )
            )
        )

        val service = TmdbOrganizationService(api, MutableStateFlow("test-api-key"))

        val detail = service.fetchOrganizationDetail(
            entityId = 49,
            kind = MetaCompanyKind.NETWORK,
            discoverType = OrganizationDiscoverType.TV_NETWORK
        )

        assertEquals("HBO", detail?.name)
        assertEquals(1, detail?.titles?.size)
        assertEquals("tmdb:1399", detail?.titles?.single()?.id)
        coVerify(exactly = 1) { api.discoverTvByNetwork("test-api-key", "en-US", 49, 1) }
    }
}
