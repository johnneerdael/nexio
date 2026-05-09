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
        // Legacy projection always emits decision URI for RuntimeAsset (asset URIs
        // are read-only in NexioArtworkFetcher and would dangle if bytes were never
        // fetched). The structured ArtworkBundle still carries the assetKey above.
        assertEquals("nexio-artwork://decision/posterDecision", overlaySlot.captured.fields.poster)
        assertEquals("nexio-artwork://decision/posterDecision", overlaySlot.captured.fields.displayPoster)
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
    fun `hydrated artwork merge keeps typed fallback refs even when legacy strings exist`() = runTest {
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

        assertSame(hydratedBackdrop, overlaySlot.captured.fields.artwork?.backdrop)
        assertSame(previewLogo, overlaySlot.captured.fields.artwork?.logo)
        assertEquals("nexio-artwork://decision/preview-logo", overlaySlot.captured.fields.displayLogo)
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

    @Test
    fun `trakt_tv_row_without_first_paint_artwork_hydrates_tvdb_and_premium_artwork`() = runTest {
        // Trakt Trending Show fixture with no first-paint artwork. The Trakt rail surfaced
        // ProviderIds for Trakt/TMDB/TVDB/IMDb. The TV artwork chain must produce:
        //   - route provider = TVDB
        //   - canonical identity = TVDB:355567
        //   - hydrated artwork = RPDB poster + TVDB backdrop + TVDB logo (representing
        //     the ArtworkRouter's per-type selection downstream of TVDB candidates +
        //     RPDB premium candidate)
        //   - overlay aliases include the row key, canonical TVDB key, and series/tv
        //     variants for trakt/imdb/tmdb so Home rows can read the overlay back under
        //     any of the in-flight content IDs.
        //
        // Lower-layer contracts (mocked here, asserted there):
        //   - TVDB type 2/3/23 -> POSTER/BACKDROP/LOGO mapping ->
        //     TvdbArtworkCandidateMapperTest / TvdbMetadataProviderAdapterArtworkTest
        //   - RPDB premium poster candidate generation from ProviderIds ->
        //     MetadataArtworkDecisionResolverTest
        //   - ArtworkRouter per-type selection (RPDB poster vs TVDB backdrop/logo) ->
        //     ArtworkRouterTest
        //   - Overlay alias shape (series/tv variants, canonical TVDB key) ->
        //     HomeArtworkOverlayKeysTest
        //   - Portrait poster card never falling back to backdrop ->
        //     ModernHomePresentationTest
        //   - tmdb:tv:<id> parser ->
        //     MetadataRouterContentIdParserTest / MetadataIdParserTypedTmdbTest
        val facade = mockk<MetadataRouterFacade>()
        val store = mockk<HydratedHomeOverlayStore>(relaxed = true)
        val sink = RecordingTraceSink()
        val overlaySlot = slot<com.nexio.tv.domain.model.HydratedHomeOverlay>()
        val aliasesSlot = slot<Set<String>>()
        val requestSlot = slot<com.nexio.tv.core.metadata.router.MetadataRequest>()

        val firstPaintIds = ProviderIds(
            trakt = "171028",
            tmdb = "76479",
            tvdb = "355567",
            imdb = "tt1190634"
        )
        val rpdbPosterRef = ArtworkDisplayRef.LegacyString(
            value = "nexio-artwork://decision/artwork-decision:poster:canonical:tvdb:series-355567:provider:RPDB:premium:true:policy:1",
            imageType = ArtworkType.POSTER,
            trace = ArtworkTrace(
                selectedProvider = "RPDB",
                sourceRole = "PREMIUM"
            )
        )
        val tvdbBackdropRef = ArtworkDisplayRef.LegacyString(
            value = "nexio-artwork://asset/artwork-asset:TVDB:backdrop:urlHash:abc:variant:none:imageLang:en:policy:1",
            imageType = ArtworkType.BACKDROP,
            trace = ArtworkTrace(
                selectedProvider = "TVDB",
                sourceRole = "PRIMARY"
            )
        )
        val tvdbLogoRef = ArtworkDisplayRef.LegacyString(
            value = "nexio-artwork://asset/artwork-asset:TVDB:logo:urlHash:def:variant:none:imageLang:en:policy:1",
            imageType = ArtworkType.LOGO,
            trace = ArtworkTrace(
                selectedProvider = "TVDB",
                sourceRole = "PRIMARY"
            )
        )
        val hydratedArtwork = ArtworkBundle(
            poster = rpdbPosterRef,
            backdrop = tvdbBackdropRef,
            logo = tvdbLogoRef
        )

        coEvery { facade.resolveRequest(capture(requestSlot)) } returns MetadataResolutionResult(
            route = MetadataRoute(
                provider = MetadataPrimaryProvider.TVDB,
                parentId = "trakt:171028",
                mediaKind = MetadataMediaKind.SERIES,
                reason = MetadataDecisionReason.ITEM_TYPE_SERIES,
                sourceContext = MetadataSourceContext(itemType = "series"),
                language = "en-US",
                targetIds = mapOf(MetadataPrimaryProvider.TVDB to "355567"),
                trace = listOf(MetadataRouteTrace(MetadataDecisionReason.ITEM_TYPE_SERIES, "series item type routes to TVDB"))
            ),
            plan = null,
            resolverSchedule = ResolverSchedule(
                depth = MetadataDepth.DETAIL_CORE,
                localResolvers = emptyList(),
                networkResolvers = emptyList()
            ),
            resolvedDocument = ResolvedMetadataDocument(
                canonicalId = "tvdb:355567",
                title = "The Boys",
                overview = "The Boys overview",
                poster = "tvdb-poster.jpg",
                backdrop = "tvdb-backdrop.jpg",
                logo = "tvdb-logo.png",
                rating = 8.4,
                runtimeMinutes = 60,
                genres = listOf("Action"),
                releaseDate = "2019-07-26",
                fieldOwners = mapOf(ResolvedField.TITLE to FieldOwner.PRIMARY),
                ignoredOverwrites = emptyList(),
                sourceRoles = mapOf(ResolvedField.TITLE to SourceRole.PRIMARY),
                sourceProviders = mapOf(ResolvedField.TITLE to "TVDB")
            ),
            displayMetadata = HomeDisplayMetadata(
                title = "The Boys",
                description = "The Boys overview",
                imdbRating = 8.4f,
                ratingSource = TitleRatingSource.TMDB,
                genres = listOf("Action"),
                releaseInfo = "2019-07-26",
                posterProviderTag = "rpdb",
                artwork = hydratedArtwork
            ),
            trace = emptyList()
        )
        coEvery {
            facade.resolveStableIdBundle(any<MetadataRoute>(), any(), any(), any())
        } returns StableIdBundle(
            itemKey = "series:trakt:171028",
            itemType = ContentType.SERIES,
            canonical = CanonicalStableIds(
                tmdbMovieId = null,
                tvdbSeriesId = "355567",
                kitsuAnimeId = null
            ),
            sidecars = SidecarStableIds(imdbId = "tt1190634"),
            source = SourceStableIds(
                sourceProvider = ProviderId.TRAKT,
                sourceItemId = "trakt:171028",
                railId = RailSource.BUILT_IN_TRAKT.name,
                observedIds = firstPaintIds
            ),
            evidence = listOf(StableIdEvidence("trakt", "tvdb", networkExecuted = false, resultId = "355567")),
            resolvedAtMs = 1L
        )
        coEvery { store.upsert(capture(overlaySlot), capture(aliasesSlot)) } returns Unit

        val traktTvRow = MetaPreview(
            id = "trakt:171028",
            type = ContentType.SERIES,
            rawType = "series",
            name = "The Boys",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = "The Boys overview",
            releaseInfo = "2019",
            runtime = null,
            imdbRating = null,
            ratingSource = null,
            genres = listOf("Action"),
            firstPaintSource = FirstPaintSource.RAIL_PREVIEW,
            firstPaintSourceProvider = ProviderId.TRAKT,
            firstPaintStableIds = firstPaintIds,
            firstPaintRailSource = RailSource.BUILT_IN_TRAKT,
            firstPaintSourceItemId = "trakt:171028",
            artwork = null
        )

        val result = coordinator(facade, store, sink).hydrate(
            item = traktTvRow,
            trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
            priority = HomeHydrationPriority.VISIBLE,
            languageTag = "en-US",
            expectedGeneration = 7L,
            currentGeneration = { 7L },
            onOverlayApplied = { true }
        )

        assertNotNull("hydration must produce an overlay", result)

        // Hydration request: ProviderIds survive preview mapping into the source context.
        val capturedRequest = requestSlot.captured
        assertEquals("trakt:171028", capturedRequest.contentId)
        assertEquals(ContentType.SERIES, capturedRequest.contentType)
        assertEquals("series", capturedRequest.sourceContext.itemType)
        assertEquals(firstPaintIds.trakt, capturedRequest.sourceContext.previewStableIds.trakt)
        assertEquals(firstPaintIds.tmdb, capturedRequest.sourceContext.previewStableIds.tmdb)
        assertEquals(firstPaintIds.tvdb, capturedRequest.sourceContext.previewStableIds.tvdb)
        assertEquals(firstPaintIds.imdb, capturedRequest.sourceContext.previewStableIds.imdb)
        assertEquals("TRAKT", capturedRequest.sourceContext.previewSourceProvider)
        assertEquals(RailSource.BUILT_IN_TRAKT.name, capturedRequest.sourceContext.previewRailSource)

        // Route provider = TVDB (proves series item type → TVDB even with a Trakt parent id).
        // Canonical identity follows the TVDB route + bundle (not the Trakt parent id).
        assertEquals(ProviderId.TVDB, overlaySlot.captured.canonicalProvider)
        assertEquals("355567", overlaySlot.captured.canonicalId)
        assertEquals("tt1190634", overlaySlot.captured.imdbId)
        assertEquals(ContentType.SERIES, overlaySlot.captured.contentType)

        // Overlay fields preserve the per-type selected artwork: RPDB poster, TVDB backdrop, TVDB logo.
        val artwork = overlaySlot.captured.fields.artwork
        assertNotNull("hydrated artwork bundle must be present on the overlay", artwork)
        assertNotNull("hydrated poster must survive overlay write", artwork?.poster)
        assertNotNull("hydrated backdrop must survive overlay write", artwork?.backdrop)
        assertNotNull("hydrated logo must survive overlay write", artwork?.logo)

        val poster = artwork?.poster
        assertTrue("poster should be a typed display ref", poster is ArtworkDisplayRef.LegacyString)
        poster as ArtworkDisplayRef.LegacyString
        assertEquals(ArtworkType.POSTER, poster.imageType)
        // "provider:RPDB" is the ArtworkDecisionKey wire format -- see ArtworkDecisionKey.serialize().
        // A change to that segment naming should reflect here.
        assertTrue("poster ref should encode the RPDB premium decision", poster.value.contains("provider:RPDB"))

        val backdrop = artwork?.backdrop
        assertTrue("backdrop should be a typed display ref", backdrop is ArtworkDisplayRef.LegacyString)
        backdrop as ArtworkDisplayRef.LegacyString
        assertEquals(ArtworkType.BACKDROP, backdrop.imageType)
        assertTrue("backdrop ref must come from TVDB", backdrop.value.contains("TVDB:backdrop"))

        val logo = artwork?.logo
        assertTrue("logo should be a typed display ref", logo is ArtworkDisplayRef.LegacyString)
        logo as ArtworkDisplayRef.LegacyString
        assertEquals(ArtworkType.LOGO, logo.imageType)
        assertTrue("logo ref must come from TVDB", logo.value.contains("TVDB:logo"))

        // Overlay published with a row-key alias so display projections find it under the
        // raw Trakt content id, plus canonical TVDB and series/tv variants for every
        // provider id observed during routing.
        val aliases = aliasesSlot.captured
        assertTrue(
            "series:trakt:171028 must alias the overlay (row key + typed series alias for trakt)",
            "series:trakt:171028" in aliases
        )
        assertTrue("typed series alias for canonical TVDB id", "series:tvdb:355567" in aliases)
        assertTrue("typed tv alias for canonical TVDB id", "tv:tvdb:355567" in aliases)
        assertTrue("typed series alias for IMDb id", "series:imdb:tt1190634" in aliases)
        assertTrue("typed tv alias for IMDb id", "tv:imdb:tt1190634" in aliases)
        assertTrue("typed series alias for TMDB id", "series:tmdb:76479" in aliases)
        assertTrue("typed tv alias for TMDB id", "tv:tmdb:76479" in aliases)

        // Sequence trace confirms the full chain reached overlay write and apply.
        // Exact sequence asserted: any new trace event added to HomeHydrationCoordinator
        // must be reflected here so observability changes don't sneak through unnoticed.
        assertEquals(
            listOf(
                "home.hydration_started",
                "home.hydration_overlay_written",
                "home.rating_and_artwork_surface",
                "home.hydration_applied"
            ),
            sink.events.map { it.eventType }
        )
        val artworkSurface = sink.events.single { it.eventType == "home.rating_and_artwork_surface" }
        @Suppress("UNCHECKED_CAST")
        val artworkPayload = artworkSurface.payload as Map<String, Any?>
        assertEquals("TVDB", artworkPayload["routeProvider"])
        assertEquals(true, artworkPayload["overlayApplied"])
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
