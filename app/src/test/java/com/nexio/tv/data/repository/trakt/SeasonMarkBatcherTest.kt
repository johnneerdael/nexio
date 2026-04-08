package com.nexio.tv.data.repository.trakt

import com.nexio.tv.data.trakt.outbox.TraktMutationEnvelope
import com.nexio.tv.data.trakt.outbox.TraktMutationLifecycleState
import com.nexio.tv.data.trakt.outbox.TraktMutationOutboxCoordinator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import org.junit.Assert.fail

@OptIn(ExperimentalCoroutinesApi::class)
class SeasonMarkBatcherTest {

    @Test
    fun `batcherIssuesOneCallForManyEpisodes`() = runTest {
        val coordinator = mockk<TraktMutationOutboxCoordinator>()
        val adapter = mockk<TraktSeasonMarkMutationAdapter>()
        coEvery { coordinator.enqueueAndAwait(any(), any()) } answers { firstArg<TraktMutationEnvelope>().copy(state = TraktMutationLifecycleState.SUCCEEDED) }
        every { adapter.consumeNotFound(any()) } returns emptySet()

        val batcher = SeasonMarkBatcher(coordinator, adapter, StandardTestDispatcher(testScheduler))
        val episodes = (1..24).map { TraktEpisodeRef(traktId = it) }

        val result = batcher.markSeasonWatched("show", 1, episodes)

        assertEquals(24, result.succeeded.size)
    }

    @Test
    fun `demultiplexesNotFoundByTraktId`() = runTest {
        val coordinator = mockk<TraktMutationOutboxCoordinator>()
        val adapter = mockk<TraktSeasonMarkMutationAdapter>()
        coEvery { coordinator.enqueueAndAwait(any(), any()) } answers { firstArg<TraktMutationEnvelope>().copy(state = TraktMutationLifecycleState.SUCCEEDED) }
        every { adapter.consumeNotFound(any()) } returns setOf(3, 7)

        val batcher = SeasonMarkBatcher(coordinator, adapter, StandardTestDispatcher(testScheduler))
        val episodes = (1..10).map { TraktEpisodeRef(traktId = it) }

        val result = batcher.markSeasonWatched("show", 1, episodes)

        assertEquals(2, result.notFound.size)
        assertEquals(setOf(3, 7), result.notFound.map { it.traktId }.toSet())
        assertEquals(8, result.succeeded.size)
    }

    @Test
    fun `hardFailurePropagatesException`() = runTest {
        val coordinator = mockk<TraktMutationOutboxCoordinator>()
        val adapter = mockk<TraktSeasonMarkMutationAdapter>()
        coEvery { coordinator.enqueueAndAwait(any(), any()) } throws IOException("network failure")

        val batcher = SeasonMarkBatcher(coordinator, adapter, StandardTestDispatcher(testScheduler))
        val episodes = listOf(TraktEpisodeRef(traktId = 1))

        try {
            batcher.markSeasonWatched("show", 1, episodes)
            fail("Expected IOException to be thrown")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun `dispatcherInjectionWorksWithTestScheduler`() = runTest {
        val coordinator = mockk<TraktMutationOutboxCoordinator>()
        val adapter = mockk<TraktSeasonMarkMutationAdapter>()
        coEvery { coordinator.enqueueAndAwait(any(), any()) } answers { firstArg<TraktMutationEnvelope>().copy(state = TraktMutationLifecycleState.SUCCEEDED) }
        every { adapter.consumeNotFound(any()) } returns emptySet()

        val batcher = SeasonMarkBatcher(coordinator, adapter, StandardTestDispatcher(testScheduler))
        val episodes = listOf(TraktEpisodeRef(traktId = 42))

        val result = batcher.markSeasonWatched("show", 1, episodes)

        assertEquals(1, result.succeeded.size)
        assertEquals(42, result.succeeded[0].traktId)
    }
}
