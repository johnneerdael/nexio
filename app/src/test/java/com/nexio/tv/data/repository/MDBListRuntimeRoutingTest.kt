package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.IntegrationCacheOwnership
import com.nexio.tv.core.integration.RecordingIntegrationRuntime
import com.nexio.tv.core.integration.byteArrayRuntimeFixture
import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.data.integration.mdblist.MDBListIntegrationProvider
import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.data.remote.api.MDBListApi
import com.nexio.tv.data.remote.dto.mdblist.MDBListRatingItemDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListRatingResponseDto
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MDBListRatings
import com.nexio.tv.domain.model.MDBListRatingsResult
import com.nexio.tv.domain.model.MDBListSettings
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaCompany
import com.nexio.tv.domain.model.MetaLink
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.Video
import io.mockk.coVerify
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class MDBListRuntimeRoutingTest {
    @Test
    fun `raw discovery request executes through runtime call`() = runTest {
        val runtime = RecordingIntegrationRuntime(successValue = """[]""")
        val api = mockk<MDBListApi>()
        val provider = MDBListIntegrationProvider(runtime, api)

        provider.getRaw(relativeUrl = "lists/top", apiKey = "mdb-key")

        assertEquals(1, runtime.callSpecs.size)
        coVerify(exactly = 0) {
            api.getRaw(any(), any())
        }
    }

    @Test
    fun `mdblist repository uses integration provider runtime keys for ratings`() = runTest {
        val runtime = RecordingIntegrationRuntime(
            successValue = MDBListRatingsResult(
                ratings = MDBListRatings(imdb = 8.8),
                hasImdbRating = true
            )
        )
        val api = mockk<MDBListApi>()
        val settings = mockk<MDBListSettingsDataStore>()
        val tmdbService = mockk<TmdbService>(relaxed = true)
        val provider = MDBListIntegrationProvider(runtime, api)
        val repository = MDBListRepository(provider, settings, tmdbService)

        every { settings.settings } returns flowOf(
            MDBListSettings(
                enabled = true,
                apiKey = "mdb-key",
                showTrakt = false,
                showImdb = true,
                showTmdb = false,
                showLetterboxd = false,
                showTomatoes = false,
                showAudience = false,
                showMetacritic = false
            )
        )

        val result = repository.getRatingsForMeta(
            meta = stubMeta("tt0137523", ContentType.MOVIE),
            fallbackItemId = "tt0137523",
            fallbackItemType = "movie"
        )

        assertEquals(8.8, result?.ratings?.imdb ?: 0.0, 0.0)
        assertTrue(runtime.keys.any { it.startsWith("mdblist:movie:tt0137523:") })
        assertEquals(
            IntegrationCacheOwnership.Media("movie:imdb:tt0137523"),
            runtime.specs.single().ownership
        )
        coVerify(exactly = 0) { api.getRating(any(), any(), any(), any()) }
    }

    @Test
    fun `episode ratings survive real runtime cache round trip`() = runTest {
        val fixture = byteArrayRuntimeFixture()
        val api = mockk<MDBListApi>()
        val provider = MDBListIntegrationProvider(fixture.runtime, api)

        coEvery {
            api.getRating("show", "imdb", "mdb-key", any())
        } returns Response.success(
            MDBListRatingResponseDto(
                ratings = listOf(
                    MDBListRatingItemDto(id = "101", rating = 8.1),
                    MDBListRatingItemDto(id = "102", rating = 7.9)
                )
            )
        )

        val first = provider.fetchEpisodeRatingsForSeason(
            cacheNamespace = "tmdb:42",
            season = 1,
            apiKey = "mdb-key",
            episodeTmdbIds = mapOf((1 to 1) to 101, (1 to 2) to 102)
        )
        val second = provider.fetchEpisodeRatingsForSeason(
            cacheNamespace = "tmdb:42",
            season = 1,
            apiKey = "mdb-key",
            episodeTmdbIds = mapOf((1 to 1) to 101, (1 to 2) to 102)
        )

        assertEquals(mapOf((1 to 1) to 8.1, (1 to 2) to 7.9), first)
        assertEquals(first, second)
        coVerify(exactly = 1) { api.getRating("show", "imdb", "mdb-key", any()) }
    }

    @Test
    fun `title ratings keep successful enabled sources when another source throws`() = runTest {
        val api = mockk<MDBListApi>()
        val provider = MDBListIntegrationProvider(com.nexio.tv.core.integration.passThroughTestRuntime(), api)

        coEvery {
            api.getRating("movie", "imdb", "mdb-key", any())
        } throws java.io.IOException("imdb unavailable")
        coEvery {
            api.getRating("movie", "tmdb", "mdb-key", any())
        } returns Response.success(
            MDBListRatingResponseDto(
                ratings = listOf(MDBListRatingItemDto(id = "tt0137523", rating = 8.4))
            )
        )

        val result = provider.fetchRatings(
            imdbId = "tt0137523",
            mediaType = "movie",
            apiKey = "mdb-key",
            providers = listOf("imdb", "tmdb")
        )

        assertEquals(null, result?.ratings?.imdb)
        assertEquals(8.4, result?.ratings?.tmdb ?: 0.0, 0.0)
    }

    private fun stubMeta(id: String, type: ContentType): Meta {
        return Meta(
            id = id,
            type = type,
            rawType = type.toApiString(),
            name = "Stub",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            runtime = null,
            director = emptyList(),
            writer = emptyList(),
            cast = emptyList(),
            castMembers = emptyList(),
            videos = emptyList<Video>(),
            productionCompanies = emptyList<MetaCompany>(),
            networks = emptyList<MetaCompany>(),
            ageRating = null,
            country = null,
            awards = null,
            language = null,
            links = emptyList<MetaLink>(),
            trailerYtIds = emptyList()
        )
    }
}
