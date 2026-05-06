package com.nexio.tv.data.repository.simkl

import com.nexio.tv.data.repository.testSimklSession
import com.nexio.tv.data.repository.SimklProgressService
import com.nexio.tv.data.repository.SimklTrackingRemoteDataSource
import com.nexio.tv.data.trakt.outbox.TraktMutationExecutionResult
import com.nexio.tv.data.trakt.outbox.TraktMutationSettlement
import com.nexio.tv.domain.model.WatchProgress
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class SimklProgressHistoryMutationAdapterTest {

    @Test
    fun `history add envelope keeps episode identity in collapse key`() {
        val envelope = SimklProgressHistoryMutationAdapter.buildHistoryAddEnvelope(
            progress = sampleProgress(),
            title = "Episode",
            year = 2025,
            session = testSimklSession()
        )

        assertEquals("show:s1:e2", envelope.collapseKey)
        assertEquals(
            SimklProgressHistoryMutationAdapter.MUTATION_KIND_HISTORY_ADD,
            envelope.mutationKind
        )
    }

    @Test
    fun `apply optimistic and rollback delegate to simkl progress service`() = kotlinx.coroutines.test.runTest {
        val remote = mockk<SimklTrackingRemoteDataSource>(relaxed = true)
        val progressService = mockk<SimklProgressService>(relaxed = true)
        val adapter = SimklProgressHistoryMutationAdapter(remote, progressService)
        val envelope = SimklProgressHistoryMutationAdapter.buildHistoryAddEnvelope(
            progress = sampleProgress(),
            title = "Episode",
            year = 2025,
            session = testSimklSession()
        )

        adapter.applyOptimistic(envelope)
        adapter.rollbackToServerTruth(
            envelope = envelope,
            failure = TraktMutationSettlement.TerminalFailure(reason = "boom", httpStatusCode = 404)
        )

        coVerify(exactly = 1) { progressService.applyOptimisticProgress(any()) }
        coVerify(exactly = 1) { progressService.rollbackQueuedHistoryRemove(any(), any(), any(), any()) }
    }

    @Test
    fun `execute returns success when simkl accepts history add`() = kotlinx.coroutines.test.runTest {
        val remote = mockk<SimklTrackingRemoteDataSource>()
        val progressService = mockk<SimklProgressService>(relaxed = true)
        val adapter = SimklProgressHistoryMutationAdapter(remote, progressService)
        val envelope = SimklProgressHistoryMutationAdapter.buildHistoryAddEnvelope(
            progress = sampleProgress(),
            title = "Episode",
            year = 2025,
            session = testSimklSession()
        )

        coEvery { remote.addHistory(any()) } returns Response.success(Unit)

        val result = adapter.execute(envelope)

        assertTrue(result is TraktMutationExecutionResult.Success)
    }

    @Test
    fun `execute returns success when simkl accepts playback delete`() = kotlinx.coroutines.test.runTest {
        val remote = mockk<SimklTrackingRemoteDataSource>()
        val progressService = mockk<SimklProgressService>(relaxed = true)
        val adapter = SimklProgressHistoryMutationAdapter(remote, progressService)
        val envelope = SimklProgressHistoryMutationAdapter.buildPlaybackDeleteEnvelope(
            playbackId = 123L,
            contentId = "show",
            season = 1,
            episode = 2,
            session = testSimklSession()
        )

        coEvery { remote.deletePlayback(any()) } returns Response.success(Unit)

        val result = adapter.execute(envelope)

        assertTrue(result is TraktMutationExecutionResult.Success)
    }

    @Test
    fun `reconcile success refreshes simkl progress state`() = kotlinx.coroutines.test.runTest {
        val remote = mockk<SimklTrackingRemoteDataSource>(relaxed = true)
        val progressService = mockk<SimklProgressService>(relaxed = true)
        val adapter = SimklProgressHistoryMutationAdapter(remote, progressService)
        val envelope = SimklProgressHistoryMutationAdapter.buildHistoryRemoveEnvelope(
            contentId = "show",
            season = 1,
            episode = 2,
            session = testSimklSession()
        )

        adapter.reconcileSuccess(envelope)

        coVerify(exactly = 1) { progressService.refreshNow() }
    }

    private fun sampleProgress(): WatchProgress {
        return WatchProgress(
            contentId = "show",
            contentType = "series",
            name = "Episode",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "show:1:2",
            season = 1,
            episode = 2,
            episodeTitle = "Episode 2",
            position = 1L,
            duration = 1L,
            lastWatched = 1234L,
            progressPercent = 100f
        )
    }
}
