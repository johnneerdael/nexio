package com.nexio.tv.core.tmdb

import com.nexio.tv.data.integration.tmdb.DefaultTmdbExternalIdLookupProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.concurrent.CancellationException

class TmdbServiceTest {

    @Test
    fun `imdbToTmdb resolves through external-id provider lookup`() = runTest {
        val externalIdLookupProvider = mockk<DefaultTmdbExternalIdLookupProvider>()
        val response = CompletableDeferred<Int?>()
        coEvery {
            externalIdLookupProvider.findTmdbIdByImdbId("tt1111111", "movie")
        } coAnswers { response.await() }

        val service = TmdbService(externalIdLookupProvider)
        val result = async { service.imdbToTmdb("tt1111111", "movie") }

        response.complete(99)

        assertEquals(99, result.await())
        coVerify(exactly = 1) { externalIdLookupProvider.findTmdbIdByImdbId("tt1111111", "movie") }
    }

    @Test
    fun `tmdbToImdb uses movie and tv external id lookups`() = runTest {
        val externalIdLookupProvider = mockk<DefaultTmdbExternalIdLookupProvider>()
        val service = TmdbService(externalIdLookupProvider)
        coEvery {
            externalIdLookupProvider.findImdbIdByTmdbId(10, "movie")
        } returns "tt0101010"
        coEvery {
            externalIdLookupProvider.findImdbIdByTmdbId(11, "tv")
        } returns "tt0202020"

        assertEquals("tt0101010", service.tmdbToImdb(10, "movie"))
        assertEquals("tt0202020", service.tmdbToImdb(11, "series"))

        coVerify(exactly = 1) { externalIdLookupProvider.findImdbIdByTmdbId(10, "movie") }
        coVerify(exactly = 1) { externalIdLookupProvider.findImdbIdByTmdbId(11, "tv") }
    }

    @Test
    fun `ensureTmdbId delegates to imdb lookup for imdb style ids`() = runTest {
        val externalIdLookupProvider = mockk<DefaultTmdbExternalIdLookupProvider>()
        coEvery {
            externalIdLookupProvider.findTmdbIdByImdbId("tt1234567", "movie")
        } returns 7

        val service = TmdbService(externalIdLookupProvider)

        assertEquals("7", service.ensureTmdbId("tt1234567", "movie"))
        coVerify(exactly = 1) { externalIdLookupProvider.findTmdbIdByImdbId("tt1234567", "movie") }
    }

    @Test
    fun `ensureTmdbId returns null for unsupported ids`() = runTest {
        val externalIdLookupProvider = mockk<DefaultTmdbExternalIdLookupProvider>()
        val service = TmdbService(externalIdLookupProvider)

        assertNull(service.ensureTmdbId("not-an-id", "movie"))
        coVerify(exactly = 0) { externalIdLookupProvider.findTmdbIdByImdbId(any(), any()) }
    }

    @Test
    fun `imdbToTmdb shared in-flight lookup cancels with CancellationException`() = runTest {
        val externalIdLookupProvider = mockk<DefaultTmdbExternalIdLookupProvider>()
        val started = CompletableDeferred<Unit>()
        val result = CompletableDeferred<Int?>()

        coEvery {
            externalIdLookupProvider.findTmdbIdByImdbId("ttcancel", "movie")
        } coAnswers {
            started.complete(Unit)
            result.await()
        }

        val service = TmdbService(externalIdLookupProvider)
        val first = async { service.imdbToTmdb("ttcancel", "movie") }
        val second = async { service.imdbToTmdb("ttcancel", "movie") }

        started.await()
        yield()
        result.completeExceptionally(CancellationException("lookup cancelled"))

        assertCancellation(first)
        assertCancellation(second)

        coVerify(exactly = 1) {
            externalIdLookupProvider.findTmdbIdByImdbId("ttcancel", "movie")
        }
    }

    @Test
    fun `tmdbToImdb shared in-flight lookup cancels with CancellationException`() = runTest {
        val externalIdLookupProvider = mockk<DefaultTmdbExternalIdLookupProvider>()
        val started = CompletableDeferred<Unit>()
        val result = CompletableDeferred<String?>()

        coEvery {
            externalIdLookupProvider.findImdbIdByTmdbId(20, "movie")
        } coAnswers {
            started.complete(Unit)
            result.await()
        }

        val service = TmdbService(externalIdLookupProvider)
        val first = async { service.tmdbToImdb(20, "movie") }
        val second = async { service.tmdbToImdb(20, "movie") }

        started.await()
        yield()
        result.completeExceptionally(CancellationException("lookup cancelled"))

        assertCancellation(first)
        assertCancellation(second)

        coVerify(exactly = 1) {
            externalIdLookupProvider.findImdbIdByTmdbId(20, "movie")
        }
    }

    private suspend fun assertCancellation(task: kotlinx.coroutines.Deferred<*>) {
        val failure = runCatching { task.await() }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(CancellationException::class.java, failure!!::class.java)
    }
}
