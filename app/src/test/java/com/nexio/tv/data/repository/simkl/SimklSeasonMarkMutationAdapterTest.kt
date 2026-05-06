package com.nexio.tv.data.repository.simkl

import com.nexio.tv.data.repository.testSimklSession
import com.nexio.tv.data.remote.dto.simkl.SimklHistoryAddRequestDto
import com.nexio.tv.data.repository.ContinueWatchingSnapshotService
import com.nexio.tv.data.repository.SimklTrackingRemoteDataSource
import com.nexio.tv.data.repository.TrackingProgressService
import com.nexio.tv.data.trakt.outbox.TraktMutationExecutionResult
import com.nexio.tv.data.trakt.outbox.TraktMutationSettlement
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import javax.inject.Provider

class SimklSeasonMarkMutationAdapterTest {

    @Test
    fun `applyOptimistic pushes completed progress for each season episode`() = kotlinx.coroutines.test.runTest {
        val remote = mockk<SimklTrackingRemoteDataSource>(relaxed = true)
        val snapshotService = mockk<ContinueWatchingSnapshotService>(relaxed = true)
        val trackingProgressService = mockk<TrackingProgressService>(relaxed = true)
        val adapter = SimklSeasonMarkMutationAdapter(
            remote = remote,
            snapshotServiceProvider = Provider { snapshotService },
            trackingProgressService = trackingProgressService
        )
        val envelope = SimklSeasonMarkMutationAdapter.buildEnvelope(
            showContentId = "tt1520211",
            showTitle = "The Walking Dead",
            showYear = 2010,
            isAnime = false,
            seasonNumber = 2,
            episodeNumbers = listOf(1, 2, 3),
            rollbackState = ContinueWatchingSnapshotService.EpisodeRollbackState(),
            session = testSimklSession()
        )

        adapter.applyOptimistic(envelope)

        verify(exactly = 3) { trackingProgressService.applyOptimisticProgress(any()) }
        verify {
            trackingProgressService.applyOptimisticProgress(
                match {
                    it.contentId == "tt1520211" &&
                        it.contentType == "series" &&
                        it.season == 2 &&
                        it.episode == 1 &&
                        it.progressPercent == 100f
                }
            )
        }
        verify {
            trackingProgressService.applyOptimisticProgress(
                match {
                    it.contentId == "tt1520211" &&
                        it.season == 2 &&
                        it.episode == 2
                }
            )
        }
        verify {
            trackingProgressService.applyOptimisticProgress(
                match {
                    it.contentId == "tt1520211" &&
                        it.season == 2 &&
                        it.episode == 3
                }
            )
        }
    }

    @Test
    fun `envelope keeps show season collapse key`() {
        val envelope = SimklSeasonMarkMutationAdapter.buildEnvelope(
            showContentId = "tt1520211",
            showTitle = "The Walking Dead",
            showYear = 2010,
            isAnime = false,
            seasonNumber = 2,
            episodeNumbers = listOf(1, 2, 3),
            rollbackState = ContinueWatchingSnapshotService.EpisodeRollbackState(),
            session = testSimklSession()
        )

        assertEquals("tt1520211:season:2", envelope.collapseKey)
        assertEquals(SimklSeasonMarkMutationAdapter.MUTATION_KIND, envelope.mutationKind)
    }

    @Test
    fun `execute returns success when simkl accepts season history batch`() = kotlinx.coroutines.test.runTest {
        val remote = mockk<SimklTrackingRemoteDataSource>()
        val snapshotService = mockk<ContinueWatchingSnapshotService>(relaxed = true)
        val trackingProgressService = mockk<TrackingProgressService>(relaxed = true)
        val adapter = SimklSeasonMarkMutationAdapter(remote, Provider { snapshotService }, trackingProgressService)
        val envelope = SimklSeasonMarkMutationAdapter.buildEnvelope(
            showContentId = "tt1520211",
            showTitle = "The Walking Dead",
            showYear = 2010,
            isAnime = false,
            seasonNumber = 2,
            episodeNumbers = listOf(1, 2, 3),
            rollbackState = ContinueWatchingSnapshotService.EpisodeRollbackState(),
            session = testSimklSession()
        )

        coEvery { remote.addHistory(any<SimklHistoryAddRequestDto>()) } returns Response.success(Unit)

        val result = adapter.execute(envelope)

        assertTrue(result is TraktMutationExecutionResult.Success)
    }

    @Test
    fun `rollback restores continue watching rails and refreshes snapshot`() = kotlinx.coroutines.test.runTest {
        val remote = mockk<SimklTrackingRemoteDataSource>(relaxed = true)
        val snapshotService = mockk<ContinueWatchingSnapshotService>(relaxed = true)
        val trackingProgressService = mockk<TrackingProgressService>(relaxed = true)
        val adapter = SimklSeasonMarkMutationAdapter(remote, Provider { snapshotService }, trackingProgressService)
        val envelope = SimklSeasonMarkMutationAdapter.buildEnvelope(
            showContentId = "tt1520211",
            showTitle = "The Walking Dead",
            showYear = 2010,
            isAnime = false,
            seasonNumber = 2,
            episodeNumbers = listOf(1, 2, 3),
            rollbackState = ContinueWatchingSnapshotService.EpisodeRollbackState(),
            session = testSimklSession()
        )

        adapter.rollbackToServerTruth(
            envelope = envelope,
            failure = TraktMutationSettlement.TerminalFailure(reason = "boom", httpStatusCode = 500)
        )

        coVerify(exactly = 1) { snapshotService.rollbackEpisodes(any<ContinueWatchingSnapshotService.EpisodeRollbackState>()) }
        coVerify(exactly = 1) { snapshotService.ensureFresh(force = true) }
    }
}
