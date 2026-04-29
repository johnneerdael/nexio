package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.core.integration.ProfileBoundaryEnforcer
import com.nexio.tv.core.integration.ProfileBoundaryException
import com.nexio.tv.core.integration.ProfileBoundaryViolation
import com.nexio.tv.core.playback.PlaybackOwnerContext
import com.nexio.tv.domain.model.TrackingProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TrackingScrobbleServicePlaybackOwnerTest {

    private fun owner(profileId: Int = 1, sessionId: String = "session-$profileId") =
        PlaybackOwnerContext(
            ownerProfileId = profileId,
            ownerSessionId = sessionId,
            startedAtEpochMs = 1L
        )

    @Test
    fun `scrobbleStart routes via owner profile not active profile`() = runTest {
        val trakt = mockk<TraktScrobbleService>(relaxed = true)
        val simkl = mockk<SimklScrobbleService>(relaxed = true)
        val state = mockk<TrackingProviderStateService>(relaxed = true)
        // owner = profile 1, but active profile state lookup would return 2 if asked
        coEvery { state.currentState(1) } returns EffectiveTrackingProviderState(
            effectiveProvider = TrackingProvider.TRAKT,
            traktAuthenticated = true,
            simklAuthenticated = false
        )

        val service = DefaultTrackingScrobbleService(
            traktScrobbleService = trakt,
            simklScrobbleService = simkl,
            trackingProviderStateService = state
        )

        val item = TrackingScrobbleItem.Movie(contentId = "tt1234567", title = "t", year = 2020)
        service.scrobbleStart(item = item, progressPercent = 10f, owner = owner(profileId = 1))

        // verify the underlying trakt service got ownerProfileId = 1 (owner.ownerProfileId)
        coVerify(exactly = 1) {
            trakt.scrobbleStart(any(), 10f, ownerProfileId = 1)
        }
    }

    @Test
    fun `late scrobble result with stale owner is discarded by enforcer`() {
        val ownerSession = ActiveProfileSession(profileId = 1, sessionId = "session-1", sessionOrdinal = 1L, startedAtMs = 1L)
        val activeSession = ActiveProfileSession(profileId = 2, sessionId = "session-2", sessionOrdinal = 2L, startedAtMs = 2L)
        val ex = assertThrows(ProfileBoundaryException::class.java) {
            ProfileBoundaryEnforcer.assertCanWriteProfileState(
                resultProfileId = ownerSession.profileId,
                resultSessionId = ownerSession.sessionId,
                activeProfileId = activeSession.profileId,
                activeSessionId = activeSession.sessionId
            )
        }
        assertEquals(ProfileBoundaryViolation.STALE_SESSION_WRITE_REJECTED, ex.violation)
    }
}
