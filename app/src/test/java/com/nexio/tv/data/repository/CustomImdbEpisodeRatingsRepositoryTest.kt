package com.nexio.tv.data.repository

import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.data.remote.CustomImdbClient
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaCompany
import com.nexio.tv.domain.model.MetaLink
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.Video
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomImdbEpisodeRatingsRepositoryTest {

    @Test
    fun `reuses full results until full ttl expires`() = runTest {
        var now = 1_000L
        val client = mockk<CustomImdbClient>()
        val tmdbService = mockk<TmdbService>(relaxed = true)
        coEvery {
            client.fetchEpisodeRatings(tconst = "tt27444205")
        } returns mapOf(
            (1 to 1) to 8.3,
            (1 to 2) to 7.1
        )

        val repository = CustomImdbEpisodeRatingsRepository(client, tmdbService).also {
            it.nowMsProvider = { now }
        }
        val requestedEpisodes = mapOf(1 to setOf(1, 2))

        val first = repository.getEpisodeRatingsForMeta(
            meta = stubMeta("tt27444205"),
            fallbackItemId = "",
            fallbackItemType = "series",
            episodesBySeason = requestedEpisodes
        )
        val second = repository.getEpisodeRatingsForMeta(
            meta = stubMeta("tt27444205"),
            fallbackItemId = "",
            fallbackItemType = "series",
            episodesBySeason = requestedEpisodes
        )

        assertEquals(first, second)
        coVerify(exactly = 1) {
            client.fetchEpisodeRatings(tconst = "tt27444205")
        }
    }

    @Test
    fun `partial results use shorter retry ttl`() = runTest {
        var now = 1_000L
        val client = mockk<CustomImdbClient>()
        val tmdbService = mockk<TmdbService>(relaxed = true)
        coEvery {
            client.fetchEpisodeRatings(any())
        } returnsMany listOf(
            mapOf((1 to 1) to 8.3),
            mapOf((1 to 1) to 8.3, (1 to 2) to 7.1)
        )

        val repository = CustomImdbEpisodeRatingsRepository(client, tmdbService).also {
            it.nowMsProvider = { now }
        }
        val requestedEpisodes = mapOf(1 to setOf(1, 2))

        val partial = repository.getEpisodeRatingsForMeta(
            meta = stubMeta("tt27444205"),
            fallbackItemId = "",
            fallbackItemType = "series",
            episodesBySeason = requestedEpisodes
        )
        val cachedInsideRetryWindow = repository.getEpisodeRatingsForMeta(
            meta = stubMeta("tt27444205"),
            fallbackItemId = "",
            fallbackItemType = "series",
            episodesBySeason = requestedEpisodes
        )

        now += CUSTOM_IMDB_EPISODE_RATINGS_RETRY_TTL_MS + 1L

        val refreshed = repository.getEpisodeRatingsForMeta(
            meta = stubMeta("tt27444205"),
            fallbackItemId = "",
            fallbackItemType = "series",
            episodesBySeason = requestedEpisodes
        )

        assertEquals(mapOf((1 to 1) to 8.3), partial)
        assertEquals(partial, cachedInsideRetryWindow)
        assertEquals(mapOf((1 to 1) to 8.3, (1 to 2) to 7.1), refreshed)
        coVerify(exactly = 2) { client.fetchEpisodeRatings(any()) }
    }

    @Test
    fun `wider request bypasses narrower cached response`() = runTest {
        var now = 1_000L
        val client = mockk<CustomImdbClient>()
        val tmdbService = mockk<TmdbService>(relaxed = true)
        coEvery { client.fetchEpisodeRatings(any()) } returnsMany listOf(
            mapOf((1 to 1) to 8.3),
            mapOf((1 to 1) to 8.3, (1 to 2) to 7.1)
        )

        val repository = CustomImdbEpisodeRatingsRepository(client, tmdbService).also {
            it.nowMsProvider = { now }
        }

        val narrow = repository.getEpisodeRatingsForMeta(
            meta = stubMeta("tt27444205"),
            fallbackItemId = "",
            fallbackItemType = "series",
            episodesBySeason = mapOf(1 to setOf(1))
        )
        val wider = repository.getEpisodeRatingsForMeta(
            meta = stubMeta("tt27444205"),
            fallbackItemId = "",
            fallbackItemType = "series",
            episodesBySeason = mapOf(1 to setOf(1, 2))
        )

        assertEquals(mapOf((1 to 1) to 8.3), narrow)
        assertEquals(mapOf((1 to 1) to 8.3, (1 to 2) to 7.1), wider)
        coVerify(exactly = 2) { client.fetchEpisodeRatings(any()) }
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
