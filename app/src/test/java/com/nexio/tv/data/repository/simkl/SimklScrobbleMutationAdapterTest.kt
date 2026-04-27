package com.nexio.tv.data.repository.simkl

import com.nexio.tv.data.remote.dto.simkl.SimklScrobbleResponseDto
import com.nexio.tv.data.repository.SimklProgressService
import com.nexio.tv.data.repository.SimklTrackingRemoteDataSource
import com.nexio.tv.data.repository.TrackingAuthSession
import com.nexio.tv.data.repository.TrackingScrobbleItem
import com.nexio.tv.data.repository.trakt.TraktWatchingNowStateController
import com.nexio.tv.data.trakt.outbox.TraktMutationExecutionResult
import com.nexio.tv.data.trakt.outbox.TraktMutationSettlement
import com.nexio.tv.domain.model.TrackingProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class SimklScrobbleMutationAdapterTest {

    @Test
    fun `scrobble envelope keeps item collapse key and rollback metadata`() {
        val envelope = SimklScrobbleMutationAdapter.buildScrobbleEnvelope(
            item = movieItem("Arrival"),
            action = "start",
            progressPercent = 14f,
            rollbackState = TraktWatchingNowStateController.Snapshot(
                active = false,
                title = "Server truth",
                contentType = "movie"
            ),
            optimisticVersion = 7L
        )

        assertEquals("simkl.scrobble:movie:tt1234567:2025", envelope.collapseKey)
        assertEquals(SimklScrobbleMutationAdapter.MUTATION_KIND_SCROBBLE, envelope.mutationKind)
    }

    @Test
    fun `adapter rolls back watching state when terminal failure settles`() = kotlinx.coroutines.test.runTest {
        val remote = mockk<SimklTrackingRemoteDataSource>(relaxed = true)
        val progressService = mockk<SimklProgressService>(relaxed = true)
        val controller = TraktWatchingNowStateController()
        val adapter = SimklScrobbleMutationAdapter(remote, progressService, controller)
        controller.publish(
            TraktWatchingNowStateController.Snapshot(
                active = true,
                title = "Arrival",
                contentType = "movie"
            )
        )
        val envelope = SimklScrobbleMutationAdapter.buildCheckinEnvelope(
            item = movieItem("Arrival"),
            message = null,
            rollbackState = TraktWatchingNowStateController.Snapshot(),
            optimisticVersion = controller.nextOptimisticVersion()
        )

        adapter.rollbackToServerTruth(
            envelope = envelope,
            failure = TraktMutationSettlement.TerminalFailure(reason = "boom", httpStatusCode = 500)
        )

        assertEquals(null, controller.current().title)
        assertTrue(!controller.current().active)
    }

    @Test
    fun `checkin execute returns success when simkl accepts request`() = kotlinx.coroutines.test.runTest {
        val remote = mockk<SimklTrackingRemoteDataSource>()
        val progressService = mockk<SimklProgressService>(relaxed = true)
        val controller = TraktWatchingNowStateController()
        val adapter = SimklScrobbleMutationAdapter(remote, progressService, controller)
        val session = slot<TrackingAuthSession>()
        val envelope = SimklScrobbleMutationAdapter.buildCheckinEnvelope(
            item = movieItem("Arrival"),
            message = null,
            rollbackState = TraktWatchingNowStateController.Snapshot(),
            optimisticVersion = 1L,
            profileId = 2
        )

        coEvery { remote.checkin(any(), capture(session)) } returns Response.success(SimklScrobbleResponseDto(action = "checkin"))

        val result = adapter.execute(envelope)

        assertTrue(result is TraktMutationExecutionResult.Success)
        assertEquals(TrackingProvider.SIMKL, session.captured.provider)
        assertEquals(2, session.captured.profileId)
        coVerify(exactly = 0) { progressService.refreshNow() }
    }

    @Test
    fun `reconcile success refreshes simkl progress state`() = kotlinx.coroutines.test.runTest {
        val remote = mockk<SimklTrackingRemoteDataSource>(relaxed = true)
        val progressService = mockk<SimklProgressService>(relaxed = true)
        val controller = TraktWatchingNowStateController()
        val adapter = SimklScrobbleMutationAdapter(remote, progressService, controller)
        val envelope = SimklScrobbleMutationAdapter.buildScrobbleEnvelope(
            item = movieItem("Arrival"),
            action = "stop",
            progressPercent = 95f,
            rollbackState = TraktWatchingNowStateController.Snapshot(),
            optimisticVersion = 1L
        )

        adapter.reconcileSuccess(envelope)

        coVerify(exactly = 1) { progressService.refreshNow() }
    }

    private fun movieItem(title: String) = TrackingScrobbleItem.Movie(
        contentId = "tt1234567",
        title = title,
        year = 2025
    )
}
