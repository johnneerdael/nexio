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
import com.nexio.tv.data.repository.TrackingNextUpEntry
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.TitleRatingSource
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
    fun `movie snapshot title rating is not copied into episode imdb rating`() = runTest {
        val progress = WatchProgress(
            contentId = "tt28650488",
            contentType = "movie",
            name = "The Super Mario Galaxy Movie",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "tt28650488",
            season = null,
            episode = null,
            episodeTitle = null,
            position = 0L,
            duration = 0L,
            lastWatched = 1L,
            source = WatchProgress.SOURCE_TRAKT_PLAYBACK
        )

        val items = buildContinueWatchingItemsForSnapshot(
            snapshot = ContinueWatchingSnapshot(
                resumeItems = listOf(progress),
                displayMetadataByItemKey = mapOf(
                    "movie:tt28650488" to HomeDisplayMetadata(
                        title = "The Super Mario Galaxy Movie",
                        imdbRating = 7.2f,
                        ratingSource = TitleRatingSource.TMDB
                    )
                )
            ),
            nowMs = 1_000L
        )

        val item = items.single() as ContinueWatchingItem.InProgress
        assertNull(item.episodeImdbRating)
        assertEquals(7.2f, item.displayMetadata?.imdbRating)
        assertEquals(TitleRatingSource.TMDB, item.displayMetadata?.ratingSource)
    }

    @Test
    fun `canonical records render safe fallback resume when raw progress has invalid episode coordinate`() = runTest {
        val rawResume = watchProgress(
            contentId = "tt9794044",
            videoId = "tt9794044:0:1",
            position = 65_066L,
            duration = 2_958_656L,
            lastWatched = 200L,
            source = WatchProgress.SOURCE_TRAKT_PLAYBACK,
            season = 0,
            episode = 1
        )
        val safeIdentity = ResumeIdentity(
            source = ContinueWatchingSource.TRAKT_PLAYBACK,
            contentId = rawResume.contentId,
            videoId = rawResume.videoId,
            season = null,
            episode = null,
            positionMs = rawResume.position,
            durationMs = rawResume.duration,
            progressPercent = rawResume.progressPercent,
            lastWatchedMs = rawResume.lastWatched
        )
        val record = ContinueWatchingRecord(
            profileId = 1,
            parentId = "series:tvdb:393268",
            contentId = "series:tvdb:393268",
            provider = TrackingProvider.TRAKT,
            routingVersion = 1,
            positionMs = rawResume.position,
            durationMs = rawResume.duration,
            episodeContext = null,
            clickTimeDisplayMetadata = null,
            source = ContinueWatchingRecord.Source.REMOTE,
            updatedAt = rawResume.lastWatched,
            canonicalKey = ContinueWatchingCanonicalKey(
                mediaKind = MetadataMediaKind.SERIES,
                canonicalParent = ContentIdentity(
                    canonicalProvider = ProviderId.TVDB,
                    canonicalId = "393268",
                    providerIds = ProviderIds(tvdb = "393268", imdb = "tt9794044")
                ),
                season = null,
                episode = null,
                profileId = 1
            ),
            streamFetchIdentity = StreamFetchIdentity(
                contentId = "tt9794044",
                videoId = "tt9794044:0:1",
                idScheme = StreamIdScheme.UNRESOLVED,
                confidence = IdentityConfidence.LOW,
                trace = listOf("test")
            ),
            resumeIdentities = listOf(safeIdentity),
            primaryResumeLookupKey = safeIdentity.lookupKey()
        )

        val items = buildContinueWatchingItemsForSnapshot(
            snapshot = ContinueWatchingSnapshot(
                resumeItems = listOf(rawResume),
                records = listOf(record)
            ),
            nowMs = 1_000L
        )

        assertEquals(1, items.size)
        val item = items.single() as ContinueWatchingItem.InProgress
        assertEquals(record.identityKey(), item.canonicalKey)
        assertEquals("tt9794044", item.progress.contentId)
        assertNull(item.progress.season)
        assertNull(item.progress.episode)
    }

    @Test
    fun `canonical record with imdb primary resume suppresses tvdb next up for same show`() = runTest {
        val imdbResume = watchProgress(
            contentId = "tt9794044",
            videoId = "tt9794044:2:1",
            position = 65_066L,
            duration = 2_958_656L,
            lastWatched = 200L,
            source = WatchProgress.SOURCE_TRAKT_PLAYBACK
        )
        val imdbIdentity = imdbResume.toResumeIdentity(ContinueWatchingSource.TRAKT_PLAYBACK)
        val record = ContinueWatchingRecord(
            profileId = 1,
            parentId = "series:tvdb:393268",
            contentId = "series:tvdb:393268:s2e1",
            provider = TrackingProvider.TRAKT,
            routingVersion = 1,
            positionMs = imdbResume.position,
            durationMs = imdbResume.duration,
            episodeContext = ContinueWatchingRecord.EpisodeContext(2, 1),
            clickTimeDisplayMetadata = null,
            source = ContinueWatchingRecord.Source.REMOTE,
            updatedAt = imdbResume.lastWatched,
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
            resumeIdentities = listOf(imdbIdentity),
            primaryResumeLookupKey = imdbIdentity.lookupKey()
        )
        val tvdbNextUp = TrackingNextUpEntry(
            contentId = "tvdb:393268",
            contentType = "series",
            name = "Citadel",
            season = 2,
            episode = 2,
            episodeTitle = "Episode 2",
            videoId = "tvdb:393268:2:2",
            firstAired = null,
            firstAiredMs = 0L,
            activityAtMs = 190L
        )

        val items = buildContinueWatchingItemsForSnapshot(
            snapshot = ContinueWatchingSnapshot(
                resumeItems = listOf(imdbResume),
                nextUpItems = listOf(tvdbNextUp),
                records = listOf(record)
            ),
            nowMs = 1_000L
        )

        assertEquals(1, items.size)
        assertEquals(ContinueWatchingItem.InProgress::class, items.single()::class)
    }

    @Test
    fun `localized episode description uses matching in progress episode overview`() = runTest {
        val tvMetadataRouter = mockk<TvMetadataRouter>()
        coEvery {
            tvMetadataRouter.fetchSeasonEpisodes(any(), any(), any(), any())
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
                contentId = "tmdb:308014",
                contentType = "series",
                name = "Game of Thrones",
                poster = null,
                backdrop = null,
                logo = null,
                videoId = "tmdb:308014:2:5",
                season = 2,
                episode = 5,
                episodeTitle = "The Ghost of Harrenhal",
                position = 1_000L,
                duration = 3_000L,
                lastWatched = 42L
            ),
            episodeDescription = "English episode"
        )

        val description = localizedContinueWatchingEpisodeMetadata(
            metadataRouterFacade = testMetadataRouterFacade(tvMetadataRouter),
            item = item
        )?.overview

        assertEquals("Nederlandse aflevering", description)
        coVerify(exactly = 1) {
            tvMetadataRouter.fetchEpisodeEnrichment(any())
        }
    }

    @Test
    fun `localized episode description uses matching next up episode overview`() = runTest {
        val tvMetadataRouter = mockk<TvMetadataRouter>()
        coEvery {
            tvMetadataRouter.fetchSeasonEpisodes(any(), any(), any(), any())
        } returns TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = listOf(
                TvSeasonEpisode(
                    episodeNumber = 1,
                    airDate = null,
                    metadata = episodeEnrichment("Nederlandse volgende aflevering", title = "Nederlandse titel")
                )
            )
        )
        coEvery {
            tvMetadataRouter.fetchEpisodeEnrichment(any())
        } returns TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = mapOf((3 to 1) to episodeEnrichment("Nederlandse volgende aflevering", title = "Nederlandse titel"))
        )
        val item = ContinueWatchingItem.NextUp(
            NextUpInfo(
                contentId = "tmdb:308014",
                contentType = "series",
                name = "Game of Thrones",
                poster = null,
                backdrop = null,
                logo = null,
                videoId = "tmdb:308014:3:1",
                season = 3,
                episode = 1,
                episodeTitle = "Valar Dohaeris",
                episodeDescription = "English next episode",
                thumbnail = null,
                lastWatched = 42L
            )
        )

        val metadata = localizedContinueWatchingEpisodeMetadata(
            metadataRouterFacade = testMetadataRouterFacade(tvMetadataRouter),
            item = item
        )

        assertEquals("Nederlandse volgende aflevering", metadata?.overview)
        assertEquals("Nederlandse titel", metadata?.title)
        coVerify(exactly = 1) {
            tvMetadataRouter.fetchEpisodeEnrichment(any())
        }
    }

    @Test
    fun `continue watching snapshot publish does not downgrade localized next up episode title`() {
        val current = ContinueWatchingItem.NextUp(
            NextUpInfo(
                contentId = "tmdb:308014",
                contentType = "series",
                name = "Berlín y la dama del armiño",
                poster = null,
                backdrop = null,
                logo = null,
                videoId = "tmdb:308014:1:4",
                season = 1,
                episode = 4,
                episodeTitle = "Chinese sinaasappels",
                episodeDescription = "Nederlandse aflevering",
                thumbnail = null,
                lastWatched = 42L
            )
        )
        val incomingRawSnapshot = ContinueWatchingItem.NextUp(
            current.info.copy(
                episodeTitle = "Oranges from China",
                episodeDescription = "English episode"
            )
        )

        val merged = preserveContinueWatchingEpisodeText(
            incoming = listOf(incomingRawSnapshot),
            current = listOf(current)
        )

        val item = merged.single() as ContinueWatchingItem.NextUp
        assertEquals("Chinese sinaasappels", item.info.episodeTitle)
        assertEquals("Nederlandse aflevering", item.info.episodeDescription)
    }

    @Test
    fun `continue watching snapshot publish does not preserve localized episode title across episode changes`() {
        val current = ContinueWatchingItem.NextUp(
            NextUpInfo(
                contentId = "tmdb:308014",
                contentType = "series",
                name = "Berlín y la dama del armiño",
                poster = null,
                backdrop = null,
                logo = null,
                videoId = "tmdb:308014:1:4",
                season = 1,
                episode = 4,
                episodeTitle = "Chinese sinaasappels",
                episodeDescription = "Nederlandse aflevering",
                thumbnail = null,
                lastWatched = 42L
            )
        )
        val incomingNextEpisode = ContinueWatchingItem.NextUp(
            current.info.copy(
                videoId = "tmdb:308014:1:5",
                episode = 5,
                episodeTitle = "After Love",
                episodeDescription = "Next English episode"
            )
        )

        val merged = preserveContinueWatchingEpisodeText(
            incoming = listOf(incomingNextEpisode),
            current = listOf(current)
        )

        val item = merged.single() as ContinueWatchingItem.NextUp
        assertEquals("After Love", item.info.episodeTitle)
        assertEquals("Next English episode", item.info.episodeDescription)
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

        val description = localizedContinueWatchingEpisodeMetadata(
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
        assertEquals("Game of Thrones", snapshotSlot.captured.clickTimeSlots.title.value)
        assertEquals(ContinueWatchingMetadataSnapshot.CURRENT_ROUTING_VERSION, snapshotSlot.captured.routingVersion)
    }

    private fun episodeEnrichment(overview: String?, title: String? = null): TvEpisodeMetadata {
        return TvEpisodeMetadata(
            providerEpisodeId = null,
            title = title,
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
        source: String,
        season: Int? = 2,
        episode: Int? = 1
    ): WatchProgress {
        return WatchProgress(
            contentId = contentId,
            contentType = "series",
            name = "Citadel",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = videoId,
            season = season,
            episode = episode,
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
