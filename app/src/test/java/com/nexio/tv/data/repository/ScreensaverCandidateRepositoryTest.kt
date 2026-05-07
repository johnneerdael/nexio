package com.nexio.tv.data.repository

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.resolver.TrailerPlaybackRef
import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.TraceHash
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.core.trace.TraceMode
import com.nexio.tv.core.trace.TraceSession
import com.nexio.tv.core.trace.TraceSessionManager
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HydratedHomeFieldTrace
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.TrailerDisplayState
import com.nexio.tv.ui.screensaver.ScreensaverSlideCandidate
import com.nexio.tv.ui.screensaver.ScreensaverTrailerCandidate
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreensaverCandidateRepositoryTest {
    @Test
    fun `image candidates are projected from resolved display surface with artwork refs rating stable ids and trace`() = runTest {
        val surface = testSurface()
        val repository = testScreensaverCandidates(surface)
        val trace = HydratedHomeFieldTrace(
            field = "title",
            selectedProvider = "TMDB",
            sourceRole = "PRIMARY"
        )
        val backdrop = artworkRef(key = "backdrop-550", imageType = ArtworkType.BACKDROP)
        surface.replaceForTest(
            profileId = 1,
            items = listOf(
                resolvedItem(
                    itemKey = "movie:tmdb:550",
                    title = "Fight Club",
                    artwork = ArtworkBundle(
                        backdrop = backdrop,
                        poster = artworkRef(key = "poster-550", imageType = ArtworkType.POSTER)
                    ),
                    sourceTrace = listOf(trace)
                )
            )
        )

        val candidates = repository.observeImageCandidates(profileId = 1).first()

        assertEquals(1, candidates.size)
        val candidate = candidates.single()
        assertEquals("movie:tmdb:550", candidate.itemKey)
        assertEquals("tmdb:550", candidate.contentId)
        assertEquals("movie", candidate.itemType)
        assertEquals("Fight Club", candidate.title)
        assertEquals("1999", candidate.subtitle)
        assertEquals("Overview", candidate.overview)
        assertEquals(listOf("Drama", "Thriller"), candidate.genres)
        assertEquals("139m", candidate.runtime)
        assertEquals(8.8, candidate.rating?.value ?: 0.0, 0.0)
        assertSame(backdrop, candidate.preferredImage)
        assertEquals("tt0137523", candidate.stableIds.imdb)
        assertEquals(listOf(trace), candidate.trace)
    }

    @Test
    fun `image candidates fall back to poster when backdrop artwork is absent`() = runTest {
        val surface = testSurface()
        val repository = testScreensaverCandidates(surface)
        val poster = artworkRef(key = "poster-550", imageType = ArtworkType.POSTER)
        surface.replaceForTest(
            profileId = 1,
            items = listOf(
                resolvedItem(
                    itemKey = "movie:tmdb:550",
                    title = "Fight Club",
                    artwork = ArtworkBundle(poster = poster)
                )
            )
        )

        val candidate = repository.observeImageCandidates(profileId = 1).first().single()

        assertSame(poster, candidate.preferredImage)
    }

    @Test
    fun `image candidates exclude items without poster or backdrop artwork`() = runTest {
        val surface = testSurface()
        val repository = testScreensaverCandidates(surface)
        surface.replaceForTest(
            profileId = 1,
            items = listOf(
                resolvedItem(
                    itemKey = "movie:tmdb:551",
                    title = "No Art",
                    artwork = ArtworkBundle()
                )
            )
        )

        assertEquals(emptyList<ScreensaverSlideCandidate>(), repository.observeImageCandidates(1).first())
    }

    @Test
    fun `image candidates exclude items without a display title`() = runTest {
        val surface = testSurface()
        val repository = testScreensaverCandidates(surface)
        surface.replaceForTest(
            profileId = 1,
            items = listOf(
                resolvedItem(itemKey = "movie:tmdb:551", title = null),
                resolvedItem(itemKey = "movie:tmdb:552", title = "   ")
            )
        )

        assertEquals(emptyList<ScreensaverSlideCandidate>(), repository.observeImageCandidates(1).first())
    }

    @Test
    fun `image candidates clear invalid resolved ratings but preserve logo artwork`() = runTest {
        val surface = testSurface()
        val repository = testScreensaverCandidates(surface)
        val logo = artworkRef(key = "logo-94997", imageType = ArtworkType.LOGO)
        val backdrop = artworkRef(key = "backdrop-94997", imageType = ArtworkType.BACKDROP)
        surface.replaceForTest(
            profileId = 1,
            items = listOf(
                resolvedItem(
                    itemKey = "series:tmdb:94997",
                    title = "House of the Dragon",
                    artwork = ArtworkBundle(backdrop = backdrop, logo = logo),
                    rating = TitleRating(1767427.0, TitleRatingSource.TMDB)
                )
            )
        )

        val candidate = repository.observeImageCandidates(profileId = 1).first().single()

        assertEquals(null, candidate.rating)
        assertSame(logo, candidate.artwork.logo)
        assertSame(backdrop, candidate.preferredImage)
    }

    @Test
    fun `trailer candidates come from resolved items even when trailer ids are empty`() = runTest {
        val surface = testSurface()
        val repository = testScreensaverCandidates(surface)
        surface.replaceForTest(
            profileId = 1,
            items = listOf(
                resolvedItem("series:tvdb:81189", title = "Breaking Bad").copy(
                    contentId = "tvdb:81189",
                    parentId = "tvdb:81189",
                    itemType = ContentType.SERIES,
                    mediaKind = MetadataMediaKind.SERIES,
                    canonicalProvider = "TVDB",
                    canonicalId = "81189",
                    stableIds = ProviderIds(tvdb = "81189", imdb = "tt0903747")
                )
            )
        )

        val candidates = repository.observeTrailerCandidates(profileId = 1).first()

        assertEquals(1, candidates.size)
        assertEquals("series:tvdb:81189", candidates.single().itemKey)
        assertEquals("Breaking Bad", candidates.single().title)
        assertTrue(candidates.single().trailerState.fallbackTrailerYtIds.isEmpty())
        assertEquals("81189", candidates.single().stableIds.tvdb)
        val lookup = candidates.single().trailerState.selectedPlaybackRef as TrailerPlaybackRef.ItemLookup
        assertEquals("Breaking Bad", lookup.title)
        assertEquals("tvdb:81189", lookup.contentId)
        assertEquals("81189", lookup.stableIds.tvdb)
    }

    @Test
    fun `trailer candidates normalize fallback ids by trimming blanks and duplicates`() = runTest {
        val surface = testSurface()
        val repository = testScreensaverCandidates(surface)
        surface.replaceForTest(
            profileId = 1,
            items = listOf(
                resolvedItem(
                    itemKey = "movie:tmdb:550",
                    title = "Fight Club",
                    fallbackTrailerYtIds = listOf(" abc123 ", "", "abc123", "   ", "def456")
                )
            )
        )

        val candidate = repository.observeTrailerCandidates(profileId = 1).first().single()

        assertEquals(listOf("abc123", "def456"), candidate.trailerState.fallbackTrailerYtIds)
    }

    @Test
    fun `trailer candidates preserve resolved trailer display state`() = runTest {
        val surface = testSurface()
        val repository = testScreensaverCandidates(surface)
        val trailerState = TrailerDisplayState(
            fallbackTrailerYtIds = listOf(" abc123 ", "", "abc123"),
            selectedPlaybackRef = TrailerPlaybackRef.InAppSource(
                videoUrl = "https://video.example.com/fight-club.m3u8",
                audioUrl = "https://audio.example.com/fight-club.m4a",
                userAgent = "NexioTest"
            ),
            availabilityReason = "provider_candidate",
            surface = "home",
            resolverSource = "TMDB",
            lastResolvedAtMs = 1234L
        )
        surface.replaceForTest(
            profileId = 1,
            items = listOf(
                resolvedItem(
                    itemKey = "movie:tmdb:550",
                    title = "Fight Club",
                    trailerState = trailerState
                )
            )
        )

        val candidate = repository.observeTrailerCandidates(profileId = 1).first().single()

        assertEquals(trailerState.copy(fallbackTrailerYtIds = listOf("abc123")), candidate.trailerState)
        assertEquals(listOf("Drama", "Thriller"), candidate.genres)
        assertEquals("139m", candidate.runtime)
    }

    @Test
    fun `trailer candidates treat blank fallback ids as empty for lazy sentinel resolution`() = runTest {
        val surface = testSurface()
        val repository = testScreensaverCandidates(surface)
        surface.replaceForTest(
            profileId = 1,
            items = listOf(
                resolvedItem(
                    itemKey = "movie:tmdb:550",
                    title = "Fight Club",
                    fallbackTrailerYtIds = listOf("", "   ", "\t")
                )
            )
        )

        val candidate = repository.observeTrailerCandidates(profileId = 1).first().single()

        assertTrue(candidate.trailerState.fallbackTrailerYtIds.isEmpty())
        assertTrue(candidate.trailerState.selectedPlaybackRef is TrailerPlaybackRef.ItemLookup)
    }

    @Test
    fun `candidate snapshot projects image and trailer candidates from one surface read`() = runTest {
        val surface = testSurface()
        val repository = testScreensaverCandidates(surface)
        surface.replaceForTest(
            surfaceKey = ResolvedDisplaySurfaceRepository.SCREENSAVER_SURFACE_KEY,
            profileId = 1,
            items = listOf(
                resolvedItem(
                    itemKey = "movie:tmdb:550",
                    title = "Fight Club",
                    fallbackTrailerYtIds = listOf(" abc123 ", "abc123")
                )
            )
        )

        val snapshot = repository.getCandidatesSnapshot(profileId = 1)

        assertEquals(listOf("movie:tmdb:550"), snapshot.imageCandidates.map { it.itemKey })
        assertEquals(listOf("abc123"), snapshot.trailerCandidates.single().trailerState.fallbackTrailerYtIds)
    }

    @Test
    fun `candidate snapshot reads screensaver surface instead of visible home surface`() = runTest {
        val surface = testSurface()
        val repository = testScreensaverCandidates(surface)
        surface.replaceForTest(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileId = 1,
            items = listOf(resolvedItem(itemKey = "movie:tmdb:home", title = "Visible Home Item"))
        )
        surface.replaceForTest(
            surfaceKey = ResolvedDisplaySurfaceRepository.SCREENSAVER_SURFACE_KEY,
            profileId = 1,
            items = listOf(
                resolvedItem(
                    itemKey = "movie:tmdb:trending",
                    title = "Trending Screensaver Item",
                    fallbackTrailerYtIds = listOf("abc123def45")
                )
            )
        )

        val snapshot = repository.getCandidatesSnapshot(profileId = 1)

        assertEquals(listOf("movie:tmdb:trending"), snapshot.imageCandidates.map { it.itemKey })
        assertEquals(listOf("movie:tmdb:trending"), snapshot.trailerCandidates.map { it.itemKey })
        assertEquals(listOf("abc123def45"), snapshot.trailerCandidates.single().trailerState.fallbackTrailerYtIds)
    }

    @Test
    fun `candidate snapshot emits pool built trace with profile hash source and candidate counts`() = runTest {
        val surface = testSurface()
        val sink = RecordingTraceSink()
        val repository = testScreensaverCandidates(
            surface = surface,
            traceEvents = TraceMetadataEvents(sink, sessionId = { "screensaver-session" }),
            profileHashForTrace = { profileId -> "profile-hash-$profileId" }
        )
        surface.replaceForTest(
            profileId = 1,
            items = listOf(
                resolvedItem(itemKey = "movie:tmdb:550", title = "Fight Club"),
                resolvedItem(
                    itemKey = "movie:tmdb:551",
                    title = "No Art",
                    artwork = ArtworkBundle()
                )
            )
        )

        val snapshot = repository.getCandidatesSnapshot(profileId = 1)

        assertEquals(1, snapshot.imageCandidates.size)
        assertEquals(1, snapshot.trailerCandidates.size)
        val event = sink.events.single()
        assertEquals("screensaver.candidate_pool_built", event.eventType)
        val payload = event.payload as Map<*, *>
        assertEquals("profile-hash-1", payload["profileHash"])
        assertEquals("RESOLVED_DISPLAY_SURFACE", payload["source"])
        assertEquals(1, payload["imageCandidateCount"])
        assertEquals(1, payload["trailerCandidateCount"])
    }

    @Test
    fun `injected constructor hashes active profile with runtime trace session salt`() = runTest {
        val surface = testSurface()
        val sink = RecordingTraceSink()
        val profileManager = mockk<com.nexio.tv.core.profile.ProfileManager> {
            every { activeProfileSession } returns MutableStateFlow(
                ActiveProfileSession(
                    profileId = 1,
                    sessionId = "active-profile-session-id",
                    sessionOrdinal = 1L,
                    startedAtMs = 1_000L
                )
            )
        }
        val traceSessionManager = mockk<TraceSessionManager> {
            every { activeSession() } returns traceSession("runtime-trace-session")
        }
        val repository = ScreensaverCandidateRepository(
            surfaceRepository = surface,
            traceEvents = TraceMetadataEvents(sink, sessionId = { "runtime-trace-session" }),
            profileManager = profileManager,
            traceSessionManager = traceSessionManager
        )
        surface.replaceForTest(
            profileId = 1,
            items = listOf(resolvedItem(itemKey = "movie:tmdb:550", title = "Fight Club"))
        )

        repository.getCandidatesSnapshot(profileId = 1)

        val payload = sink.events.single().payload as Map<*, *>
        assertEquals(TraceHash.of("runtime-trace-session", "1"), payload["profileHash"])
    }

    @Test
    fun `injected constructor emits null profile hash when no runtime trace session is active`() = runTest {
        val surface = testSurface()
        val sink = RecordingTraceSink()
        val profileManager = mockk<com.nexio.tv.core.profile.ProfileManager> {
            every { activeProfileSession } returns MutableStateFlow(
                ActiveProfileSession(
                    profileId = 1,
                    sessionId = "active-profile-session-id",
                    sessionOrdinal = 1L,
                    startedAtMs = 1_000L
                )
            )
        }
        val traceSessionManager = mockk<TraceSessionManager> {
            every { activeSession() } returns null
        }
        val repository = ScreensaverCandidateRepository(
            surfaceRepository = surface,
            traceEvents = TraceMetadataEvents(sink, sessionId = { null }),
            profileManager = profileManager,
            traceSessionManager = traceSessionManager
        )
        surface.replaceForTest(
            profileId = 1,
            items = listOf(resolvedItem(itemKey = "movie:tmdb:550", title = "Fight Club"))
        )

        repository.getCandidatesSnapshot(profileId = 1)

        val payload = sink.events.single().payload as Map<*, *>
        assertTrue(payload.containsKey("profileHash"))
        assertEquals(null, payload["profileHash"])
    }

    @Test
    fun `trailer candidates exclude items without title or artwork`() = runTest {
        val surface = testSurface()
        val repository = testScreensaverCandidates(surface)
        surface.replaceForTest(
            profileId = 1,
            items = listOf(
                resolvedItem(itemKey = "movie:tmdb:551", title = null),
                resolvedItem(itemKey = "movie:tmdb:552", title = "   "),
                resolvedItem(itemKey = "movie:tmdb:553", title = "No Art", artwork = ArtworkBundle())
            )
        )

        assertEquals(emptyList<ScreensaverTrailerCandidate>(), repository.observeTrailerCandidates(1).first())
    }

    private fun testScreensaverCandidates(
        surface: ResolvedDisplaySurfaceRepository,
        traceEvents: TraceMetadataEvents = TraceMetadataEvents(NoopRuntimeTraceSink, sessionId = { null }),
        profileHashForTrace: (Int) -> String? = { "test-profile-hash" }
    ) = ScreensaverCandidateRepository(
        surfaceRepository = surface,
        traceEvents = traceEvents,
        profileHashForTrace = profileHashForTrace
    )

    private fun resolvedItem(
        itemKey: String = "movie:tmdb:550",
        title: String? = "Fight Club",
        artwork: ArtworkBundle = ArtworkBundle(backdrop = artworkRef("backdrop-550", ArtworkType.BACKDROP)),
        rating: TitleRating? = TitleRating(8.8, TitleRatingSource.IMDB),
        sourceTrace: List<HydratedHomeFieldTrace> = emptyList(),
        fallbackTrailerYtIds: List<String> = emptyList(),
        trailerState: TrailerDisplayState = TrailerDisplayState(fallbackTrailerYtIds = fallbackTrailerYtIds)
    ) = ResolvedDisplayItem(
        itemKey = itemKey,
        contentId = "tmdb:550",
        parentId = "tmdb:550",
        itemType = ContentType.MOVIE,
        mediaKind = MetadataMediaKind.MOVIE,
        canonicalProvider = "TMDB",
        canonicalId = "550",
        imdbId = "tt0137523",
        stableIds = ProviderIds(tmdb = "550", imdb = "tt0137523"),
        display = ResolvedDisplayFields(
            title = title,
            originalTitle = null,
            year = 1999,
            releaseDate = "1999",
            overview = "Overview",
            genres = listOf("Drama", "Thriller"),
            runtimeText = "139m"
        ),
        artwork = artwork,
        rating = rating,
        trailer = trailerState,
        hydrationState = HydrationState.CANONICAL_READY,
        sourceTrace = sourceTrace,
        updatedAtMs = 1L
    )

    private fun testSurface() = ResolvedDisplaySurfaceRepository(
        activeProfileSession = {
            ActiveProfileSession(
                profileId = 1,
                sessionId = "test-session",
                sessionOrdinal = 1L,
                startedAtMs = 1_000L
            )
        }
    )

    private fun traceSession(traceSessionId: String) = TraceSession(
        traceSessionId = traceSessionId,
        startedAtEpochMs = 1_700_000_000_000L,
        appVersion = "1.0",
        buildType = "debug",
        gitSha = null,
        deviceModel = "test-device",
        androidVersion = "14",
        activeProfileHash = null,
        mode = TraceMode.SAFE_METADATA_RUNTIME,
        salt = "trace-salt"
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
        trace = ArtworkTrace(selectedProvider = "TOP_POSTERS", sourceRole = "ARTWORK")
    )
}
