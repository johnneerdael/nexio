package com.nexio.tv.data.repository

import com.nexio.tv.core.playback.PlaybackOwnerContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DefaultTrackingScrobbleServiceTest {

    private fun owner(profileId: Int): PlaybackOwnerContext =
        PlaybackOwnerContext(
            ownerProfileId = profileId,
            ownerSessionId = "session-$profileId",
            startedAtEpochMs = 1L
        )

    private fun trackingProviderStateService(
        provider: com.nexio.tv.domain.model.TrackingProvider = com.nexio.tv.domain.model.TrackingProvider.TRAKT,
        traktAuthenticated: Boolean = provider == com.nexio.tv.domain.model.TrackingProvider.TRAKT,
        simklAuthenticated: Boolean = provider == com.nexio.tv.domain.model.TrackingProvider.SIMKL
    ) = mockk<com.nexio.tv.data.repository.TrackingProviderStateService> {
        val effectiveState = com.nexio.tv.data.repository.EffectiveTrackingProviderState(
            effectiveProvider = provider,
            traktAuthenticated = traktAuthenticated,
            simklAuthenticated = simklAuthenticated
        )
        coEvery { currentState() } returns effectiveState
        coEvery { currentState(any()) } returns effectiveState
        every { currentProfileId() } returns 1
        every { state } returns flowOf(
            effectiveState
        )
    }

    @Test
    fun `episode checkin maps generic item into Trakt episode item`() = runTest {
        val traktService = mockk<com.nexio.tv.data.repository.TraktScrobbleService>()
        val simklService = mockk<com.nexio.tv.data.repository.SimklScrobbleService>(relaxed = true)
        val itemSlot = slot<com.nexio.tv.data.repository.TraktScrobbleItem>()
        coEvery { traktService.checkin(capture(itemSlot), any(), any()) } returns true

        val service = com.nexio.tv.data.repository.DefaultTrackingScrobbleService(traktService, simklService, trackingProviderStateService(), io.mockk.mockk(relaxed = true))
        val result = service.checkin(
            com.nexio.tv.data.repository.TrackingScrobbleItem.Episode(
                contentId = "tt1520211",
                showTitle = "The Walking Dead",
                showYear = 2010,
                season = 1,
                number = 4,
                episodeTitle = "Vatos"
            )
        )

        assertEquals(true, result)
        val captured = itemSlot.captured as com.nexio.tv.data.repository.TraktScrobbleItem.Episode
        assertEquals("The Walking Dead", captured.showTitle)
        assertEquals(2010, captured.showYear)
        assertEquals("tt1520211", captured.showIds.imdb)
        assertEquals(1, captured.season)
        assertEquals(4, captured.number)
        assertEquals("Vatos", captured.episodeTitle)
    }

    @Test
    fun `movie checkin returns false when ids are unsupported`() = runTest {
        val traktService = mockk<com.nexio.tv.data.repository.TraktScrobbleService>(relaxed = true)
        val simklService = mockk<com.nexio.tv.data.repository.SimklScrobbleService>(relaxed = true)
        val service = com.nexio.tv.data.repository.DefaultTrackingScrobbleService(traktService, simklService, trackingProviderStateService(), io.mockk.mockk(relaxed = true))

        val result = service.checkin(
            com.nexio.tv.data.repository.TrackingScrobbleItem.Movie(
                contentId = "unsupported-id",
                title = "Unknown",
                year = 2025
            )
        )

        assertFalse(result)
        coVerify(exactly = 0) { traktService.checkin(any(), any(), any()) }
    }

    @Test
    fun `simkl provider routes generic checkin to simkl service`() = runTest {
        val traktService = mockk<com.nexio.tv.data.repository.TraktScrobbleService>(relaxed = true)
        val simklService = mockk<com.nexio.tv.data.repository.SimklScrobbleService>()
        coEvery { simklService.checkin(any(), any(), any()) } returns true
        val service = com.nexio.tv.data.repository.DefaultTrackingScrobbleService(
            traktService,
            simklService,
            trackingProviderStateService(provider = com.nexio.tv.domain.model.TrackingProvider.SIMKL),
            io.mockk.mockk(relaxed = true)
        )

        val result = service.checkin(
            com.nexio.tv.data.repository.TrackingScrobbleItem.Movie(
                contentId = "tt1375666",
                title = "Inception",
                year = 2010
            )
        )

        assertEquals(true, result)
        coVerify(exactly = 1) { simklService.checkin(any(), any(), any()) }
        coVerify(exactly = 0) { traktService.checkin(any(), any(), any()) }
    }

    @Test
    fun `trakt scrobble start is ignored when profile lacks trakt auth`() = runTest {
        val traktService = mockk<com.nexio.tv.data.repository.TraktScrobbleService>(relaxed = true)
        val simklService = mockk<com.nexio.tv.data.repository.SimklScrobbleService>(relaxed = true)
        val service = com.nexio.tv.data.repository.DefaultTrackingScrobbleService(
            traktService,
            simklService,
            trackingProviderStateService(
                provider = com.nexio.tv.domain.model.TrackingProvider.TRAKT,
                traktAuthenticated = false,
                simklAuthenticated = false
            ),
            io.mockk.mockk(relaxed = true)
        )

        service.scrobbleStart(
            com.nexio.tv.data.repository.TrackingScrobbleItem.Movie(
                contentId = "tt1375666",
                title = "Inception",
                year = 2010
            ),
            progressPercent = 12f,
            owner = owner(1)
        )

        coVerify(exactly = 0) { traktService.scrobbleStart(any(), any(), any()) }
        coVerify(exactly = 0) { simklService.scrobbleStart(any(), any(), any()) }
    }

    @Test
    fun `trakt checkin returns false when profile lacks trakt auth`() = runTest {
        val traktService = mockk<com.nexio.tv.data.repository.TraktScrobbleService>(relaxed = true)
        val simklService = mockk<com.nexio.tv.data.repository.SimklScrobbleService>(relaxed = true)
        val service = com.nexio.tv.data.repository.DefaultTrackingScrobbleService(
            traktService,
            simklService,
            trackingProviderStateService(
                provider = com.nexio.tv.domain.model.TrackingProvider.TRAKT,
                traktAuthenticated = false,
                simklAuthenticated = false
            ),
            io.mockk.mockk(relaxed = true)
        )

        val result = service.checkin(
            com.nexio.tv.data.repository.TrackingScrobbleItem.Movie(
                contentId = "tt1375666",
                title = "Inception",
                year = 2010
            )
        )

        assertFalse(result)
        coVerify(exactly = 0) { traktService.checkin(any(), any(), any()) }
    }

    @Test
    fun `trakt scrobble start uses owner profile state and delegates owner profile`() = runTest {
        val traktService = mockk<com.nexio.tv.data.repository.TraktScrobbleService>()
        val simklService = mockk<com.nexio.tv.data.repository.SimklScrobbleService>(relaxed = true)
        coEvery { traktService.scrobbleStart(any(), any(), any()) } returns Unit
        val stateService = trackingProviderStateService(
            provider = com.nexio.tv.domain.model.TrackingProvider.TRAKT,
            traktAuthenticated = true
        )
        val service = com.nexio.tv.data.repository.DefaultTrackingScrobbleService(
            traktService,
            simklService,
            stateService,
            io.mockk.mockk(relaxed = true)
        )

        service.scrobbleStart(
            com.nexio.tv.data.repository.TrackingScrobbleItem.Movie(
                contentId = "tt1375666",
                title = "Inception",
                year = 2010
            ),
            progressPercent = 12f,
            owner = owner(2)
        )

        coVerify(exactly = 1) { stateService.currentState(2) }
        coVerify(exactly = 1) { traktService.scrobbleStart(any(), 12f, 2) }
        coVerify(exactly = 0) { simklService.scrobbleStart(any(), any(), any()) }
    }

    @Test
    fun `simkl scrobble start uses owner profile state and delegates owner profile`() = runTest {
        val traktService = mockk<com.nexio.tv.data.repository.TraktScrobbleService>(relaxed = true)
        val simklService = mockk<com.nexio.tv.data.repository.SimklScrobbleService>()
        coEvery { simklService.scrobbleStart(any(), any(), any()) } returns Unit
        val stateService = trackingProviderStateService(
            provider = com.nexio.tv.domain.model.TrackingProvider.SIMKL,
            simklAuthenticated = true
        )
        val service = com.nexio.tv.data.repository.DefaultTrackingScrobbleService(
            traktService,
            simklService,
            stateService,
            io.mockk.mockk(relaxed = true)
        )

        service.scrobbleStart(
            com.nexio.tv.data.repository.TrackingScrobbleItem.Movie(
                contentId = "tt1375666",
                title = "Inception",
                year = 2010
            ),
            progressPercent = 12f,
            owner = owner(2)
        )

        coVerify(exactly = 1) { stateService.currentState(2) }
        coVerify(exactly = 1) { simklService.scrobbleStart(any(), 12f, 2) }
        coVerify(exactly = 0) { traktService.scrobbleStart(any(), any(), any()) }
    }
}
