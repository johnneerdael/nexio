package com.nexio.tv.data.repository

import com.nexio.tv.core.playback.PlaybackOwnerContext
import com.nexio.tv.domain.model.TrackingProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingWatchingNowRoutingTest {

    private fun owner(profileId: Int): PlaybackOwnerContext =
        PlaybackOwnerContext(
            ownerProfileId = profileId,
            ownerSessionId = "session-$profileId",
            startedAtEpochMs = 1L
        )

    private fun trackingProviderStateService(
        provider: TrackingProvider
    ) = mockk<TrackingProviderStateService> {
        val state = EffectiveTrackingProviderState(
            effectiveProvider = provider,
            traktAuthenticated = provider == TrackingProvider.TRAKT,
            simklAuthenticated = provider == TrackingProvider.SIMKL
        )
        every { this@mockk.state } returns flowOf(state)
        coEvery { currentState() } returns state
        coEvery { currentState(any<Int>()) } returns state
    }

    @Test
    fun `observeWatchingNowState uses simkl flow when simkl is selected`() = runTest {
        val traktService = mockk<TraktScrobbleService>()
        val simklService = mockk<SimklScrobbleService>()
        val simklState = SimklScrobbleService.WatchingNowState(
            active = true,
            title = "SIMKL item",
            contentType = "movie",
            progressPercent = 45f,
            updatedAtMs = 123L
        )
        every { traktService.observeWatchingNowState() } returns MutableStateFlow(TraktScrobbleService.WatchingNowState())
        every { simklService.observeWatchingNowState() } returns MutableStateFlow(simklState)

        val service = DefaultTrackingScrobbleService(
            traktScrobbleService = traktService,
            simklScrobbleService = simklService,
            trackingProviderStateService = trackingProviderStateService(TrackingProvider.SIMKL),
            rejectionReporter = mockk(relaxed = true)
        )

        val observed = service.observeWatchingNowState().first()

        assertEquals("SIMKL item", observed.title)
        assertEquals(45f, observed.progressPercent)
    }

    @Test
    fun `scrobbleStart routes to simkl service when simkl is selected`() = runTest {
        val traktService = mockk<TraktScrobbleService>(relaxed = true)
        val simklService = mockk<SimklScrobbleService>(relaxed = true)
        every { traktService.observeWatchingNowState() } returns MutableStateFlow(TraktScrobbleService.WatchingNowState())
        every { simklService.observeWatchingNowState() } returns MutableStateFlow(SimklScrobbleService.WatchingNowState())

        val service = DefaultTrackingScrobbleService(
            traktScrobbleService = traktService,
            simklScrobbleService = simklService,
            trackingProviderStateService = trackingProviderStateService(TrackingProvider.SIMKL),
            rejectionReporter = mockk(relaxed = true)
        )

        service.scrobbleStart(
            TrackingScrobbleItem.Movie(
                contentId = "tt1375666",
                title = "Inception",
                year = 2010
            ),
            12f,
            owner(1)
        )

        coVerify(exactly = 1) { simklService.scrobbleStart(any(), 12f, any(), any()) }
        coVerify(exactly = 0) { traktService.scrobbleStart(any(), any(), any(), any()) }
    }
}
