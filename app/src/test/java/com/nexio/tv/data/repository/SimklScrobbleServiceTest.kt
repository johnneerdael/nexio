package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.data.repository.trakt.TraktWatchingNowStateController
import com.nexio.tv.data.trakt.outbox.TraktMutationEnvelope
import com.nexio.tv.data.trakt.outbox.ProviderMutationOutboxCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SimklScrobbleServiceTest {

    @Test
    fun `duplicate scrobble suppression is scoped by owner profile`() = runTest {
        val trackingProviderStateService = mockk<TrackingProviderStateService>()
        val coordinator = mockk<ProviderMutationOutboxCoordinator>()
        val controller = TraktWatchingNowStateController()
        val envelopes = mutableListOf<TraktMutationEnvelope>()

        coEvery { trackingProviderStateService.currentState(any()) } returns EffectiveTrackingProviderState(
            simklAuthenticated = true
        )
        coEvery { coordinator.enqueueAndDrain(capture(envelopes)) } answers { firstArg() }

        val activeProfileIdFlow = MutableStateFlow(1)
        val activeProfileSessionFlow = MutableStateFlow(
            ActiveProfileSession(profileId = 1, sessionId = "session-1", sessionOrdinal = 1L, startedAtMs = 1_000L)
        )
        val profileManager = mockk<ProfileManager> {
            every { activeProfileId } returns activeProfileIdFlow
            every { activeProfileSession } returns activeProfileSessionFlow
        }
        val service = SimklScrobbleService(
            trackingProviderStateService = trackingProviderStateService,
            watchingNowStateController = controller,
            traktMutationOutboxCoordinator = coordinator,
            profileManager = profileManager,
            traceMetadataEvents = TraceMetadataEvents(NoopRuntimeTraceSink, sessionId = { null })
        )
        val item = movieItem("simkl:12345", "Arrival")

        // First call: ownerProfileId=1 matches active=1 → passes boundary, enqueues
        service.scrobbleStart(item, progressPercent = 12f, ownerProfileId = 1)
        // Switch active profile so second call's ownerProfileId=2 also passes boundary
        activeProfileIdFlow.value = 2
        activeProfileSessionFlow.value = ActiveProfileSession(profileId = 2, sessionId = "session-2", sessionOrdinal = 1L, startedAtMs = 1_000L)
        service.scrobbleStart(item, progressPercent = 12f, ownerProfileId = 2)

        assertEquals(listOf(1, 2), envelopes.map { it.profileId })
        coVerify(exactly = 2) { coordinator.enqueueAndDrain(any()) }
    }

    private fun movieItem(contentId: String, title: String) = TrackingScrobbleItem.Movie(
        contentId = contentId,
        title = title,
        year = 2025
    )
}
