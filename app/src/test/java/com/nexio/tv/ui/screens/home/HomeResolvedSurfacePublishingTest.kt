package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.metadata.router.FieldOwner
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataResolutionResult
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataRouteTrace
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.metadata.router.ResolvedMetadataDocument
import com.nexio.tv.core.metadata.router.ResolverSchedule
import com.nexio.tv.core.metadata.router.SourceRole
import com.nexio.tv.core.metadata.router.StableIdResolutionTrigger
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.data.local.HydratedHomeOverlayStore
import com.nexio.tv.data.repository.ResolvedDisplaySurfaceRepository
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.FirstPaintSource
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.RailSource
import com.nexio.tv.domain.model.TitleRatingSource
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeResolvedSurfacePublishingTest {
    @Test
    fun `home hydration publishes resolved display surface without promoting preview rating fields`() = runTest {
        val activeSession = ActiveProfileSession(
            profileId = 7,
            sessionId = "home-session",
            sessionOrdinal = 1L,
            startedAtMs = 1_000L
        )
        val surfaceRepository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession })
        val facade = mockk<MetadataRouterFacade>()
        val overlayStore = mockk<HydratedHomeOverlayStore>(relaxed = true)
        val preview = MetaPreview(
            id = "550",
            type = ContentType.MOVIE,
            rawType = "movie",
            name = "Preview title",
            poster = "preview-poster.jpg",
            posterShape = PosterShape.POSTER,
            background = "preview-backdrop.jpg",
            logo = null,
            description = "Preview overview",
            releaseInfo = "1999",
            imdbRating = 6.5f,
            ratingSource = TitleRatingSource.TMDB,
            genres = listOf("Preview Genre"),
            firstPaintSource = FirstPaintSource.RAIL_PREVIEW,
            firstPaintSourceProvider = ProviderId.TMDB,
            firstPaintStableIds = ProviderIds(tmdb = "550", imdb = "tt-preview"),
            firstPaintRailSource = RailSource.BUILT_IN_TMDB,
            firstPaintSourceItemId = "550"
        )

        coEvery { facade.resolveRequest(any()) } returns resolutionResult(
            displayMetadata = HomeDisplayMetadata(
                title = "Canonical title",
                description = "Canonical overview",
                poster = "canonical-poster.jpg",
                backdrop = "canonical-backdrop.jpg",
                imdbRating = 8.4f,
                ratingSource = TitleRatingSource.IMDB,
                genres = listOf("Canonical Genre"),
                releaseInfo = "1999-10-15"
            )
        )
        coEvery { facade.resolveStableIdBundle(any<MetadataRoute>(), any(), any(), any()) } throws IllegalStateException("stable ids unavailable")

        HomeHydrationCoordinator(
            metadataRouterFacade = facade,
            overlayStore = overlayStore,
            traceEvents = TraceMetadataEvents(RecordingTraceSink()) { "home-test" }
        ).hydrate(
            item = preview,
            trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
            priority = HomeHydrationPriority.VISIBLE,
            languageTag = "en-US",
            expectedGeneration = 7L,
            currentGeneration = { 7L },
            onOverlayApplied = { overlay ->
                surfaceRepository.publishResolvedItems(
                    surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
                    profileSession = activeSession,
                    items = listOf(overlay.toResolvedDisplayItem())
                )
                true
            }
        )

        val published = surfaceRepository.getSnapshot(profileId = activeSession.profileId).single()
        assertEquals("movie:550", published.itemKey)
        assertEquals("Canonical title", published.display.title)
        assertEquals("Canonical overview", published.display.overview)
        assertNull(published.artwork.poster)
        assertEquals(8.4, published.rating?.value ?: 0.0, 0.001)
        assertEquals(TitleRatingSource.IMDB, published.rating?.source)
        assertEquals(HydrationState.CANONICAL_READY, published.hydrationState)
        assertTrue(preview.imdbRating == 6.5f)
    }

    @Test
    fun `home hydration does not publish resolved surface when overlay is rejected`() = runTest {
        val activeSession = ActiveProfileSession(
            profileId = 7,
            sessionId = "home-session",
            sessionOrdinal = 1L,
            startedAtMs = 1_000L
        )
        val surfaceRepository = ResolvedDisplaySurfaceRepository(activeProfileSession = { activeSession })
        val facade = mockk<MetadataRouterFacade>()
        val overlayStore = mockk<HydratedHomeOverlayStore>(relaxed = true)
        val preview = MetaPreview(
            id = "550",
            type = ContentType.MOVIE,
            rawType = "movie",
            name = "Preview title",
            poster = "preview-poster.jpg",
            posterShape = PosterShape.POSTER,
            background = "preview-backdrop.jpg",
            logo = null,
            description = "Preview overview",
            releaseInfo = "1999",
            imdbRating = 6.5f,
            ratingSource = TitleRatingSource.TMDB,
            genres = listOf("Preview Genre"),
            firstPaintSource = FirstPaintSource.RAIL_PREVIEW,
            firstPaintSourceProvider = ProviderId.TMDB,
            firstPaintStableIds = ProviderIds(tmdb = "550", imdb = "tt-preview"),
            firstPaintRailSource = RailSource.BUILT_IN_TMDB,
            firstPaintSourceItemId = "550"
        )

        coEvery { facade.resolveRequest(any()) } returns resolutionResult(
            displayMetadata = HomeDisplayMetadata(
                title = "Canonical title",
                description = "Canonical overview",
                imdbRating = 8.4f,
                ratingSource = TitleRatingSource.IMDB
            )
        )
        coEvery { facade.resolveStableIdBundle(any<MetadataRoute>(), any(), any(), any()) } throws IllegalStateException("stable ids unavailable")

        HomeHydrationCoordinator(
            metadataRouterFacade = facade,
            overlayStore = overlayStore,
            traceEvents = TraceMetadataEvents(RecordingTraceSink()) { "home-test" }
        ).hydrate(
            item = preview,
            trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
            priority = HomeHydrationPriority.VISIBLE,
            languageTag = "en-US",
            expectedGeneration = 7L,
            currentGeneration = { 7L },
            onOverlayApplied = { false }
        )

        assertTrue(surfaceRepository.getSnapshot(profileId = activeSession.profileId).isEmpty())
    }

    private fun resolutionResult(
        displayMetadata: HomeDisplayMetadata
    ) = MetadataResolutionResult(
        route = MetadataRoute(
            provider = MetadataPrimaryProvider.TMDB,
            parentId = "tmdb:550",
            mediaKind = MetadataMediaKind.MOVIE,
            reason = MetadataDecisionReason.ITEM_TYPE_MOVIE,
            sourceContext = MetadataSourceContext(),
            language = "en-US",
            targetIds = mapOf(MetadataPrimaryProvider.TMDB to "550"),
            trace = listOf(MetadataRouteTrace(MetadataDecisionReason.ITEM_TYPE_MOVIE, "movie route"))
        ),
        plan = null,
        resolverSchedule = ResolverSchedule(
            depth = MetadataDepth.DETAIL_CORE,
            localResolvers = emptyList(),
            networkResolvers = emptyList()
        ),
        resolvedDocument = ResolvedMetadataDocument(
            canonicalId = "tmdb:550",
            title = "Canonical title",
            overview = "Canonical overview",
            poster = "canonical-poster.jpg",
            backdrop = "canonical-backdrop.jpg",
            logo = null,
            rating = 8.4,
            runtimeMinutes = 139,
            genres = listOf("Canonical Genre"),
            releaseDate = "1999-10-15",
            fieldOwners = mapOf(ResolvedField.TITLE to FieldOwner.PRIMARY),
            ignoredOverwrites = emptyList(),
            sourceRoles = mapOf(ResolvedField.TITLE to SourceRole.PRIMARY),
            sourceProviders = mapOf(ResolvedField.TITLE to "TMDB")
        ),
        displayMetadata = displayMetadata,
        trace = emptyList()
    )
}
