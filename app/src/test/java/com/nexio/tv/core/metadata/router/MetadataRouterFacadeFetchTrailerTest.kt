package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.integration.TmdbApiShapes
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.core.metadata.router.resolver.TrailerPlaybackRef
import com.nexio.tv.data.remote.api.TmdbVideoResult
import com.nexio.tv.data.trailer.TrailerPlaybackSource
import com.nexio.tv.data.trailer.TrailerResolutionResult
import com.nexio.tv.data.trailer.TrailerService
import com.nexio.tv.domain.model.ContentType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class MetadataRouterFacadeFetchTrailerTest {
    @Test
    fun `fetchTrailer threads explicit seasonNumber into provider-plan media route and transport`() = runTest {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        var observedSeasonNumber: Int? = null
        var observedShape: String? = null
        val trailerService = mockk<TrailerService>()
        coEvery {
            trailerService.resolvePlaybackSource(any(), any(), any())
        } returns TrailerResolutionResult.External("https://transport.example/season-two-trailer")

        val adapter = object : MetadataProviderAdapter {
            override val provider: MetadataPrimaryProvider = MetadataPrimaryProvider.TMDB

            override fun supports(step: ProviderPlanStep): Boolean = true

            override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
                if (step.apiShapeId != TmdbApiShapes.MOVIE_VIDEOS) {
                    return ProviderStepResult(
                        step = step,
                        candidate = MetadataCandidate(provider = provider, fields = emptyMap())
                    )
                }
                observedSeasonNumber = route.seasonNumber
                observedShape = step.apiShapeId
                return ProviderStepResult(
                    step = step,
                    candidate = MetadataCandidate(
                        provider = provider,
                        resolverType = ResolverType.TRAILERS,
                        fields = mapOf(
                            ResolvedField.TRAILERS to FieldValue(
                                listOf(
                                    TmdbVideoResult(
                                        key = "season-two-trailer",
                                        site = "YouTube",
                                        type = "Trailer",
                                        iso6391 = "en"
                                    )
                                ),
                                FieldOwner.TRAILERS
                            )
                        )
                    )
                )
            }
        }

        val facade = MetadataRouterFacade(
            router = MetadataRouter(
                normalizer = MetadataRequestNormalizer(traceEvents = events),
                animeIdentityIndex = InMemoryAnimeIdentityIndex(),
                idMappingStore = InMemoryIdMappingStore(),
                traceEvents = events
            ),
            providerPlanExecutor = ProviderPlanExecutor(),
            resolverOrchestrator = ResolverOrchestrator(events),
            identityResolver = MetadataIdentityResolver(
                object : MetadataIdentityResolver.Lookup {
                    override suspend fun tmdbToTvdb(tmdbId: String): String? = if (tmdbId == "1396") "81189" else null
                    override suspend fun tvdbToTmdb(tvdbId: String): String? = null
                }
            ),
            providerPlanRunner = ProviderPlanRunner(setOf(adapter)),
            fieldResolver = FieldResolver(events),
            trailerService = trailerService
        )

        val result = facade.fetchTrailer(
            metadataRequest = MetadataRequest(
                contentId = "tmdb:603",
                contentType = ContentType.MOVIE,
                sourceContext = MetadataSourceContext(),
                language = "eng",
                depth = MetadataDepth.DETAIL_CORE,
                seasonNumber = null
            ),
            title = "The Matrix",
            tmdbId = "603",
            type = "movie",
            seasonNumber = 2
        )

        assertEquals(2, observedSeasonNumber)
        assertEquals(TmdbApiShapes.MOVIE_VIDEOS, observedShape)
        assertEquals(
            TrailerResolutionResult.External("https://transport.example/season-two-trailer"),
            result
        )
        coVerify {
            trailerService.resolvePlaybackSource(
                match { it is TrailerPlaybackRef.YouTubeId && it.videoId == "season-two-trailer" },
                "The Matrix",
                null
            )
        }
    }

    @Test
    fun `fetchTrailer returns null without transport instead of manufacturing youtube url`() = runTest {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "no-transport" })
        val adapter = object : MetadataProviderAdapter {
            override val provider: MetadataPrimaryProvider = MetadataPrimaryProvider.TMDB

            override fun supports(step: ProviderPlanStep): Boolean = true

            override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult =
                ProviderStepResult(
                    step = step,
                    candidate = MetadataCandidate(
                        provider = provider,
                        resolverType = ResolverType.TRAILERS,
                        fields = mapOf(
                            ResolvedField.TRAILERS to FieldValue(
                                listOf(
                                    TmdbVideoResult(
                                        key = "no-transport",
                                        site = "YouTube",
                                        type = "Trailer"
                                    )
                                ),
                                FieldOwner.TRAILERS
                            )
                        )
                    )
                )
        }

        val facade = MetadataRouterFacade(
            router = MetadataRouter(
                normalizer = MetadataRequestNormalizer(traceEvents = events),
                animeIdentityIndex = InMemoryAnimeIdentityIndex(),
                idMappingStore = InMemoryIdMappingStore(),
                traceEvents = events
            ),
            providerPlanExecutor = ProviderPlanExecutor(),
            resolverOrchestrator = ResolverOrchestrator(events),
            identityResolver = MetadataIdentityResolver(
                object : MetadataIdentityResolver.Lookup {
                    override suspend fun tmdbToTvdb(tmdbId: String): String? = null
                    override suspend fun tvdbToTmdb(tvdbId: String): String? = null
                }
            ),
            providerPlanRunner = ProviderPlanRunner(setOf(adapter)),
            fieldResolver = FieldResolver(events)
        )

        val result = facade.fetchTrailer(
            metadataRequest = MetadataRequest(
                contentId = "tmdb:603",
                contentType = ContentType.MOVIE,
                sourceContext = MetadataSourceContext(),
                language = "eng",
                depth = MetadataDepth.DETAIL_CORE,
                seasonNumber = null
            ),
            title = "The Matrix",
            tmdbId = "603",
            type = "movie"
        )

        assertNull(result)
    }

    @Test
    fun `fetchTrailer ranks tmdb videos before creating playback refs`() = runTest {
        val trailerService = mockk<TrailerService>()
        coEvery {
            trailerService.resolvePlaybackSource(any(), any(), any())
        } returns TrailerResolutionResult.External("https://transport.example/official-trailer")
        val facade = facadeWithTrailerFields(
            trailerService = trailerService,
            apiShapeId = TmdbApiShapes.MOVIE_VIDEOS,
            trailers = listOf(
                TmdbVideoResult(
                    key = "recap-id",
                    site = "YouTube",
                    type = "Recap",
                    iso6391 = "en",
                    official = true,
                    size = 1080
                ),
                TmdbVideoResult(
                    key = "official-trailer",
                    site = "YouTube",
                    type = "Trailer",
                    iso6391 = "en",
                    official = true,
                    size = 1080
                )
            )
        )

        facade.fetchTrailer(
            metadataRequest = movieRequest(),
            title = "The Matrix",
            tmdbId = "603",
            type = "movie"
        )

        coVerify {
            trailerService.resolvePlaybackSource(
                match { it is TrailerPlaybackRef.YouTubeId && it.videoId == "official-trailer" },
                "The Matrix",
                null
            )
        }
    }

    @Test
    fun `series title trailer does not satisfy selected season trailer availability`() = runTest {
        val facade = facadeWithTrailerFields(
            trailerService = mockk(relaxed = true),
            apiShapeId = TmdbApiShapes.TV_VIDEOS,
            trailers = listOf(
                TmdbVideoResult(
                    key = "series-title-trailer",
                    site = "YouTube",
                    type = "Trailer",
                    iso6391 = "en",
                    official = true
                )
            )
        )

        val availability = facade.fetchSeasonMediaAvailability(
            metadataRequest = seasonMediaRequest(),
            tmdbId = "1396",
            type = "series",
            seasonNumber = 2,
            contentId = "tvdb:81189"
        )

        assertFalse(availability.hasTrailerOrTeaser)
    }

    @Test
    fun `series title trailer does not satisfy selected season trailer playback`() = runTest {
        val trailerService = mockk<TrailerService>()
        coEvery {
            trailerService.resolvePlaybackSource(any(), any(), any())
        } returns TrailerResolutionResult.Playback(
            TrailerPlaybackSource(videoUrl = "https://video.example.com/series-title.m3u8")
        )
        val facade = facadeWithTrailerFields(
            trailerService = trailerService,
            apiShapeId = TmdbApiShapes.TV_VIDEOS,
            trailers = listOf(
                TmdbVideoResult(
                    key = "series-title-trailer",
                    site = "YouTube",
                    type = "Trailer",
                    iso6391 = "en",
                    official = true
                )
            )
        )

        assertNull(
            facade.fetchSeasonTrailer(
                metadataRequest = seasonMediaRequest(),
                title = "Breaking Bad",
                tmdbId = "1396",
                type = "series",
                seasonNumber = 2,
                contentId = "tvdb:81189"
            )
        )
    }

    @Test
    fun `season availability does not derive candidates from trailer service helpers`() = runTest {
        val trailerService = mockk<TrailerService>()
        coEvery {
            trailerService.getSeasonMediaAvailability(any(), any(), any(), any())
        } throws AssertionError("season availability must not be service-owned")
        coEvery {
            trailerService.getSeasonTrailerPlaybackSource(any(), any(), any(), any(), any(), any())
        } throws AssertionError("season trailer candidate discovery must not be service-owned")
        coEvery {
            trailerService.getSeasonRecapPlaybackSource(any(), any(), any(), any(), any(), any())
        } throws AssertionError("season recap candidate discovery must not be service-owned")

        val availability = emptyTrailerFacade(trailerService).fetchSeasonMediaAvailability(
            metadataRequest = seasonMediaRequest(),
            type = "series",
            seasonNumber = 2,
            contentId = "tvdb:series:1"
        )

        assertFalse(availability.hasTrailerOrTeaser)
        assertFalse(availability.hasRecap)
    }

    @Test
    fun `season playback does not fall back to trailer service candidate discovery`() = runTest {
        val trailerService = mockk<TrailerService>()
        coEvery {
            trailerService.getSeasonTrailerPlaybackSource(any(), any(), any(), any(), any(), any())
        } throws AssertionError("season trailer candidate discovery must not be service-owned")
        coEvery {
            trailerService.getSeasonRecapPlaybackSource(any(), any(), any(), any(), any(), any())
        } throws AssertionError("season recap candidate discovery must not be service-owned")

        val facade = emptyTrailerFacade(trailerService)

        assertNull(
            facade.fetchSeasonTrailer(
                metadataRequest = seasonMediaRequest(),
                title = "Demo",
                type = "series",
                seasonNumber = 2,
                contentId = "tvdb:series:1"
            )
        )
        assertNull(
            facade.fetchSeasonRecap(
                metadataRequest = seasonMediaRequest(),
                title = "Demo",
                type = "series",
                seasonNumber = 2,
                contentId = "tvdb:series:1"
            )
        )
    }

    private fun emptyTrailerFacade(trailerService: TrailerService): MetadataRouterFacade {
        val events = TraceMetadataEvents(RecordingTraceSink(), sessionId = { "season" })
        val emptyAdapter = object : MetadataProviderAdapter {
            override val provider: MetadataPrimaryProvider = MetadataPrimaryProvider.TVDB

            override fun supports(step: ProviderPlanStep): Boolean = true

            override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult =
                ProviderStepResult(
                    step = step,
                    candidate = MetadataCandidate(provider = provider, fields = emptyMap())
                )
        }

        return MetadataRouterFacade(
            router = MetadataRouter(
                normalizer = MetadataRequestNormalizer(traceEvents = events),
                animeIdentityIndex = InMemoryAnimeIdentityIndex(),
                idMappingStore = InMemoryIdMappingStore(),
                traceEvents = events
            ),
            providerPlanExecutor = ProviderPlanExecutor(),
            resolverOrchestrator = ResolverOrchestrator(events),
            identityResolver = MetadataIdentityResolver(
                object : MetadataIdentityResolver.Lookup {
                    override suspend fun tmdbToTvdb(tmdbId: String): String? = null
                    override suspend fun tvdbToTmdb(tvdbId: String): String? = null
                }
            ),
            providerPlanRunner = ProviderPlanRunner(setOf(emptyAdapter)),
            fieldResolver = FieldResolver(events),
            trailerService = trailerService
        )
    }

    private fun facadeWithTrailerFields(
        trailerService: TrailerService,
        apiShapeId: String,
        trailers: List<TmdbVideoResult>
    ): MetadataRouterFacade {
        val events = TraceMetadataEvents(RecordingTraceSink(), sessionId = { "trailers" })
        val adapter = object : MetadataProviderAdapter {
            override val provider: MetadataPrimaryProvider = MetadataPrimaryProvider.TMDB

            override fun supports(step: ProviderPlanStep): Boolean = true

            override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult =
                ProviderStepResult(
                    step = step,
                    candidate = MetadataCandidate(
                        provider = provider,
                        resolverType = ResolverType.TRAILERS,
                        fields = if (step.apiShapeId == apiShapeId) {
                            mapOf(
                                ResolvedField.TRAILERS to FieldValue(
                                    trailers,
                                    FieldOwner.TRAILERS
                                )
                            )
                        } else {
                            emptyMap()
                        }
                    )
                )
        }
        val emptyTvdbAdapter = object : MetadataProviderAdapter {
            override val provider: MetadataPrimaryProvider = MetadataPrimaryProvider.TVDB

            override fun supports(step: ProviderPlanStep): Boolean = true

            override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult =
                ProviderStepResult(
                    step = step,
                    candidate = MetadataCandidate(provider = provider, fields = emptyMap())
                )
        }

        return MetadataRouterFacade(
            router = MetadataRouter(
                normalizer = MetadataRequestNormalizer(traceEvents = events),
                animeIdentityIndex = InMemoryAnimeIdentityIndex(),
                idMappingStore = InMemoryIdMappingStore(),
                traceEvents = events
            ),
            providerPlanExecutor = ProviderPlanExecutor(),
            resolverOrchestrator = ResolverOrchestrator(events),
            identityResolver = MetadataIdentityResolver(
                object : MetadataIdentityResolver.Lookup {
                    override suspend fun tmdbToTvdb(tmdbId: String): String? = null
                    override suspend fun tvdbToTmdb(tvdbId: String): String? = if (tvdbId == "series:1" || tvdbId == "81189") "1396" else null
                }
            ),
            providerPlanRunner = ProviderPlanRunner(setOf(adapter, emptyTvdbAdapter)),
            fieldResolver = FieldResolver(events),
            trailerService = trailerService
        )
    }

    private fun movieRequest(): MetadataRequest =
        MetadataRequest(
            contentId = "tmdb:603",
            contentType = ContentType.MOVIE,
            sourceContext = MetadataSourceContext(itemType = "movie"),
            language = "eng",
            depth = MetadataDepth.DETAIL_MEDIA
        )

    private fun seasonMediaRequest(): MetadataRequest =
        MetadataRequest(
            contentId = "tvdb:series:1",
            contentType = ContentType.SERIES,
            sourceContext = MetadataSourceContext(itemType = "series"),
            language = "eng",
            depth = MetadataDepth.DETAIL_MEDIA,
            seasonNumber = 2
        )

}
