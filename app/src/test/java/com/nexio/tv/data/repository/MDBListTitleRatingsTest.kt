package com.nexio.tv.data.repository

import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.core.integration.passThroughTestRuntime
import com.nexio.tv.data.integration.mdblist.MDBListIntegrationProvider
import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.data.remote.api.MDBListApi
import com.nexio.tv.data.remote.dto.mdblist.MDBListRatingItemDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListRatingResponseDto
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MDBListSettings
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaCompany
import com.nexio.tv.domain.model.MetaLink
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.Video
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response

class MDBListTitleRatingsTest {
    @Test
    fun `getRatingsForMeta requests MDBList show ratings by TMDB id and never asks MDBList for IMDb`() = runTest {
        val api = mockk<MDBListApi>()
        val settings = mockk<MDBListSettingsDataStore>()
        val tmdbService = mockk<TmdbService>(relaxed = true)
        val repository = MDBListRepository(
            integrationProvider = MDBListIntegrationProvider(passThroughTestRuntime(), api),
            settingsDataStore = settings,
            tmdbService = tmdbService
        )
        val request = slot<com.nexio.tv.data.remote.dto.mdblist.MDBListRatingRequestDto>()

        every { settings.settings } returns flowOf(
            MDBListSettings(
                enabled = true,
                apiKey = "mdb-key",
                showTrakt = true,
                showImdb = true,
                showTmdb = true,
                showLetterboxd = false,
                showTomatoes = true,
                showAudience = true,
                showMetacritic = true
            )
        )
        coEvery {
            api.getRating("show", "tmdb", "mdb-key", capture(request))
        } returns Response.success(
            MDBListRatingResponseDto(
                ratings = listOf(MDBListRatingItemDto(id = 1399, rating = 8.4))
            )
        )
        coEvery {
            api.getRating("show", "tomatoes", "mdb-key", any())
        } returns Response.success(
            MDBListRatingResponseDto(
                ratings = listOf(MDBListRatingItemDto(id = 1399, rating = 91.0))
            )
        )
        coEvery {
            api.getRating("show", "metacritic", "mdb-key", any())
        } returns Response.success(
            MDBListRatingResponseDto(
                ratings = listOf(MDBListRatingItemDto(id = 1399, rating = 86.0))
            )
        )

        val result = repository.getRatingsForMeta(
            meta = stubMeta("tmdb:1399", ContentType.SERIES),
            fallbackItemId = "tmdb:1399",
            fallbackItemType = "series"
        )

        assertEquals(8.4, result?.ratings?.tmdb ?: 0.0, 0.0)
        assertEquals(91.0, result?.ratings?.tomatoes ?: 0.0, 0.0)
        assertEquals(86.0, result?.ratings?.metacritic ?: 0.0, 0.0)
        assertEquals(listOf(1399), request.captured.ids)
        assertEquals("tmdb", request.captured.provider)
        coVerify(exactly = 0) {
            api.getRating(any(), "imdb", any(), any())
        }
        coVerify(exactly = 0) {
            api.getRating(any(), "trakt", any(), any())
        }
        coVerify(exactly = 0) {
            api.getRating(any(), "audience", any(), any())
        }
    }

    @Test
    fun `enrichPreview only applies MDBList tomatoes and keeps IMDb rating untouched`() = runTest {
        val api = mockk<MDBListApi>()
        val settings = mockk<MDBListSettingsDataStore>()
        val tmdbService = mockk<TmdbService>(relaxed = true)
        val repository = MDBListRepository(
            integrationProvider = MDBListIntegrationProvider(passThroughTestRuntime(), api),
            settingsDataStore = settings,
            tmdbService = tmdbService
        )

        every { settings.settings } returns flowOf(
            MDBListSettings(
                enabled = true,
                apiKey = "mdb-key",
                showTrakt = false,
                showImdb = true,
                showTmdb = false,
                showLetterboxd = false,
                showTomatoes = true,
                showAudience = false,
                showMetacritic = false
            )
        )
        coEvery {
            api.getRating("movie", "tomatoes", "mdb-key", any())
        } returns Response.success(
            MDBListRatingResponseDto(
                ratings = listOf(MDBListRatingItemDto(id = 27205, rating = 87.0))
            )
        )

        val preview = MetaPreview(
            id = "tmdb:27205",
            type = ContentType.MOVIE,
            name = "Inception",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = "2010",
            imdbRating = 8.3f,
            ratingSource = TitleRatingSource.TMDB,
            genres = emptyList()
        )

        val enriched = repository.enrichPreview(preview)

        assertEquals(8.3f, enriched.imdbRating ?: 0f, 0.0f)
        assertEquals(TitleRatingSource.TMDB, enriched.ratingSource)
        assertEquals(87.0, enriched.tomatoesRating ?: 0.0, 0.0)
        coVerify(exactly = 0) {
            api.getRating(any(), "imdb", any(), any())
        }
    }

    @Test
    fun `getRatingsForMeta does not bridge tvdb primary id through tmdb`() = runTest {
        val api = mockk<MDBListApi>()
        val settings = mockk<MDBListSettingsDataStore>()
        val tmdbService = mockk<TmdbService>()
        val repository = MDBListRepository(
            integrationProvider = MDBListIntegrationProvider(passThroughTestRuntime(), api),
            settingsDataStore = settings,
            tmdbService = tmdbService
        )

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
            meta = stubMeta("tvdb:355567", ContentType.SERIES),
            fallbackItemId = "tvdb:355567",
            fallbackItemType = "series"
        )

        assertNull(result)
        coVerify(exactly = 0) { tmdbService.ensureTmdbId(any(), any()) }
        coVerify(exactly = 0) { tmdbService.tmdbToImdb(any(), any()) }
        coVerify(exactly = 0) { api.getRating(any(), any(), any(), any()) }
    }

    @Test
    fun `getRatingsForMeta does not bridge kitsu primary id through tmdb`() = runTest {
        val api = mockk<MDBListApi>()
        val settings = mockk<MDBListSettingsDataStore>()
        val tmdbService = mockk<TmdbService>()
        val repository = MDBListRepository(
            integrationProvider = MDBListIntegrationProvider(passThroughTestRuntime(), api),
            settingsDataStore = settings,
            tmdbService = tmdbService
        )

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
            meta = stubMeta("kitsu:12", ContentType.SERIES),
            fallbackItemId = "kitsu:12",
            fallbackItemType = "series"
        )

        assertNull(result)
        coVerify(exactly = 0) { tmdbService.ensureTmdbId(any(), any()) }
        coVerify(exactly = 0) { tmdbService.tmdbToImdb(any(), any()) }
        coVerify(exactly = 0) { api.getRating(any(), any(), any(), any()) }
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
