package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.integration.TmdbApiShapes
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.ProviderPlanRole
import com.nexio.tv.core.metadata.router.ProviderPlanStep
import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.data.integration.tmdb.TmdbIntegrationProvider
import com.nexio.tv.data.remote.api.TmdbEpisode
import com.nexio.tv.data.remote.api.TmdbSeasonResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TmdbMetadataProviderAdapterTest {

    @Test
    fun `season episode metadata expands TMDB still path to remote image URL`() = runTest {
        val integrationProvider = mockk<TmdbIntegrationProvider>()
        coEvery {
            integrationProvider.fetchTvSeasonEpisodes(
                tvId = 308014,
                seasonNumber = 1,
                normalizedLanguage = "en-US",
                localizationPolicyVersion = any()
            )
        } returns TmdbSeasonResponse(
            seasonNumber = 1,
            episodes = listOf(
                TmdbEpisode(
                    id = 4711,
                    episodeNumber = 3,
                    name = "Stendhal Syndrome",
                    overview = "Berlin episode",
                    stillPath = "/berlin-still.jpg",
                    airDate = "2023-12-29",
                    runtime = 48
                )
            )
        )
        val adapter = TmdbMetadataProviderAdapter(
            integrationProvider = integrationProvider,
            traceEvents = TraceMetadataEvents(NoopRuntimeTraceSink) { null }
        )

        val result = adapter.execute(
            route = MetadataRoute(
                provider = MetadataPrimaryProvider.TMDB,
                parentId = "tmdb:308014",
                mediaKind = MetadataMediaKind.SERIES,
                reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
                sourceContext = MetadataSourceContext(),
                language = "en-US",
                seasonNumber = 1,
                targetIds = mapOf(MetadataPrimaryProvider.TMDB to "308014"),
                trace = emptyList()
            ),
            step = ProviderPlanStep(
                apiShapeId = TmdbApiShapes.SEASON_EPISODES,
                provider = MetadataPrimaryProvider.TMDB,
                role = ProviderPlanRole.SEASON,
                required = true
            )
        )

        assertEquals(
            "https://image.tmdb.org/t/p/w500/berlin-still.jpg",
            result.episodeMetadata[1 to 3]?.thumbnail
        )
    }
}
