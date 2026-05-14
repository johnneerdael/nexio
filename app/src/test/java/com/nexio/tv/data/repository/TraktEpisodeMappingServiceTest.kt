package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.data.integration.trakt.TraktEpisodeMappingService
import com.nexio.tv.data.integration.trakt.TraktIntegrationProvider
import com.nexio.tv.data.remote.dto.trakt.TraktEpisodeSummaryDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TraktEpisodeMappingServiceTest {

    @Test
    fun prefetch_returns_mapping_for_show_with_episodes_in_trakt() = runBlocking {
        val episodes = listOf(
            episode(season = 1, number = 1, title = "Pilot"),
            episode(season = 1, number = 2, title = "Two")
        )
        val service = buildService(
            episodes = episodes
        )

        val mapping = service.prefetchEpisodeMapping(
            contentId = "tt1234567",
            contentType = "series",
            videoId = null,
            season = 1,
            episode = 1
        )

        assertNotNull(mapping)
        assertEquals(1, mapping?.season)
        assertEquals(1, mapping?.episode)
        assertEquals("Pilot", mapping?.title)
    }

    @Test
    fun prefetch_returns_null_when_trakt_returns_empty() = runBlocking {
        val service = buildService(
            episodes = emptyList()
        )

        val mapping = service.prefetchEpisodeMapping(
            contentId = "tt1234567",
            contentType = "series",
            videoId = null,
            season = 1,
            episode = 1
        )

        assertNull(mapping)
    }

    @Test
    fun prefetch_returns_null_when_episode_missing_from_trakt_tree() = runBlocking {
        val episodes = listOf(episode(season = 1, number = 1, title = "Pilot"))
        val service = buildService(
            episodes = episodes
        )

        // Trakt only knows S1E1 — asking for S1E5 should yield null (no addon fallback).
        val mapping = service.prefetchEpisodeMapping(
            contentId = "tt1234567",
            contentType = "series",
            videoId = null,
            season = 1,
            episode = 5
        )

        assertNull(mapping)
    }

    @Test
    fun prefetch_caches_results_per_show_id() = runBlocking {
        val episodes = listOf(
            episode(season = 1, number = 1, title = "Pilot"),
            episode(season = 1, number = 2, title = "Two")
        )
        val traktProvider = mockk<TraktIntegrationProvider> {
            coEvery { getSeasonEpisodes(any(), any(), any()) } returns IntegrationCallResult.Success(episodes)
        }
        val service = buildService(traktProvider = traktProvider)

        // Two distinct cache keys (different episode), but same showLookupId →
        // getSeasonEpisodes should only fire once thanks to the per-show/season cache.
        val first = service.prefetchEpisodeMapping(
            contentId = "tt1234567",
            contentType = "series",
            videoId = null,
            season = 1,
            episode = 1
        )
        val second = service.prefetchEpisodeMapping(
            contentId = "tt1234567",
            contentType = "series",
            videoId = null,
            season = 1,
            episode = 2
        )

        assertNotNull(first)
        assertNotNull(second)
        coVerify(exactly = 1) { traktProvider.getSeasonEpisodes(any(), any(), any()) }
    }

    @Test
    fun cached_lookup_does_not_hit_network() = runBlocking {
        val episodes = listOf(episode(season = 1, number = 1, title = "Pilot"))
        val service = buildService(episodes = episodes)

        service.prefetchEpisodeMapping(
            contentId = "tt1234567",
            contentType = "series",
            videoId = null,
            season = 1,
            episode = 1
        )
        val cached = service.getCachedEpisodeMapping(
            contentId = "tt1234567",
            contentType = "series",
            videoId = null,
            season = 1,
            episode = 1
        )

        assertNotNull(cached)
        assertEquals("Pilot", cached?.title)
    }

    @Test
    fun concurrent_prefetch_for_same_show_dedups_to_single_api_call() = runBlocking {
        val episodes = listOf(
            episode(season = 1, number = 1, title = "Pilot"),
            episode(season = 1, number = 2, title = "Two")
        )
        val traktProvider = mockk<TraktIntegrationProvider> {
            coEvery { getSeasonEpisodes(any(), any(), any()) } coAnswers {
                delay(50)
                IntegrationCallResult.Success(episodes)
            }
        }
        val service = buildService(traktProvider = traktProvider)

        val results = listOf(
            async {
                service.prefetchEpisodeMapping(
                    contentId = "tt8118186",
                    contentType = "series",
                    videoId = null,
                    season = 1,
                    episode = 1
                )
            },
            async {
                service.prefetchEpisodeMapping(
                    contentId = "tt8118186",
                    contentType = "series",
                    videoId = null,
                    season = 1,
                    episode = 2
                )
            }
        ).awaitAll()

        coVerify(exactly = 1) { traktProvider.getSeasonEpisodes(any(), any(), any()) }
        assertNotNull(results[0])
        assertNotNull(results[1])
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun buildService(
        episodes: List<TraktEpisodeSummaryDto>
    ): TraktEpisodeMappingService {
        return buildService(
            traktProvider = mockk {
                coEvery { getSeasonEpisodes(any(), any(), any()) } returns IntegrationCallResult.Success(episodes)
            }
        )
    }

    private fun buildService(traktProvider: TraktIntegrationProvider): TraktEpisodeMappingService {
        return TraktEpisodeMappingService(
            traktIntegrationProvider = traktProvider
        )
    }

    private fun episode(
        season: Int,
        number: Int,
        title: String?
    ): TraktEpisodeSummaryDto = TraktEpisodeSummaryDto(
        season = season,
        number = number,
        title = title
    )
}
