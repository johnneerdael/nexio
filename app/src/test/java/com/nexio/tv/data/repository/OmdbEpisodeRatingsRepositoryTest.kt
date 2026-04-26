package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.passThroughTestRuntime
import com.nexio.tv.core.integration.byteArrayRuntimeFixture
import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.data.integration.omdb.OmdbIntegrationProvider
import com.nexio.tv.data.local.OmdbSettingsDataStore
import com.nexio.tv.data.remote.api.OmdbApi
import com.nexio.tv.data.remote.api.OmdbSeasonEpisodeDto
import com.nexio.tv.data.remote.api.OmdbSeasonResponse
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaCompany
import com.nexio.tv.domain.model.MetaLink
import com.nexio.tv.domain.model.OmdbSettings
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.Video
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

class OmdbEpisodeRatingsRepositoryTest {

    @Test
    fun `fetches one season once and reuses cached ratings for 24h`() = runTest {
        var now = 1_000L
        val omdbApi = mockk<OmdbApi>()
        val settingsStore = mockk<OmdbSettingsDataStore>()
        val tmdbService = mockk<TmdbService>(relaxed = true)
        every { settingsStore.settings } returns flowOf(OmdbSettings(enabled = true, apiKey = "omdb-key"))
        coEvery { omdbApi.getSeason("omdb-key", "tt27444205", 1) } returns Response.success(
            OmdbSeasonResponse(
                response = "True",
                episodes = listOf(
                    OmdbSeasonEpisodeDto(episode = "1", imdbRating = "8.3", imdbId = "tt31547200"),
                    OmdbSeasonEpisodeDto(episode = "2", imdbRating = "N/A", imdbId = "tt32459853"),
                    OmdbSeasonEpisodeDto(episode = "3", imdbRating = "7.5", imdbId = "tt32459854")
                )
            )
        )

        val repository = OmdbEpisodeRatingsRepository(
            omdbProvider = OmdbIntegrationProvider(passThroughTestRuntime(), omdbApi),
            omdbSettingsDataStore = settingsStore,
            tmdbService = tmdbService
        ).also {
            it.nowMsProvider = { now }
        }
        val meta = stubMeta(id = "tt27444205")

        val first = repository.getEpisodeRatingsForMeta(
            meta = meta,
            fallbackItemId = "",
            fallbackItemType = "series",
            episodesBySeason = mapOf(1 to setOf(1, 2, 3))
        )
        val second = repository.getEpisodeRatingsForMeta(
            meta = meta,
            fallbackItemId = "",
            fallbackItemType = "series",
            episodesBySeason = mapOf(1 to setOf(1, 2, 3))
        )

        assertEquals(mapOf((1 to 1) to 8.3, (1 to 3) to 7.5), first)
        assertEquals(first, second)
        coVerify(exactly = 1) { omdbApi.getSeason("omdb-key", "tt27444205", 1) }
    }

    @Test
    fun `expired season cache serves stale values and defers retry after refresh failure`() = runTest {
        var now = 1_000L
        val omdbApi = mockk<OmdbApi>()
        val settingsStore = mockk<OmdbSettingsDataStore>()
        val tmdbService = mockk<TmdbService>(relaxed = true)
        every { settingsStore.settings } returns flowOf(OmdbSettings(enabled = true, apiKey = "omdb-key"))
        coEvery { omdbApi.getSeason("omdb-key", "tt27444205", 1) } returns Response.success(
            OmdbSeasonResponse(
                response = "True",
                episodes = listOf(
                    OmdbSeasonEpisodeDto(episode = "1", imdbRating = "8.3", imdbId = "tt31547200")
                )
            )
        ) andThenThrows IOException("rate limited")

        val repository = OmdbEpisodeRatingsRepository(
            omdbProvider = OmdbIntegrationProvider(passThroughTestRuntime(), omdbApi),
            omdbSettingsDataStore = settingsStore,
            tmdbService = tmdbService
        ).also {
            it.nowMsProvider = { now }
        }
        val meta = stubMeta(id = "tt27444205")

        val fresh = repository.getEpisodeRatingsForMeta(
            meta = meta,
            fallbackItemId = "",
            fallbackItemType = "series",
            episodesBySeason = mapOf(1 to setOf(1))
        )

        now += OMDB_EPISODE_RATINGS_TTL_MS + 1L

        val staleAfterFailure = repository.getEpisodeRatingsForMeta(
            meta = meta,
            fallbackItemId = "",
            fallbackItemType = "series",
            episodesBySeason = mapOf(1 to setOf(1))
        )
        val noRetryInsideNewTtl = repository.getEpisodeRatingsForMeta(
            meta = meta,
            fallbackItemId = "",
            fallbackItemType = "series",
            episodesBySeason = mapOf(1 to setOf(1))
        )

        assertEquals(mapOf((1 to 1) to 8.3), fresh)
        assertEquals(fresh, staleAfterFailure)
        assertEquals(fresh, noRetryInsideNewTtl)
        coVerify(exactly = 2) { omdbApi.getSeason("omdb-key", "tt27444205", 1) }
    }

    @Test
    fun `concurrent requests for the same season are coalesced`() = runTest {
        var now = 1_000L
        val omdbApi = mockk<OmdbApi>()
        val settingsStore = mockk<OmdbSettingsDataStore>()
        val tmdbService = mockk<TmdbService>(relaxed = true)
        every { settingsStore.settings } returns flowOf(OmdbSettings(enabled = true, apiKey = "omdb-key"))
        coEvery { omdbApi.getSeason("omdb-key", "tt27444205", 1) } coAnswers {
            kotlinx.coroutines.delay(50)
            Response.success(
                OmdbSeasonResponse(
                    response = "True",
                    episodes = listOf(
                        OmdbSeasonEpisodeDto(episode = "1", imdbRating = "8.3", imdbId = "tt31547200")
                    )
                )
            )
        }

        val repository = OmdbEpisodeRatingsRepository(
            omdbProvider = OmdbIntegrationProvider(passThroughTestRuntime(), omdbApi),
            omdbSettingsDataStore = settingsStore,
            tmdbService = tmdbService
        ).also {
            it.nowMsProvider = { now }
        }
        val meta = stubMeta(id = "tt27444205")

        val first = async {
            repository.getEpisodeRatingsForMeta(
                meta = meta,
                fallbackItemId = "",
                fallbackItemType = "series",
                episodesBySeason = mapOf(1 to setOf(1))
            )
        }
        val second = async {
            repository.getEpisodeRatingsForMeta(
                meta = meta,
                fallbackItemId = "",
                fallbackItemType = "series",
                episodesBySeason = mapOf(1 to setOf(1))
            )
        }

        assertEquals(first.await(), second.await())
        coVerify(exactly = 1) { omdbApi.getSeason("omdb-key", "tt27444205", 1) }
    }

    @Test
    fun `season ratings survive real runtime cache round trip`() = runTest {
        val fixture = byteArrayRuntimeFixture()
        val omdbApi = mockk<OmdbApi>()
        val provider = OmdbIntegrationProvider(fixture.runtime, omdbApi)

        coEvery { omdbApi.getSeason("omdb-key", "tt27444205", 1) } returns Response.success(
            OmdbSeasonResponse(
                response = "True",
                episodes = listOf(
                    OmdbSeasonEpisodeDto(episode = "1", imdbRating = "8.3", imdbId = "tt31547200"),
                    OmdbSeasonEpisodeDto(episode = "3", imdbRating = "7.5", imdbId = "tt32459854")
                )
            )
        )

        val first = provider.getSeasonRatings("tt27444205", "omdb-key", 1)
        val second = provider.getSeasonRatings("tt27444205", "omdb-key", 1)

        assertEquals(mapOf((1 to 1) to 8.3, (1 to 3) to 7.5), first)
        assertEquals(first, second)
        coVerify(exactly = 1) { omdbApi.getSeason("omdb-key", "tt27444205", 1) }
    }

    private fun stubMeta(id: String): Meta {
        return Meta(
            id = id,
            type = ContentType.SERIES,
            rawType = ContentType.SERIES.toApiString(),
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
