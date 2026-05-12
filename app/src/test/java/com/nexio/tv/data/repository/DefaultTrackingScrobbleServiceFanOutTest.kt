package com.nexio.tv.data.repository

import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.core.anime.projection.AnimeSeasonProjectionResolver
import com.nexio.tv.core.playback.PlaybackOwnerContext
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.TrackingProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultTrackingScrobbleServiceFanOutTest {

    private val traktService = mockk<TraktScrobbleService>(relaxed = true)
    private val simklService = mockk<SimklScrobbleService>(relaxed = true)
    private val providerStateService = mockk<TrackingProviderStateService>()
    private val rejectionReporter = mockk<ScrobbleRejectionReporter>(relaxed = true)
    private val animeProjectionResolver = mockk<AnimeSeasonProjectionResolver>(relaxed = true)
    private val animeIdMappingService = mockk<AnimeIdMappingService>(relaxed = true)

    private fun newService(): DefaultTrackingScrobbleService = DefaultTrackingScrobbleService(
        traktScrobbleService = traktService,
        simklScrobbleService = simklService,
        trackingProviderStateService = providerStateService,
        rejectionReporter = rejectionReporter,
        animeSeasonProjectionResolver = animeProjectionResolver,
        idMappingService = animeIdMappingService,
    )

    private fun owner(profileId: Int = 1, sessionId: String = "session-x") = PlaybackOwnerContext(
        ownerProfileId = profileId,
        ownerSessionId = sessionId,
        startedAtEpochMs = 1L,
    )

    private fun movieItem() = TrackingScrobbleItem.Movie(
        contentId = "tt0903747",
        title = "Breaking Bad",
        year = 2008,
    )

    private fun stubState(trakt: Boolean, simkl: Boolean) {
        val state = EffectiveTrackingProviderState(
            storedProvider = TrackingProvider.TRAKT,
            effectiveProvider = TrackingProvider.TRAKT,
            traktAuthenticated = trakt,
            simklAuthenticated = simkl,
        )
        coEvery { providerStateService.currentState(any<Int>()) } returns state
        coEvery { providerStateService.currentState() } returns state
    }

    @Test
    fun `scrobbleStart dispatches to both providers when both authed`() = runTest {
        stubState(trakt = true, simkl = true)
        newService().scrobbleStart(movieItem(), 10f, owner())

        coVerify(exactly = 1) { traktService.scrobbleStart(any(), 10f, 1, any()) }
        coVerify(exactly = 1) { simklService.scrobbleStart(any(), 10f, 1, any()) }
    }

    @Test
    fun `scrobbleStart dispatches only to Simkl when only Simkl authed`() = runTest {
        stubState(trakt = false, simkl = true)
        newService().scrobbleStart(movieItem(), 10f, owner())

        coVerify(exactly = 0) { traktService.scrobbleStart(any(), any(), any(), any()) }
        coVerify(exactly = 1) { simklService.scrobbleStart(any(), 10f, 1, any()) }
    }

    @Test
    fun `scrobbleStart dispatches only to Trakt when only Trakt authed`() = runTest {
        stubState(trakt = true, simkl = false)
        newService().scrobbleStart(movieItem(), 10f, owner())

        coVerify(exactly = 1) { traktService.scrobbleStart(any(), 10f, 1, any()) }
        coVerify(exactly = 0) { simklService.scrobbleStart(any(), any(), any(), any()) }
    }

    @Test
    fun `scrobbleStart no-ops when no provider authed`() = runTest {
        stubState(trakt = false, simkl = false)
        newService().scrobbleStart(movieItem(), 10f, owner())

        coVerify(exactly = 0) { traktService.scrobbleStart(any(), any(), any(), any()) }
        coVerify(exactly = 0) { simklService.scrobbleStart(any(), any(), any(), any()) }
    }

    @Test
    fun `scrobbleStop fans out`() = runTest {
        stubState(trakt = true, simkl = true)
        newService().scrobbleStop(movieItem(), 95f, owner())

        coVerify(exactly = 1) { traktService.scrobbleStop(any(), 95f, 1, any()) }
        coVerify(exactly = 1) { simklService.scrobbleStop(any(), 95f, 1, any()) }
    }

    @Test
    fun `scrobblePause fans out`() = runTest {
        stubState(trakt = true, simkl = true)
        newService().scrobblePause(movieItem(), 50f, owner())

        coVerify(exactly = 1) { traktService.scrobblePause(any(), 50f, 1, any()) }
        coVerify(exactly = 1) { simklService.scrobblePause(any(), 50f, 1, any()) }
    }

    @Test
    fun `checkin returns true when either provider returns true`() = runTest {
        stubState(trakt = true, simkl = true)
        coEvery { traktService.checkin(any(), any(), any(), any()) } returns false
        coEvery { simklService.checkin(any(), any(), any(), any()) } returns true

        val result = newService().checkin(movieItem(), message = null, ownerProfileId = 1)
        assert(result)
    }

    @Test
    fun `checkin returns false when both providers return false`() = runTest {
        stubState(trakt = true, simkl = true)
        coEvery { traktService.checkin(any(), any(), any(), any()) } returns false
        coEvery { simklService.checkin(any(), any(), any(), any()) } returns false

        val result = newService().checkin(movieItem(), message = null, ownerProfileId = 1)
        assert(!result)
    }

    @Test
    fun `Trakt scrobble uses hydratedIds when present`() = runTest {
        stubState(trakt = true, simkl = false)
        val captured = slot<TraktScrobbleItem>()
        coEvery { traktService.scrobbleStart(capture(captured), any(), any(), any()) } returns Unit

        val item = TrackingScrobbleItem.Movie(
            contentId = "tmdb:1396",
            title = "Breaking Bad",
            year = 2008,
            hydratedIds = ProviderIds(imdb = "tt0903747", tmdb = "1396", tvdb = "81189"),
        )
        newService().scrobbleStart(item, 10f, owner())

        val sent = captured.captured as TraktScrobbleItem.Movie
        assertEquals("tt0903747", sent.ids.imdb)
        assertEquals(1396, sent.ids.tmdb)
        assertEquals(81189, sent.ids.tvdb)
    }

    @Test
    fun `anime scrobble continues to Simkl even when Trakt anime projection fails`() = runTest {
        stubState(trakt = true, simkl = true)
        // Force Trakt anime projection to fail by returning null from the resolver lookup.
        coEvery { animeIdMappingService.resolveKitsuId(any(), any()) } returns null

        val item = TrackingScrobbleItem.Episode(
            contentId = "mal:21",
            showTitle = "Cowboy Bebop",
            showYear = 1998,
            season = 1, number = 5,
            episodeTitle = "Ballad of Fallen Angels",
            hydratedIds = ProviderIds(
                kitsu = "1", mal = "21", anilist = "1", anidb = "10",
                imdb = "tt0213338", tmdb = "30991",
            ),
        )

        newService().scrobbleStart(item, 10f, owner())

        // Trakt skipped (projection failed). Simkl still fires with the anime-aware item.
        coVerify(exactly = 0) { traktService.scrobbleStart(any(), any(), any(), any()) }
        coVerify(exactly = 1) { simklService.scrobbleStart(any(), 10f, 1, any()) }
    }

    @Test
    fun `Trakt scrobble falls back to contentId parse when hydratedIds is null`() = runTest {
        stubState(trakt = true, simkl = false)
        val captured = slot<TraktScrobbleItem>()
        coEvery { traktService.scrobbleStart(capture(captured), any(), any(), any()) } returns Unit

        val item = TrackingScrobbleItem.Movie(
            contentId = "tt9999999",
            title = "x",
            year = null,
            hydratedIds = null,
        )
        newService().scrobbleStart(item, 10f, owner())

        val sent = captured.captured as TraktScrobbleItem.Movie
        assertEquals("tt9999999", sent.ids.imdb)
    }
}
