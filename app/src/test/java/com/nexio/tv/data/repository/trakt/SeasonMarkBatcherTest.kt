package com.nexio.tv.data.repository.trakt

import com.nexio.tv.data.remote.dto.trakt.TraktEpisodeDto
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryAddNotFoundDto
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryAddResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryEpisodeAddDto
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.repository.TraktProgressService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import org.junit.Assert.fail

@OptIn(ExperimentalCoroutinesApi::class)
class SeasonMarkBatcherTest {

    private fun successResponse(
        addedEpisodes: Int = 0,
        notFoundTraktIds: List<Int> = emptyList(),
    ): Response<TraktHistoryAddResponseDto> {
        val notFoundEpisodes = notFoundTraktIds.map { id ->
            TraktEpisodeDto(ids = TraktIdsDto(trakt = id))
        }
        val body = TraktHistoryAddResponseDto(
            notFound = TraktHistoryAddNotFoundDto(episodes = notFoundEpisodes.ifEmpty { null }),
        )
        return Response.success(body)
    }

    @Test
    fun `batcherIssuesOneCallForManyEpisodes`() = runTest {
        val service = mockk<TraktProgressService>()
        val capturedEpisodes = slot<List<TraktHistoryEpisodeAddDto>>()

        coEvery { service.addHistoryBatch(capture(capturedEpisodes)) } returns successResponse()

        val batcher = SeasonMarkBatcher(service, StandardTestDispatcher(testScheduler))
        val episodes = (1..24).map { TraktEpisodeRef(traktId = it) }

        batcher.markSeasonWatched(episodes)

        coVerify(exactly = 1) { service.addHistoryBatch(any()) }
        assertEquals(24, capturedEpisodes.captured.size)
    }

    @Test
    fun `demultiplexesNotFoundByTraktId`() = runTest {
        val service = mockk<TraktProgressService>()

        coEvery { service.addHistoryBatch(any()) } returns successResponse(notFoundTraktIds = listOf(3, 7))

        val batcher = SeasonMarkBatcher(service, StandardTestDispatcher(testScheduler))
        val episodes = (1..10).map { TraktEpisodeRef(traktId = it) }

        val result = batcher.markSeasonWatched(episodes)

        assertEquals(2, result.notFound.size)
        assertEquals(setOf(3, 7), result.notFound.map { it.traktId }.toSet())
        assertEquals(8, result.succeeded.size)
    }

    @Test
    fun `hardFailurePropagatesException`() = runTest {
        val service = mockk<TraktProgressService>()

        coEvery { service.addHistoryBatch(any()) } throws IOException("network failure")

        val batcher = SeasonMarkBatcher(service, StandardTestDispatcher(testScheduler))
        val episodes = listOf(TraktEpisodeRef(traktId = 1))

        try {
            batcher.markSeasonWatched(episodes)
            fail("Expected IOException to be thrown")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun `dispatcherInjectionWorksWithTestScheduler`() = runTest {
        val service = mockk<TraktProgressService>()

        coEvery { service.addHistoryBatch(any()) } returns successResponse()

        val batcher = SeasonMarkBatcher(service, StandardTestDispatcher(testScheduler))
        val episodes = listOf(TraktEpisodeRef(traktId = 42))

        val result = batcher.markSeasonWatched(episodes)

        assertEquals(1, result.succeeded.size)
        assertEquals(42, result.succeeded[0].traktId)
    }
}
