package com.nexio.tv.data.integration.metadata

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nexio.tv.core.anime.ContentMediaKind
import com.nexio.tv.core.integration.KitsuApiShapes
import com.nexio.tv.core.media.ContentIdentity
import com.nexio.tv.core.media.MediaClipScope
import com.nexio.tv.core.media.MediaClipStore
import com.nexio.tv.core.media.MediaClipType
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.ProviderPlanRole
import com.nexio.tv.core.metadata.router.ProviderPlanStep
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.domain.model.MetaCastMember
import com.nexio.tv.domain.model.MetaCompany
import com.nexio.tv.data.integration.kitsu.KitsuIntegrationProvider
import com.nexio.tv.data.remote.api.KitsuAnimeAttributes
import com.nexio.tv.data.remote.api.KitsuAnimeCharacterAttributes
import com.nexio.tv.data.remote.api.KitsuAnimeCharacterRelationships
import com.nexio.tv.data.remote.api.KitsuAnimeCharacterResource
import com.nexio.tv.data.remote.api.KitsuAnimeProductionAttributes
import com.nexio.tv.data.remote.api.KitsuAnimeProductionRelationships
import com.nexio.tv.data.remote.api.KitsuAnimeProductionResource
import com.nexio.tv.data.remote.api.KitsuAnimeResource
import com.nexio.tv.data.remote.api.KitsuCollectionResponse
import com.nexio.tv.data.remote.api.KitsuImage
import com.nexio.tv.data.remote.api.KitsuIncludedResource
import com.nexio.tv.data.remote.api.KitsuResourceIdentifier
import com.nexio.tv.data.remote.api.KitsuCastingRelationships
import com.nexio.tv.data.remote.api.KitsuToManyRelationship
import com.nexio.tv.data.remote.api.KitsuToOneRelationship
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class KitsuMetadataProviderAdapterTest {

    @Test
    fun `ANIME_EPISODES emits episode metadata from Kitsu runtime response`() = runTest {
        val provider = mockk<KitsuIntegrationProvider>()
        coEvery {
            provider.fetchEpisodeEnrichment(
                rawId = "kitsu:12",
                kitsuId = "12",
                mediaKind = ContentMediaKind.SERIES,
                mapper = any()
            )
        } coAnswers {
            val mapper = arg<(List<KitsuAnimeResource>) -> Map<Pair<Int, Int>, TvEpisodeMetadata>>(3)
            mapper(
                listOf(
                    KitsuAnimeResource(
                        id = "1001",
                        attributes = KitsuAnimeAttributes(
                            canonicalTitle = "Romance Dawn",
                            synopsis = "A boy begins his voyage.",
                            number = 1,
                            seasonNumber = 1,
                            airdate = "1999-10-20",
                            length = 24,
                            thumbnail = KitsuImage(large = "https://media.kitsu.io/e1.jpg")
                        )
                    ),
                    KitsuAnimeResource(
                        id = "2001",
                        attributes = KitsuAnimeAttributes(
                            canonicalTitle = "Second Season",
                            number = 1,
                            seasonNumber = 2
                        )
                    )
                )
            )
        }
        val adapter = adapter(provider)

        val result = adapter.execute(
            route = kitsuRoute(itemType = "series", seasonNumber = 1),
            step = step(KitsuApiShapes.ANIME_EPISODES, ProviderPlanRole.SEASON)
        )

        val episode = result.episodeMetadata[1 to 1]
        assertNotNull(episode)
        assertEquals("kitsu:1001", episode!!.providerEpisodeId)
        assertEquals(1, episode.seasonNumber)
        assertEquals(1, episode.episodeNumber)
        assertEquals("Romance Dawn", episode.title)
        assertEquals("A boy begins his voyage.", episode.overview)
        assertEquals("https://media.kitsu.io/e1.jpg", episode.thumbnail)
        assertEquals("1999-10-20", episode.airDate)
        assertEquals(24, episode.runtimeMinutes)
        assertEquals(null, result.episodeMetadata[2 to 1])
    }

    @Test
    fun `ANIME_CORE preserves Kitsu movie item type for runtime cache ownership`() = runTest {
        val provider = mockk<KitsuIntegrationProvider>()
        coEvery {
            provider.fetchEnrichment(
                rawId = "kitsu:44390",
                kitsuId = "44390",
                mediaKind = ContentMediaKind.MOVIE,
                mapper = any()
            )
        } coAnswers {
            val mapper = arg<(KitsuAnimeResource) -> TvMetadataEnrichment?>(3)
            mapper(
                KitsuAnimeResource(
                    id = "44390",
                    attributes = KitsuAnimeAttributes(
                        canonicalTitle = "Anime Movie",
                        synopsis = "Movie synopsis",
                        subtype = "movie",
                        posterImage = KitsuImage(large = "https://media.kitsu.io/movie.jpg")
                    )
                )
            )
        }
        val adapter = adapter(provider)

        val result = adapter.execute(
            route = kitsuRoute(parentId = "kitsu:44390", kitsuId = "44390", itemType = "movie"),
            step = step(KitsuApiShapes.ANIME_CORE, ProviderPlanRole.PRIMARY_CORE)
        )

        assertEquals("Anime Movie", result.candidate?.fields?.get(ResolvedField.TITLE)?.value)
        coVerify(exactly = 1) {
            provider.fetchEnrichment(
                rawId = "kitsu:44390",
                kitsuId = "44390",
                mediaKind = ContentMediaKind.MOVIE,
                mapper = any()
            )
        }
        coVerify(exactly = 0) {
            provider.fetchEnrichment(
                rawId = "kitsu:44390",
                kitsuId = "44390",
                mediaKind = ContentMediaKind.SERIES,
                mapper = any()
            )
        }
    }

    @Test
    fun `ANIME_CORE emits youtubeVideoId as trailers field and stores Kitsu media clip`() = runTest {
        val provider = mockk<KitsuIntegrationProvider>()
        coEvery {
            provider.fetchEnrichment(
                rawId = "kitsu:12",
                kitsuId = "12",
                mediaKind = ContentMediaKind.SERIES,
                mapper = any()
            )
        } coAnswers {
            val mapper = arg<(KitsuAnimeResource) -> TvMetadataEnrichment?>(3)
            mapper(
                KitsuAnimeResource(
                    id = "12",
                    attributes = KitsuAnimeAttributes(
                        canonicalTitle = "Cowboy Bebop",
                        youtubeVideoId = "abc123"
                    )
                )
            )
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = MediaClipStore(
            context = context,
            prefsName = "kitsu_adapter_media_clip_${System.nanoTime()}",
            clock = { 1_000_000L }
        )
        val adapter = adapter(provider, mediaClipStore = store)

        val result = adapter.execute(
            route = kitsuRoute(itemType = "series"),
            step = step(KitsuApiShapes.ANIME_CORE, ProviderPlanRole.PRIMARY_CORE)
        )

        assertEquals(listOf("abc123"), result.candidate?.fields?.get(ResolvedField.TRAILERS)?.value)
        val identity = ContentIdentity(
            contentId = "kitsu:12",
            itemType = "series",
            stableIds = com.nexio.tv.domain.model.ProviderIds(kitsu = "12")
        )
        val clips = store.getCandidates(
            identity = identity,
            scope = MediaClipScope.Title(identity),
            clipTypes = setOf(MediaClipType.TRAILER),
            language = "en"
        )
        assertEquals("abc123", clips.single().externalVideoId)
        assertEquals("KITSU", clips.single().provider)
    }

    @Test
    fun `CASTINGS emits character candidate from Kitsu anime characters runtime response`() = runTest {
        val provider = mockk<KitsuIntegrationProvider>()
        coEvery {
            provider.fetchAnimeCharacters(
                rawId = "kitsu:12",
                kitsuId = "12",
                mediaKind = ContentMediaKind.SERIES
            )
        } returns KitsuCollectionResponse(
            data = listOf(
                KitsuAnimeCharacterResource(
                    id = "46064",
                    attributes = KitsuAnimeCharacterAttributes(role = "main"),
                    relationships = KitsuAnimeCharacterRelationships(
                        character = KitsuToOneRelationship(KitsuResourceIdentifier(id = "410", type = "characters")),
                        castings = KitsuToManyRelationship(
                            data = listOf(KitsuResourceIdentifier(id = "49087", type = "animeCastings"))
                        )
                    )
                )
            ),
            included = listOf(
                KitsuIncludedResource(
                    id = "410",
                    type = "characters",
                    attributes = mapOf(
                        "canonicalName" to "Brook",
                        "image" to mapOf("large" to "https://media.kitsu.io/brook.jpg")
                    )
                ),
                KitsuIncludedResource(
                    id = "49087",
                    type = "animeCastings",
                    attributes = mapOf(
                        "role" to "Voice Actor",
                        "voiceActor" to true,
                        "language" to "Japanese"
                    ),
                    relationships = KitsuCastingRelationships(
                        person = KitsuToOneRelationship(KitsuResourceIdentifier(id = "2339", type = "people"))
                    )
                ),
                KitsuIncludedResource(
                    id = "2339",
                    type = "people",
                    attributes = mapOf(
                        "name" to "Hiromi Konno",
                        "image" to mapOf("large" to "https://media.kitsu.io/hiromi.jpg")
                    )
                )
            )
        )
        val adapter = adapter(provider)

        val result = adapter.execute(
            route = kitsuRoute(itemType = "series"),
            step = step(KitsuApiShapes.CASTINGS, ProviderPlanRole.SECONDARY)
        )

        val cast = result.candidate?.fields?.get(ResolvedField.CAST)?.value as? List<MetaCastMember>
        assertNotNull(cast)
        assertEquals("Brook", cast!!.single().name)
        assertEquals("Hiromi Konno", cast.single().character)
        assertEquals("https://media.kitsu.io/brook.jpg", cast.single().photo)
        assertEquals("kitsu", cast.single().provider)
        assertEquals("410", cast.single().providerId)
    }

    @Test
    fun `ANIME_PRODUCTIONS emits organization candidate from Kitsu runtime response`() = runTest {
        val provider = mockk<KitsuIntegrationProvider>()
        coEvery {
            provider.fetchAnimeProductions(
                rawId = "kitsu:12",
                kitsuId = "12",
                mediaKind = ContentMediaKind.SERIES
            )
        } returns KitsuCollectionResponse(
            data = listOf(
                KitsuAnimeProductionResource(
                    id = "37",
                    attributes = KitsuAnimeProductionAttributes(role = "studio"),
                    relationships = KitsuAnimeProductionRelationships(
                        producer = KitsuToOneRelationship(KitsuResourceIdentifier(id = "8", type = "producers"))
                    )
                )
            ),
            included = listOf(
                KitsuIncludedResource(
                    id = "8",
                    type = "producers",
                    attributes = mapOf("name" to "Toei Animation")
                )
            )
        )
        val adapter = adapter(provider)

        val result = adapter.execute(
            route = kitsuRoute(itemType = "series"),
            step = step(KitsuApiShapes.ANIME_PRODUCTIONS, ProviderPlanRole.SECONDARY)
        )

        val companies = result.candidate?.fields?.get(ResolvedField.ORGANIZATION_LIST)?.value as? List<MetaCompany>
        assertNotNull(companies)
        assertEquals("Toei Animation", companies!!.single().name)
        assertEquals("kitsu", companies.single().provider)
        assertEquals("8", companies.single().providerId)
    }

    private fun adapter(
        provider: KitsuIntegrationProvider,
        mediaClipStore: MediaClipStore? = null
    ) = KitsuMetadataProviderAdapter(
        integrationProvider = provider,
        traceEvents = TraceMetadataEvents(NoopRuntimeTraceSink) { null },
        mediaClipStore = mediaClipStore
    )

    private fun step(shape: String, role: ProviderPlanRole) = ProviderPlanStep(
        apiShapeId = shape,
        provider = MetadataPrimaryProvider.KITSU,
        role = role,
        required = true
    )

    private fun kitsuRoute(
        parentId: String = "kitsu:12",
        kitsuId: String = "12",
        itemType: String,
        seasonNumber: Int? = null
    ) = MetadataRoute(
        provider = MetadataPrimaryProvider.KITSU,
        parentId = parentId,
        mediaKind = MetadataMediaKind.ANIME,
        reason = MetadataDecisionReason.KITSU_PREFIX_DIRECT,
        sourceContext = MetadataSourceContext(itemType = itemType),
        language = "en",
        seasonNumber = seasonNumber,
        targetIds = mapOf(MetadataPrimaryProvider.KITSU to "kitsu:$kitsuId"),
        trace = emptyList()
    )
}
