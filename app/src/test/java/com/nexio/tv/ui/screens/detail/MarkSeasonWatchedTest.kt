package com.nexio.tv.ui.screens.detail

import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataResolutionResult
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.ResolvedMetadataDocument
import com.nexio.tv.core.metadata.router.ResolverSchedule
import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.core.tvdb.TvMetadataDecision
import com.nexio.tv.core.tvdb.TvMetadataDecisionReason
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.core.tvdb.TvMetadataRouter
import com.nexio.tv.core.tvdb.TvProvider
import com.nexio.tv.core.tvdb.TvSeasonEpisode
import com.nexio.tv.data.local.TraktAuthDataStore
import com.nexio.tv.data.remote.api.TmdbEpisode
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.data.repository.ContinueWatchingSnapshotService
import com.nexio.tv.data.repository.EffectiveTrackingProviderState
import com.nexio.tv.data.repository.TrackingProgressService
import com.nexio.tv.data.repository.TrackingProviderStateService
import com.nexio.tv.data.repository.trakt.SeasonMarkBatcher
import com.nexio.tv.data.repository.trakt.TraktEpisodeRef
import com.nexio.tv.data.repository.simkl.SimklSeasonMarkMutationAdapter
import com.nexio.tv.data.trakt.outbox.TraktMutationEnvelope
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.SeasonEpisodeMark
import com.nexio.tv.domain.model.Video
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.domain.repository.WatchProgressRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import javax.inject.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class MarkSeasonWatchedTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Test 1: usesAuthoritativeEpisodeList ──────────────────────────────────

    @Test
    fun `uses TVDB season episodes when TVDB succeeds`() =
        runTest(dispatcher) {
            val watchProgressRepository = mockk<WatchProgressRepository>(relaxed = true)
            val pastDate = "2020-01-01"
            val futureDate = "2099-12-31"

            // TVDB-sourced episodes: ep1=aired, ep2=future (filtered out), ep3=null airDate (included)
            val tvdbEpisodes = listOf(
                tvSeasonEpisode(1, airDate = pastDate),
                tvSeasonEpisode(2, airDate = futureDate),
                tvSeasonEpisode(3, airDate = null)
            )
            val facade = mockk<MetadataRouterFacade>(relaxed = true)
            coEvery { facade.resolveRequest(any()) } returns buildDefaultResolutionResult("tt9999999")
            coEvery { facade.fetchTvEnrichment(any(), any()) } returns noEnrichmentDecision()
            coEvery { facade.fetchTvSeasonEpisodes(any(), any(), any(), any(), any()) } returns TvMetadataDecision(
                provider = TvProvider.TVDB,
                reason = TvMetadataDecisionReason.TVDB_SUCCESS,
                value = tvdbEpisodes
            )
            // Stub fetchTvEpisodeEnrichment so the mandatory-episode blocking path in
            // applyMetaWithEnrichment (introduced by d2955c201) resolves and applyMeta is called.
            // Without this, the relaxed-mock return value produces an empty episodeMap and the
            // ViewModel sets an error state before OnMarkSeasonWatched can be handled.
            coEvery { facade.fetchTvEpisodeEnrichment(any(), any()) } returns TvMetadataDecision(
                provider = TvProvider.TVDB,
                reason = TvMetadataDecisionReason.TVDB_SUCCESS,
                value = mapOf(
                    (1 to 1) to TvEpisodeMetadata(
                        providerEpisodeId = "tvdb:1",
                        seasonNumber = 1,
                        episodeNumber = 1,
                        airDate = "2020-01-01"
                    )
                )
            )

            coEvery { watchProgressRepository.markAsCompletedBatch(any(), any(), any()) } returns Unit
            val batchSlot = slot<List<SeasonEpisodeMark>>()
            coEvery { watchProgressRepository.markAsCompletedBatch(any(), eq(1), capture(batchSlot)) } returns Unit

            val meta = buildSeriesMeta(id = "tt9999999", videos = emptyList())
            val viewModel = buildViewModel(
                meta = meta,
                watchProgressRepository = watchProgressRepository,
                metadataRouterFacade = facade
            )
            advanceUntilIdle()

            viewModel.onEvent(MetaDetailsEvent.OnMarkSeasonWatched(1))
            advanceUntilIdle()

            // ep1 (aired) and ep3 (null airDate = no future gate) are included; ep2 (future) excluded
            assertEquals(listOf(1, 3), batchSlot.captured.map { it.episodeNumber })
            // facade.fetchTvSeasonEpisodes was the entry point — verify it was called
            coVerify(atLeast = 1) { facade.fetchTvSeasonEpisodes(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `usesAuthoritativeEpisodeList - fetchSeasonEpisodes called with TMDB id from ensureTmdbId`() =
        runTest(dispatcher) {
            val watchProgressRepository = mockk<WatchProgressRepository>(relaxed = true)

            // Two TMDB-sourced episodes for S1 — the facade is the new entry point
            val episodes = listOf(
                tvSeasonEpisode(1, airDate = "2020-01-01"),
                tvSeasonEpisode(2, airDate = "2020-01-08")
            )
            val facade = mockk<MetadataRouterFacade>(relaxed = true)
            coEvery { facade.resolveRequest(any()) } returns buildDefaultResolutionResult("tt9999999")
            coEvery { facade.fetchTvEnrichment(any(), any()) } returns noEnrichmentDecision()
            coEvery { facade.fetchTvSeasonEpisodes(any(), any(), any(), eq(1), any()) } returns TvMetadataDecision(
                provider = TvProvider.TMDB,
                reason = TvMetadataDecisionReason.TVDB_FALLBACK_TMDB,
                value = episodes
            )
            // Stub fetchTvEpisodeEnrichment so the mandatory-episode blocking path resolves.
            coEvery { facade.fetchTvEpisodeEnrichment(any(), any()) } returns TvMetadataDecision(
                provider = TvProvider.TVDB,
                reason = TvMetadataDecisionReason.TVDB_SUCCESS,
                value = mapOf(
                    (1 to 1) to TvEpisodeMetadata(
                        providerEpisodeId = "tvdb:1",
                        seasonNumber = 1,
                        episodeNumber = 1,
                        airDate = "2020-01-01"
                    )
                )
            )

            coEvery { watchProgressRepository.markAsCompletedBatch(any(), any(), any()) } returns Unit
            val batchSlot = slot<List<SeasonEpisodeMark>>()
            coEvery { watchProgressRepository.markAsCompletedBatch(any(), eq(1), capture(batchSlot)) } returns Unit

            val meta = buildSeriesMeta(id = "tt9999999", videos = emptyList())
            val viewModel = buildViewModel(
                meta = meta,
                watchProgressRepository = watchProgressRepository,
                metadataRouterFacade = facade
            )
            advanceUntilIdle()

            viewModel.onEvent(MetaDetailsEvent.OnMarkSeasonWatched(1))
            advanceUntilIdle()

            // facade.fetchTvSeasonEpisodes was invoked for season 1
            coVerify(atLeast = 1) { facade.fetchTvSeasonEpisodes(any(), any(), any(), eq(1), any()) }
            // Both episodes were passed to the batch
            assertEquals(2, batchSlot.captured.size)
        }

    // ── Test 2: lazyHydrationBugRegression ───────────────────────────────────

    @Test
    fun `lazyHydrationBugRegression - TMDB fetch used even when meta videos is sparse`() =
        runTest(dispatcher) {
            val watchProgressRepository = mockk<WatchProgressRepository>(relaxed = true)

            // meta.videos only has S1/S2 episodes (12 episodes), NOT S3
            val sparseVideos = (1..6).map { ep -> buildVideo(season = 1, episode = ep) } +
                (1..6).map { ep -> buildVideo(season = 2, episode = ep) }

            // Facade returns 24 episodes for S3 — must NOT fall back to meta.videos (which has 0 S3 eps)
            val season3Episodes = (1..24).map { ep -> tvSeasonEpisode(ep, airDate = "2020-01-01") }
            val facade = mockk<MetadataRouterFacade>(relaxed = true)
            coEvery { facade.resolveRequest(any()) } returns buildDefaultResolutionResult("tt1111111")
            coEvery { facade.fetchTvEnrichment(any(), any()) } returns noEnrichmentDecision()
            coEvery { facade.fetchTvSeasonEpisodes(any(), any(), any(), eq(3), any()) } returns TvMetadataDecision(
                provider = TvProvider.TMDB,
                reason = TvMetadataDecisionReason.TVDB_FALLBACK_TMDB,
                value = season3Episodes
            )

            val batchSlot = slot<List<com.nexio.tv.domain.model.SeasonEpisodeMark>>()
            coEvery { watchProgressRepository.markAsCompletedBatch(any(), eq(3), capture(batchSlot)) } returns Unit

            val meta = buildSeriesMeta(id = "tt1111111", videos = sparseVideos)
            val viewModel = buildViewModel(
                meta = meta,
                watchProgressRepository = watchProgressRepository,
                metadataRouterFacade = facade
            )
            advanceUntilIdle()

            viewModel.onEvent(MetaDetailsEvent.OnMarkSeasonWatched(3))
            advanceUntilIdle()

            // Must have used facade fetch (24 episodes), not meta.videos (0 S3 episodes)
            assertEquals(24, batchSlot.captured.size)
        }

    // ── Test 3: partiallyAiredSeasonOnlyMarksAired ───────────────────────────

    @Test
    fun `partiallyAiredSeasonOnlyMarksAired - unaired episodes excluded from batch`() =
        runTest(dispatcher) {
            val watchProgressRepository = mockk<WatchProgressRepository>(relaxed = true)

            val pastDate = "2020-01-01"   // always in the past
            val futureDate = "2099-12-31" // always in the future

            // 7 aired + 3 future episodes for S2; facade returns all 10, VM must filter to 7
            val episodes = (1..7).map { ep -> tvSeasonEpisode(ep, airDate = pastDate) } +
                (8..10).map { ep -> tvSeasonEpisode(ep, airDate = futureDate) }

            val facade = mockk<MetadataRouterFacade>(relaxed = true)
            coEvery { facade.resolveRequest(any()) } returns buildDefaultResolutionResult("tt5555555")
            coEvery { facade.fetchTvEnrichment(any(), any()) } returns noEnrichmentDecision()
            coEvery { facade.fetchTvSeasonEpisodes(any(), any(), any(), eq(2), any()) } returns TvMetadataDecision(
                provider = TvProvider.TMDB,
                reason = TvMetadataDecisionReason.TVDB_FALLBACK_TMDB,
                value = episodes
            )
            // Stub fetchTvEpisodeEnrichment so the mandatory-episode blocking path resolves.
            coEvery { facade.fetchTvEpisodeEnrichment(any(), any()) } returns TvMetadataDecision(
                provider = TvProvider.TVDB,
                reason = TvMetadataDecisionReason.TVDB_SUCCESS,
                value = mapOf(
                    (1 to 1) to TvEpisodeMetadata(
                        providerEpisodeId = "tvdb:1",
                        seasonNumber = 1,
                        episodeNumber = 1,
                        airDate = "2020-01-01"
                    )
                )
            )

            val batchSlot = slot<List<com.nexio.tv.domain.model.SeasonEpisodeMark>>()
            coEvery { watchProgressRepository.markAsCompletedBatch(any(), eq(2), capture(batchSlot)) } returns Unit

            val viewModel = buildViewModel(
                meta = buildSeriesMeta(),
                watchProgressRepository = watchProgressRepository,
                metadataRouterFacade = facade
            )
            advanceUntilIdle()

            viewModel.onEvent(MetaDetailsEvent.OnMarkSeasonWatched(2))
            advanceUntilIdle()

            assertEquals(7, batchSlot.captured.size)
            assertEquals((1..7).toList(), batchSlot.captured.map { it.episodeNumber })
        }

    // ── Test 4: exactlyOneBatchedPostForFullSeason ───────────────────────────

    @Test
    fun `exactlyOneBatchedPostForFullSeason - markAsCompletedBatch called once for 24 episodes`() =
        runTest(dispatcher) {
            val watchProgressRepository = mockk<WatchProgressRepository>(relaxed = true)

            val episodes24 = (1..24).map { ep -> tvSeasonEpisode(ep, airDate = "2020-01-01") }
            val facade = mockk<MetadataRouterFacade>(relaxed = true)
            coEvery { facade.resolveRequest(any()) } returns buildDefaultResolutionResult("tt5555555")
            coEvery { facade.fetchTvEnrichment(any(), any()) } returns noEnrichmentDecision()
            coEvery { facade.fetchTvSeasonEpisodes(any(), any(), any(), eq(1), any()) } returns TvMetadataDecision(
                provider = TvProvider.TMDB,
                reason = TvMetadataDecisionReason.TVDB_FALLBACK_TMDB,
                value = episodes24
            )
            // Stub fetchTvEpisodeEnrichment so the mandatory-episode blocking path resolves.
            coEvery { facade.fetchTvEpisodeEnrichment(any(), any()) } returns TvMetadataDecision(
                provider = TvProvider.TVDB,
                reason = TvMetadataDecisionReason.TVDB_SUCCESS,
                value = mapOf(
                    (1 to 1) to TvEpisodeMetadata(
                        providerEpisodeId = "tvdb:1",
                        seasonNumber = 1,
                        episodeNumber = 1,
                        airDate = "2020-01-01"
                    )
                )
            )

            val batchSlot = slot<List<com.nexio.tv.domain.model.SeasonEpisodeMark>>()
            coEvery { watchProgressRepository.markAsCompletedBatch(any(), eq(1), capture(batchSlot)) } returns Unit

            val viewModel = buildViewModel(
                meta = buildSeriesMeta(),
                watchProgressRepository = watchProgressRepository,
                metadataRouterFacade = facade
            )
            advanceUntilIdle()

            viewModel.onEvent(MetaDetailsEvent.OnMarkSeasonWatched(1))
            advanceUntilIdle()

            // Exactly one call with all 24 episodes
            coVerify(exactly = 1) { watchProgressRepository.markAsCompletedBatch(any(), 1, any()) }
            assertEquals(24, batchSlot.captured.size)

            // No direct single-episode addToHistory calls during this flow
            coVerify(exactly = 0) { watchProgressRepository.markAsCompleted(any()) }
        }

    // ── Test 5: optimisticStateAppliedAtomically (M1, M3) ────────────────────

    @Test
    fun `optimisticStateAppliedAtomicallyThroughRawSnapshot - 24 episodes removed in single update`() =
        runTest(dispatcher) {
            val showId = "tt2222222"
            val season = 1

            // Build a real ContinueWatchingSnapshotService (auth=false so the upstream
            // combine never fires — rawSnapshotState stays driven solely by mutation helpers).
            val service = buildService()

            // Seed via the real reinsertResumeEntry helper so rawSnapshotState has 24 entries.
            val resumeItems = (1..24).map { ep ->
                WatchProgress(
                    contentId = showId,
                    contentType = "series",
                    name = "Show",
                    poster = null,
                    backdrop = null,
                    logo = null,
                    videoId = "$showId:$season:$ep",
                    season = season,
                    episode = ep,
                    episodeTitle = null,
                    position = 50L,
                    duration = 100L,
                    lastWatched = ep.toLong() * 1000L,
                    progressPercent = 50f
                )
            }
            resumeItems.forEach { service.reinsertResumeEntry(it) }

            // Pre-condition: all 24 entries present in rawSnapshotState.
            assertEquals(
                "All 24 resume items must be seeded before marking",
                24,
                service.snapshotForRollback().resumeItems.size
            )

            val episodeRefs = (1..24).map { ep ->
                ContinueWatchingSnapshotService.EpisodeRef(
                    showId = showId,
                    seasonNumber = season,
                    episodeNumber = ep
                )
            }

            // applyEpisodesMarked uses rawSnapshotState.update {} under refreshMutex —
            // the removal is atomic: there is no observable intermediate partial state.
            service.applyEpisodesMarked(episodeRefs)

            // Post-condition: all 24 removed in a single atomic update.
            assertEquals(
                "All 24 resume items must be removed atomically — no items should remain",
                0,
                service.snapshotForRollback().resumeItems.size
            )
        }

    // ── Helpers shared with ContinueWatchingSnapshotServiceMutationTest ────────

    private fun buildService(): ContinueWatchingSnapshotService {
        val trackingProviderStateService = mockk<TrackingProviderStateService>(relaxed = true) {
            every { state } returns flowOf(EffectiveTrackingProviderState())
        }
        val traktSettingsDataStore = mockk<TraktSettingsDataStore>(relaxed = true) {
            every { dismissedNextUpKeys } returns flowOf(emptySet())
        }
        val traktProgressService = mockk<TrackingProgressService>(relaxed = true) {
            every { observeRemoteSnapshotLoaded() } returns flowOf(false)
            every { observeContinueWatchingNextUp() } returns flowOf(emptyList())
            every { observeSyntheticContinueWatchingNextUp() } returns flowOf(emptyList())
        }
        return ContinueWatchingSnapshotService(
            watchProgressRepository = mockk(relaxed = true) {
                every { allProgress } returns flowOf(emptyList())
            },
            trackingProgressService = traktProgressService,
            trackingProviderStateService = trackingProviderStateService,
            traktSettingsDataStore = traktSettingsDataStore,
            metadataDiskCacheStore = mockk(relaxed = true),
            snapshotStore = mockk(relaxed = true) { every { read(any()) } returns null }
        )
    }

    // ── Test 6: partialBatchHandledAsync (C1) ──────────────────────────────

    @Test
    fun `partialBatchHandledAsync - repository enqueues resolved season refs without waiting for settlement`() =
        runTest(dispatcher) {
            val showId = "tt3333333"
            val season = 2

            // 10 resume items in snapshot
            val resumeItems = (1..10).map { ep ->
                WatchProgress(
                    contentId = showId,
                    contentType = "series",
                    name = "Show",
                    poster = null,
                    backdrop = null,
                    logo = null,
                    videoId = "$showId:$season:$ep",
                    season = season,
                    episode = ep,
                    episodeTitle = null,
                    position = 50L,
                    duration = 100L,
                    lastWatched = ep.toLong() * 1000L,
                    progressPercent = 50f
                )
            }

            val traktProgressService = mockk<TrackingProgressService>(relaxed = true)
            coEvery {
                traktProgressService.resolveSeasonEpisodeTraktIds(showId, season, any())
            } returns (1..10).associate { epNum ->
                epNum to TraktEpisodeRef(
                    episodeNumber = epNum,
                    traktId = epNum
                )
            }

            val seasonMarkBatcher = mockk<SeasonMarkBatcher>()
            coEvery { seasonMarkBatcher.markSeasonWatched(any(), any(), any(), any()) } returns Unit

            val snapshotService = mockk<ContinueWatchingSnapshotService>(relaxed = true)
            every { snapshotService.snapshotForEpisodes(any()) } returns ContinueWatchingSnapshotService.EpisodeRollbackState(
                resumeItems = resumeItems
            )

            val trackingProviderStateService = mockk<TrackingProviderStateService>(relaxed = true) {
                every { state } returns flowOf(EffectiveTrackingProviderState(traktAuthenticated = true))
                coEvery { currentState() } returns EffectiveTrackingProviderState(traktAuthenticated = true)
            }

            val repo = com.nexio.tv.data.repository.WatchProgressRepositoryImpl(
                watchProgressPreferences = mockk(relaxed = true),
                trackingProviderStateService = trackingProviderStateService,
                trackingProgressService = traktProgressService,
                traktMutationOutboxCoordinator = mockk(relaxed = true),
                seasonMarkBatcher = seasonMarkBatcher,
                traktAuthService = mockk(relaxed = true) {
                    every { currentTraktProfileId() } returns 1
                },
                snapshotServiceProvider = Provider { snapshotService },
                metadataRouterFacade = mockk(relaxed = true)
            )

            val meta = buildSeriesMeta(id = showId)
            val episodes = (1..10).map(::seasonEpisodeMark)

            repo.markAsCompletedBatch(meta, season, episodes)

            val refsSlot = slot<List<TraktEpisodeRef>>()
            val rollbackSlot = slot<ContinueWatchingSnapshotService.EpisodeRollbackState>()
            coVerify(exactly = 1) {
                seasonMarkBatcher.markSeasonWatched(
                    showId,
                    season,
                    capture(refsSlot),
                    capture(rollbackSlot)
                )
            }
            assertEquals(
                (1..10).toSet(),
                refsSlot.captured.map { it.episodeNumber }.toSet()
            )
            assertEquals(10, rollbackSlot.captured.resumeItems.size)
            coVerify(exactly = 0) { snapshotService.rollbackEpisodes(any<ContinueWatchingSnapshotService.EpisodeRollbackState>()) }
            coVerify(exactly = 0) { snapshotService.ensureFresh(force = true) }
        }

    // ── Test 6.5: unresolvedEpisodeRolloutRollback (N6) ─────────────────

    @Test
    fun `unresolvedEpisodesRollback - ids that fail Trakt ID resolution are rolled back`() =
        runTest(dispatcher) {
            val showId = "tt4444445"
            val season = 1

            val resumeItems = (1..8).map { ep ->
                WatchProgress(
                    contentId = showId,
                    contentType = "series",
                    name = "Show",
                    poster = null,
                    backdrop = null,
                    logo = null,
                    videoId = "$showId:$season:$ep",
                    season = season,
                    episode = ep,
                    episodeTitle = null,
                    position = 50L,
                    duration = 100L,
                    lastWatched = ep.toLong() * 1000L,
                    progressPercent = 50f
                )
            }

            val traktProgressService = mockk<TrackingProgressService>(relaxed = true)
            coEvery {
                traktProgressService.resolveSeasonEpisodeTraktIds(showId, season, any())
            } returns mapOf(
                1 to TraktEpisodeRef(episodeNumber = 1, traktId = 101),
                2 to TraktEpisodeRef(episodeNumber = 2, traktId = 102),
                4 to TraktEpisodeRef(episodeNumber = 4, traktId = 104),
                5 to TraktEpisodeRef(episodeNumber = 5, traktId = 105),
                7 to TraktEpisodeRef(episodeNumber = 7, traktId = 107),
            )

            val seasonMarkBatcher = mockk<SeasonMarkBatcher>()
            coEvery { seasonMarkBatcher.markSeasonWatched(any(), any(), any(), any()) } returns Unit

            val snapshotService = mockk<ContinueWatchingSnapshotService>(relaxed = true)
            every { snapshotService.snapshotForEpisodes(any()) } returns ContinueWatchingSnapshotService.EpisodeRollbackState(
                resumeItems = resumeItems
            )

            val rollbackSlot = slot<ContinueWatchingSnapshotService.EpisodeRollbackState>()
            coEvery { snapshotService.rollbackEpisodes(any<ContinueWatchingSnapshotService.EpisodeRollbackState>()) } returns Unit
            coEvery { snapshotService.rollbackEpisodes(capture(rollbackSlot)) } returns Unit

            val trackingProviderStateService = mockk<TrackingProviderStateService>(relaxed = true) {
                every { state } returns flowOf(EffectiveTrackingProviderState(traktAuthenticated = true))
                coEvery { currentState() } returns EffectiveTrackingProviderState(traktAuthenticated = true)
            }

            val repo = com.nexio.tv.data.repository.WatchProgressRepositoryImpl(
                watchProgressPreferences = mockk(relaxed = true),
                trackingProviderStateService = trackingProviderStateService,
                trackingProgressService = traktProgressService,
                traktMutationOutboxCoordinator = mockk(relaxed = true),
                seasonMarkBatcher = seasonMarkBatcher,
                traktAuthService = mockk(relaxed = true) {
                    every { currentTraktProfileId() } returns 1
                },
                snapshotServiceProvider = Provider { snapshotService },
                metadataRouterFacade = mockk(relaxed = true)
            )

            val meta = buildSeriesMeta(id = showId)
            val episodes = (1..8).map(::seasonEpisodeMark)

            repo.markAsCompletedBatch(meta, season, episodes)

            coVerify(exactly = 1) { snapshotService.rollbackEpisodes(any<ContinueWatchingSnapshotService.EpisodeRollbackState>()) }
            assertEquals(
                "Rollback should include only unresolved episodes before the queued mutation settles",
                setOf(3, 6, 8),
                rollbackSlot.captured.resumeItems.mapNotNull { it.episode }.toSet()
            )
            coVerify(exactly = 0) { snapshotService.ensureFresh(force = true) }
        }

    // ── Test 7: singleEpisodeToggleRegression ─────────────────────────────────

    @Test
    fun `singleEpisodeToggleRegression - toggleEpisodeWatched uses single markAsCompleted not batch`() =
        runTest(dispatcher) {
            val watchProgressRepository = mockk<WatchProgressRepository>(relaxed = true)
            coEvery { watchProgressRepository.markAsCompleted(any()) } returns Unit

            val video = buildVideo(season = 3, episode = 5)
            val meta = buildSeriesMeta(videos = listOf(video))

            // Use a facade mock so that resolveRequest returns a non-null route and meta is hydrated
            val facade = mockk<MetadataRouterFacade>(relaxed = true)
            coEvery { facade.resolveRequest(any()) } returns buildDefaultResolutionResult(meta.id)
            coEvery { facade.fetchTvEnrichment(any(), any()) } returns noEnrichmentDecision()

            val viewModel = buildViewModel(
                meta = meta,
                watchProgressRepository = watchProgressRepository,
                metadataRouterFacade = facade
            )
            advanceUntilIdle()

            viewModel.onEvent(MetaDetailsEvent.OnToggleEpisodeWatched(video))
            advanceUntilIdle()

            // Single-episode path: markAsCompleted called once, batch NOT called
            coVerify(exactly = 1) { watchProgressRepository.markAsCompleted(any()) }
            coVerify(exactly = 0) { watchProgressRepository.markAsCompletedBatch(any(), any(), any()) }
        }

    // ── Test 8: markAsCompletedBatchFullRollbackOnThrow (N5) ─────────────────

    @Test
    fun `markAsCompletedBatchFullRollbackOnThrow - IOException causes full rollback and rethrow`() =
        runTest(dispatcher) {
            val showId = "tt4444444"
            val season = 1

            val resumeItems = (1..5).map { ep ->
                WatchProgress(
                    contentId = showId,
                    contentType = "series",
                    name = "Show",
                    poster = null,
                    backdrop = null,
                    logo = null,
                    videoId = "$showId:$season:$ep",
                    season = season,
                    episode = ep,
                    episodeTitle = null,
                    position = 50L,
                    duration = 100L,
                    lastWatched = ep.toLong() * 1000L,
                    progressPercent = 50f
                )
            }

            val traktProgressService = mockk<TrackingProgressService>(relaxed = true)
            coEvery {
                traktProgressService.resolveSeasonEpisodeTraktIds(showId, season, any())
            } returns (1..5).associate { epNum ->
                epNum to TraktEpisodeRef(
                    episodeNumber = epNum,
                    traktId = epNum
                )
            }

            val seasonMarkBatcher = mockk<SeasonMarkBatcher>()
            coEvery { seasonMarkBatcher.markSeasonWatched(any(), any(), any(), any()) } throws IOException("network error")

            val snapshotService = mockk<ContinueWatchingSnapshotService>(relaxed = true)
            every { snapshotService.snapshotForEpisodes(any()) } returns ContinueWatchingSnapshotService.EpisodeRollbackState(
                resumeItems = resumeItems
            )

            val rollbackSlot = slot<ContinueWatchingSnapshotService.EpisodeRollbackState>()
            coEvery { snapshotService.rollbackEpisodes(any<ContinueWatchingSnapshotService.EpisodeRollbackState>()) } returns Unit
            coEvery { snapshotService.rollbackEpisodes(capture(rollbackSlot)) } returns Unit

            val trackingProviderStateService = mockk<TrackingProviderStateService>(relaxed = true) {
                every { state } returns flowOf(EffectiveTrackingProviderState(traktAuthenticated = true))
                coEvery { currentState() } returns EffectiveTrackingProviderState(traktAuthenticated = true)
            }

            val repo = com.nexio.tv.data.repository.WatchProgressRepositoryImpl(
                watchProgressPreferences = mockk(relaxed = true),
                trackingProviderStateService = trackingProviderStateService,
                trackingProgressService = traktProgressService,
                traktMutationOutboxCoordinator = mockk(relaxed = true),
                seasonMarkBatcher = seasonMarkBatcher,
                traktAuthService = mockk(relaxed = true) {
                    every { currentTraktProfileId() } returns 1
                },
                snapshotServiceProvider = Provider { snapshotService },
                metadataRouterFacade = mockk(relaxed = true)
            )

            val meta = buildSeriesMeta(id = showId)
            val episodes = (1..5).map(::seasonEpisodeMark)

            var thrownException: Exception? = null
            try {
                repo.markAsCompletedBatch(meta, season, episodes)
            } catch (e: IOException) {
                thrownException = e
            }

            // Exception must be rethrown
            assertEquals("network error", thrownException?.message)

            // Full rollback must be called with all 5 episodes
            coVerify(exactly = 1) { snapshotService.rollbackEpisodes(any<ContinueWatchingSnapshotService.EpisodeRollbackState>()) }
            assertEquals(5, rollbackSlot.captured.resumeItems.size)
            assertEquals(
                (1..5).toSet(),
                rollbackSlot.captured.resumeItems.mapNotNull { it.episode }.toSet()
            )
        }

    // ── Test 9: markAsCompletedBatchFullRollbackOnThrowRestoresAllRails (N5-ext) ─

    @Test
    fun `markAsCompletedBatchFullRollbackOnThrowRestoresAllRails - IOException restores nextUp and traktUpNext rails`() =
        runTest(dispatcher) {
            val showId = "tt5555556"
            val season = 2

            val resumeItems = (1..3).map { ep ->
                WatchProgress(
                    contentId = showId,
                    contentType = "series",
                    name = "Show",
                    poster = null,
                    backdrop = null,
                    logo = null,
                    videoId = "$showId:$season:$ep",
                    season = season,
                    episode = ep,
                    episodeTitle = null,
                    position = 50L,
                    duration = 100L,
                    lastWatched = ep.toLong() * 1000L,
                    progressPercent = 50f
                )
            }

            val nextUpItems = (1..2).map { ep ->
                com.nexio.tv.data.repository.TrackingNextUpEntry(
                    contentId = showId,
                    name = "Show",
                    season = season,
                    episode = ep,
                    episodeTitle = "Episode $ep",
                    videoId = "$showId:$season:$ep",
                    firstAired = null,
                    firstAiredMs = 0L,
                    activityAtMs = ep.toLong() * 500L
                )
            }

            val traktUpNextItems = (3..4).map { ep ->
                com.nexio.tv.data.repository.TrackingNextUpEntry(
                    contentId = showId,
                    name = "Show",
                    season = season,
                    episode = ep,
                    episodeTitle = "Episode $ep",
                    videoId = "$showId:$season:$ep",
                    firstAired = null,
                    firstAiredMs = 0L,
                    activityAtMs = ep.toLong() * 500L
                )
            }

            val traktProgressService = mockk<TrackingProgressService>(relaxed = true)
            coEvery {
                traktProgressService.resolveSeasonEpisodeTraktIds(showId, season, any())
            } returns (1..3).associate { epNum ->
                epNum to com.nexio.tv.data.repository.trakt.TraktEpisodeRef(
                    episodeNumber = epNum,
                    traktId = epNum
                )
            }

            val seasonMarkBatcher = mockk<SeasonMarkBatcher>()
            coEvery { seasonMarkBatcher.markSeasonWatched(any(), any(), any(), any()) } throws IOException("network failure")

            val snapshotService = mockk<ContinueWatchingSnapshotService>(relaxed = true)
            every { snapshotService.snapshotForEpisodes(any()) } returns ContinueWatchingSnapshotService.EpisodeRollbackState(
                resumeItems = resumeItems,
                nextUpItems = nextUpItems,
                traktUpNextItems = traktUpNextItems
            )

            val rollbackSlot = slot<ContinueWatchingSnapshotService.EpisodeRollbackState>()
            coEvery { snapshotService.rollbackEpisodes(any<ContinueWatchingSnapshotService.EpisodeRollbackState>()) } returns Unit
            coEvery { snapshotService.rollbackEpisodes(capture(rollbackSlot)) } returns Unit

            val trackingProviderStateService = mockk<TrackingProviderStateService>(relaxed = true) {
                every { state } returns flowOf(EffectiveTrackingProviderState(traktAuthenticated = true))
                coEvery { currentState() } returns EffectiveTrackingProviderState(traktAuthenticated = true)
            }

            val repo = com.nexio.tv.data.repository.WatchProgressRepositoryImpl(
                watchProgressPreferences = mockk(relaxed = true),
                trackingProviderStateService = trackingProviderStateService,
                trackingProgressService = traktProgressService,
                traktMutationOutboxCoordinator = mockk(relaxed = true),
                seasonMarkBatcher = seasonMarkBatcher,
                traktAuthService = mockk(relaxed = true) {
                    every { currentTraktProfileId() } returns 1
                },
                snapshotServiceProvider = Provider { snapshotService },
                metadataRouterFacade = mockk(relaxed = true)
            )

            val meta = buildSeriesMeta(id = showId)
            val episodes = (1..3).map(::seasonEpisodeMark)

            var thrownException: Exception? = null
            try {
                repo.markAsCompletedBatch(meta, season, episodes)
            } catch (e: IOException) {
                thrownException = e
            }

            // Exception must be rethrown
            assertEquals("network failure", thrownException?.message)

            // rollbackEpisodes must be called
            coVerify(exactly = 1) { snapshotService.rollbackEpisodes(any<ContinueWatchingSnapshotService.EpisodeRollbackState>()) }

            // The snapshot passed to rollback must contain all three rails intact
            assertEquals(
                "Rollback snapshot must preserve all resumeItems",
                3,
                rollbackSlot.captured.resumeItems.size
            )
            assertEquals(
                "Rollback snapshot must preserve all nextUpItems",
                2,
                rollbackSlot.captured.nextUpItems.size
            )
            assertEquals(
                "Rollback snapshot must preserve all traktUpNextItems",
                2,
                rollbackSlot.captured.traktUpNextItems.size
            )
            assertEquals(
                (1..3).toSet(),
                rollbackSlot.captured.resumeItems.mapNotNull { it.episode }.toSet()
            )
        }

    @Test
    fun `simkl provider routes season batch through simkl outbox envelope`() =
        runTest(dispatcher) {
            val showId = "tt3333333"
            val season = 2
            val episodes = (1..3).map(::seasonEpisodeMark)
            val snapshotService = mockk<ContinueWatchingSnapshotService>(relaxed = true)
            every { snapshotService.snapshotForEpisodes(any()) } returns ContinueWatchingSnapshotService.EpisodeRollbackState()

            val trackingProviderStateService = mockk<TrackingProviderStateService>(relaxed = true) {
                val state = EffectiveTrackingProviderState(
                    effectiveProvider = com.nexio.tv.domain.model.TrackingProvider.SIMKL,
                    traktAuthenticated = false,
                    simklAuthenticated = true
                )
                every { this@mockk.state } returns flowOf(state)
                coEvery { currentState() } returns state
            }

            val envelopeSlot = slot<TraktMutationEnvelope>()
            val outbox = mockk<com.nexio.tv.data.trakt.outbox.TraktMutationOutboxCoordinator>(relaxed = true)
            coEvery { outbox.enqueueAndDrain(capture(envelopeSlot)) } answers { envelopeSlot.captured }

            val repo = com.nexio.tv.data.repository.WatchProgressRepositoryImpl(
                watchProgressPreferences = mockk(relaxed = true),
                trackingProviderStateService = trackingProviderStateService,
                trackingProgressService = mockk(relaxed = true),
                traktMutationOutboxCoordinator = outbox,
                seasonMarkBatcher = mockk(relaxed = true),
                traktAuthService = mockk(relaxed = true) {
                    every { currentTraktProfileId() } returns 1
                },
                snapshotServiceProvider = Provider { snapshotService },
                metadataRouterFacade = mockk(relaxed = true)
            )

            val meta = buildSeriesMeta(id = showId)
            repo.markAsCompletedBatch(meta, season, episodes)

            assertEquals(SimklSeasonMarkMutationAdapter.ADAPTER_KEY, envelopeSlot.captured.adapterKey)
            assertEquals(SimklSeasonMarkMutationAdapter.MUTATION_KIND, envelopeSlot.captured.mutationKind)
        }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns a minimal [MetadataResolutionResult] with a non-null route so that
     * [MetaDetailsViewModel.loadMeta] takes the canonical path and sets [_uiState.meta].
     */
    private fun buildDefaultResolutionResult(contentId: String) = MetadataResolutionResult(
        route = MetadataRoute(
            provider = MetadataPrimaryProvider.TMDB,
            parentId = contentId,
            mediaKind = MetadataMediaKind.SERIES,
            reason = MetadataDecisionReason.ITEM_TYPE_SERIES,
            sourceContext = MetadataSourceContext(),
            targetIds = mapOf(MetadataPrimaryProvider.TMDB to contentId),
            trace = emptyList()
        ),
        plan = null,
        resolverSchedule = ResolverSchedule(
            depth = MetadataDepth.DETAIL_CORE,
            localResolvers = emptyList(),
            networkResolvers = emptyList()
        ),
        resolvedDocument = ResolvedMetadataDocument(
            canonicalId = contentId,
            title = "Test Show",
            overview = null,
            poster = null,
            backdrop = null,
            logo = null,
            rating = null,
            runtimeMinutes = null,
            fieldOwners = emptyMap(),
            ignoredOverwrites = emptyList()
        ),
        displayMetadata = HomeDisplayMetadata(title = "Test Show"),
        trace = emptyList()
    )

    /**
     * Returns a [TvMetadataDecision] with no enrichment value — used to stub [fetchTvEnrichment]
     * so that [MetaDetailsViewModel.enrichMeta] completes without ClassCastExceptions from
     * relaxed mockk generics.
     */
    private fun noEnrichmentDecision(): TvMetadataDecision<TvMetadataEnrichment> =
        TvMetadataDecision(
            provider = TvProvider.TMDB,
            reason = TvMetadataDecisionReason.TVDB_INACTIVE,
            value = null
        )

    private fun tmdbEpisode(
        episodeNumber: Int,
        airDate: String? = null
    ): TmdbEpisode = TmdbEpisode(
        id = episodeNumber,
        episodeNumber = episodeNumber,
        name = "Episode $episodeNumber",
        airDate = airDate
    )

    private fun tvSeasonEpisode(
        episodeNumber: Int,
        airDate: String? = null
    ): TvSeasonEpisode = TvSeasonEpisode(
        episodeNumber = episodeNumber,
        airDate = airDate,
        metadata = TvEpisodeMetadata(
            providerEpisodeId = "tvdb:$episodeNumber",
            seasonNumber = 1,
            episodeNumber = episodeNumber,
            airDate = airDate
        )
    )

    private fun seasonEpisodeMark(
        episodeNumber: Int,
        airDate: String? = null
    ): SeasonEpisodeMark = SeasonEpisodeMark(
        episodeNumber = episodeNumber,
        airDate = airDate
    )

    private fun buildVideo(season: Int, episode: Int): Video = Video(
        id = "vid:$season:$episode",
        title = "S${season}E${episode}",
        released = null,
        thumbnail = null,
        season = season,
        episode = episode,
        overview = null,
        runtime = null
    )

    private fun buildSeriesMeta(
        id: String = "tt5555555",
        videos: List<Video> = emptyList()
    ): Meta = Meta(
        id = id,
        type = ContentType.SERIES,
        rawType = "series",
        name = "Test Show",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = "2020",
        imdbRating = null,
        genres = emptyList(),
        runtime = null,
        director = emptyList(),
        cast = emptyList(),
        castMembers = emptyList(),
        videos = videos,
        productionCompanies = emptyList(),
        networks = emptyList(),
        country = null,
        awards = null,
        language = null,
        links = emptyList(),
        trailerYtIds = emptyList()
    )

    private fun buildViewModel(
        meta: Meta = buildSeriesMeta(),
        tmdbService: TmdbService = defaultTmdbService(),
        tmdbMetadataService: TmdbMetadataService = mockk(relaxed = true),
        tvMetadataRouter: TvMetadataRouter = defaultTvMetadataRouter(tmdbService, tmdbMetadataService),
        watchProgressRepository: WatchProgressRepository = mockk(relaxed = true),
        metadataRouterFacade: MetadataRouterFacade? = null
    ): MetaDetailsViewModel {
        val wrappedWatchProgressRepository = mockk<WatchProgressRepository>(relaxed = true)
        every { wrappedWatchProgressRepository.getAllEpisodeProgress(any()) } returns flowOf(emptyMap())
        every { wrappedWatchProgressRepository.getProgress(any()) } returns flowOf(null)
        coEvery { wrappedWatchProgressRepository.markAsCompleted(any()) } coAnswers {
            watchProgressRepository.markAsCompleted(firstArg())
        }
        coEvery { wrappedWatchProgressRepository.markAsCompletedBatch(any(), any(), any()) } coAnswers {
            watchProgressRepository.markAsCompletedBatch(firstArg(), secondArg(), thirdArg())
        }

        return buildMetaDetailsViewModel(
            meta = meta,
            tmdbService = tmdbService,
            tmdbMetadataService = tmdbMetadataService,
            tvMetadataRouter = tvMetadataRouter,
            watchProgressRepository = wrappedWatchProgressRepository,
            libraryRepository = defaultLibraryRepository(),
            metadataRouterFacade = metadataRouterFacade
        )
    }

    private fun defaultTvMetadataRouter(
        tmdbService: TmdbService,
        tmdbMetadataService: TmdbMetadataService
    ): TvMetadataRouter {
        val router = mockk<TvMetadataRouter>(relaxed = true)
        coEvery { router.fetchEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.TMDB,
            reason = TvMetadataDecisionReason.TVDB_INACTIVE,
            value = null
        )
        coEvery { router.fetchEpisodeEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.TMDB,
            reason = TvMetadataDecisionReason.TVDB_INACTIVE,
            value = emptyMap()
        )
        coEvery { router.fetchSeasonEpisodes(any(), any(), any(), any()) } coAnswers {
            val contentId = firstArg<String>()
            val fallbackContentId = secondArg<String?>()
            val seasonNumber = thirdArg<Int>()
            val tmdbId = tmdbService.ensureTmdbId(contentId, ContentType.SERIES.toApiString())
                ?: fallbackContentId?.let { tmdbService.ensureTmdbId(it, ContentType.SERIES.toApiString()) }
            val episodes = tmdbId
                ?.toIntOrNull()
                ?.let { tvId -> tmdbMetadataService.fetchSeasonEpisodes(tvId, seasonNumber, null) }
                .orEmpty()
            TvMetadataDecision(
                provider = TvProvider.TMDB,
                reason = TvMetadataDecisionReason.TVDB_FALLBACK_TMDB,
                value = episodes.map { episode ->
                    TvSeasonEpisode(
                        episodeNumber = episode.episodeNumber,
                        airDate = episode.airDate,
                        metadata = TvEpisodeMetadata(
                            providerEpisodeId = episode.id?.let { "tmdb:$it" },
                            seasonNumber = seasonNumber,
                            episodeNumber = episode.episodeNumber,
                            title = episode.name,
                            airDate = episode.airDate
                        )
                    )
                }
            )
        }
        return router
    }
}
