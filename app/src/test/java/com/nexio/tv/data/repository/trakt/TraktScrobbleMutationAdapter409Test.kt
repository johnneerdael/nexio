package com.nexio.tv.data.repository.trakt

import com.nexio.tv.data.repository.testTraktSession
import com.nexio.tv.data.integration.trakt.TraktIntegrationProvider
import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.remote.dto.trakt.TraktCheckinResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.remote.dto.trakt.TraktScrobbleResponseDto
import com.nexio.tv.data.repository.TraktProgressService
import com.nexio.tv.data.repository.TraktScrobbleItem
import com.nexio.tv.data.trakt.outbox.TraktMutationExecutionResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * Contract test: Trakt returns 409 for /scrobble/stop and /scrobble/start when the item is
 * already in the user's history (e.g. scrobbled by another device). The adapter maps this to
 * [TraktMutationExecutionResult.Success] so the outbox does not retry forever.
 *
 * These tests use approach (b) — exercising the public [TraktScrobbleMutationAdapter.execute]
 * surface with a mocked [TraktIntegrationProvider] that returns Response.error(409, ...).
 * No production code was changed.
 */
class TraktScrobbleMutationAdapter409Test {

    private val traktIntegrationProvider = mockk<TraktIntegrationProvider>(relaxed = true)
    private val traktProgressService = mockk<TraktProgressService>(relaxed = true)
    private val controller = TraktWatchingNowStateController()
    private val adapter = TraktScrobbleMutationAdapter(
        traktIntegrationProvider = traktIntegrationProvider,
        traktProgressService = traktProgressService,
        watchingNowStateController = controller,
        playerSettingsDataStore = playerSettingsStore()
    )

    // ── /scrobble/stop ────────────────────────────────────────────────────────

    @Test
    fun `stop 409 already scrobbled is treated as success`() = runTest {
        val response409 = errorResponse409()
        coEvery { traktIntegrationProvider.scrobble(any(), action = "stop", body = any()) } returns response409

        val envelope = TraktScrobbleMutationAdapter.buildScrobbleEnvelope(
            item = movieItem(),
            action = "stop",
            progressPercent = 95f,
            rollbackState = TraktWatchingNowStateController.Snapshot(),
            optimisticVersion = 1L,
            session = testTraktSession()
        )

        val result = adapter.execute(envelope)

        assertTrue(
            "Trakt 409 on /scrobble/stop means 'already scrobbled' and must map to Success",
            result is TraktMutationExecutionResult.Success
        )
    }

    // ── /scrobble/start ───────────────────────────────────────────────────────

    @Test
    fun `start 409 already scrobbled is treated as success`() = runTest {
        val response409 = errorResponse409()
        coEvery { traktIntegrationProvider.scrobble(any(), action = "start", body = any()) } returns response409

        val envelope = TraktScrobbleMutationAdapter.buildScrobbleEnvelope(
            item = movieItem(),
            action = "start",
            progressPercent = 5f,
            rollbackState = TraktWatchingNowStateController.Snapshot(),
            optimisticVersion = 2L,
            session = testTraktSession()
        )

        val result = adapter.execute(envelope)

        assertTrue(
            "Trakt 409 on /scrobble/start must map to Success",
            result is TraktMutationExecutionResult.Success
        )
    }

    // ── /checkin (bonus — same guard at line 93 of the adapter) ───────────────

    @Test
    fun `checkin 409 already checked in is treated as success`() = runTest {
        val response409: Response<TraktCheckinResponseDto> =
            Response.error(409, "{}".toResponseBody("application/json".toMediaType()))
        coEvery { traktIntegrationProvider.checkin(any(), body = any()) } returns response409

        val envelope = TraktScrobbleMutationAdapter.buildCheckinEnvelope(
            item = movieItem(),
            message = null,
            rollbackState = TraktWatchingNowStateController.Snapshot(),
            optimisticVersion = 3L,
            session = testTraktSession()
        )

        val result = adapter.execute(envelope)

        assertTrue(
            "Trakt 409 on /checkin must map to Success",
            result is TraktMutationExecutionResult.Success
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Creates a 409 error response with an empty JSON body (for scrobble endpoints). */
    private fun errorResponse409(): Response<TraktScrobbleResponseDto> =
        Response.error(409, "{}".toResponseBody("application/json".toMediaType()))

    private fun movieItem() = TraktScrobbleItem.Movie(
        title = "Arrival",
        year = 2016,
        ids = TraktIdsDto(imdb = "tt2543164")
    )

    private fun playerSettingsStore(
        settings: PlayerSettings = PlayerSettings()
    ): PlayerSettingsDataStore =
        mockk<PlayerSettingsDataStore> {
            every { playerSettings } returns flowOf(settings)
        }
}
