package com.nexio.tv.data.repository

import com.nexio.tv.data.repository.trakt.TraktWatchingNowStateController
import com.nexio.tv.data.trakt.outbox.TraktMutationEnvelope
import com.nexio.tv.data.trakt.outbox.ProviderMutationOutboxCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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

        val service = SimklScrobbleService(
            trackingProviderStateService = trackingProviderStateService,
            watchingNowStateController = controller,
            traktMutationOutboxCoordinator = coordinator
        )
        val item = movieItem("simkl:12345", "Arrival")

        service.scrobbleStart(item, progressPercent = 12f, ownerProfileId = 1)
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
