package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.integration.TmdbApiShapes
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
import com.nexio.tv.core.tmdb.TmdbEnrichment
import com.nexio.tv.core.tmdb.TmdbSeasonEnrichment
import com.nexio.tv.data.integration.tmdb.TmdbIntegrationProvider
import com.nexio.tv.data.remote.api.TmdbEpisode
import com.nexio.tv.data.remote.api.TmdbSeasonResponse
import com.nexio.tv.domain.model.SeasonDisplay
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TmdbMetadataProviderAdapterTest {

    @Test
    fun `TV core metadata exposes all TMDB season summaries for detail episode hydration`() = runTest {
        val integrationProvider = mockk<TmdbIntegrationProvider>()
        coEvery {
            integrationProvider.fetchTvCore(
                tvId = 299201,
                normalizedLanguage = "en-US",
                activePosterProvider = null,
                localizationPolicyVersion = any()
            )
        } returns TmdbEnrichment(
            localizedTitle = "Australian Survivor",
            description = "Australian Survivor 2002",
            genres = emptyList(),
            backdrop = null,
            logo = null,
            poster = null,
            directorMembers = emptyList(),
            writerMembers = emptyList(),
            castMembers = emptyList(),
            releaseInfo = "2002-02-13",
            rating = null,
            runtimeMinutes = null,
            director = emptyList(),
            writer = emptyList(),
            productionCompanies = emptyList(),
            networks = emptyList(),
            ageRating = null,
            countries = listOf("Australia"),
            language = "en",
            collectionId = null,
            collectionName = null,
            seasons = listOf(
                TmdbSeasonEnrichment(
                    seasonNumber = 1,
                    title = "Season 1",
                    overview = null,
                    episodeCount = 13,
                    airDate = "2002-02-13"
                ),
                TmdbSeasonEnrichment(
                    seasonNumber = 2,
                    title = "Season 2",
                    overview = null,
                    episodeCount = 12,
                    airDate = "2006-08-17"
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
                parentId = "tmdb:299201",
                mediaKind = MetadataMediaKind.SERIES,
                reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
                sourceContext = MetadataSourceContext(),
                language = "en-US",
                targetIds = mapOf(MetadataPrimaryProvider.TMDB to "tmdb:299201"),
                trace = emptyList()
            ),
            step = ProviderPlanStep(
                apiShapeId = TmdbApiShapes.TV_CORE,
                provider = MetadataPrimaryProvider.TMDB,
                role = ProviderPlanRole.PRIMARY_CORE,
                required = true
            )
        )

        @Suppress("UNCHECKED_CAST")
        val seasons = result.candidate!!.fields[ResolvedField.EPISODES]?.value as List<SeasonDisplay>

        assertEquals(listOf(1, 2), seasons.map { it.seasonNumber })
        assertEquals(13, seasons[0].episodes.size)
        assertEquals(12, seasons[1].episodes.size)
    }

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
