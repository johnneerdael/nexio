package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataResolutionResult
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.ResolvedMetadataDocument
import com.nexio.tv.core.metadata.router.ResolverSchedule
import com.nexio.tv.data.local.WatchProgressPreferences
import com.nexio.tv.data.repository.trakt.SeasonMarkBatcher
import com.nexio.tv.data.trakt.outbox.TraktMutationOutboxCoordinator
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.TrackingProvider
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import javax.inject.Provider
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class WatchProgressRepositoryRouterRoutingTest {

    @Test
    fun `fetchContentMetadata routes via resolveRequest with WatchProgress fields as addonMetadata`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val metaRepository = mockk<MetaRepository>(relaxed = true)
        val captured = slot<MetadataRequest>()
        coEvery { facade.resolveRequest(capture(captured)) } returns successResult()

        val progress = WatchProgress(
            contentId = "tvdb:355567",
            contentType = "series",
            name = "Sample TV",
            poster = "p",
            backdrop = "b",
            logo = "l",
            videoId = "tvdb:355567:1:1",
            season = 1,
            episode = 1,
            episodeTitle = null,
            position = 0L,
            duration = 0L,
            lastWatched = 0L
        )

        val repo = buildRepository(facade = facade, metaRepository = metaRepository)
        repo.fetchContentMetadata(progress)

        coVerify(exactly = 1) { facade.resolveRequest(any()) }
        coVerify(exactly = 0) { metaRepository.getMetaFromAllAddons(any(), any(), any(), any(), any()) }
        val ctx = captured.captured.sourceContext
        assertNotNull(ctx.addonMetadata)
        assertEquals("Sample TV", ctx.addonMetadata!!.title)
        assertEquals("p", ctx.addonMetadata!!.poster)
        assertEquals("b", ctx.addonMetadata!!.backdrop)
        assertEquals("l", ctx.addonMetadata!!.logo)
    }

    @Test
    fun `fetchContentMetadata returns null when route is null`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val metaRepository = mockk<MetaRepository>(relaxed = true)
        coEvery { facade.resolveRequest(any()) } returns successResult().copy(route = null)

        val progress = WatchProgress(
            contentId = "tvdb:355567",
            contentType = "series",
            name = "Sample TV",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "tvdb:355567:1:1",
            season = null,
            episode = null,
            episodeTitle = null,
            position = 0L,
            duration = 0L,
            lastWatched = 0L
        )

        val repo = buildRepository(facade = facade, metaRepository = metaRepository)
        val result = repo.fetchContentMetadata(progress)

        assertEquals(null, result)
    }

    @Test
    fun `fetchContentMetadata falls back to WatchProgress fields when displayMetadata has nulls`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val metaRepository = mockk<MetaRepository>(relaxed = true)
        coEvery { facade.resolveRequest(any()) } returns successResult().copy(
            displayMetadata = HomeDisplayMetadata(title = null, poster = null, backdrop = null, logo = null)
        )

        val progress = WatchProgress(
            contentId = "tvdb:355567",
            contentType = "series",
            name = "Fallback Name",
            poster = "fallback-p",
            backdrop = "fallback-b",
            logo = "fallback-l",
            videoId = "tvdb:355567:1:1",
            season = null,
            episode = null,
            episodeTitle = null,
            position = 0L,
            duration = 0L,
            lastWatched = 0L
        )

        val repo = buildRepository(facade = facade, metaRepository = metaRepository)
        val result = repo.fetchContentMetadata(progress)

        assertNotNull(result)
        assertEquals("Fallback Name", result!!.name)
        assertEquals("fallback-p", result.poster)
        assertEquals("fallback-b", result.backdrop)
        assertEquals("fallback-l", result.logo)
    }

    private fun buildRepository(
        facade: MetadataRouterFacade,
        metaRepository: MetaRepository
    ): WatchProgressRepositoryImpl {
        val trackingProviderStateService = mockk<TrackingProviderStateService> {
            every { state } returns flowOf(
                EffectiveTrackingProviderState(
                    effectiveProvider = TrackingProvider.TRAKT,
                    simklAuthenticated = false
                )
            )
            coEvery { currentState() } returns EffectiveTrackingProviderState(
                effectiveProvider = TrackingProvider.TRAKT,
                simklAuthenticated = false
            )
        }
        return WatchProgressRepositoryImpl(
            watchProgressPreferences = mockk<WatchProgressPreferences>(relaxed = true),
            trackingProviderStateService = trackingProviderStateService,
            trackingProgressService = mockk(relaxed = true) {
                every { observeAllProgress() } returns flowOf(emptyList())
                every { observeRemoteSnapshotLoaded() } returns flowOf(true)
                every { observeContinueWatchingNextUp() } returns flowOf(emptyList())
                every { observeSyntheticContinueWatchingNextUp() } returns flowOf(emptyList())
                every { observeEpisodeProgress(any()) } returns flowOf(emptyMap())
                every { observeMovieWatched(any()) } returns flowOf(false)
            },
            traktMutationOutboxCoordinator = mockk<TraktMutationOutboxCoordinator>(relaxed = true),
            metaRepository = metaRepository,
            seasonMarkBatcher = mockk<SeasonMarkBatcher>(relaxed = true),
            traktAuthService = mockk(relaxed = true) {
                every { currentTraktProfileId() } returns 1
            },
            snapshotServiceProvider = Provider {
                mockk<ContinueWatchingSnapshotService>(relaxed = true)
            },
            metadataRouterFacade = facade
        )
    }

    private fun successResult() = MetadataResolutionResult(
        route = MetadataRoute(
            provider = MetadataPrimaryProvider.TVDB,
            parentId = "tvdb:355567",
            mediaKind = MetadataMediaKind.SERIES,
            reason = MetadataDecisionReason.ITEM_TYPE_SERIES,
            sourceContext = MetadataSourceContext(),
            targetIds = mapOf(MetadataPrimaryProvider.TVDB to "tvdb:355567"),
            trace = emptyList()
        ),
        plan = null,
        resolverSchedule = ResolverSchedule(MetadataDepth.DETAIL_CORE, emptyList(), emptyList()),
        resolvedDocument = ResolvedMetadataDocument(
            canonicalId = "tvdb:355567",
            title = "Sample TV",
            overview = null,
            poster = "tvdb-p",
            backdrop = "tvdb-b",
            logo = null,
            rating = null,
            runtimeMinutes = null,
            fieldOwners = emptyMap(),
            ignoredOverwrites = emptyList()
        ),
        displayMetadata = HomeDisplayMetadata(
            title = "Sample TV",
            poster = "tvdb-p",
            backdrop = "tvdb-b"
        ),
        trace = emptyList()
    )
}
