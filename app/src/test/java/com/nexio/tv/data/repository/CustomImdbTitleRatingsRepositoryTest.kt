package com.nexio.tv.data.repository

import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.data.local.ImdbSettingsDataStore
import com.nexio.tv.data.remote.CustomImdbClient
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ImdbSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomImdbTitleRatingsRepositoryTest {
    @Test
    fun `fetches configured imdb title rating by imdb id`() = runTest {
        val client = mockk<CustomImdbClient>()
        val settings = mockk<ImdbSettingsDataStore>()
        val tmdbService = mockk<TmdbService>(relaxed = true)
        val repository = CustomImdbTitleRatingsRepository(client, settings, tmdbService)

        every { settings.settings } returns flowOf(
            ImdbSettings(enabled = true, baseUrl = "https://ratings.example.com", apiKey = "secret-key")
        )
        coEvery {
            client.fetchTitleRatings("https://ratings.example.com", "secret-key", listOf("tt0944947"))
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
    fun `resolves tmdb id to imdb before custom bulk request`() = runTest {
        val client = mockk<CustomImdbClient>()
        val settings = mockk<ImdbSettingsDataStore>()
        val tmdbService = mockk<TmdbService>()
        val repository = CustomImdbTitleRatingsRepository(client, settings, tmdbService)

        every { settings.settings } returns flowOf(
            ImdbSettings(enabled = true, baseUrl = "https://ratings.example.com", apiKey = "secret-key")
        )
        coEvery { tmdbService.ensureTmdbId("tmdb:1399", "series") } returns "1399"
        coEvery { tmdbService.tmdbToImdb(1399, "series") } returns "tt0944947"
        coEvery {
            client.fetchTitleRatings("https://ratings.example.com", "secret-key", listOf("tt0944947"))
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
