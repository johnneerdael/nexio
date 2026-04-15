package com.nexio.tv.data.repository

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

    private fun trackingProviderStateService(
        provider: com.nexio.tv.domain.model.TrackingProvider = com.nexio.tv.domain.model.TrackingProvider.TRAKT,
        traktAuthenticated: Boolean = provider == com.nexio.tv.domain.model.TrackingProvider.TRAKT,
        simklAuthenticated: Boolean = provider == com.nexio.tv.domain.model.TrackingProvider.SIMKL
    ) = mockk<com.nexio.tv.data.repository.TrackingProviderStateService> {
        coEvery { currentState() } returns com.nexio.tv.data.repository.EffectiveTrackingProviderState(
            effectiveProvider = provider,
            traktAuthenticated = traktAuthenticated,
            simklAuthenticated = simklAuthenticated
        )
        every { state } returns flowOf(
            com.nexio.tv.data.repository.EffectiveTrackingProviderState(
                effectiveProvider = provider,
                traktAuthenticated = traktAuthenticated,
                simklAuthenticated = simklAuthenticated
            )
        )
    }

    @Test
    fun `episode checkin maps generic item into Trakt episode item`() = runTest {
        val traktService = mockk<com.nexio.tv.data.repository.TraktScrobbleService>()
        val simklService = mockk<com.nexio.tv.data.repository.SimklScrobbleService>(relaxed = true)
        val itemSlot = slot<com.nexio.tv.data.repository.TraktScrobbleItem>()
        coEvery { traktService.checkin(capture(itemSlot), any()) } returns true

        val service = com.nexio.tv.data.repository.DefaultTrackingScrobbleService(traktService, simklService, trackingProviderStateService())
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
        val service = com.nexio.tv.data.repository.DefaultTrackingScrobbleService(traktService, simklService, trackingProviderStateService())

        val result = service.checkin(
            com.nexio.tv.data.repository.TrackingScrobbleItem.Movie(
                contentId = "unsupported-id",
                title = "Unknown",
                year = 2025
            )
        )

        assertFalse(result)
        coVerify(exactly = 0) { traktService.checkin(any(), any()) }
    }

    @Test
    fun `simkl provider routes generic checkin to simkl service`() = runTest {
        val traktService = mockk<com.nexio.tv.data.repository.TraktScrobbleService>(relaxed = true)
        val simklService = mockk<com.nexio.tv.data.repository.SimklScrobbleService>()
        coEvery { simklService.checkin(any(), any()) } returns true
        val service = com.nexio.tv.data.repository.DefaultTrackingScrobbleService(
            traktService,
            simklService,
            trackingProviderStateService(provider = com.nexio.tv.domain.model.TrackingProvider.SIMKL)
        )

        val result = service.checkin(
            com.nexio.tv.data.repository.TrackingScrobbleItem.Movie(
                contentId = "tt1375666",
                title = "Inception",
                year = 2010
            )
        )

        assertEquals(true, result)
        coVerify(exactly = 1) { simklService.checkin(any(), any()) }
        coVerify(exactly = 0) { traktService.checkin(any(), any()) }
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
            )
        )

        service.scrobbleStart(
            com.nexio.tv.data.repository.TrackingScrobbleItem.Movie(
                contentId = "tt1375666",
                title = "Inception",
                year = 2010
            ),
            progressPercent = 12f
        )

        coVerify(exactly = 0) { traktService.scrobbleStart(any(), any()) }
        coVerify(exactly = 0) { simklService.scrobbleStart(any(), any()) }
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
            )
        )

        val result = service.checkin(
            com.nexio.tv.data.repository.TrackingScrobbleItem.Movie(
                contentId = "tt1375666",
                title = "Inception",
                year = 2010
            )
        )

        assertFalse(result)
        coVerify(exactly = 0) { traktService.checkin(any(), any()) }
    }
}
