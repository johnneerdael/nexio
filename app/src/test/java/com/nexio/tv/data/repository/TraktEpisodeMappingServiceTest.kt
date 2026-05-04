package com.nexio.tv.data.repository

import com.nexio.tv.data.remote.api.TraktApi
import com.nexio.tv.data.remote.dto.trakt.TraktEpisodeSummaryDto
import com.nexio.tv.data.remote.dto.trakt.TraktShowSeasonWithEpisodesDto
import com.nexio.tv.domain.model.TrackingProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response

class TraktEpisodeMappingServiceTest {

    @Test
    fun prefetch_returns_mapping_for_show_with_episodes_in_trakt() = runBlocking {
        val seasons = listOf(
            seasonWith(
                number = 1,
                episodes = listOf(
                    episode(season = 1, number = 1, title = "Pilot"),
                    episode(season = 1, number = 2, title = "Two")
                )
            )
        )
        val service = buildService(
            traktApi = mockk {
                coEvery { getShowSeasons(any(), any(), any()) } returns Response.success(seasons)
            }
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
            traktApi = mockk {
                coEvery { getShowSeasons(any(), any(), any()) } returns Response.success(emptyList())
            }
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
        val seasons = listOf(
            seasonWith(
                number = 1,
                episodes = listOf(episode(season = 1, number = 1, title = "Pilot"))
            )
        )
        val service = buildService(
            traktApi = mockk {
                coEvery { getShowSeasons(any(), any(), any()) } returns Response.success(seasons)
            }
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
        val seasons = listOf(
            seasonWith(
                number = 1,
                episodes = listOf(
                    episode(season = 1, number = 1, title = "Pilot"),
                    episode(season = 1, number = 2, title = "Two")
                )
            )
        )
        val traktApi = mockk<TraktApi> {
            coEvery { getShowSeasons(any(), any(), any()) } returns Response.success(seasons)
        }
        val service = buildService(traktApi = traktApi)

        // Two distinct cache keys (different episode), but same showLookupId →
        // getShowSeasons should only fire once thanks to the per-show cache.
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
        coVerify(exactly = 1) { traktApi.getShowSeasons(any(), any(), any()) }
    }

    @Test
    fun cached_lookup_does_not_hit_network() = runBlocking {
        val seasons = listOf(
            seasonWith(
                number = 1,
                episodes = listOf(episode(season = 1, number = 1, title = "Pilot"))
            )
        )
        val traktApi = mockk<TraktApi> {
            coEvery { getShowSeasons(any(), any(), any()) } returns Response.success(seasons)
        }
        val service = buildService(traktApi = traktApi)

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

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun buildService(traktApi: TraktApi): TraktEpisodeMappingService {
        val session = TrackingAuthSession(
            provider = TrackingProvider.TRAKT,
            profileId = 1,
            credentialHash = "hash-p1"
        )
        val traktAuthService = mockk<TraktAuthService> {
            coEvery { accountScopedSession() } returns session
            coEvery { accountScopedSession(any()) } returns session
            coEvery {
                executeAuthorizedRequestWithinRuntimeCall(
                    any<TrackingAuthSession>(),
                    any<suspend (String) -> Response<List<TraktShowSeasonWithEpisodesDto>>>()
                )
            } coAnswers {
                val block = secondArg<suspend (String) -> Response<List<TraktShowSeasonWithEpisodesDto>>>()
                block("Bearer hash-p1")
            }
        }
        return TraktEpisodeMappingService(
            traktApi = traktApi,
            traktAuthService = traktAuthService
        )
    }

    private fun seasonWith(
        number: Int,
        episodes: List<TraktEpisodeSummaryDto>
    ): TraktShowSeasonWithEpisodesDto = TraktShowSeasonWithEpisodesDto(
        number = number,
        title = "Season $number",
        ids = null,
        episodes = episodes
    )

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
