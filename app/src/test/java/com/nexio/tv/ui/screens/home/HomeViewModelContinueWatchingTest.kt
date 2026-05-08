package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.metadata.router.testMetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.core.tvdb.TvMetadataDecision
import com.nexio.tv.core.tvdb.TvMetadataDecisionReason
import com.nexio.tv.core.tvdb.TvMetadataRouter
import com.nexio.tv.core.tvdb.TvProvider
import com.nexio.tv.core.tvdb.TvSeasonEpisode
import com.nexio.tv.data.repository.ContinueWatchingCanonicalKey
import com.nexio.tv.data.repository.ContinueWatchingRecord
import com.nexio.tv.data.repository.ContinueWatchingSource
import com.nexio.tv.data.repository.ContinueWatchingMetadataSnapshot
import com.nexio.tv.data.repository.ContinueWatchingSnapshot
import com.nexio.tv.data.repository.ContinueWatchingSnapshotService
import com.nexio.tv.data.repository.IdentityConfidence
import com.nexio.tv.data.repository.ResumeIdentity
import com.nexio.tv.data.repository.StreamFetchIdentity
import com.nexio.tv.data.repository.StreamIdScheme
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.TrackingProvider
import com.nexio.tv.domain.model.WatchProgress
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeViewModelContinueWatchingTest {

    @Test
    fun `continue watching snapshot records collapse aliases while preserving resume and stream identities`() = runTest {
        val localResume = watchProgress(
            contentId = "tvdb:393268",
            videoId = "tvdb:393268:2:1",
            position = 65_066L,
            duration = 2_958_656L,
            lastWatched = 100L,
            source = WatchProgress.SOURCE_LOCAL
        )
        val remoteResume = watchProgress(
            contentId = "tt9794044",
            videoId = "tt9794044:2:1",
            position = 0L,
            duration = 0L,
            lastWatched = 200L,
            source = WatchProgress.SOURCE_TRAKT_PLAYBACK
        )
        val localIdentity = localResume.toResumeIdentity(ContinueWatchingSource.LOCAL)
        val remoteIdentity = remoteResume.toResumeIdentity(ContinueWatchingSource.TRAKT_PLAYBACK)
        val record = ContinueWatchingRecord(
            profileId = 1,
            parentId = "series:tvdb:393268",
            contentId = "series:tvdb:393268:s2e1",
            provider = TrackingProvider.TRAKT,
            routingVersion = 1,
            positionMs = 65_066L,
            durationMs = 2_958_656L,
            episodeContext = ContinueWatchingRecord.EpisodeContext(2, 1),
            clickTimeDisplayMetadata = null,
            source = ContinueWatchingRecord.Source.LOCAL,
            updatedAt = 200L,
            canonicalKey = ContinueWatchingCanonicalKey(
                mediaKind = MetadataMediaKind.SERIES,
                canonicalParent = ContentIdentity(
                    canonicalProvider = ProviderId.TVDB,
                    canonicalId = "393268",
                    providerIds = ProviderIds(tvdb = "393268", imdb = "tt9794044")
                ),
                season = 2,
                episode = 1,
                profileId = 1
            ),
            streamFetchIdentity = StreamFetchIdentity(
                contentId = "tt9794044",
                videoId = "tt9794044:2:1",
                idScheme = StreamIdScheme.IMDB_EPISODE,
                confidence = IdentityConfidence.HIGH,
                trace = listOf("test")
            ),
            resumeIdentities = listOf(localIdentity, remoteIdentity),
            primaryResumeLookupKey = localIdentity.lookupKey()
        )

        val items = buildContinueWatchingItemsForSnapshot(
            snapshot = ContinueWatchingSnapshot(
                resumeItems = listOf(localResume, remoteResume),
                records = listOf(record)
            ),
            nowMs = 1_000L
        )

        assertEquals(1, items.size)
        val item = items.single() as ContinueWatchingItem.InProgress
        assertEquals(record.identityKey(), item.canonicalKey)
        assertEquals("tt9794044:2:1", item.streamFetchVideoId)
        assertEquals("tvdb:393268", item.progress.contentId)
        assertEquals("tvdb:393268:2:1", item.progress.videoId)
        assertEquals(65_066L, item.progress.position)
        assertEquals(200L, item.progress.lastWatched)
    }

    @Test
    fun `localized episode description uses matching in progress episode overview`() = runTest {
        val tvMetadataRouter = mockk<TvMetadataRouter>()
        coEvery {
            tvMetadataRouter.fetchSeasonEpisodes("tt0944947", "tt0944947", 2, null)
        } returns TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = listOf(
                TvSeasonEpisode(
                    episodeNumber = 5,
                    airDate = null,
                    metadata = episodeEnrichment("Nederlandse aflevering")
                )
            )
        )
        coEvery {
            tvMetadataRouter.fetchEpisodeEnrichment(any())
        } returns TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = mapOf((2 to 5) to episodeEnrichment("Nederlandse aflevering"))
        )
        val item = ContinueWatchingItem.InProgress(
            progress = WatchProgress(
                contentId = "tt0944947",
                contentType = "series",
                name = "Game of Thrones",
                poster = null,
                backdrop = null,
                logo = null,
                videoId = "tt0944947:2:5",
                season = 2,
                episode = 5,
                episodeTitle = "The Ghost of Harrenhal",
                position = 1_000L,
                duration = 3_000L,
                lastWatched = 42L
            ),
            episodeDescription = "English episode"
        )

        val description = localizedContinueWatchingEpisodeDescription(
            metadataRouterFacade = testMetadataRouterFacade(tvMetadataRouter),
            item = item
        )

        assertEquals("Nederlandse aflevering", description)
        coVerify(exactly = 1) {
            tvMetadataRouter.fetchEpisodeEnrichment(any())
        }
    }

    @Test
    fun `localized episode description uses matching next up episode overview`() = runTest {
        val tvMetadataRouter = mockk<TvMetadataRouter>()
        coEvery {
            tvMetadataRouter.fetchSeasonEpisodes("tt0944947", "tt0944947", 3, null)
        } returns TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = listOf(
                TvSeasonEpisode(
                    episodeNumber = 1,
                    airDate = null,
                    metadata = episodeEnrichment("Nederlandse volgende aflevering")
                )
            )
        )
        coEvery {
            tvMetadataRouter.fetchEpisodeEnrichment(any())
        } returns TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = mapOf((3 to 1) to episodeEnrichment("Nederlandse volgende aflevering"))
        )
        val item = ContinueWatchingItem.NextUp(
            NextUpInfo(
                contentId = "tt0944947",
                contentType = "series",
                name = "Game of Thrones",
                poster = null,
                backdrop = null,
                logo = null,
                videoId = "tt0944947:3:1",
                season = 3,
                episode = 1,
                episodeTitle = "Valar Dohaeris",
                episodeDescription = "English next episode",
                thumbnail = null,
                lastWatched = 42L
            )
        )

        val description = localizedContinueWatchingEpisodeDescription(
            metadataRouterFacade = testMetadataRouterFacade(tvMetadataRouter),
            item = item
        )

        assertEquals("Nederlandse volgende aflevering", description)
        coVerify(exactly = 1) {
            tvMetadataRouter.fetchEpisodeEnrichment(any())
        }
    }

    @Test
    fun `localized episode description skips non episodic items`() = runTest {
        val tvMetadataRouter = mockk<TvMetadataRouter>()
        val tmdbMetadataService = mockk<TmdbMetadataService>()
        val item = ContinueWatchingItem.InProgress(
            progress = WatchProgress(
                contentId = "tt123",
                contentType = "movie",
                name = "Movie",
                poster = null,
                backdrop = null,
                logo = null,
                videoId = "tt123",
                season = null,
                episode = null,
                episodeTitle = null,
                position = 1_000L,
                duration = 3_000L,
                lastWatched = 42L
            )
        )

        val description = localizedContinueWatchingEpisodeDescription(
            metadataRouterFacade = testMetadataRouterFacade(tvMetadataRouter),
            item = item
        )

        assertNull(description)
        coVerify(exactly = 0) {
            tvMetadataRouter.fetchEpisodeEnrichment(any())
        }
        coVerify(exactly = 0) {
            tmdbMetadataService.fetchEpisodeEnrichment(any(), any())
        }
    }

    @Test
    fun `continue watching playback click persists route context and click-time metadata`() = runTest {
        val viewModel = mockk<HomeViewModel>()
        val snapshotService = mockk<ContinueWatchingSnapshotService>()
        val snapshotSlot = slot<ContinueWatchingMetadataSnapshot>()
        val item = ContinueWatchingItem.InProgress(
            progress = WatchProgress(
                contentId = "tvdb:121361",
                contentType = "series",
                name = "Game of Thrones",
                poster = "poster",
                backdrop = "backdrop",
                logo = "logo",
                videoId = "tvdb:121361:1:1",
                season = 1,
                episode = 1,
                episodeTitle = "Winter Is Coming",
                position = 1_000L,
                duration = 3_000L,
                lastWatched = 42L
            )
        )
        every { viewModel.metadataRouterFacade } returns testMetadataRouterFacade(mockk(relaxed = true))
        every { viewModel.continueWatchingSnapshotService } returns snapshotService
        coJustRun { snapshotService.recordMetadataSnapshot("series:tvdb:121361", capture(snapshotSlot)) }

        viewModel.recordContinueWatchingRouteContextForPlayback(item)

        coVerify(exactly = 1) {
            snapshotService.recordMetadataSnapshot("series:tvdb:121361", any())
        }
        assertEquals("tvdb:121361", snapshotSlot.captured.parentId)
        assertEquals("Game of Thrones", snapshotSlot.captured.clickTimeDisplayMetadata.title)
        assertEquals(ContinueWatchingMetadataSnapshot.CURRENT_ROUTING_VERSION, snapshotSlot.captured.routingVersion)
    }

    private fun episodeEnrichment(overview: String?): TvEpisodeMetadata {
        return TvEpisodeMetadata(
            providerEpisodeId = null,
            title = null,
            overview = overview,
            thumbnail = null,
            airDate = null,
            runtimeMinutes = null
        )
    }

    private fun watchProgress(
        contentId: String,
        videoId: String,
        position: Long,
        duration: Long,
        lastWatched: Long,
        source: String
    ): WatchProgress {
        return WatchProgress(
            contentId = contentId,
            contentType = "series",
            name = "Citadel",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = videoId,
            season = 2,
            episode = 1,
            episodeTitle = "Episode 1",
            position = position,
            duration = duration,
            lastWatched = lastWatched,
            source = source
        )
    }

    private fun WatchProgress.toResumeIdentity(source: ContinueWatchingSource): ResumeIdentity {
        return ResumeIdentity(
            source = source,
            contentId = contentId,
            videoId = videoId,
            season = season,
            episode = episode,
            positionMs = position,
            durationMs = duration,
            progressPercent = progressPercent,
            lastWatchedMs = lastWatched
        )
    }
}
