package com.nexio.tv.ui.screens.detail

import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataResolutionResult
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.ResolvedMetadataDocument
import com.nexio.tv.core.metadata.router.ResolverSchedule
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.core.tvdb.TvMetadataDecision
import com.nexio.tv.core.tvdb.TvMetadataDecisionReason
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.core.tvdb.TvProvider
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.repository.WatchProgressRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MetaDetailsEffectiveContentIdTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `episode progress lookup switches to loaded meta id`() = runTest(dispatcher) {
        val watchProgressRepository = mockk<WatchProgressRepository>(relaxed = true)
        every { watchProgressRepository.getAllEpisodeProgress(any(), any()) } returns flowOf(emptyMap())
        every { watchProgressRepository.getProgress(any(), any()) } returns flowOf(null)

        // Use a mock facade that returns canonicalId="tt0944947" so that canonical.toMeta()
        // produces meta.id = "tt0944947". This verifies that the effectiveContentId switches
        // from the initial itemId ("tmdb:1399") to the loaded meta's id ("tt0944947").
        val facade = mockk<MetadataRouterFacade>(relaxed = true)
        coEvery { facade.resolveRequest(any()) } returns buildResolutionResult(canonicalId = "tt0944947")
        coEvery { facade.fetchTvEnrichment(any(), any()) } returns TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_INACTIVE,
            value = null
        )
        // Stub fetchTvEpisodeEnrichment so the mandatory-episode blocking path (d2955c201)
        // resolves and applyMeta is reached. A single episode stub ensures episodeHydratedMeta
        // has non-empty videos, bypassing the error-state guard.
        coEvery { facade.fetchTvEpisodeEnrichment(any(), any()) } returns TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = mapOf(
                (1 to 1) to TvEpisodeMetadata(
                    providerEpisodeId = "tvdb:1",
                    seasonNumber = 1,
                    episodeNumber = 1,
                    airDate = "2020-01-01"
                )
            )
        )

        buildMetaDetailsViewModel(
            meta = seriesMeta("tt0944947"),
            itemId = "tmdb:1399",
            itemType = "series",
            watchProgressRepository = watchProgressRepository,
            metadataRouterFacade = facade
        )
        advanceUntilIdle()

        verify { watchProgressRepository.getAllEpisodeProgress(1, "tt0944947") }
    }

    private fun buildResolutionResult(canonicalId: String) = MetadataResolutionResult(
        route = MetadataRoute(
            provider = MetadataPrimaryProvider.TVDB,
            parentId = canonicalId,
            mediaKind = MetadataMediaKind.SERIES,
            reason = MetadataDecisionReason.ITEM_TYPE_SERIES,
            sourceContext = MetadataSourceContext(),
            targetIds = mapOf(MetadataPrimaryProvider.TVDB to canonicalId),
            trace = emptyList()
        ),
        plan = null,
        resolverSchedule = ResolverSchedule(
            depth = MetadataDepth.DETAIL_CORE,
            localResolvers = emptyList(),
            networkResolvers = emptyList()
        ),
        resolvedDocument = ResolvedMetadataDocument(
            canonicalId = canonicalId,
            title = "Test Show",
            overview = null,
            poster = null,
            backdrop = null,
            logo = null,
            rating = null,
            runtimeMinutes = null,
            fieldOwners = emptyMap(),
            ignoredOverwrites = emptyList()
        ),
        displayMetadata = HomeDisplayMetadata(title = "Test Show"),
        trace = emptyList()
    )

    private fun seriesMeta(id: String): Meta {
        return Meta(
            id = id,
            type = ContentType.SERIES,
            rawType = "series",
            name = "Series",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            runtime = null,
            director = emptyList(),
            writer = emptyList(),
            cast = emptyList(),
            castMembers = emptyList(),
            videos = emptyList(),
            productionCompanies = emptyList(),
            networks = emptyList(),
            country = null,
            awards = null,
            language = null,
            links = emptyList()
        )
    }
}
