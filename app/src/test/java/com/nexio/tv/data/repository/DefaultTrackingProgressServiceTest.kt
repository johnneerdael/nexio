package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.TrackingProvider
import com.nexio.tv.domain.model.WatchProgress
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultTrackingProgressServiceTest {

    private fun trackingProviderStateService(
        provider: TrackingProvider
    ) = mockk<TrackingProviderStateService> {
        val state = EffectiveTrackingProviderState(
            effectiveProvider = provider,
            traktAuthenticated = provider == TrackingProvider.TRAKT,
            simklAuthenticated = provider == TrackingProvider.SIMKL
        )
        every { this@mockk.state } returns flowOf(state)
    }

    private fun profileScopedTrackingProviderStateService(
        profileState: EffectiveTrackingProviderState
    ) = mockk<TrackingProviderStateService> {
        every { this@mockk.state } returns flowOf(
            EffectiveTrackingProviderState(
                effectiveProvider = TrackingProvider.TRAKT,
                traktAuthenticated = true
            )
        )
        every { stateForProfile(any()) } returns flowOf(EffectiveTrackingProviderState())
        every { stateForProfile(2) } returns flowOf(profileState)
    }

    private fun multiProviderTrackingProviderStateService() = mockk<TrackingProviderStateService> {
        val state = EffectiveTrackingProviderState(
            effectiveProvider = TrackingProvider.TRAKT,
            traktAuthenticated = true,
            simklAuthenticated = true
        )
        every { this@mockk.state } returns flowOf(state)
    }

    private fun unauthenticatedProviderStateService(
        storedProvider: TrackingProvider = TrackingProvider.TRAKT
    ) = mockk<TrackingProviderStateService> {
        val state = EffectiveTrackingProviderState(
            storedProvider = storedProvider,
            effectiveProvider = storedProvider,
            traktAuthenticated = false,
            simklAuthenticated = false
        )
        every { this@mockk.state } returns flowOf(state)
        coEvery { currentState() } returns state
    }

    @Test
    fun `observeAllProgress switches to simkl flow when simkl is selected`() = runTest {
        val traktService = mockk<TraktProgressService>()
        val simklService = mockk<SimklProgressService>()
        val traktProgress = listOf(sampleProgress("tt1"))
        val simklProgress = listOf(sampleProgress("tt2"))

        every { traktService.observeAllProgress() } returns flowOf(traktProgress)
        every { simklService.observeAllProgress() } returns flowOf(simklProgress)
        every { simklService.observeRemoteSnapshotLoaded() } returns flowOf(true)
        every { traktService.observeRemoteSnapshotLoaded() } returns flowOf(true)
        every { traktService.observeContinueWatchingNextUp() } returns flowOf(emptyList())
        every { traktService.observeSyntheticContinueWatchingNextUp() } returns flowOf(emptyList())
        every { simklService.observeContinueWatchingNextUp() } returns flowOf(emptyList())
        every { simklService.observeSyntheticContinueWatchingNextUp() } returns flowOf(emptyList())
        every { traktService.observeEpisodeProgress(any()) } returns flowOf(emptyMap())
        every { simklService.observeEpisodeProgress(any()) } returns flowOf(emptyMap())
        every { traktService.observeMovieWatched(any()) } returns flowOf(false)
        every { simklService.observeMovieWatched(any()) } returns flowOf(false)

        val service = DefaultTrackingProgressService(
            traktProgressService = traktService,
            simklProgressService = simklService,
            trackingProviderStateService = trackingProviderStateService(TrackingProvider.SIMKL)
        )

        val observed = service.observeAllProgress().firstValue()

        assertEquals(simklProgress, observed)
    }

    @Test
    fun `observeContinueWatchingNextUp switches to simkl flow when simkl is selected`() = runTest {
        val traktService = mockk<TraktProgressService>()
        val simklService = mockk<SimklProgressService>()
        val simklNextUp = listOf(
            TrackingNextUpEntry(
                contentId = "tt0903747",
                name = "Breaking Bad",
                season = 1,
                episode = 6,
                episodeTitle = null,
                videoId = "tt0903747:1:6",
                firstAired = null,
                firstAiredMs = 0L,
                activityAtMs = 123L
            )
        )

        every { traktService.observeAllProgress() } returns flowOf(emptyList())
        every { simklService.observeAllProgress() } returns flowOf(emptyList())
        every { simklService.observeRemoteSnapshotLoaded() } returns flowOf(true)
        every { traktService.observeRemoteSnapshotLoaded() } returns flowOf(true)
        every { traktService.observeContinueWatchingNextUp() } returns flowOf(emptyList())
        every { traktService.observeSyntheticContinueWatchingNextUp() } returns flowOf(emptyList())
        every { simklService.observeContinueWatchingNextUp() } returns flowOf(simklNextUp)
        every { simklService.observeSyntheticContinueWatchingNextUp() } returns flowOf(simklNextUp)
        every { traktService.observeEpisodeProgress(any()) } returns flowOf(emptyMap())
        every { simklService.observeEpisodeProgress(any()) } returns flowOf(emptyMap())
        every { traktService.observeMovieWatched(any()) } returns flowOf(false)
        every { simklService.observeMovieWatched(any()) } returns flowOf(false)

        val service = DefaultTrackingProgressService(
            traktProgressService = traktService,
            simklProgressService = simklService,
            trackingProviderStateService = trackingProviderStateService(TrackingProvider.SIMKL)
        )

        val observed = service.observeContinueWatchingNextUp().firstValue()

        assertEquals(simklNextUp, observed)
    }

    @Test
    fun `observeRemoteSnapshotLoaded switches to simkl flow when simkl is selected`() = runTest {
        val traktService = mockk<TraktProgressService>()
        val simklService = mockk<SimklProgressService>()

        every { traktService.observeAllProgress() } returns flowOf(emptyList())
        every { simklService.observeAllProgress() } returns flowOf(emptyList())
        every { simklService.observeRemoteSnapshotLoaded() } returns flowOf(false)
        every { traktService.observeRemoteSnapshotLoaded() } returns flowOf(true)
        every { traktService.observeContinueWatchingNextUp() } returns flowOf(emptyList())
        every { traktService.observeSyntheticContinueWatchingNextUp() } returns flowOf(emptyList())
        every { simklService.observeContinueWatchingNextUp() } returns flowOf(emptyList())
        every { simklService.observeSyntheticContinueWatchingNextUp() } returns flowOf(emptyList())
        every { traktService.observeEpisodeProgress(any()) } returns flowOf(emptyMap())
        every { simklService.observeEpisodeProgress(any()) } returns flowOf(emptyMap())
        every { traktService.observeMovieWatched(any()) } returns flowOf(false)
        every { simklService.observeMovieWatched(any()) } returns flowOf(false)

        val service = DefaultTrackingProgressService(
            traktProgressService = traktService,
            simklProgressService = simklService,
            trackingProviderStateService = trackingProviderStateService(TrackingProvider.SIMKL)
        )

        val observed = service.observeRemoteSnapshotLoaded().firstValue()

        assertEquals(false, observed)
    }

    @Test
    fun `profile scoped observeAllProgress reads requested profile provider state`() = runTest {
        val traktService = mockk<TraktProgressService>()
        val simklService = mockk<SimklProgressService>()
        val activeProfileProgress = listOf(sampleProgress("tt-active"))
        val profileTwoProgress = listOf(sampleProgress("tt-profile-two"))

        every { traktService.observeAllProgress() } returns flowOf(activeProfileProgress)
        every { traktService.observeAllProgress(2) } returns flowOf(profileTwoProgress)
        every { simklService.observeAllProgress() } returns flowOf(emptyList())
        every { simklService.observeAllProgress(any()) } returns flowOf(emptyList())

        val service = DefaultTrackingProgressService(
            traktProgressService = traktService,
            simklProgressService = simklService,
            trackingProviderStateService = profileScopedTrackingProviderStateService(
                EffectiveTrackingProviderState(
                    effectiveProvider = TrackingProvider.TRAKT,
                    traktAuthenticated = true
                )
            )
        )

        val observed = service.observeAllProgress(2).firstValue()

        assertEquals(profileTwoProgress, observed)
    }

    @Test
    fun `profile scoped next up reads requested profile provider state`() = runTest {
        val traktService = mockk<TraktProgressService>()
        val simklService = mockk<SimklProgressService>()
        val activeNextUp = listOf(traktNextUp("series:active"))
        val profileTwoNextUp = listOf(traktNextUp("series:profile-two"))

        every { traktService.observeContinueWatchingNextUp() } returns flowOf(activeNextUp)
        every { traktService.observeContinueWatchingNextUp(2) } returns flowOf(profileTwoNextUp)
        every { traktService.observeSyntheticContinueWatchingNextUp() } returns flowOf(activeNextUp)
        every { traktService.observeSyntheticContinueWatchingNextUp(2) } returns flowOf(profileTwoNextUp)
        every { simklService.observeContinueWatchingNextUp() } returns flowOf(emptyList())
        every { simklService.observeContinueWatchingNextUp(any()) } returns flowOf(emptyList())
        every { simklService.observeSyntheticContinueWatchingNextUp() } returns flowOf(emptyList())
        every { simklService.observeSyntheticContinueWatchingNextUp(any()) } returns flowOf(emptyList())

        val service = DefaultTrackingProgressService(
            traktProgressService = traktService,
            simklProgressService = simklService,
            trackingProviderStateService = profileScopedTrackingProviderStateService(
                EffectiveTrackingProviderState(
                    effectiveProvider = TrackingProvider.TRAKT,
                    traktAuthenticated = true
                )
            )
        )

        val observed = service.observeContinueWatchingNextUp(2).firstValue()

        assertEquals("series:profile-two", observed.single().contentId)
    }

    @Test
    fun `observeRemoteSnapshotLoaded waits for all active providers`() = runTest {
        val traktService = mockk<TraktProgressService>()
        val simklService = mockk<SimklProgressService>()

        every { traktService.observeRemoteSnapshotLoaded() } returns flowOf(true)
        every { simklService.observeRemoteSnapshotLoaded() } returns flowOf(false)

        val service = DefaultTrackingProgressService(
            traktProgressService = traktService,
            simklProgressService = simklService,
            trackingProviderStateService = multiProviderTrackingProviderStateService()
        )

        val observed = service.observeRemoteSnapshotLoaded().firstValue()

        assertEquals(false, observed)
    }

    @Test
    fun `observeMovieWatched switches to simkl flow when simkl is selected`() = runTest {
        val traktService = mockk<TraktProgressService>()
        val simklService = mockk<SimklProgressService>()

        every { traktService.observeAllProgress() } returns flowOf(emptyList())
        every { simklService.observeAllProgress() } returns flowOf(emptyList())
        every { simklService.observeRemoteSnapshotLoaded() } returns flowOf(true)
        every { traktService.observeRemoteSnapshotLoaded() } returns flowOf(true)
        every { traktService.observeContinueWatchingNextUp() } returns flowOf(emptyList())
        every { traktService.observeSyntheticContinueWatchingNextUp() } returns flowOf(emptyList())
        every { simklService.observeContinueWatchingNextUp() } returns flowOf(emptyList())
        every { simklService.observeSyntheticContinueWatchingNextUp() } returns flowOf(emptyList())
        every { traktService.observeEpisodeProgress(any()) } returns flowOf(emptyMap())
        every { simklService.observeEpisodeProgress(any()) } returns flowOf(emptyMap())
        every { traktService.observeMovieWatched("tt1375666") } returns flowOf(false)
        every { simklService.observeMovieWatched("tt1375666") } returns flowOf(true)

        val service = DefaultTrackingProgressService(
            traktProgressService = traktService,
            simklProgressService = simklService,
            trackingProviderStateService = trackingProviderStateService(TrackingProvider.SIMKL)
        )

        val observed = service.observeMovieWatched("tt1375666").firstValue()

        assertEquals(true, observed)
    }

    @Test
    fun `refreshNow uses current route scoped provider state`() = runTest {
        val traktService = mockk<TraktProgressService>(relaxed = true)
        val simklService = mockk<SimklProgressService>(relaxed = true)
        val trackingProviderStateService = mockk<TrackingProviderStateService> {
            every { state } returns flowOf(
                EffectiveTrackingProviderState(
                    effectiveProvider = TrackingProvider.TRAKT,
                    traktAuthenticated = true,
                    simklAuthenticated = false
                )
            )
            coEvery { currentState() } returns EffectiveTrackingProviderState(
                effectiveProvider = TrackingProvider.SIMKL,
                traktAuthenticated = false,
                simklAuthenticated = true
            )
        }
        val service = DefaultTrackingProgressService(
            traktProgressService = traktService,
            simklProgressService = simklService,
            trackingProviderStateService = trackingProviderStateService
        )

        service.refreshNow()

        coVerify(exactly = 1) { simklService.refreshNowImmediate() }
        coVerify(exactly = 0) { traktService.refreshNowImmediate() }
    }

    @Test
    fun `startup refresh does not force Trakt full account sync`() = runTest {
        val traktService = mockk<TraktProgressService>(relaxed = true)
        val simklService = mockk<SimklProgressService>(relaxed = true)
        val mdbListService = mockk<com.nexio.tv.data.integration.mdblist.MDBListProgressService>(relaxed = true)
        val trackingProviderStateService = mockk<TrackingProviderStateService> {
            every { state } returns flowOf(
                EffectiveTrackingProviderState(
                    effectiveProvider = TrackingProvider.TRAKT,
                    traktAuthenticated = true,
                    simklAuthenticated = true,
                    mdbListAuthenticated = true
                )
            )
            coEvery { currentState() } returns EffectiveTrackingProviderState(
                effectiveProvider = TrackingProvider.TRAKT,
                traktAuthenticated = true,
                simklAuthenticated = true,
                mdbListAuthenticated = true
            )
        }
        val service = DefaultTrackingProgressService(
            traktProgressService = traktService,
            simklProgressService = simklService,
            mdbListProgressService = mdbListService,
            trackingProviderStateService = trackingProviderStateService
        )

        service.refreshOnStartup()

        coVerify(exactly = 1) { traktService.requestEventDrivenRefresh() }
        coVerify(exactly = 0) { traktService.refreshNowImmediate() }
        coVerify(exactly = 1) { simklService.refreshNow() }
        coVerify(exactly = 0) { simklService.refreshNowImmediate() }
        coVerify(exactly = 1) { mdbListService.refreshNowImmediate() }
    }

    @Test
    fun `unauthenticated profile does not observe Trakt progress`() = runTest {
        val traktService = mockk<TraktProgressService>(relaxed = true)
        val simklService = mockk<SimklProgressService>(relaxed = true)
        val service = DefaultTrackingProgressService(
            traktProgressService = traktService,
            simklProgressService = simklService,
            trackingProviderStateService = unauthenticatedProviderStateService()
        )

        val observed = service.observeAllProgress().firstValue()

        assertEquals(emptyList<WatchProgress>(), observed)
        coVerify(exactly = 0) { traktService.refreshNow() }
    }

    @Test
    fun `unauthenticated profile reports remote snapshot unloaded without calling Trakt`() = runTest {
        val traktService = mockk<TraktProgressService>()
        val simklService = mockk<SimklProgressService>()
        val service = DefaultTrackingProgressService(
            traktProgressService = traktService,
            simklProgressService = simklService,
            trackingProviderStateService = unauthenticatedProviderStateService()
        )

        val observed = service.observeRemoteSnapshotLoaded().firstValue()

        assertEquals(false, observed)
    }

    private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.firstValue(): T = first()

    private fun sampleProgress(contentId: String): WatchProgress {
        return WatchProgress(
            contentId = contentId,
            contentType = "movie",
            name = contentId,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = contentId,
            season = null,
            episode = null,
            episodeTitle = null,
            position = 1L,
            duration = 1L,
            lastWatched = 1L,
            progressPercent = 100f
        )
    }

    private fun traktNextUp(contentId: String): TraktProgressService.NextUpEntry =
        TraktProgressService.NextUpEntry(
            contentId = contentId,
            name = contentId,
            season = 1,
            episode = 1,
            episodeTitle = null,
            videoId = "$contentId:1:1",
            firstAired = null,
            firstAiredMs = 0L,
            activityAtMs = 1L
        )
}
