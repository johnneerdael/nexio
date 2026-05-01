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
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class MDBListTitleRatingsTest {
    @Test
    fun `getRatingsForMeta requests imdb when showImdb is enabled`() = runTest {
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
                showTomatoes = false,
                showAudience = false,
                showMetacritic = false
            )
        )
        coEvery {
            api.getRating("show", "imdb", "mdb-key", any())
        } returns Response.success(
            MDBListRatingResponseDto(
                ratings = listOf(MDBListRatingItemDto(id = "tt0944947", rating = 9.2))
            )
        )

        val result = repository.getRatingsForMeta(
            meta = stubMeta("tt0944947", ContentType.SERIES),
            fallbackItemId = "tt0944947",
            fallbackItemType = "series"
        )

        assertEquals(9.2, result?.ratings?.imdb ?: 0.0, 0.0)
        assertTrue(result?.hasImdbRating == true)
    }

    @Test
    fun `getRatingsForMeta uses imdb override before resolving metadata ids`() = runTest {
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
                showTrakt = false,
                showImdb = true,
                showTmdb = false,
                showLetterboxd = false,
                showTomatoes = false,
                showAudience = false,
                showMetacritic = false
            )
        )
        coEvery {
            api.getRating("show", "imdb", "mdb-key", capture(request))
        } returns Response.success(
            MDBListRatingResponseDto(
                ratings = listOf(MDBListRatingItemDto(id = "tt0944947", rating = 8.8))
            )
        )

        val result = repository.getRatingsForMeta(
            meta = stubMeta("tmdb:1399", ContentType.SERIES),
            fallbackItemId = "tmdb:1399",
            fallbackItemType = "series",
            imdbIdOverride = " tt0944947 "
        )

        assertEquals(8.8, result?.ratings?.imdb ?: 0.0, 0.0)
        assertEquals(listOf("tt0944947"), request.captured.ids)
        coVerify(exactly = 0) { tmdbService.tmdbToImdb(any(), any()) }
    }

    @Test
    fun `enrichPreview replaces tmdb score with mdblist imdb rating`() = runTest {
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
                showTomatoes = false,
                showAudience = false,
                showMetacritic = false
            )
        )
        coEvery {
            api.getRating("movie", "imdb", "mdb-key", any())
        } returns Response.success(
            MDBListRatingResponseDto(
                ratings = listOf(MDBListRatingItemDto(id = "tt1375666", rating = 8.8))
            )
        )

        val preview = MetaPreview(
            id = "tt1375666",
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

        assertEquals(8.8f, enriched.imdbRating ?: 0f, 0.0f)
        assertEquals(TitleRatingSource.IMDB, enriched.ratingSource)
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
