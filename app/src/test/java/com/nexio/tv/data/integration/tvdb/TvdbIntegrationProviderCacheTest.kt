package com.nexio.tv.data.integration.tvdb

import com.nexio.tv.core.integration.byteArrayRuntimeFixture
import com.nexio.tv.core.tvdb.TvdbAuthService
import com.nexio.tv.data.remote.api.TvdbApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response

class TvdbIntegrationProviderCacheTest {

    @Test
    fun `series translation 404 is cached as a negative hit`() = runTest {
        val fixture = byteArrayRuntimeFixture()
        val tvdbApi = mockk<TvdbApi>()
        val authService = mockk<TvdbAuthService>()
        coEvery { authService.bearerToken() } returns "Bearer token"
        coEvery {
            tvdbApi.getSeriesTranslation("Bearer token", 303904, "nld")
        } returns Response.error(404, "not found".toResponseBody("application/json".toMediaType()))

        val provider = TvdbIntegrationProvider(fixture.runtime, tvdbApi, authService)

        assertNull(provider.fetchSeriesTranslation(303904, "nld"))
        assertNull(provider.fetchSeriesTranslation(303904, "nld"))

        coVerify(exactly = 1) {
            tvdbApi.getSeriesTranslation("Bearer token", 303904, "nld")
        }
    }

    @Test
    fun `episode translation 404 is cached as a negative hit`() = runTest {
        val fixture = byteArrayRuntimeFixture()
        val tvdbApi = mockk<TvdbApi>()
        val authService = mockk<TvdbAuthService>()
        coEvery { authService.bearerToken() } returns "Bearer token"
        coEvery {
            tvdbApi.getEpisodeTranslation("Bearer token", 7930476, "nld")
        } returns Response.error(404, "not found".toResponseBody("application/json".toMediaType()))

        val provider = TvdbIntegrationProvider(fixture.runtime, tvdbApi, authService)

        assertNull(provider.fetchEpisodeTranslation(7930476, "nld"))
        assertNull(provider.fetchEpisodeTranslation(7930476, "nld"))

        coVerify(exactly = 1) {
            tvdbApi.getEpisodeTranslation("Bearer token", 7930476, "nld")
        }
    }
}
