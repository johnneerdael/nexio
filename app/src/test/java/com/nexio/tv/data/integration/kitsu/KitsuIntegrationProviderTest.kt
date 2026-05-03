package com.nexio.tv.data.integration.kitsu

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationFetchOptions
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.IntegrationStreamHandle
import com.nexio.tv.core.integration.IntegrationStreamSpec
import com.nexio.tv.core.anime.ContentMediaKind
import com.nexio.tv.data.remote.api.KitsuAnimeCharacterResource
import com.nexio.tv.data.remote.api.KitsuCollectionResponse
import com.nexio.tv.data.repository.KitsuAuthService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import retrofit2.Response

class KitsuIntegrationProviderTest {

    @Test
    fun `public Kitsu metadata retries without stale auth after unauthorized response`() = runTest {
        val runtime = PassthroughRuntime()
        val api = mockk<com.nexio.tv.data.remote.api.KitsuApi>()
        val authService = mockk<KitsuAuthService>()
        coEvery { authService.validAccessToken() } returns "stale-token"
        coEvery {
            api.getAnimeCharacters(
                authorization = "Bearer stale-token",
                id = "12"
            )
        } returns Response.error(401, """{"errors":[]}""".toResponseBody("application/json".toMediaType()))
        coEvery {
            api.getAnimeCharacters(
                authorization = null,
                id = "12"
            )
        } returns Response.success(
            KitsuCollectionResponse(
                data = listOf(
                    KitsuAnimeCharacterResource(id = "ac1")
                )
            )
        )
        val provider = KitsuIntegrationProvider(
            runtime = runtime,
            kitsuApi = api,
            kitsuAuthService = authService
        )

        val result = provider.fetchAnimeCharacters(
            rawId = "kitsu:12",
            kitsuId = "12",
            mediaKind = ContentMediaKind.SERIES
        )

        assertNotNull(result)
        assertEquals("ac1", result!!.data!!.single().id)
        coVerify(exactly = 1) {
            api.getAnimeCharacters(
                authorization = "Bearer stale-token",
                id = "12"
            )
        }
        coVerify(exactly = 1) {
            api.getAnimeCharacters(
                authorization = null,
                id = "12"
            )
        }
    }

    private class PassthroughRuntime : IntegrationRuntime {
        override suspend fun <T> get(
            spec: IntegrationSpec<T>,
            options: IntegrationFetchOptions
        ): IntegrationFetchResult<T> =
            when (val result = spec.load()) {
                is IntegrationLoadResult.Success -> IntegrationFetchResult.Updated(result.value)
                is IntegrationLoadResult.HttpError,
                is IntegrationLoadResult.NetworkError -> IntegrationFetchResult.Missing
            }

        override suspend fun <T> call(spec: IntegrationCallSpec<T>): IntegrationCallResult<T> =
            error("call is not used in this test")

        override suspend fun <T> open(spec: IntegrationStreamSpec<T>): IntegrationStreamHandle<T>? =
            error("open is not used in this test")
    }
}
