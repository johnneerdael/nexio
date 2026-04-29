package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.core.integration.ProfileBoundaryEnforcer
import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.data.local.TraktAuthState
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.repository.trakt.TraktWatchingNowStateController
import com.nexio.tv.data.trakt.outbox.ProviderMutationOutboxCoordinator
import com.nexio.tv.domain.model.TrackingProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * F2-H-02 pin: a late-arriving scrobble result for a *stale session* of the same profile
 * MUST be rejected at result-completion time.
 *
 * The scenario: user is on profile 1 (session "s1"). Playback starts and a scrobble request is
 * enqueued. The user then switches away and back — now on profile 1 again but in a new session
 * "s2". The network response for the original scrobble request (owner session "s1") arrives and
 * tries to write to the outbox. Because the active profile ID is still 1, the current
 * [checkScrobbleBoundary] implementation (which only compares profile IDs) does NOT reject the
 * stale write. This is the bug.
 *
 * After Task 8 adds a session-level recheck via
 * [ProfileBoundaryEnforcer.assertCanWriteProfileState], the outbox enqueue will be blocked when
 * the result session does not match the current active session, even if the profile ID matches.
 *
 * This test is intentionally RED: the "stale session, same profile" case currently lets the
 * outbox write proceed (coVerify exactly = 0 fails), demonstrating the missing recheck.
 *
 * Companion to F2-H-01 tests in [TraktScrobbleServiceProfileBoundaryTest] /
 * [SimklScrobbleServiceProfileBoundaryTest], which gate the *enqueue* time.
 */
class TrackingProgressServiceProfileBoundaryRecheckTest {

    /**
     * F2-H-02 core scenario:
     * ownerProfileId == activeProfileId (both 1) but sessions differ ("s1" vs "s2").
     *
     * Current behaviour (bug): outbox.enqueueAndDrain IS called because checkScrobbleBoundary
     * only compares profile IDs.
     *
     * Expected behaviour after Task 8: outbox.enqueueAndDrain must NOT be called — the
     * session-level assertCanWriteProfileState recheck rejects the stale result.
     *
     * RED: coVerify(exactly = 0) will fail today, confirming the missing recheck.
     */
    @Test
    fun `late scrobble from stale session is rejected even when profile id still matches`() = runTest {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s2" })

        // Active profile = 1, but the active *session* has already advanced to "s2"
        // (e.g. the user left profile 1, returned to profile 1, creating a new session).
        val activeProfileIdFlow = MutableStateFlow(1)
        val activeProfileSessionFlow = MutableStateFlow(
            ActiveProfileSession(
                profileId = 1,
                sessionId = "s2",   // ← new session for same profile
                sessionOrdinal = 2L,
                startedAtMs = 2_000L
            )
        )
        val profileManager = mockk<ProfileManager> {
            every { this@mockk.activeProfileId } returns activeProfileIdFlow
            every { this@mockk.activeProfileSession } returns activeProfileSessionFlow
        }
        val authService = mockk<TraktAuthService> {
            coEvery { getAuthState(any()) } returns authenticatedState()
            every { hasRequiredCredentials() } returns true
            // The auth session for profile 1 is resolved to the *original* (stale) session "s1"
            every { currentAuthSession() } returns TrackingAuthSession(TrackingProvider.TRAKT, 1)
        }
        val authGateway = TraktRepositoryAuthGateway(authService)
        val watchingNow = TraktWatchingNowStateController()
        val outbox = mockk<ProviderMutationOutboxCoordinator>(relaxed = true)
        coEvery { outbox.enqueueAndDrain(any()) } answers { firstArg() }

        val service = TraktScrobbleService(
            traktAuthService = authGateway,
            watchingNowStateController = watchingNow,
            traktMutationOutboxCoordinator = outbox,
            profileManager = profileManager,
            traceMetadataEvents = events
        )

        // ownerProfileId = 1 matches active profile id = 1, but ownerSession = "s1" is stale.
        // The scrobble result "belongs" to the original session "s1" for profile 1.
        // After Task 8, ownerSessionId must be passed explicitly so the session-level recheck
        // can detect the mismatch ("s1" != "s2") even though the profile IDs match.
        service.scrobbleStop(
            item = movieItem("Inception"),
            progressPercent = 95f,
            ownerProfileId = 1,         // same profile — this is what makes it an F2-H-02 case
            ownerSessionId = "s1"       // stale session — differs from active session "s2"
        )

        // assertCanWriteProfileState(resultSession="s1", activeSession="s2") throws
        // ProfileBoundaryException → checkScrobbleBoundary returns false → outbox not called.
        coVerify(exactly = 0) { outbox.enqueueAndDrain(any()) }
    }

    /**
     * Sanity / green path: same profile AND same session — write must proceed.
     *
     * This test must remain green both before and after Task 8.
     */
    @Test
    fun `fresh scrobble for current profile and current session writes to outbox`() = runTest {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })

        val activeProfileIdFlow = MutableStateFlow(1)
        val activeProfileSessionFlow = MutableStateFlow(
            ActiveProfileSession(
                profileId = 1,
                sessionId = "s1",
                sessionOrdinal = 1L,
                startedAtMs = 1_000L
            )
        )
        val profileManager = mockk<ProfileManager> {
            every { this@mockk.activeProfileId } returns activeProfileIdFlow
            every { this@mockk.activeProfileSession } returns activeProfileSessionFlow
        }
        val authService = mockk<TraktAuthService> {
            coEvery { getAuthState(any()) } returns authenticatedState()
            every { hasRequiredCredentials() } returns true
            every { currentAuthSession() } returns TrackingAuthSession(TrackingProvider.TRAKT, 1)
        }
        val authGateway = TraktRepositoryAuthGateway(authService)
        val watchingNow = TraktWatchingNowStateController()
        val outbox = mockk<ProviderMutationOutboxCoordinator>(relaxed = true)
        coEvery { outbox.enqueueAndDrain(any()) } answers { firstArg() }

        val service = TraktScrobbleService(
            traktAuthService = authGateway,
            watchingNowStateController = watchingNow,
            traktMutationOutboxCoordinator = outbox,
            profileManager = profileManager,
            traceMetadataEvents = events
        )

        service.scrobbleStop(
            item = movieItem("Inception"),
            progressPercent = 95f,
            ownerProfileId = 1
        )

        // Same profile, same session — outbox must be called exactly once.
        coVerify(exactly = 1) { outbox.enqueueAndDrain(any()) }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun movieItem(title: String) = TraktScrobbleItem.Movie(
        title = title,
        year = 2010,
        ids = TraktIdsDto(imdb = "tt1375666")
    )

    private fun authenticatedState() = TraktAuthState(
        accessToken = "access",
        refreshToken = "refresh"
    )
}
