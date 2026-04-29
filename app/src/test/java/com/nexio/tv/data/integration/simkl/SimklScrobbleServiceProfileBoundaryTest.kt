package com.nexio.tv.data.integration.simkl

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.data.repository.EffectiveTrackingProviderState
import com.nexio.tv.data.repository.SimklScrobbleService
import com.nexio.tv.data.repository.TrackingProviderStateService
import com.nexio.tv.data.repository.TrackingScrobbleItem
import com.nexio.tv.data.repository.trakt.TraktWatchingNowStateController
import com.nexio.tv.data.trakt.outbox.ProviderMutationOutboxCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * F2-H-01 RED pin: when the playback owner profile differs from the active profile at scrobble
 * enqueue time, checkScrobbleBoundary MUST cause the enqueue to be SKIPPED — telemetry-only is
 * not enough. The mutation MUST NOT reach the outbox coordinator.
 *
 * SimklScrobbleService uses the same ProviderMutationOutboxCoordinator as TraktScrobbleService
 * (traktMutationOutboxCoordinator parameter), not a separate Simkl-specific outbox.
 *
 * These tests currently FAIL (red) because checkScrobbleBoundary is telemetry-only.
 * Task 6 will convert checkScrobbleBoundary to return Boolean and gate the callers,
 * at which point these tests will turn green.
 */
class SimklScrobbleServiceProfileBoundaryTest {

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun makeService(
        activeProfileId: Int,
        ownerProfileId: Int,
        outbox: ProviderMutationOutboxCoordinator
    ): SimklScrobbleService {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s-simkl-test" })
        val activeProfileIdFlow = MutableStateFlow(activeProfileId)
        val profileManager = mockk<ProfileManager> {
            every { this@mockk.activeProfileId } returns activeProfileIdFlow
        }
        val trackingProviderStateService = mockk<TrackingProviderStateService> {
            coEvery { currentState(any()) } returns EffectiveTrackingProviderState(simklAuthenticated = true)
            every { currentProfileId() } returns ownerProfileId
        }
        val watchingNow = TraktWatchingNowStateController()
        return SimklScrobbleService(
            trackingProviderStateService = trackingProviderStateService,
            watchingNowStateController = watchingNow,
            traktMutationOutboxCoordinator = outbox,
            profileManager = profileManager,
            traceMetadataEvents = events
        )
    }

    private fun stubMovieItem(): TrackingScrobbleItem = TrackingScrobbleItem.Movie(
        contentId = "simkl:12345",
        title = "Test Movie",
        year = 2024
    )

    // ── scrobbleStart — profile mismatch MUST block enqueue ─────────────────

    @Test
    fun `mismatched profile blocks scrobbleStart enqueue`() = runTest {
        val outbox = mockk<ProviderMutationOutboxCoordinator>(relaxed = true)
        coEvery { outbox.enqueueAndDrain(any()) } answers { firstArg() }

        // active = 2, owner = 1 → mismatch
        val service = makeService(activeProfileId = 2, ownerProfileId = 1, outbox = outbox)

        service.scrobbleStart(
            item = stubMovieItem(),
            progressPercent = 10f,
            ownerProfileId = 1
        )

        // RED: currently fails because enqueue proceeds despite mismatch
        coVerify(exactly = 0) { outbox.enqueueAndDrain(any()) }
    }

    @Test
    fun `matched profile permits scrobbleStart enqueue`() = runTest {
        val outbox = mockk<ProviderMutationOutboxCoordinator>(relaxed = true)
        coEvery { outbox.enqueueAndDrain(any()) } answers { firstArg() }

        // active = 1, owner = 1 → match
        val service = makeService(activeProfileId = 1, ownerProfileId = 1, outbox = outbox)

        service.scrobbleStart(
            item = stubMovieItem(),
            progressPercent = 10f,
            ownerProfileId = 1
        )

        coVerify(exactly = 1) { outbox.enqueueAndDrain(any()) }
    }

    // ── scrobbleStop — profile mismatch MUST block enqueue ──────────────────

    @Test
    fun `mismatched profile blocks scrobbleStop enqueue`() = runTest {
        val outbox = mockk<ProviderMutationOutboxCoordinator>(relaxed = true)
        coEvery { outbox.enqueueAndDrain(any()) } answers { firstArg() }

        // active = 2, owner = 1 → mismatch
        val service = makeService(activeProfileId = 2, ownerProfileId = 1, outbox = outbox)

        service.scrobbleStop(
            item = stubMovieItem(),
            progressPercent = 95f,
            ownerProfileId = 1
        )

        // RED: currently fails because enqueue proceeds despite mismatch
        coVerify(exactly = 0) { outbox.enqueueAndDrain(any()) }
    }

    // ── checkin — profile mismatch MUST block enqueue ───────────────────────

    @Test
    fun `mismatched profile blocks checkin enqueue`() = runTest {
        val outbox = mockk<ProviderMutationOutboxCoordinator>(relaxed = true)
        coEvery { outbox.enqueueAndDrain(any()) } answers { firstArg() }

        // active = 3, owner = 1 → mismatch
        val service = makeService(activeProfileId = 3, ownerProfileId = 1, outbox = outbox)

        service.checkin(
            item = stubMovieItem(),
            ownerProfileId = 1
        )

        // RED: currently fails because enqueue proceeds despite mismatch
        coVerify(exactly = 0) { outbox.enqueueAndDrain(any()) }
    }

    @Test
    fun `matched profile permits checkin enqueue`() = runTest {
        val outbox = mockk<ProviderMutationOutboxCoordinator>(relaxed = true)
        coEvery { outbox.enqueueAndDrain(any()) } answers { firstArg() }

        // active = 1, owner = 1 → match
        val service = makeService(activeProfileId = 1, ownerProfileId = 1, outbox = outbox)

        service.checkin(
            item = stubMovieItem(),
            ownerProfileId = 1
        )

        coVerify(exactly = 1) { outbox.enqueueAndDrain(any()) }
    }
}
