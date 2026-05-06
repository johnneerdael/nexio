package com.nexio.tv.data.repository.trakt

import com.nexio.tv.data.repository.testTraktSession
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.data.remote.dto.trakt.TraktCollectionAddMovieDto
import com.nexio.tv.data.remote.dto.trakt.TraktCollectionAddRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktCollectionAddResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktCollectionRemoveMovieDto
import com.nexio.tv.data.remote.dto.trakt.TraktCollectionRemoveRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktCollectionRemoveResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktCollectionWriteCountsDto
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.repository.TraktLibraryService
import com.nexio.tv.data.trakt.outbox.TraktMutationExecutionResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.inject.Provider

class TraktLibraryMutationAdapterCollectionTest {

    private val executor = mockk<TraktLibraryMutationExecutor>()
    private val libraryServiceProvider = mockk<Provider<TraktLibraryService>>(relaxed = true)
    private val adapter = TraktLibraryMutationAdapter(
        executor = executor,
        libraryServiceProvider = libraryServiceProvider
    )

    // ── Envelope construction ────────────────────────────────────────────────

    @Test
    fun `buildCollectionAddEnvelope creates envelope with correct mutation kind`() {
        val body = addBody()
        val envelope = TraktLibraryMutationAdapter.buildCollectionAddEnvelope(body = body, session = testTraktSession())

        assertEquals(TraktLibraryMutationAdapter.MUTATION_KIND_COLLECTION_ADD, envelope.mutationKind)
    }

    @Test
    fun `buildCollectionRemoveEnvelope creates envelope with correct mutation kind`() {
        val body = removeBody()
        val envelope = TraktLibraryMutationAdapter.buildCollectionRemoveEnvelope(body = body, session = testTraktSession())

        assertEquals(TraktLibraryMutationAdapter.MUTATION_KIND_COLLECTION_REMOVE, envelope.mutationKind)
    }

    @Test
    fun `buildCollectionAddEnvelope uses ADAPTER_KEY`() {
        val envelope = TraktLibraryMutationAdapter.buildCollectionAddEnvelope(body = addBody(), session = testTraktSession())

        assertEquals(TraktLibraryMutationAdapter.ADAPTER_KEY, envelope.adapterKey)
    }

    @Test
    fun `buildCollectionRemoveEnvelope uses ADAPTER_KEY`() {
        val envelope = TraktLibraryMutationAdapter.buildCollectionRemoveEnvelope(body = removeBody(), session = testTraktSession())

        assertEquals(TraktLibraryMutationAdapter.ADAPTER_KEY, envelope.adapterKey)
    }

    // ── Dispatch: collection add ─────────────────────────────────────────────

    @Test
    fun `executeCollectionAdd returns Success when provider succeeds`() = runBlocking {
        coEvery { executor.addToCollection(any()) } returns IntegrationCallResult.Success(
            TraktCollectionAddResponseDto(added = TraktCollectionWriteCountsDto(movies = 1))
        )

        val envelope = TraktLibraryMutationAdapter.buildCollectionAddEnvelope(body = addBody(), session = testTraktSession())
        val result = adapter.execute(envelope)

        assertTrue(result is TraktMutationExecutionResult.Success)
    }

    @Test
    fun `executeCollectionAdd returns Success with http 201`() = runBlocking {
        coEvery { executor.addToCollection(any()) } returns IntegrationCallResult.Success(
            TraktCollectionAddResponseDto(added = TraktCollectionWriteCountsDto(movies = 1))
        )

        val envelope = TraktLibraryMutationAdapter.buildCollectionAddEnvelope(body = addBody(), session = testTraktSession())
        val result = adapter.execute(envelope) as TraktMutationExecutionResult.Success

        assertEquals(201, result.httpStatusCode)
    }

    @Test
    fun `executeCollectionAdd returns Failure when provider returns HttpError`() = runBlocking {
        coEvery { executor.addToCollection(any()) } returns IntegrationCallResult.HttpError(statusCode = 422)

        val envelope = TraktLibraryMutationAdapter.buildCollectionAddEnvelope(body = addBody(), session = testTraktSession())
        val result = adapter.execute(envelope)

        assertTrue(result is TraktMutationExecutionResult.Failure)
        assertEquals(422, (result as TraktMutationExecutionResult.Failure).httpStatusCode)
    }

    @Test
    fun `executeCollectionAdd returns Failure when provider returns Missing`() = runBlocking {
        coEvery { executor.addToCollection(any()) } returns IntegrationCallResult.Missing

        val envelope = TraktLibraryMutationAdapter.buildCollectionAddEnvelope(body = addBody(), session = testTraktSession())
        val result = adapter.execute(envelope)

        assertTrue(result is TraktMutationExecutionResult.Failure)
    }

    @Test
    fun `executeCollectionAdd returns Failure when provider returns NetworkError`() = runBlocking {
        coEvery { executor.addToCollection(any()) } returns IntegrationCallResult.NetworkError(
            throwable = RuntimeException("timeout")
        )

        val envelope = TraktLibraryMutationAdapter.buildCollectionAddEnvelope(body = addBody(), session = testTraktSession())
        val result = adapter.execute(envelope)

        assertTrue(result is TraktMutationExecutionResult.Failure)
    }

    // ── Dispatch: collection remove ──────────────────────────────────────────

    @Test
    fun `executeCollectionRemove returns Success when provider succeeds`() = runBlocking {
        coEvery { executor.removeFromCollection(any()) } returns IntegrationCallResult.Success(
            TraktCollectionRemoveResponseDto(deleted = TraktCollectionWriteCountsDto(movies = 1))
        )

        val envelope = TraktLibraryMutationAdapter.buildCollectionRemoveEnvelope(body = removeBody(), session = testTraktSession())
        val result = adapter.execute(envelope)

        assertTrue(result is TraktMutationExecutionResult.Success)
    }

    @Test
    fun `executeCollectionRemove returns Failure when provider returns Missing`() = runBlocking {
        coEvery { executor.removeFromCollection(any()) } returns IntegrationCallResult.Missing

        val envelope = TraktLibraryMutationAdapter.buildCollectionRemoveEnvelope(body = removeBody(), session = testTraktSession())
        val result = adapter.execute(envelope)

        assertTrue(result is TraktMutationExecutionResult.Failure)
    }

    @Test
    fun `executeCollectionRemove returns Failure when provider returns HttpError`() = runBlocking {
        coEvery { executor.removeFromCollection(any()) } returns IntegrationCallResult.HttpError(statusCode = 404)

        val envelope = TraktLibraryMutationAdapter.buildCollectionRemoveEnvelope(body = removeBody(), session = testTraktSession())
        val result = adapter.execute(envelope)

        assertTrue(result is TraktMutationExecutionResult.Failure)
        assertEquals(404, (result as TraktMutationExecutionResult.Failure).httpStatusCode)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun addBody() = TraktCollectionAddRequestDto(
        movies = listOf(TraktCollectionAddMovieDto(ids = TraktIdsDto(imdb = "tt0372784")))
    )

    private fun removeBody() = TraktCollectionRemoveRequestDto(
        movies = listOf(TraktCollectionRemoveMovieDto(ids = TraktIdsDto(imdb = "tt0372784")))
    )
}
