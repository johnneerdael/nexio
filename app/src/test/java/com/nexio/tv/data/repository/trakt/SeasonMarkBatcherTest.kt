package com.nexio.tv.data.repository.trakt

import com.nexio.tv.data.repository.testTraktSession
import com.nexio.tv.data.repository.ContinueWatchingSnapshotService
import com.nexio.tv.data.repository.TraktAuthService
import com.nexio.tv.data.trakt.outbox.TraktMutationEnvelope
import com.nexio.tv.data.trakt.outbox.TraktMutationOutboxCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SeasonMarkBatcherTest {

    @Test
    fun `batcher enqueues one queued mutation for many episodes`() = runTest {
        val coordinator = mockk<TraktMutationOutboxCoordinator>()
        coEvery { coordinator.enqueueAndDrain(any()) } answers { firstArg<TraktMutationEnvelope>() }

        val batcher = SeasonMarkBatcher(
            traktMutationOutboxCoordinator = coordinator,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            traktAuthService = traktAuthService()
        )
        val episodes = (1..24).map { episodeNumber ->
            TraktEpisodeRef(
                episodeNumber = episodeNumber,
                traktId = episodeNumber
            )
        }
        val rollbackState = ContinueWatchingSnapshotService.EpisodeRollbackState()

        batcher.markSeasonWatched("show", 1, episodes, rollbackState)

        val envelopeSlot = slot<TraktMutationEnvelope>()
        coVerify(exactly = 1) { coordinator.enqueueAndDrain(capture(envelopeSlot)) }
        assertEquals("show:season:1", envelopeSlot.captured.collapseKey)
    }

    @Test
    fun `hard enqueue failure propagates exception`() = runTest {
        val coordinator = mockk<TraktMutationOutboxCoordinator>()
        coEvery { coordinator.enqueueAndDrain(any()) } throws IOException("storage failure")

        val batcher = SeasonMarkBatcher(
            traktMutationOutboxCoordinator = coordinator,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            traktAuthService = traktAuthService()
        )
        val episodes = listOf(
            TraktEpisodeRef(
                episodeNumber = 1,
                traktId = 42
            )
        )

        try {
            batcher.markSeasonWatched(
                showContentId = "show",
                seasonNumber = 1,
                episodes = episodes,
                rollbackState = ContinueWatchingSnapshotService.EpisodeRollbackState()
            )
            throw AssertionError("Expected IOException")
        } catch (error: IOException) {
            assertEquals("storage failure", error.message)
        }
    }

    private fun traktAuthService(): TraktAuthService {
        return mockk {
            coEvery { mutationAccountScopedSession(any()) } answers {
                firstArg<com.nexio.tv.data.repository.TrackingAuthSession>().copy(
                    credentialHash = "trakt-test-credential"
                )
            }
        }
    }
}
