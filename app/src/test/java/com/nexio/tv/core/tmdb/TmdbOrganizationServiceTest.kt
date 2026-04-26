package com.nexio.tv.core.tmdb

import com.nexio.tv.data.remote.api.TmdbCompanyDetailsResponse
import com.nexio.tv.data.remote.api.TmdbDiscoverResponse
import com.nexio.tv.data.remote.api.TmdbDiscoverResult
import com.nexio.tv.data.remote.api.TmdbNetworkDetailsResponse
import com.nexio.tv.data.integration.tmdb.TmdbOrganizationProvider
import com.nexio.tv.domain.model.MetaCompanyKind
import com.nexio.tv.domain.model.OrganizationDiscoverType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import org.junit.Assert.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class TmdbOrganizationServiceTest {

    @Test
    fun `company detail uses company details plus movie discover`() = runTest {
        val provider = mockk<TmdbOrganizationProvider>()
        coEvery { provider.getCompanyDetails(1) } returns TmdbCompanyDetailsResponse(
            id = 1,
            name = "Lucasfilm Ltd.",
            description = "",
            headquarters = "San Francisco, California",
            homepage = "https://www.lucasfilm.com",
            originCountry = "US"
        )
        coEvery { provider.discoverMoviesByCompany(1, "en-US") } returns TmdbDiscoverResponse(
            totalResults = 1,
            results = listOf(
                TmdbDiscoverResult(id = 11, title = "Star Wars", releaseDate = "1977-05-25")
            )
        )

        val service = TmdbOrganizationService(provider)

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
        coVerify(exactly = 1) { provider.getCompanyDetails(1) }
        coVerify(exactly = 1) { provider.discoverMoviesByCompany(1, "en-US") }
    }

    @Test
    fun `tv network detail uses with_networks discover`() = runTest {
        val provider = mockk<TmdbOrganizationProvider>()
        coEvery { provider.getNetworkDetails(49) } returns TmdbNetworkDetailsResponse(
            id = 49,
            name = "HBO",
            headquarters = "New York City, New York",
            homepage = "https://www.hbo.com",
            originCountry = "US"
        )
        coEvery { provider.discoverTvByNetwork(49, "en-US") } returns TmdbDiscoverResponse(
            totalResults = 1,
            results = listOf(
                TmdbDiscoverResult(id = 1399, name = "Game of Thrones", firstAirDate = "2011-04-17")
            )
        )

        val service = TmdbOrganizationService(provider)

        val detail = service.fetchOrganizationDetail(
            entityId = 49,
            kind = MetaCompanyKind.NETWORK,
            discoverType = OrganizationDiscoverType.TV_NETWORK
        )

        assertEquals("HBO", detail?.name)
        assertEquals(1, detail?.titles?.size)
        assertEquals("tmdb:1399", detail?.titles?.single()?.id)
        coVerify(exactly = 1) { provider.getNetworkDetails(49) }
        coVerify(exactly = 1) { provider.discoverTvByNetwork(49, "en-US") }
    }

    @Test
    fun `cancellation is not swallowed as null result`() = runTest {
        val provider = mockk<TmdbOrganizationProvider>()
        coEvery { provider.getCompanyDetails(1) } coAnswers {
            delay(Long.MAX_VALUE)
            null
        }

        val service = TmdbOrganizationService(provider)
        val fetch = async {
            service.fetchOrganizationDetail(
                entityId = 1,
                kind = MetaCompanyKind.COMPANY,
                discoverType = OrganizationDiscoverType.MOVIE_COMPANY
            )
        }

        yield()
        fetch.cancel(CancellationException("test"))

        var canceled = false
        try {
            fetch.await()
        } catch (cancellation: CancellationException) {
            canceled = true
        }
        assertTrue(canceled)
    }
}
