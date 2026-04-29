package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.data.repository.trakt.TraktWatchingNowStateController
import com.nexio.tv.data.trakt.outbox.ProviderMutationOutboxCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimklScrobbleServiceProfileBoundaryTest {

    @Test
    fun `enqueueScrobble with envelope profile differing from active emits playback_scrobble_rejected`() = runTest {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        val activeProfileIdFlow = MutableStateFlow(2) // active = 2
        val profileManager = mockk<ProfileManager> {
            every { this@mockk.activeProfileId } returns activeProfileIdFlow
        }
        val trackingProviderStateService = mockk<TrackingProviderStateService>()
        coEvery { trackingProviderStateService.currentState(any()) } returns EffectiveTrackingProviderState(
            simklAuthenticated = true
        )
        val watchingNow = TraktWatchingNowStateController()
        val outbox = mockk<ProviderMutationOutboxCoordinator>(relaxed = true)
        coEvery { outbox.enqueueAndDrain(any()) } answers { firstArg() }

        val service = SimklScrobbleService(
            trackingProviderStateService = trackingProviderStateService,
            watchingNowStateController = watchingNow,
            traktMutationOutboxCoordinator = outbox,
            profileManager = profileManager,
            traceMetadataEvents = events
        )

        service.scrobbleStart(
            item = movieItem("simkl:1", "X"),
            progressPercent = 10f,
            ownerProfileId = 1
        )

        val rejected = sink.events.filter { it.eventType == "playback.scrobble_rejected" }
        assertTrue(
            "expected playback.scrobble_rejected event, got: ${sink.events.map { it.eventType }}",
            rejected.isNotEmpty()
        )
        val payload = rejected.first().payload as Map<*, *>
        assertEquals(1, payload["envelopeProfileId"])
        assertEquals(2, payload["activeProfileId"])
        assertTrue(
            "operation must start with simkl. got=${payload["operation"]}",
            (payload["operation"] as String).startsWith("simkl.")
        )

        // Enqueue is now BLOCKED on profile mismatch (F2-H-01 / F2-F-05 behaviour change).
        coVerify(exactly = 0) { outbox.enqueueAndDrain(any()) }
    }

    @Test
    fun `enqueueScrobble with matching profile does not emit playback_scrobble_rejected`() = runTest {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        val activeProfileIdFlow = MutableStateFlow(1) // active = 1
        val profileManager = mockk<ProfileManager> {
            every { this@mockk.activeProfileId } returns activeProfileIdFlow
        }
        val trackingProviderStateService = mockk<TrackingProviderStateService>()
        coEvery { trackingProviderStateService.currentState(any()) } returns EffectiveTrackingProviderState(
            simklAuthenticated = true
        )
        val watchingNow = TraktWatchingNowStateController()
        val outbox = mockk<ProviderMutationOutboxCoordinator>(relaxed = true)
        coEvery { outbox.enqueueAndDrain(any()) } answers { firstArg() }

        val service = SimklScrobbleService(
            trackingProviderStateService = trackingProviderStateService,
            watchingNowStateController = watchingNow,
            traktMutationOutboxCoordinator = outbox,
            profileManager = profileManager,
            traceMetadataEvents = events
        )

        service.scrobbleStart(
            item = movieItem("simkl:1", "X"),
            progressPercent = 10f,
            ownerProfileId = 1
        )

        val rejected = sink.events.filter { it.eventType == "playback.scrobble_rejected" }
        assertTrue(
            "must not emit when matching, got ${sink.events.map { it.eventType }}",
            rejected.isEmpty()
        )
    }

    private fun movieItem(contentId: String, title: String) = TrackingScrobbleItem.Movie(
        contentId = contentId,
        title = title,
        year = 2020
    )
}
