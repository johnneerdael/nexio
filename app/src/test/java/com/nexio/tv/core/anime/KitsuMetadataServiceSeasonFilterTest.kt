package com.nexio.tv.core.anime

import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.data.integration.kitsu.KitsuIntegrationProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KitsuMetadataServiceSeasonFilterTest {

    // Simulate the raw episodes that KitsuIntegrationProvider would map — seasons 1, 2, and 3.
    private val allEpisodesByKey: Map<Pair<Int, Int>, TvEpisodeMetadata> = mapOf(
        (1 to 1) to TvEpisodeMetadata(seasonNumber = 1, episodeNumber = 1, title = "S1E1"),
        (1 to 2) to TvEpisodeMetadata(seasonNumber = 1, episodeNumber = 2, title = "S1E2"),
        (2 to 1) to TvEpisodeMetadata(seasonNumber = 2, episodeNumber = 1, title = "S2E1"),
        (3 to 1) to TvEpisodeMetadata(seasonNumber = 3, episodeNumber = 1, title = "S3E1"),
        (3 to 2) to TvEpisodeMetadata(seasonNumber = 3, episodeNumber = 2, title = "S3E2"),
        (3 to 3) to TvEpisodeMetadata(seasonNumber = 3, episodeNumber = 3, title = "S3E3")
    )

    private fun buildService(): KitsuMetadataService {
        val provider = mockk<KitsuIntegrationProvider>()
        coEvery {
            provider.fetchEpisodeEnrichment(
                rawId = any(),
                kitsuId = any(),
                mediaKind = any(),
                mapper = any()
            )
        } coAnswers {
            // Invoke the mapper lambda with a representative list — the actual list contents
            // don't matter because KitsuMetadataService applies its own season filter after
            // calling the mapper. We pass the pre-mapped result directly by having the mock
            // invoke the mapper arg with an empty list and then returning our test map.
            // Since the mapper is the 4th argument (index 3), invoke it and ignore its result,
            // then return our controlled dataset.
            allEpisodesByKey
        }

        val idMappingService = AnimeIdMappingService(
            assetProvider = {
                AnimeIdMapAsset(
                    schemaVersion = 1,
                    recordsByKitsu = mapOf(
                        "13881" to AnimeIdMapRecord(
                            kitsu = "13881",
                            mal = "33486",
                            tmdb = null,
                            mediaType = "series"
                        )
                    ),
                    byKitsu = mapOf("13881" to "13881"),
                    byMal = mapOf("33486" to "13881"),
                    byAnilist = emptyMap(),
                    byAnidb = emptyMap(),
                    byTvdb = emptyMap(),
                    byTmdbMovie = emptyMap(),
                    byTmdbSeries = emptyMap(),
                    byImdb = emptyMap()
                )
            }
        )
        return KitsuMetadataService(provider = provider, idMappingService = idMappingService)
    }

    @Test
    fun `empty seasonNumbers returns all episodes regardless of their season attribute`() = runTest {
        val service = buildService()

        val result = service.fetchEpisodeEnrichment(
            rawId = "kitsu:13881",
            mediaKind = ContentMediaKind.SERIES,
            seasonNumbers = emptyList()
        )

        assertEquals(
            "All 6 episodes across seasons 1, 2, and 3 must be returned when seasonNumbers is empty",
            6,
            result.size
        )
        assertTrue("Season 1 episode 1 must be present", result.containsKey(1 to 1))
        assertTrue("Season 1 episode 2 must be present", result.containsKey(1 to 2))
        assertTrue("Season 2 episode 1 must be present", result.containsKey(2 to 1))
        assertTrue("Season 3 episode 1 must be present", result.containsKey(3 to 1))
        assertTrue("Season 3 episode 2 must be present", result.containsKey(3 to 2))
        assertTrue("Season 3 episode 3 must be present", result.containsKey(3 to 3))
    }

    @Test
    fun `seasonNumbers with season 3 only returns season-3 episodes`() = runTest {
        val service = buildService()

        val result = service.fetchEpisodeEnrichment(
            rawId = "kitsu:13881",
            mediaKind = ContentMediaKind.SERIES,
            seasonNumbers = listOf(3)
        )

        assertEquals(
            "Only the 3 season-3 episodes must be returned when seasonNumbers = [3]",
            3,
            result.size
        )
        assertTrue("Season 3 episode 1 must be present", result.containsKey(3 to 1))
        assertTrue("Season 3 episode 2 must be present", result.containsKey(3 to 2))
        assertTrue("Season 3 episode 3 must be present", result.containsKey(3 to 3))
        assertTrue("Season 1 episodes must be absent", result.none { (k, _) -> k.first == 1 })
        assertTrue("Season 2 episodes must be absent", result.none { (k, _) -> k.first == 2 })
    }
}
