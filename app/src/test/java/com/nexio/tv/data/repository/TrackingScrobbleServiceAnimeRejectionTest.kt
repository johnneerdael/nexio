package com.nexio.tv.data.repository

import com.nexio.tv.core.playback.PlaybackOwnerContext
import com.nexio.tv.domain.model.TrackingProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TrackingScrobbleServiceAnimeRejectionTest {

    private fun owner(profileId: Int = 1): PlaybackOwnerContext =
        PlaybackOwnerContext(
            ownerProfileId = profileId,
            ownerSessionId = "session-$profileId",
            startedAtEpochMs = 1L
        )

    private fun traktOnlyStateService(): TrackingProviderStateService =
        mockk {
            val state = EffectiveTrackingProviderState(
                effectiveProvider = TrackingProvider.TRAKT,
                traktAuthenticated = true,
                simklAuthenticated = false
            )
            coEvery { currentState() } returns state
            coEvery { currentState(any()) } returns state
            every { currentProfileId() } returns 1
            every { this@mockk.state } returns flowOf(state)
        }

    @Test
    fun `scrobbleStart with kitsu content id does not call traktScrobbleService and reports visible rejection`() = runTest {
        val traktService = mockk<TraktScrobbleService>(relaxed = true)
        val simklService = mockk<SimklScrobbleService>(relaxed = true)
        val rejectionReporter = mockk<ScrobbleRejectionReporter>(relaxed = true)

        val service = DefaultTrackingScrobbleService(
            traktService,
            simklService,
            traktOnlyStateService(),
            rejectionReporter
        )

        service.scrobbleStart(
            TrackingScrobbleItem.Episode(
                contentId = "kitsu:13881",
                showTitle = "Some Anime",
                showYear = 2023,
                season = 1,
                number = 1,
                episodeTitle = "Episode 1"
            ),
            progressPercent = 10f,
            owner = owner()
        )

        coVerify(exactly = 0) { traktService.scrobbleStart(any(), any(), any()) }
        verify(exactly = 1) {
            rejectionReporter.reportRejection(
                contentId = "kitsu:13881",
                reason = ScrobbleRejectionReason.NO_PARSEABLE_IDS,
                provider = TrackingProvider.TRAKT
            )
        }
    }
}
