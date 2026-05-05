package com.nexio.tv

import com.nexio.tv.data.trailer.TrailerPlaybackSource
import com.nexio.tv.data.trailer.TrailerResolutionResult
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.ui.navigation.Screen
import com.nexio.tv.ui.screensaver.IdleScreensaverPresentationMode
import com.nexio.tv.ui.screensaver.IdleTrailerScreensaverCandidate
import com.nexio.tv.ui.screensaver.IdleTrailerScreensaverPlayback
import com.nexio.tv.ui.screensaver.IdleTrailerScreensaverSessionStart
import com.nexio.tv.ui.screensaver.PlaybackIdleGateSnapshot
import com.nexio.tv.ui.screensaver.RESOLVE_TRAILER_BY_ITEM_SENTINEL
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
                trailerId = "abc123def45",
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
                trailerId = "abc123def45",
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
    fun `idle trailer resolver uses item context and stable ids when trailer id is sentinel`() = runBlocking {
        val candidate = buildTrailerCandidate(
            itemId = "source:breaking-bad",
            trailerIds = emptyList(),
            stableIds = ProviderIds(tvdb = "81189", tmdb = "1396", imdb = "tt0903747", kitsu = "7442")
        )
        val requests = mutableListOf<IdleTrailerResolverRequest>()

        val source = resolveIdleTrailerScreensaverPlaybackSource(
            candidate = candidate,
            trailerId = RESOLVE_TRAILER_BY_ITEM_SENTINEL
        ) { request ->
            requests += request
            TrailerResolutionResult.Playback(
                TrailerPlaybackSource(videoUrl = "https://video.example.com/breaking-bad.m3u8")
            )
        }

        assertEquals("https://video.example.com/breaking-bad.m3u8", source?.videoUrl)
        assertEquals(
            IdleTrailerResolverRequest(
                title = "Example source:breaking-bad",
                year = "2024",
                tmdbId = "1396",
                type = "movie",
                contentId = "tvdb:81189",
                fallbackYtIds = emptyList()
            ),
            requests.single()
        )
    }

    @Test
    fun `idle trailer resolver passes explicit fallback youtube id without building a youtube url`() = runBlocking {
        val candidate = buildTrailerCandidate(
            itemId = "source:movie",
            trailerIds = listOf("abc123def45"),
            stableIds = ProviderIds(tmdb = "550")
        )
        val requests = mutableListOf<IdleTrailerResolverRequest>()

        resolveIdleTrailerScreensaverPlaybackSource(
            candidate = candidate,
            trailerId = "abc123def45"
        ) { request ->
            requests += request
            TrailerResolutionResult.Playback(
                TrailerPlaybackSource(videoUrl = "https://video.example.com/movie.m3u8")
            )
        }

        assertEquals(listOf("abc123def45"), requests.single().fallbackYtIds)
        assertFalse(requests.single().fallbackYtIds.single().startsWith("https://www.youtube.com/watch"))
        assertEquals("tmdb:550", requests.single().contentId)
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
            logoUrl = null,
            backgroundUrl = "https://image.example.com/$itemId.jpg",
            fallbackArtworkUrls = listOf("https://image.example.com/$itemId.jpg"),
            genres = emptyList(),
            description = null,
            releaseInfo = "2024",
            runtime = null,
            imdbRating = null,
            tomatoesRating = null,
            trailerYtIds = trailerIds,
            stableIds = stableIds
        )
    }
}
