package com.nexio.tv.core.anime

import com.nexio.tv.data.remote.api.KitsuAnimeAttributes
import com.nexio.tv.data.remote.api.KitsuAnimeResource
import com.nexio.tv.data.remote.api.KitsuApi
import com.nexio.tv.data.remote.api.KitsuCollectionResponse
import com.nexio.tv.data.remote.api.KitsuImage
import com.nexio.tv.data.remote.api.KitsuResourceResponse
import com.nexio.tv.data.repository.KitsuAuthService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response

class KitsuMetadataServiceTest {
    @Test
    fun `fetchEnrichment maps kitsu details`() = runTest {
        val api = mockk<KitsuApi>()
        val mapper = mockk<AnimeIdMappingService>()
        val auth = mockk<KitsuAuthService>()
        val service = KitsuMetadataService(api, mapper, auth)

        coEvery { mapper.resolveKitsuId(AnimeStremioId(AnimeIdSource.MAL, "5114"), ContentMediaKind.SERIES) } returns "3936"
        coEvery { auth.providerEnabled() } returns true
        coEvery { auth.validAccessToken() } returns null
        coEvery { api.getAnime(null, "3936", "categories,mediaRelationships.destination") } returns Response.success(
            KitsuResourceResponse(
                data = KitsuAnimeResource(
                    id = "3936",
                    attributes = KitsuAnimeAttributes(
                        canonicalTitle = "Fullmetal Alchemist: Brotherhood",
                        synopsis = "Two brothers search for a Philosopher's Stone.",
                        subtype = "TV",
                        startDate = "2009-04-05",
                        endDate = "2010-07-04",
                        episodeCount = 64,
                        episodeLength = 24,
                        averageRating = "88.12",
                        ageRating = "R",
                        posterImage = KitsuImage(original = "https://media.kitsu.io/poster.jpg"),
                        coverImage = KitsuImage(original = "https://media.kitsu.io/cover.jpg")
                    )
                )
            )
        )

        val enrichment = service.fetchEnrichment("mal:5114", ContentMediaKind.SERIES)

        assertEquals("Fullmetal Alchemist: Brotherhood", enrichment?.localizedTitle)
        assertEquals("Two brothers search for a Philosopher's Stone.", enrichment?.description)
        assertEquals("https://media.kitsu.io/poster.jpg", enrichment?.poster)
        assertEquals("https://media.kitsu.io/cover.jpg", enrichment?.backdrop)
        assertEquals("2009-04-05", enrichment?.releaseInfo)
        assertEquals(24, enrichment?.runtimeMinutes)
        assertEquals(8.812, enrichment?.rating ?: 0.0, 0.001)
        assertEquals("R", enrichment?.ageRating)
    }

    @Test
    fun `fetchEpisodeEnrichment maps kitsu episode numbers`() = runTest {
        val api = mockk<KitsuApi>()
        val mapper = mockk<AnimeIdMappingService>()
        val auth = mockk<KitsuAuthService>()
        val service = KitsuMetadataService(api, mapper, auth)

        coEvery { mapper.resolveKitsuId(AnimeStremioId(AnimeIdSource.KITSU, "1"), ContentMediaKind.SERIES) } returns "1"
        coEvery { auth.providerEnabled() } returns true
        coEvery { auth.validAccessToken() } returns null
        coEvery { api.getAnimeEpisodes(null, "1", 20, 0) } returns Response.success(
            KitsuCollectionResponse(
                data = listOf(
                    KitsuAnimeResource(
                        id = "episode-1",
                        attributes = KitsuAnimeAttributes(
                            canonicalTitle = "Asteroid Blues",
                            synopsis = "Spike and Jet chase a bounty.",
                            number = 1,
                            seasonNumber = 1,
                            airdate = "1998-04-03",
                            length = 24,
                            thumbnail = KitsuImage(original = "https://media.kitsu.io/e1.jpg")
                        )
                    )
                )
            )
        )

        val episodes = service.fetchEpisodeEnrichment("kitsu:1", ContentMediaKind.SERIES, listOf(1))

        assertEquals("Asteroid Blues", episodes[1 to 1]?.title)
        assertEquals("1998-04-03", episodes[1 to 1]?.airDate)
        assertEquals("https://media.kitsu.io/e1.jpg", episodes[1 to 1]?.thumbnail)
    }

    @Test
    fun `passes bearer token to Kitsu when authenticated`() = runTest {
        val api = mockk<KitsuApi>()
        val mapper = mockk<AnimeIdMappingService>()
        val auth = mockk<KitsuAuthService>()
        val service = KitsuMetadataService(api, mapper, auth)

        coEvery { mapper.resolveKitsuId(any(), any()) } returns "3936"
        coEvery { auth.providerEnabled() } returns true
        coEvery { auth.validAccessToken() } returns "access-token"
        coEvery { api.getAnime("Bearer access-token", "3936", any()) } returns Response.success(
            KitsuResourceResponse(data = KitsuAnimeResource(id = "3936", attributes = KitsuAnimeAttributes(canonicalTitle = "Title")))
        )

        service.fetchEnrichment("mal:5114", ContentMediaKind.SERIES)

        coVerify(exactly = 1) { api.getAnime("Bearer access-token", "3936", any()) }
    }

    @Test
    fun `returns null when id is not anime`() = runTest {
        val auth = mockk<KitsuAuthService>()
        coEvery { auth.providerEnabled() } returns true
        val service = KitsuMetadataService(mockk(relaxed = true), mockk(relaxed = true), auth)

        assertNull(service.fetchEnrichment("trakt:123", ContentMediaKind.SERIES))
    }

    @Test
    fun `returns null when provider is disabled`() = runTest {
        val api = mockk<KitsuApi>(relaxed = true)
        val auth = mockk<KitsuAuthService>()
        coEvery { auth.providerEnabled() } returns false
        val service = KitsuMetadataService(api, mockk(relaxed = true), auth)

        assertNull(service.fetchEnrichment("mal:5114", ContentMediaKind.SERIES))
        coVerify(exactly = 0) { api.getAnime(any(), any(), any()) }
    }
}
