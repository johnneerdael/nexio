package com.nexio.tv.data.repository.trakt

import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.repository.TraktProgressService
import com.nexio.tv.data.repository.TraktScrobbleItem
import com.nexio.tv.data.repository.testTraktSession
import com.nexio.tv.data.trakt.outbox.TraktMutationExecutionResult
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktScrobbleMutationAdapterCrossWatchTest {
    @Test
    fun `high progress pause is suppressed not converted to stop`() {
        val envelope = TraktScrobbleMutationAdapter.buildScrobbleEnvelope(
            item = movie(),
            action = "pause",
            progressPercent = 80f,
            rollbackState = TraktWatchingNowStateController.Snapshot(),
            optimisticVersion = 1L,
            session = testTraktSession()
        )

        assertEquals("pause", envelope.payload.get("action").asString)
        assertTrue(envelope.payload.get("suppressSend").asBoolean)
    }

    @Test
    fun `suppressed high progress pause does not call trakt`() = runTest {
        val traktIntegrationProvider = mockk<com.nexio.tv.data.integration.trakt.TraktIntegrationProvider>(relaxed = true)
        val adapter = TraktScrobbleMutationAdapter(
            traktIntegrationProvider = traktIntegrationProvider,
            traktProgressService = mockk<TraktProgressService>(relaxed = true),
            watchingNowStateController = TraktWatchingNowStateController(),
            playerSettingsDataStore = playerSettingsStore()
        )
        val envelope = TraktScrobbleMutationAdapter.buildScrobbleEnvelope(
            item = movie(),
            action = "pause",
            progressPercent = 80f,
            rollbackState = TraktWatchingNowStateController.Snapshot(),
            optimisticVersion = 1L,
            session = testTraktSession()
        )

        val result = adapter.execute(envelope)

        assertTrue(result is TraktMutationExecutionResult.Success)
        coVerify(exactly = 0) { traktIntegrationProvider.scrobble(any(), any(), any()) }
    }

    @Test
    fun `trakt movie payload preserves tvdb id when present`() {
        val envelope = TraktScrobbleMutationAdapter.buildScrobbleEnvelope(
            item = movie(),
            action = "start",
            progressPercent = 12f,
            rollbackState = TraktWatchingNowStateController.Snapshot(),
            optimisticVersion = 1L,
            session = testTraktSession()
        )

        assertEquals(98765, envelope.payload.get("tvdb").asInt)
        assertFalse(envelope.payload.has("suppressSend"))
    }

    private fun movie() = TraktScrobbleItem.Movie(
        title = "Arrival",
        year = 2016,
        ids = TraktIdsDto(imdb = "tt2543164", tmdb = 329865, tvdb = 98765)
    )

    private fun playerSettingsStore(): PlayerSettingsDataStore {
        return mockk<PlayerSettingsDataStore> {
            every { playerSettings } returns flowOf(PlayerSettings())
        }
    }
}
