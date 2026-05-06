package com.nexio.tv

import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.data.trailer.TrailerPlaybackSource
import com.nexio.tv.data.trailer.TrailerResolutionResult
import com.nexio.tv.core.metadata.router.resolver.TrailerAvailability
import com.nexio.tv.core.metadata.router.resolver.TrailerPlaybackRef
import com.nexio.tv.core.metadata.router.resolver.TrailerResolveRequest
import com.nexio.tv.core.metadata.router.resolver.TrailerResolution
import com.nexio.tv.core.metadata.router.resolver.TrailerSurface
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.TrailerDisplayState
import com.nexio.tv.ui.navigation.Screen
import com.nexio.tv.ui.screensaver.IdleScreensaverPresentationMode
import com.nexio.tv.ui.screensaver.IdleTrailerScreensaverCandidate
import com.nexio.tv.ui.screensaver.IdleTrailerScreensaverPlayback
import com.nexio.tv.ui.screensaver.IdleTrailerScreensaverSessionStart
import com.nexio.tv.ui.screensaver.PlaybackIdleGateSnapshot
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityIdleScreensaverTest {

    @Test
    fun `idle screensaver timeout bounds preserve five minute default`() {
        assertEquals(5L * 60 * 1000L, MainActivity.IDLE_SCREENSAVER_DEFAULT_TIMEOUT_MS)
        assertEquals(60L * 1000L, MainActivity.IDLE_SCREENSAVER_MIN_TIMEOUT_MS)
        assertEquals(10L * 60 * 1000L, MainActivity.IDLE_SCREENSAVER_MAX_TIMEOUT_MS)
    }

    @Test
    fun `idle timer resets when in app trailer starts`() {
        assertTrue(
            shouldRegisterIdleInteractionForTrailerPlaybackTransition(
                previousActive = false,
                currentActive = true
            )
        )
    }

    @Test
    fun `idle timer resets when in app trailer ends`() {
        assertTrue(
            shouldRegisterIdleInteractionForTrailerPlaybackTransition(
                previousActive = true,
                currentActive = false
            )
        )
    }

    @Test
    fun `home route is not eligible while modern home trailer is active`() {
        assertFalse(
            isIdleScreensaverEligibleRoute(
                currentRoute = Screen.Home.route,
                playbackIdleSnapshot = PlaybackIdleGateSnapshot(),
                inAppTrailerPlaybackActive = true
            )
        )
    }

    @Test
    fun `detail route is not eligible while detail trailer is active`() {
        assertFalse(
            isIdleScreensaverEligibleRoute(
                currentRoute = Screen.Detail.route,
                playbackIdleSnapshot = PlaybackIdleGateSnapshot(),
                inAppTrailerPlaybackActive = true
            )
        )
    }

    @Test
    fun `detail route stays eligible when trailer playback is muted or otherwise not active time`() {
        assertTrue(
            isIdleScreensaverEligibleRoute(
                currentRoute = Screen.Detail.route,
                playbackIdleSnapshot = PlaybackIdleGateSnapshot(),
                inAppTrailerPlaybackActive = false
            )
        )
    }

    @Test
    fun `home route remains eligible without active in app trailer`() {
        assertTrue(
            isIdleScreensaverEligibleRoute(
                currentRoute = Screen.Home.route,
                playbackIdleSnapshot = PlaybackIdleGateSnapshot(),
                inAppTrailerPlaybackActive = false
            )
        )
    }

    @Test
    fun `idle screensaver start is blocked while activity is not resumed`() {
        assertFalse(
            shouldScheduleIdleScreensaverStart(
                lifecycleState = Lifecycle.State.CREATED,
                idleScreensaverEligible = true,
                idleScreensaverVisible = false,
                slideCount = 0,
                trailerCandidateCount = 1
            )
        )

        assertFalse(
            shouldScheduleIdleScreensaverStart(
                lifecycleState = Lifecycle.State.STARTED,
                idleScreensaverEligible = true,
                idleScreensaverVisible = false,
                slideCount = 0,
                trailerCandidateCount = 1
            )
        )
    }

    @Test
    fun `idle screensaver start is allowed only when resumed eligible hidden and has content`() {
        assertTrue(
            shouldScheduleIdleScreensaverStart(
                lifecycleState = Lifecycle.State.RESUMED,
                idleScreensaverEligible = true,
                idleScreensaverVisible = false,
                slideCount = 0,
                trailerCandidateCount = 1
            )
        )

        assertFalse(
            shouldScheduleIdleScreensaverStart(
                lifecycleState = Lifecycle.State.RESUMED,
                idleScreensaverEligible = true,
                idleScreensaverVisible = true,
                slideCount = 0,
                trailerCandidateCount = 1
            )
        )

        assertFalse(
            shouldScheduleIdleScreensaverStart(
                lifecycleState = Lifecycle.State.RESUMED,
                idleScreensaverEligible = true,
                idleScreensaverVisible = false,
                slideCount = 0,
                trailerCandidateCount = 0
            )
        )
    }

    @Test
    fun `presentation mode prefers trailer session when feature is enabled and startup succeeded`() {
        val trailerSession = IdleTrailerScreensaverSessionStart(
            candidates = listOf(buildTrailerCandidate("movie-1")),
            initialPlayback = IdleTrailerScreensaverPlayback(
                candidate = buildTrailerCandidate("movie-1"),
                playbackRef = TrailerPlaybackRef.YouTubeId("abc123def45"),
                source = TrailerPlaybackSource(videoUrl = "https://video.example.com/1.mp4"),
                index = 0
            )
        )

        assertEquals(
            IdleScreensaverPresentationMode.TRAILER,
            chooseIdleScreensaverPresentationMode(
                trailerScreensaverEnabled = true,
                trailerSessionStart = trailerSession
            )
        )
    }

    @Test
    fun `presentation mode falls back to image when trailer startup is unavailable`() {
        assertEquals(
            IdleScreensaverPresentationMode.IMAGE,
            chooseIdleScreensaverPresentationMode(
                trailerScreensaverEnabled = true,
                trailerSessionStart = null
            )
        )
    }

    @Test
    fun `presentation mode stays image when trailer feature is disabled`() {
        val trailerSession = IdleTrailerScreensaverSessionStart(
            candidates = listOf(buildTrailerCandidate("movie-1")),
            initialPlayback = IdleTrailerScreensaverPlayback(
                candidate = buildTrailerCandidate("movie-1"),
                playbackRef = TrailerPlaybackRef.YouTubeId("abc123def45"),
                source = TrailerPlaybackSource(videoUrl = "https://video.example.com/1.mp4"),
                index = 0
            )
        )

        assertEquals(
            IdleScreensaverPresentationMode.IMAGE,
            chooseIdleScreensaverPresentationMode(
                trailerScreensaverEnabled = false,
                trailerSessionStart = trailerSession
            )
        )
    }

    @Test
    fun `idle diagnostics logging is debug only`() {
        assertTrue(shouldLogIdleScreensaverDiagnostics(isDebugBuild = true))
        assertFalse(shouldLogIdleScreensaverDiagnostics(isDebugBuild = false))
    }

    @Test
    fun `idle diagnostics message includes the gating fields`() {
        val message = buildIdleScreensaverDiagnosticsMessage(
            event = "start_blocked",
            currentRoute = Screen.Home.route,
            idleScreensaverEligible = false,
            idleScreensaverVisible = false,
            slideCount = 10,
            trailerCandidateCount = 39,
            trailerScreensaverEnabled = true,
            inAppTrailerPlaybackActive = true,
            idleLastInteractionAtMs = 1_000L,
            elapsedMs = 120_000L,
            remainingDelayMs = 0L,
            trailerSessionReady = false
        )

        assertTrue(message.contains("event=start_blocked"))
        assertTrue(message.contains("route=home"))
        assertTrue(message.contains("eligible=false"))
        assertTrue(message.contains("visible=false"))
        assertTrue(message.contains("slides=10"))
        assertTrue(message.contains("trailerCandidates=39"))
        assertTrue(message.contains("trailerEnabled=true"))
        assertTrue(message.contains("inAppTrailerActive=true"))
        assertTrue(message.contains("lastInteractionMs=1000"))
        assertTrue(message.contains("elapsedMs=120000"))
        assertTrue(message.contains("remainingMs=0"))
        assertTrue(message.contains("trailerSessionReady=false"))
    }

    @Test
    fun `idle trailer resolver uses item context and stable ids for item lookup refs`() = runBlocking {
        val candidate = buildTrailerCandidate(
            itemId = "source:breaking-bad",
            trailerIds = emptyList(),
            stableIds = ProviderIds(tvdb = "81189", tmdb = "1396", imdb = "tt0903747", kitsu = "7442")
        )
        val requests = mutableListOf<TrailerResolveRequest>()

        val source = resolveIdleTrailerScreensaverPlaybackSource(
            candidate = candidate,
            playbackRef = TrailerPlaybackRef.ItemLookup(
                title = candidate.title,
                year = "2024",
                stableIds = candidate.stableIds,
                type = candidate.itemType,
                contentId = "tvdb:81189"
            ),
            resolveTrailer = { request ->
                requests += request
                TrailerResolution(
                    availability = TrailerAvailability(available = true, reason = "provider_candidate"),
                    candidates = listOf(TrailerPlaybackRef.InAppSource("https://video.example.com/breaking-bad.m3u8")),
                    selected = TrailerPlaybackRef.InAppSource("https://video.example.com/breaking-bad.m3u8"),
                    trace = emptyList()
                )
            },
            resolvePlaybackSource = { ref ->
                require(ref is TrailerPlaybackRef.InAppSource)
                TrailerResolutionResult.Playback(
                    TrailerPlaybackSource(videoUrl = "https://video.example.com/breaking-bad.m3u8")
                )
            }
        )

        assertEquals("https://video.example.com/breaking-bad.m3u8", source?.videoUrl)
        assertEquals(
            "Example source:breaking-bad",
            requests.single().title
        )
        assertEquals("2024", requests.single().year)
        assertEquals(ProviderIds(tvdb = "81189", tmdb = "1396", imdb = "tt0903747", kitsu = "7442"), requests.single().stableIds)
        assertEquals("movie", requests.single().type)
        assertEquals("tvdb:81189", requests.single().contentId)
        assertEquals(emptyList<String>(), requests.single().fallbackYtIds)
        assertTrue(requests.single().providerCandidates.single() is TrailerPlaybackRef.ItemLookup)
        assertEquals(TrailerSurface.SCREENSAVER, requests.single().surface)
    }

    @Test
    fun `idle trailer item lookup ref is selected when explicit trailer ids are absent`() = runBlocking {
        val candidate = buildTrailerCandidate(
            itemId = "source:breaking-bad",
            trailerIds = emptyList(),
            stableIds = ProviderIds(tvdb = "81189", tmdb = "1396", imdb = "tt0903747")
        )
        val resolver = com.nexio.tv.core.metadata.router.resolver.TrailerResolver(
            com.nexio.tv.core.trace.TraceMetadataEvents(
                com.nexio.tv.core.integration.RecordingTraceSink(),
                sessionId = { "screensaver" }
            )
        )
        val playbackRefs = mutableListOf<TrailerPlaybackRef>()

        val source = resolveIdleTrailerScreensaverPlaybackSource(
            candidate = candidate,
            playbackRef = TrailerPlaybackRef.ItemLookup(
                title = candidate.title,
                year = "2024",
                stableIds = candidate.stableIds,
                type = candidate.itemType,
                contentId = "tvdb:81189"
            ),
            resolveTrailer = resolver::resolveTrailer,
            resolvePlaybackSource = { ref ->
                playbackRefs += ref
                TrailerResolutionResult.Playback(
                    TrailerPlaybackSource(videoUrl = "https://video.example.com/by-item.m3u8")
                )
            }
        )

        assertEquals("https://video.example.com/by-item.m3u8", source?.videoUrl)
        assertEquals(1, playbackRefs.size)
        assertTrue(playbackRefs.single().toString().contains("1396"))
        assertFalse(playbackRefs.single() is TrailerPlaybackRef.YouTubeId)
    }

    @Test
    fun `idle trailer resolver passes explicit fallback youtube id without building a youtube url`() = runBlocking {
        val candidate = buildTrailerCandidate(
            itemId = "source:movie",
            trailerIds = listOf("abc123def45"),
            stableIds = ProviderIds(tmdb = "550")
        )
        val requests = mutableListOf<TrailerResolveRequest>()
        val playbackRefs = mutableListOf<TrailerPlaybackRef>()

        resolveIdleTrailerScreensaverPlaybackSource(
            candidate = candidate,
            playbackRef = TrailerPlaybackRef.YouTubeId("abc123def45"),
            resolveTrailer = { request ->
                requests += request
                TrailerResolution(
                    availability = TrailerAvailability(available = true, reason = "fallback_youtube_id"),
                    candidates = listOf(TrailerPlaybackRef.YouTubeId("abc123def45")),
                    selected = TrailerPlaybackRef.YouTubeId("abc123def45"),
                    trace = emptyList()
                )
            },
            resolvePlaybackSource = { ref ->
                playbackRefs += ref
                TrailerResolutionResult.Playback(
                    TrailerPlaybackSource(videoUrl = "https://video.example.com/movie.m3u8")
                )
            }
        )

        assertEquals(emptyList<String>(), requests.single().fallbackYtIds)
        assertEquals(TrailerPlaybackRef.YouTubeId("abc123def45"), requests.single().providerCandidates.single())
        assertEquals("tmdb:550", requests.single().contentId)
        assertEquals(TrailerPlaybackRef.YouTubeId("abc123def45"), playbackRefs.single())
    }

    private fun buildTrailerCandidate(itemId: String): IdleTrailerScreensaverCandidate {
        return buildTrailerCandidate(
            itemId = itemId,
            trailerIds = listOf("abc123def45"),
            stableIds = ProviderIds()
        )
    }

    private fun buildTrailerCandidate(
        itemId: String,
        trailerIds: List<String>,
        stableIds: ProviderIds
    ): IdleTrailerScreensaverCandidate {
        return IdleTrailerScreensaverCandidate(
            itemId = itemId,
            itemType = "movie",
            addonBaseUrl = "https://api.example.com",
            title = "Example $itemId",
            logoArtwork = null,
            backgroundArtwork = artworkRef("https://image.example.com/$itemId.jpg", ArtworkType.BACKDROP),
            fallbackArtwork = listOf(artworkRef("https://image.example.com/$itemId.jpg", ArtworkType.BACKDROP)),
            genres = emptyList(),
            description = null,
            releaseInfo = "2024",
            runtime = null,
            imdbRating = null,
            tomatoesRating = null,
            trailerState = TrailerDisplayState(fallbackTrailerYtIds = trailerIds),
            stableIds = stableIds
        )
    }

    private fun artworkRef(value: String, imageType: ArtworkType): ArtworkDisplayRef =
        ArtworkDisplayRef.LegacyString(
            value = value,
            imageType = imageType,
            trace = ArtworkTrace.empty()
        )
}
