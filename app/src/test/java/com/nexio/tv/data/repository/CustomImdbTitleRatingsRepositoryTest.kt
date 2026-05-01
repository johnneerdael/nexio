package com.nexio.tv.data.repository

import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.data.remote.CustomImdbClient
import com.nexio.tv.domain.model.ContentType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomImdbTitleRatingsRepositoryTest {
    @Test
    fun `fetches configured imdb title rating by imdb id`() = runTest {
        val client = mockk<CustomImdbClient>()
        val tmdbService = mockk<TmdbService>(relaxed = true)
        val repository = CustomImdbTitleRatingsRepository(client, tmdbService)

        coEvery {
            client.fetchTitleRatings(listOf("tt0944947"))
        } returns mapOf("tt0944947" to 9.2)

        val rating = repository.getTitleRating(
            contentId = "tt0944947",
            fallbackItemId = "tt0944947",
            contentType = ContentType.SERIES,
            fallbackItemType = "series"
        )

        assertEquals(9.2, rating ?: 0.0, 0.0)
    }

    @Test
    fun `direct imdb rating extracts canonical imdb id`() = runTest {
        val client = mockk<CustomImdbClient>()
        val tmdbService = mockk<TmdbService>(relaxed = true)
        val repository = CustomImdbTitleRatingsRepository(client, tmdbService)

        coEvery {
            client.fetchTitleRatings(listOf("tt0944947"))
        } returns mapOf("tt0944947" to 9.2)

        val rating = repository.getTitleRatingByImdbId(" tt0944947/foo ")

        assertEquals(9.2, rating ?: 0.0, 0.0)
        coVerify(exactly = 1) { client.fetchTitleRatings(listOf("tt0944947")) }
    }

    @Test
    fun `direct imdb rating rejects invalid imdb sidecar`() = runTest {
        val client = mockk<CustomImdbClient>()
        val tmdbService = mockk<TmdbService>(relaxed = true)
        val repository = CustomImdbTitleRatingsRepository(client, tmdbService)

        val rating = repository.getTitleRatingByImdbId("ttbad")

        assertEquals(null, rating)
        coVerify(exactly = 0) { client.fetchTitleRatings(any()) }
    }

    @Test
    fun `resolves tmdb id to imdb before custom bulk request`() = runTest {
        val client = mockk<CustomImdbClient>()
        val tmdbService = mockk<TmdbService>()
        val repository = CustomImdbTitleRatingsRepository(client, tmdbService)

        coEvery { tmdbService.ensureTmdbId("tmdb:1399", "series") } returns "1399"
        coEvery { tmdbService.tmdbToImdb(1399, "series") } returns "tt0944947"
        coEvery {
            client.fetchTitleRatings(listOf("tt0944947"))
        } returns mapOf("tt0944947" to 9.2)

        val rating = repository.getTitleRating(
            contentId = "tmdb:1399",
            fallbackItemId = "tmdb:1399",
            contentType = ContentType.SERIES,
            fallbackItemType = "series"
        )

        assertEquals(9.2, rating ?: 0.0, 0.0)
        coVerify(exactly = 1) { tmdbService.tmdbToImdb(1399, "series") }
    }
}
