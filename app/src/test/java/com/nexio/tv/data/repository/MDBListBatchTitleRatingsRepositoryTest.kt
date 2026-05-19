package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.passThroughTestRuntime
import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.data.integration.mdblist.MDBListIntegrationProvider
import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.data.remote.api.MDBListApi
import com.nexio.tv.data.remote.dto.mdblist.MDBListRatingItemDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListRatingRequestDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListRatingResponseDto
import com.nexio.tv.domain.model.MDBListSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class MDBListBatchTitleRatingsRepositoryTest {
    @Test
    fun `mdblist batch ratings batches 50 tmdb ids into one call per source`() = runTest {
        val api = mockk<MDBListApi>()
        val repository = repository(api)
        val tmdbIds = (1..50).map { it.toString() }
        val request = slot<MDBListRatingRequestDto>()

        coEvery {
            api.getRating("show", "tmdb", "mdb-key", capture(request))
        } returns Response.success(ratingResponse(tmdbIds))
        coEvery {
            api.getRating("show", "letterboxd", "mdb-key", any())
        } returns Response.success(ratingResponse(tmdbIds))
        coEvery {
            api.getRating("show", "tomatoes", "mdb-key", any())
        } returns Response.success(ratingResponse(tmdbIds))
        coEvery {
            api.getRating("show", "metacritic", "mdb-key", any())
        } returns Response.success(ratingResponse(tmdbIds))

        val result = repository.getTitleRatings(
            requests = tmdbIds.map {
                MDBListTitleRatingRequest(
                    stableId = it,
                    mediaType = "show",
                    requestProvider = "tmdb",
                    ratingSources = listOf("tmdb", "letterboxd", "tomatoes", "metacritic")
                )
            },
            cacheOnly = false
        )

        assertEquals(50, result.size)
        assertEquals((1..50).toList(), request.captured.ids)
        coVerify(exactly = 1) { api.getRating("show", "tmdb", "mdb-key", any()) }
        coVerify(exactly = 1) { api.getRating("show", "letterboxd", "mdb-key", any()) }
        coVerify(exactly = 1) { api.getRating("show", "tomatoes", "mdb-key", any()) }
        coVerify(exactly = 1) { api.getRating("show", "metacritic", "mdb-key", any()) }
    }

    @Test
    fun `mdblist batch ratings cache only does not call network when cache empty`() = runTest {
        val api = mockk<MDBListApi>()
        val repository = repository(api)

        val result = repository.getTitleRatings(
            requests = listOf(
                MDBListTitleRatingRequest(
                    stableId = "1399",
                    mediaType = "show",
                    requestProvider = "tmdb",
                    ratingSources = listOf("tmdb")
                )
            ),
            cacheOnly = true
        )

        assertTrue(result.isEmpty())
        coVerify(exactly = 0) { api.getRating(any(), any(), any(), any()) }
    }

    @Test
    fun `mdblist batch ratings cache hit suppresses network`() = runTest {
        val api = mockk<MDBListApi>()
        val repository = repository(api)

        coEvery {
            api.getRating("show", "tmdb", "mdb-key", any())
        } returns Response.success(ratingResponse(listOf("1399")))

        val request = listOf(
            MDBListTitleRatingRequest(
                stableId = "1399",
                mediaType = "show",
                requestProvider = "tmdb",
                ratingSources = listOf("tmdb")
            )
        )

        assertEquals(8.0, repository.getTitleRatings(request, cacheOnly = false).values.single().tmdb ?: 0.0, 0.0)
        assertEquals(8.0, repository.getTitleRatings(request, cacheOnly = false).values.single().tmdb ?: 0.0, 0.0)
        coVerify(exactly = 1) { api.getRating("show", "tmdb", "mdb-key", any()) }
    }

    @Test
    fun `mdblist batch ratings does not request imdb trakt or audience`() = runTest {
        val api = mockk<MDBListApi>()
        val repository = repository(api)

        coEvery {
            api.getRating("show", "tmdb", "mdb-key", any())
        } returns Response.success(ratingResponse(listOf("1399")))

        repository.getTitleRatings(
            requests = listOf(
                MDBListTitleRatingRequest(
                    stableId = "1399",
                    mediaType = "show",
                    requestProvider = "tmdb",
                    ratingSources = listOf("imdb", "trakt", "audience", "tmdb")
                )
            ),
            cacheOnly = false
        )

        coVerify(exactly = 1) { api.getRating("show", "tmdb", "mdb-key", any()) }
        coVerify(exactly = 0) { api.getRating(any(), "imdb", any(), any()) }
        coVerify(exactly = 0) { api.getRating(any(), "trakt", any(), any()) }
        coVerify(exactly = 0) { api.getRating(any(), "audience", any(), any()) }
    }

    @Test
    fun `fetchRatingBatch returns http 429 with retry after`() = runTest {
        val api = mockk<MDBListApi>()
        val provider = MDBListIntegrationProvider(passThroughTestRuntime(), api)

        coEvery {
            api.getRating("show", "tmdb", "mdb-key", any())
        } returns Response.error(429, "rate limited".toResponseBody())

        val result = provider.fetchRatingBatch(
            mediaType = "show",
            ratingType = "tmdb",
            requestProvider = "tmdb",
            ids = listOf("1399"),
            apiKey = "mdb-key"
        )

        assertTrue(result is IntegrationCallResult.HttpError)
        assertEquals(429, (result as IntegrationCallResult.HttpError).statusCode)
    }

    private fun repository(api: MDBListApi): MDBListRepository {
        val settings = mockk<MDBListSettingsDataStore>()
        every { settings.settings } returns flowOf(
            MDBListSettings(
                enabled = true,
                apiKey = "mdb-key",
                showTmdb = true,
                showLetterboxd = true,
                showTomatoes = true,
                showMetacritic = true
            )
        )
        return MDBListRepository(
            integrationProvider = MDBListIntegrationProvider(passThroughTestRuntime(), api),
            settingsDataStore = settings,
            tmdbService = mockk<TmdbService>(relaxed = true)
        )
    }

    private fun ratingResponse(ids: List<String>): MDBListRatingResponseDto {
        return MDBListRatingResponseDto(
            ratings = ids.map { MDBListRatingItemDto(id = it.toInt(), rating = 8.0) }
        )
    }
}
