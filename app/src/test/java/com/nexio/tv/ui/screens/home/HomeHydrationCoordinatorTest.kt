package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkAssetKey
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.metadata.router.CanonicalStableIds
import com.nexio.tv.core.metadata.router.FieldOwner
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataResolutionResult
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataRouteFailure
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeHydrationCoordinatorTest {
    @Test
    fun `visible hydration writes overlay with canonical resolved fields`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val store = mockk<HydratedHomeOverlayStore>(relaxed = true)
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
        coEvery { store.upsert(capture(overlaySlot), capture(aliasesSlot)) } returns Unit

        val result = coordinator(facade, store, sink).hydrate(
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
        assertEquals(8.4f, overlaySlot.captured.fields.imdbRating ?: 0f, 0f)
        assertEquals(TitleRatingSource.TMDB, overlaySlot.captured.fields.ratingSource)
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
            listOf(
                "home.hydration_started",
                "home.hydration_overlay_written",
                "home.rating_and_artwork_surface",
                "home.hydration_applied"
            ),
            sink.events.map { it.eventType }
        )
        coVerify(exactly = 0) {
            facade.resolveStableIdBundle(any<com.nexio.tv.core.metadata.router.MetadataRequest>(), any(), any())
        }
    }

    @Test
    fun `visible hydration preserves typed artwork in written overlay fields`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val store = mockk<HydratedHomeOverlayStore>(relaxed = true)
        val sink = RecordingTraceSink()
        val overlaySlot = slot<com.nexio.tv.domain.model.HydratedHomeOverlay>()
        val artwork = ArtworkBundle(
            poster = ArtworkDisplayRef.RuntimeAsset(
                decisionKey = ArtworkDecisionKey("posterDecision"),
                assetKey = ArtworkAssetKey("posterAsset"),
                imageType = ArtworkType.POSTER,
                selectedProvider = null,
                sourceRole = ArtworkSourceRole.PRIMARY,
                trace = ArtworkTrace.empty()
            )
        )

        coEvery { facade.resolveRequest(any()) } returns resolutionResult(
            displayMetadata = HomeDisplayMetadata(
                title = "Canonical title",
                artwork = artwork
            )
        )
        coEvery { facade.resolveStableIdBundle(any<MetadataRoute>(), any(), any(), any()) } returns stableBundle("movie:550")
        coEvery { store.upsert(capture(overlaySlot), any()) } returns Unit

        coordinator(facade, store, sink).hydrate(
            item = preview(id = "550", title = "Preview title", stableIds = ProviderIds(tmdb = "550")),
            trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
            priority = HomeHydrationPriority.VISIBLE,
            languageTag = "en-US",
            expectedGeneration = 7L,
            currentGeneration = { 7L },
            onOverlayApplied = { true }
        )

        assertEquals(artwork, overlaySlot.captured.fields.artwork)
        assertEquals("nexio-artwork://asset/posterAsset", overlaySlot.captured.fields.poster)
        assertEquals("nexio-artwork://asset/posterAsset", overlaySlot.captured.fields.displayPoster)
    }

    @Test
    fun `hydration overlay includes hydrated logo when resolver returns logo`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val store = mockk<HydratedHomeOverlayStore>(relaxed = true)
        val overlaySlot = slot<com.nexio.tv.domain.model.HydratedHomeOverlay>()
        coEvery { store.upsert(capture(overlaySlot), any()) } returns Unit
        val hydratedLogo = artworkRef("tvdb-logo", ArtworkType.LOGO)
        coEvery { facade.resolveRequest(any()) } returns resolutionResult(
            displayMetadata = HomeDisplayMetadata(
                title = "Canonical title",
                artwork = ArtworkBundle(logo = hydratedLogo)
            )
        )
        coEvery { facade.resolveStableIdBundle(any<MetadataRoute>(), any(), any(), any()) } returns stableBundle("series:tmdb:94997")
        val preview = preview(
            id = "tmdb:94997",
            type = ContentType.SERIES,
            rating = 8.3f,
            artwork = ArtworkBundle()
        )

        HomeHydrationCoordinator(
            metadataRouterFacade = facade,
            overlayStore = store,
            traceEvents = TraceMetadataEvents(RecordingTraceSink()) { "home-test" }
        ).hydrate(
            item = preview,
            trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
            priority = HomeHydrationPriority.VISIBLE,
            languageTag = "en-US",
            expectedGeneration = 7L,
            currentGeneration = { 7L },
            onOverlayApplied = { true }
        )

        assertSame(hydratedLogo, overlaySlot.captured.fields.artwork?.logo)
    }

    @Test
    fun `hydrated logo overrides preview logo when available`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val store = mockk<HydratedHomeOverlayStore>(relaxed = true)
        val overlaySlot = slot<com.nexio.tv.domain.model.HydratedHomeOverlay>()
        coEvery { store.upsert(capture(overlaySlot), any()) } returns Unit
        val previewLogo = artworkRef("preview-logo", ArtworkType.LOGO)
        val hydratedLogo = artworkRef("tvdb-logo", ArtworkType.LOGO)
        coEvery { facade.resolveRequest(any()) } returns resolutionResult(
            displayMetadata = HomeDisplayMetadata(
                title = "Canonical title",
                artwork = ArtworkBundle(logo = hydratedLogo)
            )
        )
        coEvery { facade.resolveStableIdBundle(any<MetadataRoute>(), any(), any(), any()) } returns stableBundle("series:tmdb:94997")
        val preview = preview(
            id = "tmdb:94997",
            type = ContentType.SERIES,
            rating = 8.3f,
            artwork = ArtworkBundle(logo = previewLogo)
        )

        HomeHydrationCoordinator(
            metadataRouterFacade = facade,
            overlayStore = store,
            traceEvents = TraceMetadataEvents(RecordingTraceSink()) { "home-test" }
        ).hydrate(
            item = preview,
            trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
            priority = HomeHydrationPriority.VISIBLE,
            languageTag = "en-US",
            expectedGeneration = 7L,
            currentGeneration = { 7L },
            onOverlayApplied = { true }
        )

        assertSame(hydratedLogo, overlaySlot.captured.fields.artwork?.logo)
    }

    @Test
    fun `preview logo survives when hydrated logo is absent`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val store = mockk<HydratedHomeOverlayStore>(relaxed = true)
        val overlaySlot = slot<com.nexio.tv.domain.model.HydratedHomeOverlay>()
        coEvery { store.upsert(capture(overlaySlot), any()) } returns Unit
        coEvery { facade.resolveRequest(any()) } returns resolutionResult(
            displayMetadata = HomeDisplayMetadata(
                title = "Canonical title",
                artwork = ArtworkBundle(
                    backdrop = artworkRef("canonical-backdrop", ArtworkType.BACKDROP)
                )
            )
        )
        coEvery { facade.resolveStableIdBundle(any<MetadataRoute>(), any(), any(), any()) } returns stableBundle("series:tmdb:94997")
        val previewLogo = artworkRef("preview-logo", ArtworkType.LOGO)
        val preview = preview(
            id = "tmdb:94997",
            type = ContentType.SERIES,
            rating = 8.3f,
            artwork = ArtworkBundle(logo = previewLogo)
        )

        HomeHydrationCoordinator(
            metadataRouterFacade = facade,
            overlayStore = store,
            traceEvents = TraceMetadataEvents(RecordingTraceSink()) { "home-test" }
        ).hydrate(
            item = preview,
            trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
            priority = HomeHydrationPriority.VISIBLE,
            languageTag = "en-US",
            expectedGeneration = 7L,
            currentGeneration = { 7L },
            onOverlayApplied = { true }
        )

        assertSame(previewLogo, overlaySlot.captured.fields.artwork?.logo)
        assertNotNull(overlaySlot.captured.fields.artwork?.backdrop)
    }

    @Test
    fun `hydrated legacy logo prevents preview structured logo fallback`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val store = mockk<HydratedHomeOverlayStore>(relaxed = true)
        val overlaySlot = slot<com.nexio.tv.domain.model.HydratedHomeOverlay>()
        coEvery { store.upsert(capture(overlaySlot), any()) } returns Unit
        val hydratedBackdrop = artworkRef("canonical-backdrop", ArtworkType.BACKDROP)
        coEvery { facade.resolveRequest(any()) } returns resolutionResult(
            displayMetadata = HomeDisplayMetadata(
                title = "Canonical title",
                logo = "hydrated-logo-url-or-key",
                artwork = ArtworkBundle(backdrop = hydratedBackdrop)
            )
        )
        coEvery { facade.resolveStableIdBundle(any<MetadataRoute>(), any(), any(), any()) } returns stableBundle("series:tmdb:94997")
        val previewLogo = artworkRef("preview-logo", ArtworkType.LOGO)
        val preview = preview(
            id = "tmdb:94997",
            type = ContentType.SERIES,
            rating = 8.3f,
            artwork = ArtworkBundle(logo = previewLogo)
        )

        HomeHydrationCoordinator(
            metadataRouterFacade = facade,
            overlayStore = store,
            traceEvents = TraceMetadataEvents(RecordingTraceSink()) { "home-test" }
        ).hydrate(
            item = preview,
            trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
            priority = HomeHydrationPriority.VISIBLE,
            languageTag = "en-US",
            expectedGeneration = 7L,
            currentGeneration = { 7L },
            onOverlayApplied = { true }
        )

        assertEquals("hydrated-logo-url-or-key", overlaySlot.captured.fields.displayLogo)
        assertSame(hydratedBackdrop, overlaySlot.captured.fields.artwork?.backdrop)
        assertNull(overlaySlot.captured.fields.artwork?.logo)
    }

    @Test
    fun `hydration emits rating and artwork surface trace`() = runTest {
        val sink = RecordingTraceSink()
        val facade = mockk<MetadataRouterFacade>()
        val store = mockk<HydratedHomeOverlayStore>(relaxed = true)
        val overlaySlot = slot<com.nexio.tv.domain.model.HydratedHomeOverlay>()
        coEvery { store.upsert(any(), any()) } returns Unit
        coEvery { store.upsert(capture(overlaySlot), any()) } returns Unit
        coEvery { facade.resolveRequest(any()) } returns resolutionResult(
            displayMetadata = HomeDisplayMetadata(
                title = "House of the Dragon",
                imdbRating = 8.3f,
                ratingSource = TitleRatingSource.TMDB,
                artwork = ArtworkBundle(logo = artworkRef("tvdb-logo", ArtworkType.LOGO))
            )
        )
        coEvery { facade.resolveStableIdBundle(any<MetadataRoute>(), any(), any(), any()) } returns stableBundle("series:tmdb:94997")
        val preview = preview(
            id = "tmdb:94997",
            type = ContentType.SERIES,
            rating = null,
            artwork = ArtworkBundle()
        )

        HomeHydrationCoordinator(
            metadataRouterFacade = facade,
            overlayStore = store,
            traceEvents = TraceMetadataEvents(sink) { "home-test" }
        ).hydrate(
            item = preview,
            trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
            priority = HomeHydrationPriority.VISIBLE,
            languageTag = "en-US",
            expectedGeneration = 7L,
            currentGeneration = { 7L },
            onOverlayApplied = { true }
        )

        val event = sink.events.last { it.eventType == "home.rating_and_artwork_surface" }
        @Suppress("UNCHECKED_CAST")
        val payload = event.payload as Map<String, Any?>
        assertFalse(payload.containsKey("itemKey"))
        assertFalse(payload.containsKey("title"))
        assertTrue((payload["itemKeyHash"] as String).isNotBlank())
        assertFalse(payload.values.any { value -> value.toString().contains("series:tmdb:94997") })
        assertFalse(payload.values.any { value -> value.toString().contains("tmdb:94997") })
        assertFalse(payload.values.any { value -> value.toString().contains("House of the Dragon") })
        assertEquals(false, payload["firstPaintLogoPresent"])
        assertEquals(true, payload["hydratedLogoPresent"])
        assertEquals(8.3f, payload["hydratedRatingValue"])
        assertEquals("TMDB", payload["hydratedRatingSource"])
        assertEquals(null, payload["firstPaintTmdbId"])
        assertTrue((payload["firstPaintTmdbIdHash"] as String).isNotBlank())
    }

    @Test
    fun `hydration trace uses sanitized first paint rating state`() = runTest {
        val sink = RecordingTraceSink()
        val facade = mockk<MetadataRouterFacade>()
        val store = mockk<HydratedHomeOverlayStore>(relaxed = true)
        coEvery { store.upsert(any(), any()) } returns Unit
        coEvery { facade.resolveRequest(any()) } returns resolutionResult(
            displayMetadata = HomeDisplayMetadata(title = "House of the Dragon")
        )
        coEvery { facade.resolveStableIdBundle(any<MetadataRoute>(), any(), any(), any()) } returns stableBundle("series:tmdb:94997")
        val preview = preview(
            id = "tmdb:94997",
            type = ContentType.SERIES,
            rating = 1767427f,
            artwork = ArtworkBundle()
        )

        HomeHydrationCoordinator(
            metadataRouterFacade = facade,
            overlayStore = store,
            traceEvents = TraceMetadataEvents(sink) { "home-test" }
        ).hydrate(
            item = preview,
            trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
            priority = HomeHydrationPriority.VISIBLE,
            languageTag = "en-US",
            expectedGeneration = 7L,
            currentGeneration = { 7L },
            onOverlayApplied = { true }
        )

        val event = sink.events.last { it.eventType == "home.rating_and_artwork_surface" }
        @Suppress("UNCHECKED_CAST")
        val payload = event.payload as Map<String, Any?>
        assertFalse(payload.containsKey("itemKey"))
        assertFalse(payload.containsKey("title"))
        assertTrue((payload["itemKeyHash"] as String).isNotBlank())
        assertFalse(payload.values.any { value -> value.toString().contains("series:tmdb:94997") })
        assertFalse(payload.values.any { value -> value.toString().contains("tmdb:94997") })
        assertFalse(payload.values.any { value -> value.toString().contains("House of the Dragon") })
        assertEquals(null, payload["firstPaintRatingValue"])
        assertEquals(false, payload["firstPaintRatingAccepted"])
        assertEquals("OUT_OF_RANGE_TITLE_RATING", payload["firstPaintRatingRejectReason"])
    }

    @Test
    fun `hydration trace sanitizes invalid hydrated rating state`() = runTest {
        val sink = RecordingTraceSink()
        val facade = mockk<MetadataRouterFacade>()
        val store = mockk<HydratedHomeOverlayStore>(relaxed = true)
        val overlaySlot = slot<com.nexio.tv.domain.model.HydratedHomeOverlay>()
        coEvery { store.upsert(capture(overlaySlot), any()) } returns Unit
        coEvery { facade.resolveRequest(any()) } returns resolutionResult(
            displayMetadata = HomeDisplayMetadata(
                title = "House of the Dragon",
                imdbRating = 1767427f,
                ratingSource = TitleRatingSource.TMDB
            )
        )
        coEvery { facade.resolveStableIdBundle(any<MetadataRoute>(), any(), any(), any()) } returns stableBundle("series:tmdb:94997")
        val preview = preview(
            id = "tmdb:94997",
            type = ContentType.SERIES,
            rating = null,
            artwork = ArtworkBundle()
        )

        HomeHydrationCoordinator(
            metadataRouterFacade = facade,
            overlayStore = store,
            traceEvents = TraceMetadataEvents(sink) { "home-test" }
        ).hydrate(
            item = preview,
            trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
            priority = HomeHydrationPriority.VISIBLE,
            languageTag = "en-US",
            expectedGeneration = 7L,
            currentGeneration = { 7L },
            onOverlayApplied = { true }
        )

        val event = sink.events.last { it.eventType == "home.rating_and_artwork_surface" }
        @Suppress("UNCHECKED_CAST")
        val payload = event.payload as Map<String, Any?>
        assertFalse(payload.containsKey("title"))
        assertFalse(payload.values.any { value -> value.toString().contains("House of the Dragon") })
        assertEquals(null, payload["hydratedRatingValue"])
        assertEquals(null, payload["hydratedRatingSource"])
        assertNull(overlaySlot.captured.fields.imdbRating)
        assertNull(overlaySlot.captured.fields.ratingSource)
    }

    @Test
    fun `already stale generation is ignored before provider resolution`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val store = mockk<HydratedHomeOverlayStore>(relaxed = true)
        val sink = RecordingTraceSink()
        var applied = false

        val result = coordinator(facade, store, sink).hydrate(
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
        val sink = RecordingTraceSink()
        var generation = 7L
        var applied = false

        coEvery { facade.resolveRequest(any()) } answers {
            generation = 8L
            resolutionResult()
        }

        val result = coordinator(facade, store, sink).hydrate(
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
        val sink = RecordingTraceSink()
        var applied = false

        coEvery { facade.resolveRequest(any()) } returns resolutionResult()
        coEvery { facade.resolveStableIdBundle(any<MetadataRoute>(), any(), any(), any()) } returns stableBundle("movie:550")

        val result = coordinator(facade, store, sink).hydrate(
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
        val sink = RecordingTraceSink()
        var generation = 7L
        var applied = false

        coEvery { facade.resolveRequest(any()) } returns resolutionResult()
        coEvery { facade.resolveStableIdBundle(any<MetadataRoute>(), any(), any(), any()) } returns stableBundle("movie:550")
        coEvery { store.upsert(any(), any()) } answers {
            generation = 8L
            Unit
        }

        val result = coordinator(facade, store, sink).hydrate(
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
        val sink = RecordingTraceSink()
        var generation = 7L
        var applied = false

        coEvery { facade.resolveRequest(any()) } returns resolutionResult()
        coEvery { facade.resolveStableIdBundle(any<MetadataRoute>(), any(), any(), any()) } answers {
            generation = 8L
            stableBundle("movie:550")
        }

        val result = coordinator(facade, store, sink).hydrate(
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
        coVerify(exactly = 0) { store.upsert(any(), any()) }
        assertEquals(
            listOf("home.hydration_started", "home.hydration_ignored"),
            sink.events.map { it.eventType }
        )
    }

    @Test
    fun `stable bundle failure still writes and applies canonical overlay`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val store = mockk<HydratedHomeOverlayStore>(relaxed = true)
        val sink = RecordingTraceSink()
        val overlaySlot = slot<com.nexio.tv.domain.model.HydratedHomeOverlay>()
        val aliasesSlot = slot<Set<String>>()
        var applied: com.nexio.tv.domain.model.HydratedHomeOverlay? = null

        coEvery { facade.resolveRequest(any()) } returns resolutionResult()
        coEvery { facade.resolveStableIdBundle(any<MetadataRoute>(), any(), any(), any()) } throws IllegalStateException("stable ids failed")
        coEvery { store.upsert(capture(overlaySlot), capture(aliasesSlot)) } returns Unit

        val result = coordinator(facade, store, sink).hydrate(
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
        assertEquals(8.4f, overlaySlot.captured.fields.imdbRating ?: 0f, 0f)
        assertTrue(aliasesSlot.captured.containsAll(listOf("movie:550", "movie:imdb:tt0137523", "movie:tmdb:550")))
        assertEquals(
            listOf(
                "home.hydration_started",
                "home.hydration_overlay_written",
                "home.rating_and_artwork_surface",
                "home.hydration_applied"
            ),
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
        val sink = RecordingTraceSink()
        val overlaySlot = slot<com.nexio.tv.domain.model.HydratedHomeOverlay>()
        var applied: com.nexio.tv.domain.model.HydratedHomeOverlay? = null

        coEvery { facade.resolveRequest(any()) } returns resolutionResult(canonicalId = null)
        coEvery { facade.resolveStableIdBundle(any<MetadataRoute>(), any(), any(), any()) } throws IllegalStateException("stable ids failed")
        coEvery { store.upsert(capture(overlaySlot), any()) } returns Unit

        val result = coordinator(facade, store, sink).hydrate(
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
            listOf(
                "home.hydration_started",
                "home.hydration_overlay_written",
                "home.rating_and_artwork_surface",
                "home.hydration_applied"
            ),
            sink.events.map { it.eventType }
        )
    }

    @Test
    fun `hydration failure keeps preview path and does not write`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val store = mockk<HydratedHomeOverlayStore>(relaxed = true)
        val sink = RecordingTraceSink()
        var applied = false

        coEvery { facade.resolveRequest(any()) } throws IllegalStateException("router failed")

        val result = coordinator(facade, store, sink).hydrate(
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
        assertEquals(
            listOf("home.hydration_started", "home.hydration_failed_using_preview"),
            sink.events.map { it.eventType }
        )
    }

    @Test
    fun `identity resolution failure emits stable unobfuscated reason`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val store = mockk<HydratedHomeOverlayStore>(relaxed = true)
        val sink = RecordingTraceSink()

        coEvery {
            facade.resolveRequest(any())
        } throws MetadataRouteFailure.IdentityResolutionFailed(
            parentId = "tmdb:321376",
            provider = MetadataPrimaryProvider.TVDB
        )

        val result = coordinator(facade, store, sink).hydrate(
            item = preview(
                id = "tmdb:321376",
                title = "Preview series",
                type = ContentType.SERIES,
                rawType = "series",
                stableIds = ProviderIds(tmdb = "321376")
            ),
            trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM,
            priority = HomeHydrationPriority.FOCUSED,
            languageTag = "en-US",
            expectedGeneration = 7L,
            currentGeneration = { 7L },
            onOverlayApplied = { true }
        )

        assertNull(result)
        val failure = sink.events.single { it.eventType == "home.hydration_failed_using_preview" }
        @Suppress("UNCHECKED_CAST")
        val payload = failure.payload as Map<String, Any?>
        assertEquals("identity_resolution_failed", payload["reason"])
    }

    @Test
    fun `rejected applied overlay writes durable overlay but does not emit applied trace`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val store = mockk<HydratedHomeOverlayStore>()
        val sink = RecordingTraceSink()
        val overlaySlot = slot<com.nexio.tv.domain.model.HydratedHomeOverlay>()

        coEvery { facade.resolveRequest(any()) } returns resolutionResult()
        coEvery { facade.resolveStableIdBundle(any<MetadataRoute>(), any(), any(), any()) } returns stableBundle("movie:550")
        coEvery { store.upsert(capture(overlaySlot), any()) } returns Unit

        val result = coordinator(facade, store, sink).hydrate(
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
            listOf(
                "home.hydration_started",
                "home.hydration_overlay_written",
                "home.rating_and_artwork_surface"
            ),
            sink.events.map { it.eventType }
        )
    }

    private fun coordinator(
        facade: MetadataRouterFacade,
        store: HydratedHomeOverlayStore,
        sink: RecordingTraceSink
    ) = HomeHydrationCoordinator(
        metadataRouterFacade = facade,
        overlayStore = store,
        traceEvents = TraceMetadataEvents(sink) { "home-test" }
    )

    private fun preview(
        id: String,
        title: String = "Preview title",
        stableIds: ProviderIds = ProviderIds(),
        type: ContentType = ContentType.MOVIE,
        rawType: String = type.toApiString(),
        rating: Float? = 6.5f,
        artwork: ArtworkBundle? = null
    ) = MetaPreview(
        id = id,
        type = type,
        rawType = rawType,
        name = title,
        poster = "preview-poster.jpg",
        posterShape = PosterShape.POSTER,
        background = "preview-backdrop.jpg",
        logo = null,
        description = "Preview overview",
        releaseInfo = "1999",
        runtime = null,
        imdbRating = rating,
        ratingSource = TitleRatingSource.TMDB,
        genres = listOf("Drama"),
        firstPaintSource = FirstPaintSource.RAIL_PREVIEW,
        firstPaintSourceProvider = ProviderId.TMDB,
        firstPaintStableIds = stableIds,
        firstPaintRailSource = RailSource.BUILT_IN_TMDB,
        firstPaintSourceItemId = id,
        artwork = artwork
    )

    private fun artworkRef(
        key: String,
        imageType: ArtworkType
    ) = ArtworkDisplayRef.RuntimeAsset(
        decisionKey = ArtworkDecisionKey(key),
        assetKey = null,
        imageType = imageType,
        selectedProvider = null,
        sourceRole = ArtworkSourceRole.PREMIUM,
        trace = ArtworkTrace.empty()
    )

    private fun resolutionResult(
        canonicalId: String? = "tmdb:550",
        displayMetadata: HomeDisplayMetadata = HomeDisplayMetadata(
            title = "Canonical title",
            description = "Canonical overview",
            poster = "poster.jpg",
            backdrop = "backdrop.jpg",
            imdbRating = 8.4f,
            ratingSource = TitleRatingSource.TMDB,
            genres = listOf("Canonical Genre"),
            releaseInfo = "1999-10-15"
        )
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
        displayMetadata = displayMetadata,
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
