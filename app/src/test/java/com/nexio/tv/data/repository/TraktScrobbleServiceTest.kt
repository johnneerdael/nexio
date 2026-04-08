package com.nexio.tv.data.repository

import com.nexio.tv.data.local.TraktAuthState
import com.nexio.tv.data.remote.api.TraktApi
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeout

class TraktScrobbleServiceTest {

    @Test
    fun `replacePendingWatchingMutation keeps earliest rollback state while latest intent wins`() {
        val existing = PendingWatchingMutation(
            request = WatchingMutationRequest.Scrobble(
                action = "start",
                item = movieItem("Existing"),
                progressPercent = 12f
            ),
            rollbackState = TraktScrobbleService.WatchingNowState(
                active = false,
                title = "Server truth"
            ),
            optimisticVersion = 1L
        )
        val incoming = PendingWatchingMutation(
            request = WatchingMutationRequest.Scrobble(
                action = "stop",
                item = movieItem("Existing"),
                progressPercent = 42f
            ),
            rollbackState = TraktScrobbleService.WatchingNowState(
                active = true,
                title = "Optimistic start"
            ),
            optimisticVersion = 2L
        )

        val merged = replacePendingWatchingMutation(existing = existing, incoming = incoming)

        assertEquals(existing.rollbackState, merged.rollbackState)
        assertEquals(incoming.request, merged.request)
        assertEquals(incoming.optimisticVersion, merged.optimisticVersion)
        assertFalse(shouldRollbackWatchingMutation(currentOptimisticVersion = 2L, failedMutationVersion = 1L))
        assertTrue(shouldRollbackWatchingMutation(currentOptimisticVersion = 2L, failedMutationVersion = 2L))
    }

    @Test
    fun `checkin applies optimistic watching state and rolls back on failure`() = runTest {
        val traktApi = mockk<TraktApi>()
        val traktAuthService = mockk<TraktAuthService>()
        val traktProgressService = mockk<TraktProgressService>(relaxed = true)
        val requestGate = CompletableDeferred<Unit>()

        coEvery { traktAuthService.getCurrentAuthState() } returns authenticatedState()
        every { traktAuthService.hasRequiredCredentials() } returns true
        coEvery { traktAuthService.executeAuthorizedWriteRequest<Any?>(any()) } coAnswers {
            requestGate.await()
            errorResponse(code = 500)
        }

        val service = TraktScrobbleService(
            traktApi = traktApi,
            traktAuthService = traktAuthService,
            traktProgressService = traktProgressService
        )

        val mutation = async { service.checkin(movieItem("Arrival")) }

        awaitState(service) { it.active && it.title == "Arrival" && it.progressPercent == null }

        requestGate.complete(Unit)

        assertFalse(mutation.await())
        awaitState(service) { !it.active && it.title == null && it.progressPercent == null }
        coVerify(exactly = 0) { traktProgressService.refreshNow() }
    }

    @Test
    fun `older failed mutation does not rollback newer optimistic watching state`() = runTest {
        val traktApi = mockk<TraktApi>()
        val traktAuthService = mockk<TraktAuthService>()
        val traktProgressService = mockk<TraktProgressService>(relaxed = true)
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        var callCount = 0

        coEvery { traktAuthService.getCurrentAuthState() } returns authenticatedState()
        every { traktAuthService.hasRequiredCredentials() } returns true
        coEvery { traktAuthService.executeAuthorizedWriteRequest<Any?>(any()) } coAnswers {
            when (callCount++) {
                0 -> {
                    firstGate.await()
                    errorResponse(code = 500)
                }

                else -> {
                    secondGate.await()
                    successResponse()
                }
            }
        }

        val service = TraktScrobbleService(
            traktApi = traktApi,
            traktAuthService = traktAuthService,
            traktProgressService = traktProgressService
        )
        val item = movieItem("Heat")

        val first = async { service.checkin(item) }
        awaitState(service) { it.active && it.title == "Heat" && it.progressPercent == null }

        val second = launch { service.scrobbleStop(item = item, progressPercent = 42f) }
        awaitState(service) { !it.active && it.title == "Heat" && it.progressPercent == 42f }

        firstGate.complete(Unit)
        awaitState(service) { !it.active && it.title == "Heat" && it.progressPercent == 42f }

        secondGate.complete(Unit)
        second.join()

        assertFalse(first.await())
        awaitState(service) { !it.active && it.title == "Heat" && it.progressPercent == 42f }
        coVerify(exactly = 1) { traktProgressService.refreshNow() }
    }

    private suspend fun awaitState(
        service: TraktScrobbleService,
        predicate: (TraktScrobbleService.WatchingNowState) -> Boolean
    ) {
        withTimeout(2.seconds) {
            while (!predicate(service.observeWatchingNowState().value)) {
                delay(10)
            }
        }
    }

    private fun movieItem(title: String) = TraktScrobbleItem.Movie(
        title = title,
        year = 2025,
        ids = TraktIdsDto(imdb = "tt1234567")
    )

    private fun authenticatedState() = TraktAuthState(
        accessToken = "access",
        refreshToken = "refresh"
    )

    private fun errorResponse(code: Int): Response<Any?> {
        return Response.error(code, "{}".toResponseBody("application/json".toMediaType()))
    }

    private fun successResponse(): Response<Any?> = Response.success(null)
}
