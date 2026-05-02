package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.metadata.router.CanonicalStableIds
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
import com.nexio.tv.core.metadata.router.SidecarStableIds
import com.nexio.tv.core.metadata.router.SourceRole
import com.nexio.tv.core.metadata.router.SourceStableIds
import com.nexio.tv.core.metadata.router.StableIdBundle
import com.nexio.tv.core.metadata.router.StableIdEvidence
import com.nexio.tv.core.metadata.router.StableIdResolutionTrigger
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.data.local.HydratedHomeOverlayStore
import com.nexio.tv.data.repository.TitleRatingOverrideRepository
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.FirstPaintSource
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HomeItemHydrationState
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.RailSource
import com.nexio.tv.domain.model.TitleRatingSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeHydrationCoordinatorTest {
    @Test
    fun `visible hydration writes overlay with canonical fields and imdb rating enrichment`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val store = mockk<HydratedHomeOverlayStore>(relaxed = true)
        val ratings = mockk<TitleRatingOverrideRepository>()
        val sink = RecordingTraceSink()
        val overlaySlot = slot<com.nexio.tv.domain.model.HydratedHomeOverlay>()
        val aliasesSlot = slot<Set<String>>()
        val preview = preview(
            id = "550",
            title = "Preview title",
            stableIds = ProviderIds(tmdb = "550", imdb = "tt0137523")
        )
        val bundle = stableBundle(itemKey = "movie:550")
        var applied: com.nexio.tv.domain.model.HydratedHomeOverlay? = null

        coEvery {
            facade.resolveRequest(match { request ->
                request.contentId == "550" &&
                    request.contentType == ContentType.MOVIE &&
                    request.language == "en-US" &&
                    request.depth == MetadataDepth.DETAIL_CORE &&
                    request.sourceContext.previewStableIds.tmdb == "550" &&
                    request.sourceContext.previewStableIds.imdb == "tt0137523" &&
                    request.sourceContext.previewSourceProvider == "TMDB" &&
                    request.sourceContext.previewRailSource == "BUILT_IN_TMDB" &&
                    request.sourceContext.addonMetadata?.title == "Preview title"
            })
        } returns resolutionResult()
        coEvery {
            facade.resolveStableIdBundle(
                any<MetadataRoute>(),
                any(),
                StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
                "movie:550"
            )
        } returns bundle
        coEvery { ratings.enrichPreview(any(), bundle) } answers {
            firstArg<MetaPreview>().copy(imdbRating = 8.8f, ratingSource = TitleRatingSource.IMDB)
        }
        coEvery { store.upsert(capture(overlaySlot), capture(aliasesSlot)) } returns Unit

        val result = coordinator(facade, store, ratings, sink).hydrate(
            item = preview,
            trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
            priority = HomeHydrationPriority.VISIBLE,
            languageTag = "en-US",
            expectedGeneration = 7L,
            currentGeneration = { 7L },
            onOverlayApplied = {
                applied = it
                true
            }
        )

        assertSame(overlaySlot.captured, result)
        assertSame(overlaySlot.captured, applied)
        assertEquals("Canonical title", overlaySlot.captured.fields.title)
        assertEquals("Canonical overview", overlaySlot.captured.fields.description)
        assertEquals("poster.jpg", overlaySlot.captured.fields.poster)
        assertEquals(listOf("Canonical Genre"), overlaySlot.captured.fields.genres)
        assertEquals("1999-10-15", overlaySlot.captured.fields.releaseInfo)
        assertEquals(8.8f, overlaySlot.captured.fields.imdbRating ?: 0f, 0f)
        assertEquals(TitleRatingSource.IMDB, overlaySlot.captured.fields.ratingSource)
        assertEquals(ProviderId.TMDB, overlaySlot.captured.canonicalProvider)
        assertEquals("550", overlaySlot.captured.canonicalId)
        assertEquals("tt0137523", overlaySlot.captured.imdbId)
        assertEquals(HomeItemHydrationState.CANONICAL_READY, overlaySlot.captured.state)
        assertEquals(
            listOf(com.nexio.tv.domain.model.HydratedHomeFieldTrace("TITLE", "TMDB", "PRIMARY")),
            overlaySlot.captured.fieldTrace
        )
        assertTrue(aliasesSlot.captured.containsAll(listOf("movie:550", "movie:imdb:tt0137523", "movie:tmdb:550", "movie:tvdb:81189", "movie:kitsu:42")))
        assertEquals(
            listOf("home.hydration_started", "home.hydration_overlay_written", "home.hydration_applied"),
            sink.events.map { it.eventType }
        )
        coVerify(exactly = 0) {
            facade.resolveStableIdBundle(any<com.nexio.tv.core.metadata.router.MetadataRequest>(), any(), any())
        }
    }

    @Test
    fun `already stale generation is ignored before provider resolution`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val store = mockk<HydratedHomeOverlayStore>(relaxed = true)
        val ratings = mockk<TitleRatingOverrideRepository>()
        val sink = RecordingTraceSink()
        var applied = false

        val result = coordinator(facade, store, ratings, sink).hydrate(
            item = preview(id = "550", title = "Preview title", stableIds = ProviderIds(tmdb = "550")),
            trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
            priority = HomeHydrationPriority.VISIBLE,
            languageTag = "en-US",
            expectedGeneration = 7L,
            currentGeneration = { 8L },
            onOverlayApplied = {
                applied = true
                true
            }
        )

        assertNull(result)
        assertFalse(applied)
        coVerify(exactly = 0) { facade.resolveRequest(any()) }
        coVerify(exactly = 0) { facade.resolveStableIdBundle(any<MetadataRoute>(), any(), any(), any()) }
        coVerify(exactly = 0) { ratings.enrichPreview(any(), any()) }
        coVerify(exactly = 0) { store.upsert(any(), any()) }
        assertEquals(
            listOf("home.hydration_started", "home.hydration_ignored"),
            sink.events.map { it.eventType }
        )
    }

    @Test
    fun `generation mismatch after provider resolution avoids stable ids rating write and apply`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val store = mockk<HydratedHomeOverlayStore>(relaxed = true)
        val ratings = mockk<TitleRatingOverrideRepository>()
        val sink = RecordingTraceSink()
        var generation = 7L
        var applied = false

        coEvery { facade.resolveRequest(any()) } answers {
            generation = 8L
            resolutionResult()
        }

        val result = coordinator(facade, store, ratings, sink).hydrate(
            item = preview(id = "550", title = "Preview title", stableIds = ProviderIds(tmdb = "550")),
            trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
            priority = HomeHydrationPriority.VISIBLE,
            languageTag = "en-US",
            expectedGeneration = 7L,
            currentGeneration = { generation },
            onOverlayApplied = {
                applied = true
                true
            }
        )

        assertNull(result)
        assertFalse(applied)
        coVerify(exactly = 1) { facade.resolveRequest(any()) }
        coVerify(exactly = 0) { facade.resolveStableIdBundle(any<MetadataRoute>(), any(), any(), any()) }
        coVerify(exactly = 0) { ratings.enrichPreview(any(), any()) }
        coVerify(exactly = 0) { store.upsert(any(), any()) }
        assertEquals(
            listOf("home.hydration_started", "home.hydration_ignored"),
            sink.events.map { it.eventType }
        )
    }

    @Test
    fun `late generation mismatch is ignored and does not write`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val store = mockk<HydratedHomeOverlayStore>(relaxed = true)
        val ratings = mockk<TitleRatingOverrideRepository>()
        val sink = RecordingTraceSink()
        var applied = false

        coEvery { facade.resolveRequest(any()) } returns resolutionResult()
        coEvery { facade.resolveStableIdBundle(any<MetadataRoute>(), any(), any(), any()) } returns stableBundle("movie:550")
        coEvery { ratings.enrichPreview(any(), any()) } answers { firstArg() }

        val result = coordinator(facade, store, ratings, sink).hydrate(
            item = preview(id = "550", title = "Preview title", stableIds = ProviderIds(tmdb = "550")),
            trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
            priority = HomeHydrationPriority.VISIBLE,
            languageTag = "en-US",
            expectedGeneration = 7L,
            currentGeneration = { 8L },
            onOverlayApplied = {
                applied = true
                true
            }
        )

        assertNull(result)
        assertFalse(applied)
        coVerify(exactly = 0) { store.upsert(any(), any()) }
        assertEquals(
            listOf("home.hydration_started", "home.hydration_ignored"),
            sink.events.map { it.eventType }
        )
    }

    @Test
    fun `generation change during store write is ignored and does not apply overlay`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val store = mockk<HydratedHomeOverlayStore>()
        val ratings = mockk<TitleRatingOverrideRepository>()
        val sink = RecordingTraceSink()
        var generation = 7L
        var applied = false

        coEvery { facade.resolveRequest(any()) } returns resolutionResult()
        coEvery { facade.resolveStableIdBundle(any<MetadataRoute>(), any(), any(), any()) } returns stableBundle("movie:550")
        coEvery { ratings.enrichPreview(any(), any()) } answers { firstArg() }
        coEvery { store.upsert(any(), any()) } answers {
            generation = 8L
            Unit
        }

        val result = coordinator(facade, store, ratings, sink).hydrate(
            item = preview(id = "550", title = "Preview title", stableIds = ProviderIds(tmdb = "550")),
            trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
            priority = HomeHydrationPriority.VISIBLE,
            languageTag = "en-US",
            expectedGeneration = 7L,
            currentGeneration = { generation },
            onOverlayApplied = {
                applied = true
                true
            }
        )

        assertNull(result)
        assertFalse(applied)
        coVerify(exactly = 1) { store.upsert(any(), any()) }
        assertEquals(
            listOf("home.hydration_started", "home.hydration_ignored"),
            sink.events.map { it.eventType }
        )
    }

    @Test
    fun `generation change during stable id resolution avoids rating enrichment write and apply`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val store = mockk<HydratedHomeOverlayStore>(relaxed = true)
        val ratings = mockk<TitleRatingOverrideRepository>()
        val sink = RecordingTraceSink()
        var generation = 7L
        var applied = false

        coEvery { facade.resolveRequest(any()) } returns resolutionResult()
        coEvery { facade.resolveStableIdBundle(any<MetadataRoute>(), any(), any(), any()) } answers {
            generation = 8L
            stableBundle("movie:550")
        }
        coEvery { ratings.enrichPreview(any(), any()) } answers { firstArg() }

        val result = coordinator(facade, store, ratings, sink).hydrate(
            item = preview(id = "550", title = "Preview title", stableIds = ProviderIds(tmdb = "550")),
            trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
            priority = HomeHydrationPriority.VISIBLE,
            languageTag = "en-US",
            expectedGeneration = 7L,
            currentGeneration = { generation },
            onOverlayApplied = {
                applied = true
                true
            }
        )

        assertNull(result)
        assertFalse(applied)
        coVerify(exactly = 0) { ratings.enrichPreview(any(), any()) }
        coVerify(exactly = 0) { store.upsert(any(), any()) }
        assertEquals(
            listOf("home.hydration_started", "home.hydration_ignored"),
            sink.events.map { it.eventType }
        )
    }

    @Test
    fun `stable bundle failure still writes and applies canonical overlay with nullable rating enrichment`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val store = mockk<HydratedHomeOverlayStore>(relaxed = true)
        val ratings = mockk<TitleRatingOverrideRepository>()
        val sink = RecordingTraceSink()
        val overlaySlot = slot<com.nexio.tv.domain.model.HydratedHomeOverlay>()
        val aliasesSlot = slot<Set<String>>()
        var applied: com.nexio.tv.domain.model.HydratedHomeOverlay? = null

        coEvery { facade.resolveRequest(any()) } returns resolutionResult()
        coEvery { facade.resolveStableIdBundle(any<MetadataRoute>(), any(), any(), any()) } throws IllegalStateException("stable ids failed")
        coEvery { ratings.enrichPreview(any(), null) } answers {
            firstArg<MetaPreview>().copy(imdbRating = 7.7f, ratingSource = TitleRatingSource.IMDB)
        }
        coEvery { store.upsert(capture(overlaySlot), capture(aliasesSlot)) } returns Unit

        val result = coordinator(facade, store, ratings, sink).hydrate(
            item = preview(
                id = "550",
                title = "Preview title",
                stableIds = ProviderIds(tmdb = "550", imdb = "tt0137523")
            ),
            trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
            priority = HomeHydrationPriority.VISIBLE,
            languageTag = "en-US",
            expectedGeneration = 7L,
            currentGeneration = { 7L },
            onOverlayApplied = {
                applied = it
                true
            }
        )

        assertSame(overlaySlot.captured, result)
        assertSame(overlaySlot.captured, applied)
        assertEquals(ProviderId.TMDB, overlaySlot.captured.canonicalProvider)
        assertEquals("550", overlaySlot.captured.canonicalId)
        assertEquals("tt0137523", overlaySlot.captured.imdbId)
        assertEquals(7.7f, overlaySlot.captured.fields.imdbRating ?: 0f, 0f)
        assertTrue(aliasesSlot.captured.containsAll(listOf("movie:550", "movie:imdb:tt0137523", "movie:tmdb:550")))
        assertEquals(
            listOf("home.hydration_started", "home.hydration_overlay_written", "home.hydration_applied"),
            sink.events.map { it.eventType }
        )
        val appliedPayload = sink.events.last().payload as Map<*, *>
        assertEquals("STABLE_ID_UNAVAILABLE", appliedPayload["cacheDecision"])
        assertEquals(false, appliedPayload["networkExecuted"])
    }

    @Test
    fun `stable bundle failure uses route target id when document canonical id is missing`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val store = mockk<HydratedHomeOverlayStore>(relaxed = true)
        val ratings = mockk<TitleRatingOverrideRepository>()
        val sink = RecordingTraceSink()
        val overlaySlot = slot<com.nexio.tv.domain.model.HydratedHomeOverlay>()
        var applied: com.nexio.tv.domain.model.HydratedHomeOverlay? = null

        coEvery { facade.resolveRequest(any()) } returns resolutionResult(canonicalId = null)
        coEvery { facade.resolveStableIdBundle(any<MetadataRoute>(), any(), any(), any()) } throws IllegalStateException("stable ids failed")
        coEvery { ratings.enrichPreview(any(), null) } answers { firstArg() }
        coEvery { store.upsert(capture(overlaySlot), any()) } returns Unit

        val result = coordinator(facade, store, ratings, sink).hydrate(
            item = preview(
                id = "550",
                title = "Preview title",
                stableIds = ProviderIds(tmdb = "550", imdb = "tt0137523")
            ),
            trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
            priority = HomeHydrationPriority.VISIBLE,
            languageTag = "en-US",
            expectedGeneration = 7L,
            currentGeneration = { 7L },
            onOverlayApplied = {
                applied = it
                true
            }
        )

        assertSame(overlaySlot.captured, result)
        assertSame(overlaySlot.captured, applied)
        assertEquals(ProviderId.TMDB, overlaySlot.captured.canonicalProvider)
        assertEquals("550", overlaySlot.captured.canonicalId)
        assertEquals("tt0137523", overlaySlot.captured.imdbId)
        assertEquals(
            listOf("home.hydration_started", "home.hydration_overlay_written", "home.hydration_applied"),
            sink.events.map { it.eventType }
        )
    }

    @Test
    fun `hydration failure keeps preview path and does not write`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val store = mockk<HydratedHomeOverlayStore>(relaxed = true)
        val ratings = mockk<TitleRatingOverrideRepository>(relaxed = true)
        val sink = RecordingTraceSink()
        var applied = false

        coEvery { facade.resolveRequest(any()) } throws IllegalStateException("router failed")

        val result = coordinator(facade, store, ratings, sink).hydrate(
            item = preview(id = "550", title = "Preview title", stableIds = ProviderIds(tmdb = "550")),
            trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
            priority = HomeHydrationPriority.VISIBLE,
            languageTag = "en-US",
            expectedGeneration = 7L,
            currentGeneration = { 7L },
            onOverlayApplied = {
                applied = true
                true
            }
        )

        assertNull(result)
        assertFalse(applied)
        coVerify(exactly = 0) { store.upsert(any(), any()) }
        coVerify(exactly = 0) { ratings.enrichPreview(any(), any()) }
        assertEquals(
            listOf("home.hydration_started", "home.hydration_failed_using_preview"),
            sink.events.map { it.eventType }
        )
    }

    @Test
    fun `rejected applied overlay writes durable overlay but does not emit applied trace`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val store = mockk<HydratedHomeOverlayStore>()
        val ratings = mockk<TitleRatingOverrideRepository>()
        val sink = RecordingTraceSink()
        val overlaySlot = slot<com.nexio.tv.domain.model.HydratedHomeOverlay>()

        coEvery { facade.resolveRequest(any()) } returns resolutionResult()
        coEvery { facade.resolveStableIdBundle(any<MetadataRoute>(), any(), any(), any()) } returns stableBundle("movie:550")
        coEvery { ratings.enrichPreview(any(), any()) } answers { firstArg() }
        coEvery { store.upsert(capture(overlaySlot), any()) } returns Unit

        val result = coordinator(facade, store, ratings, sink).hydrate(
            item = preview(id = "550", title = "Preview title", stableIds = ProviderIds(tmdb = "550")),
            trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
            priority = HomeHydrationPriority.VISIBLE,
            languageTag = "en-US",
            expectedGeneration = 7L,
            currentGeneration = { 7L },
            onOverlayApplied = { false }
        )

        assertNull(result)
        assertEquals("550", overlaySlot.captured.canonicalId)
        assertEquals(
            listOf("home.hydration_started", "home.hydration_overlay_written"),
            sink.events.map { it.eventType }
        )
    }

    private fun coordinator(
        facade: MetadataRouterFacade,
        store: HydratedHomeOverlayStore,
        ratings: TitleRatingOverrideRepository,
        sink: RecordingTraceSink
    ) = HomeHydrationCoordinator(
        metadataRouterFacade = facade,
        overlayStore = store,
        titleRatingOverrideRepository = ratings,
        traceEvents = TraceMetadataEvents(sink) { "home-test" }
    )

    private fun preview(
        id: String,
        title: String,
        stableIds: ProviderIds
    ) = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        rawType = "movie",
        name = title,
        poster = "preview-poster.jpg",
        posterShape = PosterShape.POSTER,
        background = "preview-backdrop.jpg",
        logo = null,
        description = "Preview overview",
        releaseInfo = "1999",
        runtime = null,
        imdbRating = 6.5f,
        ratingSource = TitleRatingSource.TMDB,
        genres = listOf("Drama"),
        firstPaintSource = FirstPaintSource.RAIL_PREVIEW,
        firstPaintSourceProvider = ProviderId.TMDB,
        firstPaintStableIds = stableIds,
        firstPaintRailSource = RailSource.BUILT_IN_TMDB,
        firstPaintSourceItemId = id
    )

    private fun resolutionResult(
        canonicalId: String? = "tmdb:550"
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
            canonicalId = canonicalId,
            title = "Canonical title",
            overview = "Canonical overview",
            poster = "poster.jpg",
            backdrop = "backdrop.jpg",
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
        displayMetadata = HomeDisplayMetadata(
            title = "Canonical title",
            description = "Canonical overview",
            poster = "poster.jpg",
            backdrop = "backdrop.jpg",
            imdbRating = 8.4f,
            ratingSource = TitleRatingSource.TMDB,
            genres = listOf("Canonical Genre"),
            releaseInfo = "1999-10-15"
        ),
        trace = emptyList()
    )

    private fun stableBundle(itemKey: String) = StableIdBundle(
        itemKey = itemKey,
        itemType = ContentType.MOVIE,
        canonical = CanonicalStableIds(
            tmdbMovieId = "550",
            tvdbSeriesId = "81189",
            kitsuAnimeId = "42"
        ),
        sidecars = SidecarStableIds(imdbId = "tt0137523"),
        source = SourceStableIds(
            sourceProvider = ProviderId.TMDB,
            sourceItemId = "550",
            railId = RailSource.BUILT_IN_TMDB.name,
            observedIds = ProviderIds(tmdb = "550", imdb = "tt0137523")
        ),
        evidence = listOf(StableIdEvidence("tmdb", "imdb", networkExecuted = false, resultId = "tt0137523")),
        resolvedAtMs = 1L
    )
}
