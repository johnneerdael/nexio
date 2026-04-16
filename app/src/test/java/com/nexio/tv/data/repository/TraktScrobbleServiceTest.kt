package com.nexio.tv.data.repository

import com.nexio.tv.data.local.TraktAuthState
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.domain.model.TrackingProvider
import com.nexio.tv.data.repository.trakt.TraktWatchingNowStateController
import com.nexio.tv.data.trakt.outbox.TraktMutationEnvelope
import com.nexio.tv.data.trakt.outbox.TraktMutationOutboxCoordinator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeout

class TraktScrobbleServiceTest {

    @Test
    fun `replacePendingWatchingMutation keeps earliest rollback state while latest intent wins`() {
        val existing = PendingWatchingMutation(
            request = WatchingMutationRequest.Scrobble(
                action = "start",
                item = movieItem("Existing"),
                progressPercent = 12f,
                optimisticVersion = 1L
            ),
            rollbackState = TraktWatchingNowStateController.Snapshot(
                active = false,
                title = "Server truth"
            ),
            optimisticVersion = 1L
        )
        val incoming = PendingWatchingMutation(
            request = WatchingMutationRequest.Scrobble(
                action = "stop",
                item = movieItem("Existing"),
                progressPercent = 42f,
                optimisticVersion = 2L
            ),
            rollbackState = TraktWatchingNowStateController.Snapshot(
                active = true,
                title = "Optimistic start"
            ),
            optimisticVersion = 2L
        )

        val merged = replacePendingWatchingMutation(existing = existing, incoming = incoming)

        assertEquals(existing.rollbackState, merged.rollbackState)
        assertEquals(incoming.request, merged.request)
        assertEquals(incoming.optimisticVersion, merged.optimisticVersion)
    }

    @Test
    fun `checkin applies optimistic watching state and rolls back on failure`() = runTest {
        val traktAuthService = mockk<TraktAuthService>()
        val coordinator = mockk<TraktMutationOutboxCoordinator>()
        val controller = TraktWatchingNowStateController()
        val requestGate = CompletableDeferred<Unit>()

        coEvery { traktAuthService.getCurrentAuthState() } returns authenticatedState()
        every { traktAuthService.hasRequiredCredentials() } returns true
        every { traktAuthService.currentAuthSession() } returns TrackingAuthSession(TrackingProvider.TRAKT, 1)
        coEvery { coordinator.enqueueAndDrain(any()) } coAnswers {
            requestGate.await()
            throw IllegalStateException("boom")
        }

        val service = TraktScrobbleService(
            traktAuthService = traktAuthService,
            watchingNowStateController = controller,
            traktMutationOutboxCoordinator = coordinator
        )

        val mutation = async { service.checkin(movieItem("Arrival")) }

        awaitState(service) { it.active && it.title == "Arrival" && it.progressPercent == null }

        requestGate.complete(Unit)

        assertFalse(mutation.await())
        awaitState(service) { !it.active && it.title == null && it.progressPercent == null }
    }

    @Test
    fun `older failed mutation does not rollback newer optimistic watching state`() = runTest {
        val traktAuthService = mockk<TraktAuthService>()
        val coordinator = mockk<TraktMutationOutboxCoordinator>()
        val controller = TraktWatchingNowStateController()
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        var callCount = 0

        coEvery { traktAuthService.getCurrentAuthState() } returns authenticatedState()
        every { traktAuthService.hasRequiredCredentials() } returns true
        every { traktAuthService.currentAuthSession() } returns TrackingAuthSession(TrackingProvider.TRAKT, 1)
        coEvery { coordinator.enqueueAndDrain(any()) } coAnswers {
            when (callCount++) {
                0 -> {
                    firstGate.await()
                    throw IllegalStateException("boom")
                }

                else -> {
                    secondGate.await()
                    firstArg<TraktMutationEnvelope>()
                }
            }
        }

        val service = TraktScrobbleService(
            traktAuthService = traktAuthService,
            watchingNowStateController = controller,
            traktMutationOutboxCoordinator = coordinator
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
}
