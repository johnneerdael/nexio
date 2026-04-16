package com.nexio.tv.data.repository.trakt

import com.nexio.tv.data.remote.api.TraktApi
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryAddResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.repository.TraktAuthService
import com.nexio.tv.data.repository.TraktProgressService
import com.nexio.tv.data.trakt.outbox.TraktMutationExecutionResult
import com.nexio.tv.data.trakt.outbox.TraktMutationSettlement
import com.nexio.tv.domain.model.WatchProgress
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class TraktProgressHistoryMutationAdapterTest {

    @Test
    fun `history add envelope keeps episode identity in collapse key`() {
        val envelope = TraktProgressHistoryMutationAdapter.buildHistoryAddEnvelope(
            progress = sampleProgress(),
            title = "Episode",
            year = 2025
        )

        assertEquals("show:s1:e2", envelope.collapseKey)
        assertEquals(
            TraktProgressHistoryMutationAdapter.MUTATION_KIND_HISTORY_ADD,
            envelope.mutationKind
        )
    }

    @Test
    fun `history remove envelope keeps show intent separate`() {
        val envelope = TraktProgressHistoryMutationAdapter.buildHistoryRemoveEnvelope(
            contentId = "show",
            season = null,
            episode = null,
            removeShow = true
        )

        assertEquals("show:show", envelope.collapseKey)
        assertEquals(
            TraktProgressHistoryMutationAdapter.MUTATION_KIND_HISTORY_REMOVE,
            envelope.mutationKind
        )
    }

    @Test
    fun `apply optimistic and rollback delegate to progress service`() = kotlinx.coroutines.test.runTest {
        val traktApi = mockk<TraktApi>(relaxed = true)
        val traktAuthService = mockk<TraktAuthService>(relaxed = true)
        val traktProgressService = mockk<TraktProgressService>(relaxed = true)
        val adapter = TraktProgressHistoryMutationAdapter(
            traktApi = traktApi,
            traktAuthService = traktAuthService,
            traktProgressService = traktProgressService
        )
        val envelope = TraktProgressHistoryMutationAdapter.buildHistoryAddEnvelope(
            progress = sampleProgress(),
            title = "Episode",
            year = 2025
        )

        adapter.applyOptimistic(envelope)
        adapter.rollbackToServerTruth(
            envelope = envelope,
            failure = TraktMutationSettlement.TerminalFailure(reason = "boom", httpStatusCode = 404)
        )

        coVerify(exactly = 1) { traktProgressService.applyOptimisticProgress(any()) }
        coVerify(exactly = 1) { traktProgressService.rollbackQueuedHistoryAdd(any()) }
    }

    @Test
    fun `execute returns success when Trakt accepts history add`() = kotlinx.coroutines.test.runTest {
        val traktApi = mockk<TraktApi>(relaxed = true)
        val traktAuthService = mockk<TraktAuthService>()
        val traktProgressService = mockk<TraktProgressService>()
        val adapter = TraktProgressHistoryMutationAdapter(
            traktApi = traktApi,
            traktAuthService = traktAuthService,
            traktProgressService = traktProgressService
        )
        val envelope = TraktProgressHistoryMutationAdapter.buildHistoryAddEnvelope(
            progress = sampleProgress(),
            title = "Episode",
            year = 2025
        )

        every {
            traktProgressService.buildHistoryAddRequestForOutbox(any(), any(), any())
        } returns mockk(relaxed = true)
        every {
            traktProgressService.hasHistoryAddNotFoundForOutbox(any())
        } returns false
        coEvery {
            traktAuthService.executeAuthorizedWriteRequest<TraktHistoryAddResponseDto>(any(), any())
        } returns Response.success(null)

        val result = adapter.execute(envelope)

        assertTrue(result is TraktMutationExecutionResult.Success)
    }

    @Test
    fun `execute returns success when Trakt accepts playback delete`() = kotlinx.coroutines.test.runTest {
        val traktApi = mockk<TraktApi>(relaxed = true)
        val traktAuthService = mockk<TraktAuthService>()
        val traktProgressService = mockk<TraktProgressService>(relaxed = true)
        val adapter = TraktProgressHistoryMutationAdapter(
            traktApi = traktApi,
            traktAuthService = traktAuthService,
            traktProgressService = traktProgressService
        )
        val envelope = TraktProgressHistoryMutationAdapter.buildPlaybackDeleteEnvelope(
            playbackId = 123L,
            contentId = "show",
            season = 1,
            episode = 2
        )

        coEvery {
            traktAuthService.executeAuthorizedWriteRequest<Unit>(any(), any())
        } returns Response.success(Unit)

        val result = adapter.execute(envelope)

        assertTrue(result is TraktMutationExecutionResult.Success)
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
            progressPercent = 100f,
            traktShowId = 99,
            traktEpisodeId = 199
        )
    }
}
