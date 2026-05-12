package com.nexio.tv.data.repository.simkl

import com.nexio.tv.data.repository.testSimklSession
import com.nexio.tv.data.remote.dto.simkl.SimklScrobbleResponseDto
import com.nexio.tv.data.repository.SimklProgressService
import com.nexio.tv.data.repository.SimklTrackingRemoteDataSource
import com.nexio.tv.data.repository.TrackingAuthSession
import com.nexio.tv.data.repository.TrackingScrobbleItem
import com.nexio.tv.data.repository.trakt.TraktWatchingNowStateController
import com.nexio.tv.data.trakt.outbox.TraktMutationExecutionResult
import com.nexio.tv.data.trakt.outbox.TraktMutationSettlement
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.TrackingProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class SimklScrobbleMutationAdapterTest {

    @Test
    fun `scrobble envelope keeps item collapse key and rollback metadata`() {
        val envelope = SimklScrobbleMutationAdapter.buildScrobbleEnvelope(
            item = movieItem("Arrival"),
            action = "start",
            progressPercent = 14f,
            rollbackState = TraktWatchingNowStateController.Snapshot(
                active = false,
                title = "Server truth",
                contentType = "movie"
            ),
            optimisticVersion = 7L,
            session = testSimklSession()
        )

        assertEquals("simkl.scrobble:movie:tt1234567:2025", envelope.collapseKey)
        assertEquals(SimklScrobbleMutationAdapter.MUTATION_KIND_SCROBBLE, envelope.mutationKind)
    }

    @Test
    fun `adapter rolls back watching state when terminal failure settles`() = kotlinx.coroutines.test.runTest {
        val remote = mockk<SimklTrackingRemoteDataSource>(relaxed = true)
        val progressService = mockk<SimklProgressService>(relaxed = true)
        val controller = TraktWatchingNowStateController()
        val adapter = SimklScrobbleMutationAdapter(remote, progressService, controller)
        controller.publish(
            TraktWatchingNowStateController.Snapshot(
                active = true,
                title = "Arrival",
                contentType = "movie"
            )
        )
        val envelope = SimklScrobbleMutationAdapter.buildCheckinEnvelope(
            item = movieItem("Arrival"),
            message = null,
            rollbackState = TraktWatchingNowStateController.Snapshot(),
            optimisticVersion = controller.nextOptimisticVersion(),
            session = testSimklSession()
        )

        adapter.rollbackToServerTruth(
            envelope = envelope,
            failure = TraktMutationSettlement.TerminalFailure(reason = "boom", httpStatusCode = 500)
        )

        assertEquals(null, controller.current().title)
        assertTrue(!controller.current().active)
    }

    @Test
    fun `checkin execute returns success when simkl accepts request`() = kotlinx.coroutines.test.runTest {
        val remote = mockk<SimklTrackingRemoteDataSource>()
        val progressService = mockk<SimklProgressService>(relaxed = true)
        val controller = TraktWatchingNowStateController()
        val adapter = SimklScrobbleMutationAdapter(remote, progressService, controller)
        val session = slot<TrackingAuthSession>()
        val envelope = SimklScrobbleMutationAdapter.buildCheckinEnvelope(
            item = movieItem("Arrival"),
            message = null,
            rollbackState = TraktWatchingNowStateController.Snapshot(),
            optimisticVersion = 1L,
            session = testSimklSession(profileId = 2)
        )

        coEvery { remote.checkin(any(), capture(session)) } returns Response.success(SimklScrobbleResponseDto(action = "checkin"))

        val result = adapter.execute(envelope)

        assertTrue(result is TraktMutationExecutionResult.Success)
        assertEquals(TrackingProvider.SIMKL, session.captured.provider)
        assertEquals(2, session.captured.profileId)
        coVerify(exactly = 0) { progressService.refreshNow() }
    }

    @Test
    fun `reconcile success refreshes simkl progress state`() = kotlinx.coroutines.test.runTest {
        val remote = mockk<SimklTrackingRemoteDataSource>(relaxed = true)
        val progressService = mockk<SimklProgressService>(relaxed = true)
        val controller = TraktWatchingNowStateController()
        val adapter = SimklScrobbleMutationAdapter(remote, progressService, controller)
        val envelope = SimklScrobbleMutationAdapter.buildScrobbleEnvelope(
            item = movieItem("Arrival"),
            action = "stop",
            progressPercent = 95f,
            rollbackState = TraktWatchingNowStateController.Snapshot(),
            optimisticVersion = 1L,
            session = testSimklSession()
        )

        adapter.reconcileSuccess(envelope)

        coVerify(exactly = 1) { progressService.refreshNow() }
    }

    @Test
    fun `movie envelope uses hydratedIds when present`() {
        val envelope = SimklScrobbleMutationAdapter.buildScrobbleEnvelope(
            item = TrackingScrobbleItem.Movie(
                contentId = "tmdb:1396",
                title = "Breaking Bad",
                year = 2008,
                hydratedIds = ProviderIds(imdb = "tt0903747", tmdb = "1396", simkl = "5045"),
            ),
            action = "start",
            progressPercent = 10f,
            rollbackState = TraktWatchingNowStateController.Snapshot(),
            optimisticVersion = 1L,
            session = testSimklSession()
        )

        val payload = envelope.payload
        assertEquals("tt0903747", payload.get("imdb").asString)
        assertEquals("1396", payload.get("tmdb").asString)
        assertEquals(5045L, payload.get("simkl").asLong)
    }

    @Test
    fun `episode envelope uses hydratedIds for show ids`() {
        val envelope = SimklScrobbleMutationAdapter.buildScrobbleEnvelope(
            item = TrackingScrobbleItem.Episode(
                contentId = "tmdb:1396",
                showTitle = "Breaking Bad",
                showYear = 2008,
                season = 5,
                number = 14,
                episodeTitle = "Ozymandias",
                hydratedIds = ProviderIds(imdb = "tt0903747", tmdb = "1396", simkl = "5045"),
            ),
            action = "start",
            progressPercent = 10f,
            rollbackState = TraktWatchingNowStateController.Snapshot(),
            optimisticVersion = 1L,
            session = testSimklSession()
        )

        val payload = envelope.payload
        assertEquals("tt0903747", payload.get("showImdb").asString)
        assertEquals("1396", payload.get("showTmdb").asString)
        assertEquals(5045L, payload.get("showSimkl").asLong)
        assertEquals(5, payload.get("season").asInt)
        assertEquals(14, payload.get("number").asInt)
    }

    @Test
    fun `envelope falls back to contentId parse when hydratedIds is null`() {
        val envelope = SimklScrobbleMutationAdapter.buildScrobbleEnvelope(
            item = TrackingScrobbleItem.Movie(
                contentId = "tt1234567",
                title = "x",
                year = 2025,
                hydratedIds = null,
            ),
            action = "start",
            progressPercent = 10f,
            rollbackState = TraktWatchingNowStateController.Snapshot(),
            optimisticVersion = 1L,
            session = testSimklSession()
        )

        val payload = envelope.payload
        assertEquals("tt1234567", payload.get("imdb").asString)
        assertNull(payload.get("tmdb"))
        assertNull(payload.get("simkl"))
    }

    @Test
    fun `episode with anime IDs routes to anime parent and includes anime ids`() {
        val envelope = SimklScrobbleMutationAdapter.buildScrobbleEnvelope(
            item = TrackingScrobbleItem.Episode(
                contentId = "kitsu:1",
                showTitle = "Cowboy Bebop",
                showYear = 1998,
                season = 1, number = 5,
                episodeTitle = "Ballad of Fallen Angels",
                hydratedIds = ProviderIds(
                    kitsu = "1", mal = "1", anilist = "1", anidb = "10",
                    imdb = "tt0213338", tmdb = "30991", simkl = "5045",
                ),
            ),
            action = "start",
            progressPercent = 10f,
            rollbackState = TraktWatchingNowStateController.Snapshot(),
            optimisticVersion = 1L,
            session = testSimklSession()
        )

        val payload = envelope.payload
        // Anime detection routes to the "anime" parent envelope, not "show".
        assertEquals("anime", payload.get("parentKind").asString)
        // Anime IDs flow through.
        assertEquals("1", payload.get("showMal").asString)
        assertEquals("1", payload.get("showAnilist").asString)
        assertEquals("1", payload.get("showKitsu").asString)
        assertEquals("10", payload.get("showAnidb").asString)
        // Plus the usual show IDs.
        assertEquals("tt0213338", payload.get("showImdb").asString)
        assertEquals(5045L, payload.get("showSimkl").asLong)
    }

    @Test
    fun `non-anime episode keeps show parent`() {
        val envelope = SimklScrobbleMutationAdapter.buildScrobbleEnvelope(
            item = TrackingScrobbleItem.Episode(
                contentId = "tt0903747",
                showTitle = "Breaking Bad",
                showYear = 2008,
                season = 5, number = 14,
                episodeTitle = "Ozymandias",
                hydratedIds = ProviderIds(imdb = "tt0903747", tmdb = "1396"),
            ),
            action = "start",
            progressPercent = 10f,
            rollbackState = TraktWatchingNowStateController.Snapshot(),
            optimisticVersion = 1L,
            session = testSimklSession()
        )

        assertEquals("show", envelope.payload.get("parentKind").asString)
    }

    private fun movieItem(title: String) = TrackingScrobbleItem.Movie(
        contentId = "tt1234567",
        title = title,
        year = 2025
    )
}
