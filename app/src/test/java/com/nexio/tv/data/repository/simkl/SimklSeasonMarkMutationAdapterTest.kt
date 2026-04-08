package com.nexio.tv.data.repository.simkl

import com.nexio.tv.data.remote.dto.simkl.SimklHistoryAddRequestDto
import com.nexio.tv.data.repository.ContinueWatchingSnapshotService
import com.nexio.tv.data.repository.SimklTrackingRemoteDataSource
import com.nexio.tv.data.trakt.outbox.TraktMutationExecutionResult
import com.nexio.tv.data.trakt.outbox.TraktMutationSettlement
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import javax.inject.Provider

class SimklSeasonMarkMutationAdapterTest {

    @Test
    fun `envelope keeps show season collapse key`() {
        val envelope = SimklSeasonMarkMutationAdapter.buildEnvelope(
            showContentId = "tt1520211",
            showTitle = "The Walking Dead",
            showYear = 2010,
            isAnime = false,
            seasonNumber = 2,
            episodeNumbers = listOf(1, 2, 3),
            rollbackState = ContinueWatchingSnapshotService.EpisodeRollbackState()
        )

        assertEquals("tt1520211:season:2", envelope.collapseKey)
        assertEquals(SimklSeasonMarkMutationAdapter.MUTATION_KIND, envelope.mutationKind)
    }

    @Test
    fun `execute returns success when simkl accepts season history batch`() = kotlinx.coroutines.test.runTest {
        val remote = mockk<SimklTrackingRemoteDataSource>()
        val snapshotService = mockk<ContinueWatchingSnapshotService>(relaxed = true)
        val adapter = SimklSeasonMarkMutationAdapter(remote, Provider { snapshotService })
        val envelope = SimklSeasonMarkMutationAdapter.buildEnvelope(
            showContentId = "tt1520211",
            showTitle = "The Walking Dead",
            showYear = 2010,
            isAnime = false,
            seasonNumber = 2,
            episodeNumbers = listOf(1, 2, 3),
            rollbackState = ContinueWatchingSnapshotService.EpisodeRollbackState()
        )

        coEvery { remote.addHistory(any<SimklHistoryAddRequestDto>()) } returns Response.success(Unit)

        val result = adapter.execute(envelope)

        assertTrue(result is TraktMutationExecutionResult.Success)
    }

    @Test
    fun `rollback restores continue watching rails and refreshes snapshot`() = kotlinx.coroutines.test.runTest {
        val remote = mockk<SimklTrackingRemoteDataSource>(relaxed = true)
        val snapshotService = mockk<ContinueWatchingSnapshotService>(relaxed = true)
        val adapter = SimklSeasonMarkMutationAdapter(remote, Provider { snapshotService })
        val envelope = SimklSeasonMarkMutationAdapter.buildEnvelope(
            showContentId = "tt1520211",
            showTitle = "The Walking Dead",
            showYear = 2010,
            isAnime = false,
            seasonNumber = 2,
            episodeNumbers = listOf(1, 2, 3),
            rollbackState = ContinueWatchingSnapshotService.EpisodeRollbackState()
        )

        adapter.rollbackToServerTruth(
            envelope = envelope,
            failure = TraktMutationSettlement.TerminalFailure(reason = "boom", httpStatusCode = 500)
        )

        coVerify(exactly = 1) { snapshotService.rollbackEpisodes(any<ContinueWatchingSnapshotService.EpisodeRollbackState>()) }
        coVerify(exactly = 1) { snapshotService.ensureFresh(force = true) }
    }
}
