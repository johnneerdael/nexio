package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.integration.TmdbApiShapes
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.data.remote.api.TmdbVideoResult
import com.nexio.tv.data.trailer.TrailerResolutionResult
import com.nexio.tv.domain.model.ContentType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataRouterFacadeFetchTrailerTest {
    @Test
    fun `fetchTrailer threads explicit seasonNumber into provider-plan media route`() = runTest {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        var observedSeasonNumber: Int? = null
        var observedShape: String? = null

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
                                        type = "Trailer"
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
            type = "movie",
            seasonNumber = 2
        )

        assertEquals(2, observedSeasonNumber)
        assertEquals(TmdbApiShapes.MOVIE_VIDEOS, observedShape)
        assertEquals(
            TrailerResolutionResult.External("https://www.youtube.com/watch?v=season-two-trailer"),
            result
        )
    }
}
