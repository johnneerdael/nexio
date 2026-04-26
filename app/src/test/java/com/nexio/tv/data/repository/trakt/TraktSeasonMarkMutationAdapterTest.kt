package com.nexio.tv.data.repository.trakt

import com.nexio.tv.data.remote.dto.trakt.TraktEpisodeDto
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryAddNotFoundDto
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryAddResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.repository.ContinueWatchingSnapshotService
import com.nexio.tv.data.repository.TraktProgressService
import com.nexio.tv.data.repository.TrackingAuthSession
import com.nexio.tv.data.repository.TrackingNextUpEntry
import com.nexio.tv.data.trakt.outbox.TraktMutationExecutionResult
import com.nexio.tv.data.trakt.outbox.TraktMutationSettlement
import com.nexio.tv.domain.model.TrackingProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import javax.inject.Provider

class TraktSeasonMarkMutationAdapterTest {

    @Test
    fun `applyOptimistic pushes completed progress for each season episode`() = kotlinx.coroutines.test.runTest {
        val executor = mockk<TraktProgressMutationExecutor>(relaxed = true)
        val snapshotService = mockk<ContinueWatchingSnapshotService>(relaxed = true)
        val progressService = mockk<TraktProgressService>(relaxed = true)
        val adapter = TraktSeasonMarkMutationAdapter(
            traktProgressMutationExecutor = executor,
            snapshotServiceProvider = Provider { snapshotService },
            traktProgressService = progressService
        )
        val envelope = TraktSeasonMarkMutationAdapter.buildEnvelope(
            showContentId = "show-1",
            seasonNumber = 2,
            episodes = listOf(
                TraktEpisodeRef(episodeNumber = 1, traktId = 101),
                TraktEpisodeRef(episodeNumber = 2, traktId = 102),
                TraktEpisodeRef(episodeNumber = 3, traktId = 103)
            ),
            rollbackState = rollbackState(
                showId = "show-1",
                season = 2,
                episodes = 1..3
            )
        )

        adapter.applyOptimistic(envelope)

        verify(exactly = 3) { progressService.applyOptimisticProgress(any()) }
        verify {
            progressService.applyOptimisticProgress(
                match {
                    it.contentId == "show-1" &&
                        it.contentType == "series" &&
                        it.season == 2 &&
                        it.episode == 1 &&
                        it.progressPercent == 100f
                }
            )
        }
        verify {
            progressService.applyOptimisticProgress(
                match {
                    it.contentId == "show-1" &&
                        it.season == 2 &&
                        it.episode == 2
                }
            )
        }
        verify {
            progressService.applyOptimisticProgress(
                match {
                    it.contentId == "show-1" &&
                        it.season == 2 &&
                        it.episode == 3
                }
            )
        }
    }

    @Test
    fun `execute keeps partial not_found as success and reconcile rolls back only unresolved episodes`() = kotlinx.coroutines.test.runTest {
        val executor = mockk<TraktProgressMutationExecutor>()
        val snapshotService = mockk<ContinueWatchingSnapshotService>(relaxed = true)
        val progressService = mockk<TraktProgressService>(relaxed = true)
        val adapter = TraktSeasonMarkMutationAdapter(
            traktProgressMutationExecutor = executor,
            snapshotServiceProvider = Provider { snapshotService },
            traktProgressService = progressService
        )
        val envelope = TraktSeasonMarkMutationAdapter.buildEnvelope(
            showContentId = "show-1",
            seasonNumber = 2,
            episodes = listOf(
                TraktEpisodeRef(episodeNumber = 1, traktId = 101),
                TraktEpisodeRef(episodeNumber = 2, traktId = 102),
                TraktEpisodeRef(episodeNumber = 3, traktId = 103)
            ),
            rollbackState = rollbackState(
                showId = "show-1",
                season = 2,
                episodes = 1..3
            )
        )
        coEvery { executor.addHistory(any(), any()) } returns Response.success(
            TraktHistoryAddResponseDto(
                notFound = TraktHistoryAddNotFoundDto(
                    episodes = listOf(
                        TraktEpisodeDto(ids = TraktIdsDto(trakt = 102))
                    )
                )
            )
        )

        val execution = adapter.execute(envelope)
        adapter.reconcileSuccess(envelope)

        assertTrue(execution is TraktMutationExecutionResult.Success)
        val rollbackSlot = slot<ContinueWatchingSnapshotService.EpisodeRollbackState>()
        coVerify(exactly = 1) { snapshotService.rollbackEpisodes(capture(rollbackSlot)) }
        assertEquals(setOf(2), rollbackSlot.captured.resumeItems.mapNotNull { it.episode }.toSet())
        coVerify(exactly = 1) { snapshotService.ensureFresh(force = true) }
    }

    @Test
    fun `execute sends season batch mutation with envelope profile session`() = kotlinx.coroutines.test.runTest {
        val executor = mockk<TraktProgressMutationExecutor>()
        val snapshotService = mockk<ContinueWatchingSnapshotService>(relaxed = true)
        val progressService = mockk<TraktProgressService>(relaxed = true)
        val adapter = TraktSeasonMarkMutationAdapter(
            traktProgressMutationExecutor = executor,
            snapshotServiceProvider = Provider { snapshotService },
            traktProgressService = progressService
        )
        val envelope = TraktSeasonMarkMutationAdapter.buildEnvelope(
            showContentId = "show-1",
            seasonNumber = 2,
            episodes = listOf(TraktEpisodeRef(episodeNumber = 1, traktId = 101)),
            rollbackState = rollbackState(
                showId = "show-1",
                season = 2,
                episodes = 1..1
            ),
            profileId = 42
        )
        coEvery { executor.addHistory(any(), any()) } returns Response.success(TraktHistoryAddResponseDto())

        adapter.execute(envelope)

        coVerify(exactly = 1) {
            executor.addHistory(
                TrackingAuthSession(TrackingProvider.TRAKT, 42),
                any()
            )
        }
    }

    @Test
    fun `terminal failure rolls back full season snapshot and refreshes server truth`() = kotlinx.coroutines.test.runTest {
        val executor = mockk<TraktProgressMutationExecutor>(relaxed = true)
        val snapshotService = mockk<ContinueWatchingSnapshotService>(relaxed = true)
        val progressService = mockk<TraktProgressService>(relaxed = true)
        val adapter = TraktSeasonMarkMutationAdapter(
            traktProgressMutationExecutor = executor,
            snapshotServiceProvider = Provider { snapshotService },
            traktProgressService = progressService
        )
        val rollbackState = rollbackState(
            showId = "show-2",
            season = 1,
            episodes = 4..6
        )
        val envelope = TraktSeasonMarkMutationAdapter.buildEnvelope(
            showContentId = "show-2",
            seasonNumber = 1,
            episodes = listOf(
                TraktEpisodeRef(episodeNumber = 4, traktId = 204),
                TraktEpisodeRef(episodeNumber = 5, traktId = 205),
                TraktEpisodeRef(episodeNumber = 6, traktId = 206)
            ),
            rollbackState = rollbackState
        )

        adapter.rollbackToServerTruth(
            envelope,
            TraktMutationSettlement.TerminalFailure(
                reason = "permanent failure",
                httpStatusCode = 422
            )
        )

        coVerify(exactly = 1) { snapshotService.rollbackEpisodes(rollbackState) }
        coVerify(exactly = 1) { snapshotService.ensureFresh(force = true) }
    }

    private fun rollbackState(
        showId: String,
        season: Int,
        episodes: IntRange
    ): ContinueWatchingSnapshotService.EpisodeRollbackState {
        return ContinueWatchingSnapshotService.EpisodeRollbackState(
            resumeItems = episodes.map { episode ->
                com.nexio.tv.domain.model.WatchProgress(
                    contentId = showId,
                    contentType = "series",
                    name = "Show",
                    poster = null,
                    backdrop = null,
                    logo = null,
                    videoId = "$showId:$season:$episode",
                    season = season,
                    episode = episode,
                    episodeTitle = "Episode $episode",
                    position = 50L,
                    duration = 100L,
                    lastWatched = episode.toLong(),
                    progressPercent = 50f
                )
            },
            nextUpItems = episodes.map { episode ->
                TrackingNextUpEntry(
                    contentId = showId,
                    name = "Show",
                    season = season,
                    episode = episode,
                    episodeTitle = "Episode $episode",
                    videoId = "$showId:$season:$episode",
                    firstAired = null,
                    firstAiredMs = 0L,
                    activityAtMs = episode.toLong()
                )
            }
        )
    }
}
