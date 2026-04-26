package com.nexio.tv.core.anime

import com.nexio.tv.core.integration.passThroughTestRuntime
import com.nexio.tv.data.integration.kitsu.KitsuIntegrationProvider
import com.nexio.tv.data.remote.api.KitsuAnimeAttributes
import com.nexio.tv.data.remote.api.KitsuAnimeCharacterAttributes
import com.nexio.tv.data.remote.api.KitsuAnimeCharacterRelationships
import com.nexio.tv.data.remote.api.KitsuAnimeCharacterResource
import com.nexio.tv.data.remote.api.KitsuAnimeProductionAttributes
import com.nexio.tv.data.remote.api.KitsuAnimeProductionRelationships
import com.nexio.tv.data.remote.api.KitsuAnimeProductionResource
import com.nexio.tv.data.remote.api.KitsuAnimeResource
import com.nexio.tv.data.remote.api.KitsuApi
import com.nexio.tv.data.remote.api.KitsuAnimeStaffAttributes
import com.nexio.tv.data.remote.api.KitsuAnimeStaffRelationships
import com.nexio.tv.data.remote.api.KitsuAnimeStaffResource
import com.nexio.tv.data.remote.api.KitsuCastingAttributes
import com.nexio.tv.data.remote.api.KitsuCastingRelationships
import com.nexio.tv.data.remote.api.KitsuCastingResource
import com.nexio.tv.data.remote.api.KitsuCollectionResponse
import com.nexio.tv.data.remote.api.KitsuImage
import com.nexio.tv.data.remote.api.KitsuIncludedResource
import com.nexio.tv.data.remote.api.KitsuInstallmentRelationships
import com.nexio.tv.data.remote.api.KitsuInstallmentResource
import com.nexio.tv.data.remote.api.KitsuLinks
import com.nexio.tv.data.remote.api.KitsuMediaRelationshipAttributes
import com.nexio.tv.data.remote.api.KitsuMediaRelationshipRelationships
import com.nexio.tv.data.remote.api.KitsuMediaRelationshipResource
import com.nexio.tv.data.remote.api.KitsuResourceResponse
import com.nexio.tv.data.remote.api.KitsuResourceIdentifier
import com.nexio.tv.data.remote.api.KitsuToOneRelationship
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
        val service = service(api, mapper, auth)

        coEvery { mapper.resolveKitsuId(AnimeStremioId(AnimeIdSource.MAL, "5114"), ContentMediaKind.SERIES) } returns "3936"
        coEvery { auth.providerEnabled() } returns true
        coEvery { auth.providerAuthenticated() } returns true
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
        assertNull("Kitsu average should not masquerade as IMDb or TMDB", enrichment?.rating)
        assertEquals("R", enrichment?.ageRating)
    }

    @Test
    fun `fetchEnrichment allows native kitsu ids without authentication`() = runTest {
        val api = mockk<KitsuApi>()
        val auth = mockk<KitsuAuthService>()
        val service = service(api, mockk(relaxed = true), auth)

        coEvery { auth.providerEnabled() } returns true
        coEvery { auth.validAccessToken() } returns null
        coEvery { api.getAnime(null, "3936", "categories,mediaRelationships.destination") } returns Response.success(
            KitsuResourceResponse(
                data = KitsuAnimeResource(
                    id = "3936",
                    attributes = KitsuAnimeAttributes(canonicalTitle = "Fullmetal Alchemist: Brotherhood")
                )
            )
        )

        val enrichment = service.fetchEnrichment("kitsu:3936", ContentMediaKind.SERIES)

        assertEquals("Fullmetal Alchemist: Brotherhood", enrichment?.localizedTitle)
        coVerify(exactly = 1) { api.getAnime(null, "3936", "categories,mediaRelationships.destination") }
    }

    @Test
    fun `fetchEpisodeEnrichment maps kitsu episode numbers`() = runTest {
        val api = mockk<KitsuApi>()
        val mapper = mockk<AnimeIdMappingService>()
        val auth = mockk<KitsuAuthService>()
        val service = service(api, mapper, auth)

        coEvery { mapper.resolveKitsuId(AnimeStremioId(AnimeIdSource.KITSU, "1"), ContentMediaKind.SERIES) } returns "1"
        coEvery { auth.providerEnabled() } returns true
        coEvery { auth.providerAuthenticated() } returns true
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
    fun `fetchEpisodeEnrichment allows native kitsu ids without authentication`() = runTest {
        val api = mockk<KitsuApi>()
        val auth = mockk<KitsuAuthService>()
        val service = service(api, mockk(relaxed = true), auth)

        coEvery { auth.providerEnabled() } returns true
        coEvery { auth.validAccessToken() } returns null
        coEvery { api.getAnimeEpisodes(null, "3936", 20, 0) } returns Response.success(
            KitsuCollectionResponse(
                data = listOf(
                    KitsuAnimeResource(
                        id = "episode-1",
                        attributes = KitsuAnimeAttributes(
                            canonicalTitle = "Episode 1",
                            number = 1,
                            seasonNumber = 1
                        )
                    )
                )
            )
        )

        val episodes = service.fetchEpisodeEnrichment("kitsu:3936", ContentMediaKind.SERIES, listOf(1))

        assertEquals("Episode 1", episodes[1 to 1]?.title)
        coVerify(exactly = 1) { api.getAnimeEpisodes(null, "3936", 20, 0) }
    }

    @Test
    fun `fetchEpisodeEnrichment follows kitsu episode pages`() = runTest {
        val api = mockk<KitsuApi>()
        val mapper = mockk<AnimeIdMappingService>()
        val auth = mockk<KitsuAuthService>()
        val service = service(api, mapper, auth)

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
                            number = 1,
                            seasonNumber = 1
                        )
                    )
                ),
                links = KitsuLinks(next = "https://kitsu.io/api/edge/anime/1/episodes?page%5Boffset%5D=20")
            )
        )
        coEvery { api.getAnimeEpisodes(null, "1", 20, 20) } returns Response.success(
            KitsuCollectionResponse(
                data = listOf(
                    KitsuAnimeResource(
                        id = "episode-21",
                        attributes = KitsuAnimeAttributes(
                            canonicalTitle = "Boogie Woogie Feng Shui",
                            number = 21,
                            seasonNumber = 1
                        )
                    )
                )
            )
        )

        val episodes = service.fetchEpisodeEnrichment("kitsu:1", ContentMediaKind.SERIES, listOf(1))

        assertEquals("Asteroid Blues", episodes[1 to 1]?.title)
        assertEquals("Boogie Woogie Feng Shui", episodes[1 to 21]?.title)
        coVerify(exactly = 1) { api.getAnimeEpisodes(null, "1", 20, 0) }
        coVerify(exactly = 1) { api.getAnimeEpisodes(null, "1", 20, 20) }
    }

    @Test
    fun `fetchEpisodeEnrichment without season filter keeps all kitsu seasons`() = runTest {
        val api = mockk<KitsuApi>()
        val mapper = mockk<AnimeIdMappingService>()
        val auth = mockk<KitsuAuthService>()
        val service = service(api, mapper, auth)

        coEvery { mapper.resolveKitsuId(AnimeStremioId(AnimeIdSource.KITSU, "1"), ContentMediaKind.SERIES) } returns "1"
        coEvery { auth.providerEnabled() } returns true
        coEvery { auth.validAccessToken() } returns null
        coEvery { api.getAnimeEpisodes(null, "1", 20, 0) } returns Response.success(
            KitsuCollectionResponse(
                data = listOf(
                    KitsuAnimeResource(
                        id = "episode-1",
                        attributes = KitsuAnimeAttributes(
                            canonicalTitle = "Season One",
                            number = 1,
                            seasonNumber = 1
                        )
                    ),
                    KitsuAnimeResource(
                        id = "episode-2",
                        attributes = KitsuAnimeAttributes(
                            canonicalTitle = "Season Two",
                            number = 1,
                            seasonNumber = 2
                        )
                    )
                )
            )
        )

        val episodes = service.fetchEpisodeEnrichment("kitsu:1", ContentMediaKind.SERIES, emptyList())

        assertEquals("Season One", episodes[1 to 1]?.title)
        assertEquals("Season Two", episodes[2 to 1]?.title)
    }

    @Test
    fun `fetchSeasonEpisodes maps kitsu episodes for a season`() = runTest {
        val api = mockk<KitsuApi>()
        val mapper = mockk<AnimeIdMappingService>()
        val auth = mockk<KitsuAuthService>()
        val service = service(api, mapper, auth)

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
                            number = 1,
                            seasonNumber = 1,
                            airdate = "1998-04-03"
                        )
                    )
                )
            )
        )

        val episodes = service.fetchSeasonEpisodes("kitsu:1", ContentMediaKind.SERIES, 1)

        assertEquals(1, episodes.size)
        assertEquals(1, episodes.first().episodeNumber)
        assertEquals("1998-04-03", episodes.first().airDate)
        assertEquals("Asteroid Blues", episodes.first().metadata.title)
    }

    @Test
    fun `fetchAdvancedDetail maps Kitsu characters staff productions and related titles`() = runTest {
        val api = mockk<KitsuApi>()
        val mapper = mockk<AnimeIdMappingService>()
        val auth = mockk<KitsuAuthService>()
        val service = service(api, mapper, auth)

        coEvery { mapper.resolveKitsuId(any(), any()) } returns "1"
        coEvery { auth.validAccessToken() } returns null
        coEvery { api.getCastingsByMedia(null, "1", "person,character", 40, 0) } returns Response.success(
            KitsuCollectionResponse(
                data = listOf(
                    KitsuCastingResource(
                        id = "cast-1",
                        attributes = KitsuCastingAttributes(
                            role = "Voice Actor",
                            voiceActor = true,
                            featured = true,
                            language = "Japanese"
                        ),
                        relationships = KitsuCastingRelationships(
                            character = KitsuToOneRelationship(KitsuResourceIdentifier(id = "char-1", type = "characters")),
                            person = KitsuToOneRelationship(KitsuResourceIdentifier(id = "person-actor-1", type = "people"))
                        )
                    )
                ),
                included = listOf(
                    KitsuIncludedResource(
                        id = "char-1",
                        type = "characters",
                        attributes = mapOf(
                            "name" to "Spike Spiegel",
                            "image" to mapOf("original" to "https://media.kitsu.io/spike.jpg")
                        )
                    ),
                    KitsuIncludedResource(
                        id = "person-actor-1",
                        type = "people",
                        attributes = mapOf(
                            "name" to "Koichi Yamadera",
                            "image" to mapOf("original" to "https://media.kitsu.io/yamadera.jpg")
                        )
                    )
                )
            )
        )
        coEvery { api.getAnimeStaff(null, "1", "person", 40, 0) } returns Response.success(
            KitsuCollectionResponse(
                data = listOf(
                    KitsuAnimeStaffResource(
                        id = "staff-1",
                        attributes = KitsuAnimeStaffAttributes(role = "Director"),
                        relationships = KitsuAnimeStaffRelationships(
                            person = KitsuToOneRelationship(KitsuResourceIdentifier(id = "person-1", type = "people"))
                        )
                    )
                ),
                included = listOf(
                    KitsuIncludedResource(
                        id = "person-1",
                        type = "people",
                        attributes = mapOf("name" to "Shinichiro Watanabe")
                    )
                )
            )
        )
        coEvery { api.getAnimeProductions(null, "1", "producer", 40, 0) } returns Response.success(
            KitsuCollectionResponse(
                data = listOf(
                    KitsuAnimeProductionResource(
                        id = "prod-1",
                        attributes = KitsuAnimeProductionAttributes(role = "studio"),
                        relationships = KitsuAnimeProductionRelationships(
                            producer = KitsuToOneRelationship(KitsuResourceIdentifier(id = "producer-1", type = "producers"))
                        )
                    )
                ),
                included = listOf(
                    KitsuIncludedResource(
                        id = "producer-1",
                        type = "producers",
                        attributes = mapOf("name" to "Sunrise")
                    )
                )
            )
        )
        coEvery { api.getAnimeMediaRelationships(null, "1", "destination", 40, 0) } returns Response.success(
            KitsuCollectionResponse(
                data = listOf(
                    KitsuMediaRelationshipResource(
                        id = "rel-1",
                        attributes = KitsuMediaRelationshipAttributes(role = "sequel"),
                        relationships = KitsuMediaRelationshipRelationships(
                            destination = KitsuToOneRelationship(KitsuResourceIdentifier(id = "11", type = "anime"))
                        )
                    )
                ),
                included = listOf(
                    KitsuIncludedResource(
                        id = "11",
                        type = "anime",
                        attributes = mapOf(
                            "canonicalTitle" to "Cowboy Bebop Movie",
                            "synopsis" to "Movie synopsis",
                            "posterImage" to mapOf("original" to "https://media.kitsu.io/movie.jpg")
                        )
                    )
                )
            )
        )

        val detail = service.fetchAdvancedDetail("kitsu:1", ContentMediaKind.SERIES, preferredLanguageCode = "ja")

        assertEquals("char-1", detail?.characters?.single()?.characterId)
        assertEquals("Spike Spiegel", detail?.characters?.single()?.characterName)
        assertEquals("Voice Actor", detail?.characters?.single()?.role)
        assertEquals("https://media.kitsu.io/spike.jpg", detail?.characters?.single()?.characterImage)
        assertEquals("person-actor-1", detail?.characters?.single()?.actorId)
        assertEquals("Koichi Yamadera", detail?.characters?.single()?.actorName)
        assertEquals("https://media.kitsu.io/yamadera.jpg", detail?.characters?.single()?.actorImage)
        assertEquals("Japanese", detail?.characters?.single()?.language)
        assertEquals("person-1", detail?.staff?.single()?.personId)
        assertEquals("Shinichiro Watanabe", detail?.staff?.single()?.personName)
        assertEquals("Director", detail?.staff?.single()?.role)
        assertEquals("producer-1", detail?.productionCompanies?.single()?.producerId)
        assertEquals("Sunrise", detail?.productionCompanies?.single()?.producerName)
        assertEquals("studio", detail?.productionCompanies?.single()?.role)
        assertEquals(listOf("Cowboy Bebop Movie"), detail?.relatedTitles?.map { it.title })
        assertEquals("11", detail?.relatedTitles?.first()?.mediaId)
        assertEquals("anime", detail?.relatedTitles?.first()?.mediaType)
        assertEquals("sequel", detail?.relatedTitles?.first()?.relationKind)
        assertEquals(emptySet<String>(), detail?.franchiseIds)
    }

    @Test
    fun `fetchAdvancedDetail deduplicates related titles across Kitsu relationship entries`() = runTest {
        val api = mockk<KitsuApi>()
        val mapper = mockk<AnimeIdMappingService>()
        val auth = mockk<KitsuAuthService>()
        val service = service(api, mapper, auth)

        coEvery { mapper.resolveKitsuId(any(), any()) } returns "1"
        coEvery { auth.validAccessToken() } returns null
        coEvery { api.getCastingsByMedia(null, "1", "person,character", 40, 0) } returns Response.success(KitsuCollectionResponse())
        coEvery { api.getAnimeStaff(null, "1", "person", 40, 0) } returns Response.success(KitsuCollectionResponse())
        coEvery { api.getAnimeProductions(null, "1", "producer", 40, 0) } returns Response.success(KitsuCollectionResponse())
        coEvery { api.getAnimeMediaRelationships(null, "1", "destination", 40, 0) } returns Response.success(
            KitsuCollectionResponse(
                data = listOf(
                    KitsuMediaRelationshipResource(
                        id = "rel-1",
                        attributes = KitsuMediaRelationshipAttributes(role = "sequel"),
                        relationships = KitsuMediaRelationshipRelationships(
                            destination = KitsuToOneRelationship(KitsuResourceIdentifier(id = "11", type = "anime"))
                        )
                    ),
                    KitsuMediaRelationshipResource(
                        id = "rel-2",
                        attributes = KitsuMediaRelationshipAttributes(role = "side_story"),
                        relationships = KitsuMediaRelationshipRelationships(
                            destination = KitsuToOneRelationship(KitsuResourceIdentifier(id = "11", type = "anime"))
                        )
                    )
                ),
                included = listOf(
                    KitsuIncludedResource(
                        id = "11",
                        type = "anime",
                        attributes = mapOf("canonicalTitle" to "Cowboy Bebop Movie")
                    )
                )
            )
        )

        val detail = service.fetchAdvancedDetail("kitsu:1", ContentMediaKind.SERIES)

        assertEquals(1, detail?.relatedTitles?.size)
        assertEquals("Cowboy Bebop Movie", detail?.relatedTitles?.single()?.title)
    }

    @Test
    fun `fetchAdvancedDetail skips production entries without stable producer identity`() = runTest {
        val api = mockk<KitsuApi>()
        val mapper = mockk<AnimeIdMappingService>()
        val auth = mockk<KitsuAuthService>()
        val service = service(api, mapper, auth)

        coEvery { mapper.resolveKitsuId(any(), any()) } returns "1"
        coEvery { auth.validAccessToken() } returns null
        coEvery { api.getCastingsByMedia(null, "1", "person,character", 40, 0) } returns Response.success(KitsuCollectionResponse())
        coEvery { api.getAnimeStaff(null, "1", "person", 40, 0) } returns Response.success(KitsuCollectionResponse())
        coEvery { api.getAnimeMediaRelationships(null, "1", "destination", 40, 0) } returns Response.success(KitsuCollectionResponse())
        coEvery { api.getAnimeInstallments(null, "1", "media", 40, 0) } returns Response.success(KitsuCollectionResponse())
        coEvery { api.getAnimeProductions(null, "1", "producer", 40, 0) } returns Response.success(
            KitsuCollectionResponse(
                data = listOf(
                    KitsuAnimeProductionResource(
                        id = "prod-1",
                        attributes = KitsuAnimeProductionAttributes(role = "studio"),
                        relationships = KitsuAnimeProductionRelationships(
                            producer = KitsuToOneRelationship(KitsuResourceIdentifier(id = "producer-1", type = "producers"))
                        )
                    )
                ),
                included = listOf(
                    KitsuIncludedResource(
                        id = "producer-1",
                        type = "producers",
                        attributes = emptyMap()
                    )
                )
            )
        )

        val detail = service.fetchAdvancedDetail("kitsu:1", ContentMediaKind.SERIES)

        assertEquals(0, detail?.productionCompanies?.size)
    }

    @Test
    fun `fetchAdvancedDetail prefers Japanese over English when source language is unknown`() = runTest {
        val api = mockk<KitsuApi>()
        val mapper = mockk<AnimeIdMappingService>()
        val auth = mockk<KitsuAuthService>()
        val service = service(api, mapper, auth)

        coEvery { mapper.resolveKitsuId(any(), any()) } returns "1"
        coEvery { auth.validAccessToken() } returns null
        coEvery { api.getAnimeStaff(null, "1", "person", 40, 0) } returns Response.success(KitsuCollectionResponse())
        coEvery { api.getAnimeProductions(null, "1", "producer", 40, 0) } returns Response.success(KitsuCollectionResponse())
        coEvery { api.getAnimeMediaRelationships(null, "1", "destination", 40, 0) } returns Response.success(KitsuCollectionResponse())
        coEvery { api.getCastingsByMedia(null, "1", "person,character", 40, 0) } returns Response.success(
            KitsuCollectionResponse(
                data = listOf(
                    KitsuCastingResource(
                        id = "cast-en",
                        attributes = KitsuCastingAttributes(role = "Voice Actor", voiceActor = true, featured = true, language = "English"),
                        relationships = KitsuCastingRelationships(
                            character = KitsuToOneRelationship(KitsuResourceIdentifier(id = "char-1", type = "characters")),
                            person = KitsuToOneRelationship(KitsuResourceIdentifier(id = "person-en", type = "people"))
                        )
                    ),
                    KitsuCastingResource(
                        id = "cast-ja",
                        attributes = KitsuCastingAttributes(role = "Voice Actor", voiceActor = true, featured = true, language = "Japanese"),
                        relationships = KitsuCastingRelationships(
                            character = KitsuToOneRelationship(KitsuResourceIdentifier(id = "char-1", type = "characters")),
                            person = KitsuToOneRelationship(KitsuResourceIdentifier(id = "person-ja", type = "people"))
                        )
                    )
                ),
                included = listOf(
                    KitsuIncludedResource(id = "char-1", type = "characters", attributes = mapOf("name" to "Spike Spiegel")),
                    KitsuIncludedResource(id = "person-en", type = "people", attributes = mapOf("name" to "English Actor")),
                    KitsuIncludedResource(id = "person-ja", type = "people", attributes = mapOf("name" to "Japanese Actor"))
                )
            )
        )

        val detail = service.fetchAdvancedDetail("kitsu:1", ContentMediaKind.SERIES, preferredLanguageCode = null)

        assertEquals("Japanese Actor", detail?.characters?.single()?.actorName)
        assertEquals("Japanese", detail?.characters?.single()?.language)
    }

    @Test
    fun `fetchAdvancedDetail prefers Korean when requested source language is Korean`() = runTest {
        val api = mockk<KitsuApi>()
        val mapper = mockk<AnimeIdMappingService>()
        val auth = mockk<KitsuAuthService>()
        val service = service(api, mapper, auth)

        coEvery { mapper.resolveKitsuId(any(), any()) } returns "1"
        coEvery { auth.validAccessToken() } returns null
        coEvery { api.getAnimeStaff(null, "1", "person", 40, 0) } returns Response.success(KitsuCollectionResponse())
        coEvery { api.getAnimeProductions(null, "1", "producer", 40, 0) } returns Response.success(KitsuCollectionResponse())
        coEvery { api.getAnimeMediaRelationships(null, "1", "destination", 40, 0) } returns Response.success(KitsuCollectionResponse())
        coEvery { api.getCastingsByMedia(null, "1", "person,character", 40, 0) } returns Response.success(
            KitsuCollectionResponse(
                data = listOf(
                    KitsuCastingResource(
                        id = "cast-ja",
                        attributes = KitsuCastingAttributes(role = "Voice Actor", voiceActor = true, featured = true, language = "Japanese"),
                        relationships = KitsuCastingRelationships(
                            character = KitsuToOneRelationship(KitsuResourceIdentifier(id = "char-1", type = "characters")),
                            person = KitsuToOneRelationship(KitsuResourceIdentifier(id = "person-ja", type = "people"))
                        )
                    ),
                    KitsuCastingResource(
                        id = "cast-ko",
                        attributes = KitsuCastingAttributes(role = "Voice Actor", voiceActor = true, featured = true, language = "Korean"),
                        relationships = KitsuCastingRelationships(
                            character = KitsuToOneRelationship(KitsuResourceIdentifier(id = "char-1", type = "characters")),
                            person = KitsuToOneRelationship(KitsuResourceIdentifier(id = "person-ko", type = "people"))
                        )
                    )
                ),
                included = listOf(
                    KitsuIncludedResource(id = "char-1", type = "characters", attributes = mapOf("name" to "Character")),
                    KitsuIncludedResource(id = "person-ja", type = "people", attributes = mapOf("name" to "Japanese Actor")),
                    KitsuIncludedResource(id = "person-ko", type = "people", attributes = mapOf("name" to "Korean Actor"))
                )
            )
        )

        val detail = service.fetchAdvancedDetail("kitsu:1", ContentMediaKind.SERIES, preferredLanguageCode = "ko")

        assertEquals("Korean Actor", detail?.characters?.single()?.actorName)
        assertEquals("Korean", detail?.characters?.single()?.language)
    }

    @Test
    fun `passes bearer token to Kitsu when authenticated`() = runTest {
        val api = mockk<KitsuApi>()
        val mapper = mockk<AnimeIdMappingService>()
        val auth = mockk<KitsuAuthService>()
        val service = service(api, mapper, auth)

        coEvery { mapper.resolveKitsuId(any(), any()) } returns "3936"
        coEvery { auth.providerEnabled() } returns true
        coEvery { auth.providerAuthenticated() } returns true
        coEvery { auth.validAccessToken() } returns "access-token"
        coEvery { api.getAnime("Bearer access-token", "3936", any()) } returns Response.success(
            KitsuResourceResponse(data = KitsuAnimeResource(id = "3936", attributes = KitsuAnimeAttributes(canonicalTitle = "Title")))
        )

        service.fetchEnrichment("mal:5114", ContentMediaKind.SERIES)

        coVerify(exactly = 1) { api.getAnime("Bearer access-token", "3936", any()) }
    }

    @Test
    fun `returns null when id is not anime`() = runTest {
        val auth = mockk<KitsuAuthService>(relaxed = true)
        val service = service(mockk(relaxed = true), mockk(relaxed = true), auth)

        assertNull(service.fetchEnrichment("trakt:123", ContentMediaKind.SERIES))
    }

    @Test
    fun `fetchEnrichment does not require provider enabled state`() = runTest {
        val api = mockk<KitsuApi>()
        val mapper = mockk<AnimeIdMappingService>()
        val auth = mockk<KitsuAuthService>()
        val service = service(api, mapper, auth)

        coEvery { mapper.resolveKitsuId(any(), any()) } returns "3936"
        coEvery { auth.validAccessToken() } returns null
        coEvery { api.getAnime(null, "3936", any()) } returns Response.success(
            KitsuResourceResponse(
                data = KitsuAnimeResource(
                    id = "3936",
                    attributes = KitsuAnimeAttributes(canonicalTitle = "Fullmetal Alchemist: Brotherhood")
                )
            )
        )

        val enrichment = service.fetchEnrichment("mal:5114", ContentMediaKind.SERIES)

        assertEquals("Fullmetal Alchemist: Brotherhood", enrichment?.localizedTitle)
        coVerify(exactly = 1) { api.getAnime(null, "3936", any()) }
    }
    private fun service(
        api: KitsuApi,
        mapper: AnimeIdMappingService,
        auth: KitsuAuthService
    ): KitsuMetadataService {
        val provider = KitsuIntegrationProvider(
            runtime = passThroughTestRuntime(),
            kitsuApi = api,
            kitsuAuthService = auth
        )
        return KitsuMetadataService(provider, mapper)
    }
}
