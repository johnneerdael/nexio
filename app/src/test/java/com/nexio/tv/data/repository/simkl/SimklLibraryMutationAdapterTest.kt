package com.nexio.tv.data.repository.simkl

import com.nexio.tv.data.remote.dto.simkl.SimklAddToListRequestDto
import com.nexio.tv.data.remote.dto.simkl.SimklAddToListResponseDto
import com.nexio.tv.data.remote.dto.simkl.SimklMediaRefDto
import com.nexio.tv.data.repository.SimklLibraryService
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

class SimklLibraryMutationAdapterTest {

    @Test
    fun `add envelope keeps simkl list collapse key`() {
        val envelope = SimklLibraryMutationAdapter.buildAddToListEnvelope(
            listKey = SimklLibraryService.WATCHLIST_KEY,
            body = SimklAddToListRequestDto(
                to = "plantowatch",
                movies = listOf(SimklMediaRefDto(title = "Arrival", year = 2016))
            ),
            rollbackState = SimklLibraryService.LibraryRollbackState()
        )

        assertEquals("simkl.library:${SimklLibraryService.WATCHLIST_KEY}", envelope.collapseKey)
        assertEquals(SimklLibraryMutationAdapter.MUTATION_KIND_ADD_TO_LIST, envelope.mutationKind)
    }

    @Test
    fun `adapter rolls back library state when terminal failure settles`() = kotlinx.coroutines.test.runTest {
        val remote = mockk<SimklTrackingRemoteDataSource>(relaxed = true)
        val libraryService = mockk<SimklLibraryService>(relaxed = true)
        val adapter = SimklLibraryMutationAdapter(remote, Provider { libraryService })
        val envelope = SimklLibraryMutationAdapter.buildRemoveEnvelope(
            body = com.nexio.tv.data.remote.dto.simkl.SimklHistoryRemoveRequestDto(),
            rollbackState = SimklLibraryService.LibraryRollbackState()
        )

        adapter.rollbackToServerTruth(
            envelope = envelope,
            failure = TraktMutationSettlement.TerminalFailure(reason = "boom", httpStatusCode = 500)
        )

        coVerify(exactly = 1) { libraryService.rollbackQueuedLibraryMutation(any(), envelope.profileId) }
    }

    @Test
    fun `execute returns success when simkl accepts add to list`() = kotlinx.coroutines.test.runTest {
        val remote = mockk<SimklTrackingRemoteDataSource>()
        val libraryService = mockk<SimklLibraryService>(relaxed = true)
        val adapter = SimklLibraryMutationAdapter(remote, Provider { libraryService })
        val envelope = SimklLibraryMutationAdapter.buildAddToListEnvelope(
            listKey = SimklLibraryService.WATCHLIST_KEY,
            body = SimklAddToListRequestDto(
                to = "plantowatch",
                movies = listOf(SimklMediaRefDto(title = "Arrival", year = 2016))
            ),
            rollbackState = SimklLibraryService.LibraryRollbackState()
        )

        coEvery { remote.addToList(any(), any()) } returns Response.success(SimklAddToListResponseDto(result = "ok"))

        val result = adapter.execute(envelope)

        assertTrue(result is TraktMutationExecutionResult.Success)
    }

    @Test
    fun `reconcile success refreshes simkl library state`() = kotlinx.coroutines.test.runTest {
        val remote = mockk<SimklTrackingRemoteDataSource>(relaxed = true)
        val libraryService = mockk<SimklLibraryService>(relaxed = true)
        val adapter = SimklLibraryMutationAdapter(remote, Provider { libraryService })
        val envelope = SimklLibraryMutationAdapter.buildAddToListEnvelope(
            listKey = SimklLibraryService.WATCHLIST_KEY,
            body = SimklAddToListRequestDto(
                to = "plantowatch",
                movies = listOf(SimklMediaRefDto(title = "Arrival", year = 2016))
            ),
            rollbackState = SimklLibraryService.LibraryRollbackState()
        )

        adapter.reconcileSuccess(envelope)

        coVerify(exactly = 1) { libraryService.refreshProfile(profileId = envelope.profileId, force = true) }
    }
}
