package com.nexio.tv.data.repository

import com.nexio.tv.core.artwork.ArtworkProviderCapabilityResolver
import com.nexio.tv.core.artwork.ArtworkProviderResolver
import com.nexio.tv.core.artwork.ArtworkProviderSettingsSource
import com.nexio.tv.core.artwork.toLegacyArtworkString
import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.core.metadata.router.StableIdResolutionTrigger
import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.data.local.HydratedHomeOverlayStore
import com.nexio.tv.domain.model.ArtworkProviderSettings
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.UnifiedWatchlistMembership
import com.nexio.tv.domain.model.UnifiedWatchlistMembershipConfidence
import com.nexio.tv.domain.model.UnifiedWatchlistSource
import com.nexio.tv.ui.screens.home.HomeHydrationCoordinator
import com.nexio.tv.ui.screens.home.HomeHydrationPriority
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedWatchlistSurfacePublisherTest {
    @Test
    fun `publish emits preview rows before waiting for hydration`() = runTest {
        val session = testProfileSession()
        val publishedBatches = mutableListOf<List<ResolvedDisplayItem>>()
        val publisher = publisher(
            session = session,
            onHydrateStarted = {
                assertEquals(1, publishedBatches.size)
                assertEquals("Arrival", publishedBatches.single().single().display.title)
                delay(10)
            },
            onPublishBatch = { publishedBatches += it }
        )

        publisher.publish(
            profileSession = session,
            memberships = listOf(
                UnifiedWatchlistMembership(
                    authorityKey = "movie:imdb:tt2543164",
                    contentType = ContentType.MOVIE,
                    presentIn = setOf(UnifiedWatchlistSource.TRAKT),
                    sourceRefs = emptyList(),
                    confidence = UnifiedWatchlistMembershipConfidence.STRONG,
                    title = "Arrival",
                    year = 2016,
                    imdbId = "tt2543164",
                    tmdbId = 329865
                )
            )
        )

        assertEquals(2, publishedBatches.size)
    }

    @Test
    fun `publish hydrates watchlist memberships through home hydration path`() = runTest {
        val session = testProfileSession()
        val capturedPreview = slot<MetaPreview>()
        val capturedResolvedItems = slot<List<ResolvedDisplayItem>>()
        val publisher = publisher(
            session = session,
            onHydrate = capturedPreview,
            onPublish = capturedResolvedItems
        )

        publisher.publish(
            profileSession = session,
            memberships = listOf(
                UnifiedWatchlistMembership(
                    authorityKey = "movie:imdb:tt2543164",
                    contentType = ContentType.MOVIE,
                    presentIn = setOf(UnifiedWatchlistSource.TRAKT, UnifiedWatchlistSource.SIMKL),
                    sourceRefs = emptyList(),
                    confidence = UnifiedWatchlistMembershipConfidence.STRONG,
                    title = "Arrival",
                    year = 2016,
                    imdbId = "tt2543164",
                    tmdbId = 329865,
                    traktId = 17809,
                    simklId = 536500
                )
            )
        )

        assertEquals("imdb:tt2543164", capturedPreview.captured.id)
        assertEquals("tt2543164", capturedPreview.captured.firstPaintStableIds.imdb)
        assertEquals("329865", capturedPreview.captured.firstPaintStableIds.tmdb)
        assertEquals("17809", capturedPreview.captured.firstPaintStableIds.trakt)
        assertEquals("536500", capturedPreview.captured.firstPaintStableIds.simkl)
        assertEquals("Arrival", capturedResolvedItems.captured.single().display.title)
        assertEquals("tt2543164", capturedResolvedItems.captured.single().stableIds.imdb)
    }

    @Test
    fun `publish uses membership display metadata for first preview rows`() = runTest {
        val session = testProfileSession()
        val publishedBatches = mutableListOf<List<ResolvedDisplayItem>>()
        val publisher = publisher(
            session = session,
            onHydrateStarted = { delay(10) },
            onPublishBatch = { publishedBatches += it }
        )

        publisher.publish(
            profileSession = session,
            memberships = listOf(
                UnifiedWatchlistMembership(
                    authorityKey = "movie:imdb:tt32820897",
                    contentType = ContentType.MOVIE,
                    presentIn = setOf(UnifiedWatchlistSource.TRAKT),
                    sourceRefs = emptyList(),
                    confidence = UnifiedWatchlistMembershipConfidence.STRONG,
                    title = "Demon Slayer",
                    year = 2025,
                    imdbId = "tt32820897",
                    tmdbId = 1311031,
                    poster = "nexio-artwork://decision/poster",
                    background = "https://image.tmdb.org/t/p/w1280/backdrop.jpg",
                    logo = "https://image.tmdb.org/t/p/w500/logo.png",
                    description = "The Corps are drawn into the Infinity Castle.",
                    imdbRating = 7.7f,
                    genres = listOf("Animation", "Action")
                )
            )
        )

        val firstPaint = publishedBatches.first().single()
        assertEquals("Demon Slayer", firstPaint.display.title)
        assertEquals("The Corps are drawn into the Infinity Castle.", firstPaint.display.overview)
        assertEquals(listOf("Animation", "Action"), firstPaint.display.genres)
        assertEquals("nexio-artwork://decision/poster", firstPaint.artwork.poster.toLegacyArtworkString())
        assertEquals("https://image.tmdb.org/t/p/w1280/backdrop.jpg", firstPaint.artwork.backdrop.toLegacyArtworkString())
        assertEquals("https://image.tmdb.org/t/p/w500/logo.png", firstPaint.artwork.logo.toLegacyArtworkString())
    }

    @Test
    fun `publish clears the unified watchlist surface when memberships are empty`() = runTest {
        val session = testProfileSession()
        val capturedResolvedItems = slot<List<ResolvedDisplayItem>>()
        val publisher = publisher(
            session = session,
            onPublish = capturedResolvedItems
        )

        publisher.publish(profileSession = session, memberships = emptyList())

        assertTrue(capturedResolvedItems.captured.isEmpty())
    }

    private fun publisher(
        session: ActiveProfileSession,
        onHydrate: CapturingSlot<MetaPreview>? = null,
        onHydrateStarted: suspend () -> Unit = {},
        onPublish: CapturingSlot<List<ResolvedDisplayItem>>? = null,
        onPublishBatch: (List<ResolvedDisplayItem>) -> Unit = {}
    ): UnifiedWatchlistSurfacePublisher {
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileSession } returns MutableStateFlow(session)

        val profileBoundary = mockk<ProfileBoundary>()
        every { profileBoundary.currentLanguageTag() } returns "en-US"

        val settingsSource = mockk<ArtworkProviderSettingsSource>()
        every { settingsSource.settings } returns flowOf(ArtworkProviderSettings())

        val overlayStore = mockk<HydratedHomeOverlayStore>()
        every {
            overlayStore.readForItemKeys(any(), "en-US", any(), any())
        } returns emptyMap()

        val hydrationCoordinator = mockk<HomeHydrationCoordinator>()
        coEvery {
            hydrationCoordinator.hydrate(
                item = if (onHydrate != null) capture(onHydrate) else any(),
                trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
                priority = HomeHydrationPriority.VISIBLE,
                languageTag = "en-US",
                expectedGeneration = any(),
                currentGeneration = any(),
                onOverlayApplied = any()
            )
        } coAnswers {
            onHydrateStarted()
            null
        }

        val surfaceRepository = mockk<ResolvedDisplaySurfaceRepository>()
        every {
            surfaceRepository.publishResolvedItems(
                surfaceKey = ResolvedDisplaySurfaceRepository.UNIFIED_WATCHLIST_SURFACE_KEY,
                profileSession = session,
                items = if (onPublish != null) capture(onPublish) else any(),
                replace = true
            )
        } answers {
            @Suppress("UNCHECKED_CAST")
            onPublishBatch(invocation.args[2] as List<ResolvedDisplayItem>)
            true
        }

        return UnifiedWatchlistSurfacePublisher(
            profileManager = profileManager,
            profileBoundary = profileBoundary,
            hydrationCoordinator = hydrationCoordinator,
            overlayStore = overlayStore,
            resolvedDisplaySurfaceRepository = surfaceRepository,
            settingsSource = settingsSource,
            artworkProviderResolver = ArtworkProviderResolver(ArtworkProviderCapabilityResolver()),
            traceEvents = TraceMetadataEvents(NoopRuntimeTraceSink, sessionId = { null })
        )
    }

    private fun testProfileSession() = ActiveProfileSession(
        profileId = 1,
        sessionId = "test-session",
        sessionOrdinal = 7L,
        startedAtMs = 1L
    )
}
