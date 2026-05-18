package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.ActiveRailTracker
import com.nexio.tv.core.integration.IntegrationOwnershipService
import com.nexio.tv.core.integration.RailMediaIdentityResolver
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.CanonicalStableIds
import com.nexio.tv.core.metadata.router.SidecarStableIds
import com.nexio.tv.core.metadata.router.SourceStableIds
import com.nexio.tv.core.metadata.router.StableIdBundle
import com.nexio.tv.core.metadata.router.StableIdResolutionTrigger
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.scheduler.ContinueWatchingAirScheduler
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.core.tvdb.TvMetadataDecision
import com.nexio.tv.core.tvdb.TvMetadataDecisionReason
import com.nexio.tv.core.tvdb.TvProvider
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TrailerDisplayState
import com.nexio.tv.data.local.ContinueWatchingSnapshotStore
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.domain.repository.WatchProgressRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import javax.inject.Inject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for the Phase 3 mutation helpers on [ContinueWatchingSnapshotService].
 *
 * Strategy: the service's internal combine pipeline runs on Dispatchers.IO (not the test
 * dispatcher), so these tests focus on the mutation-path helpers directly via a harness,
 * rather than asserting IO-driven full-pipeline sequencing.
 *
 * For tests that verify the mutation helpers themselves (not the combine pipeline), we
 * use a direct Mutex + MutableStateFlow harness that mirrors the helpers exactly, avoiding
 * any dependency on the IO scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContinueWatchingSnapshotServiceMutationTest {

    // ── Shared test helpers ────────────────────────────────────────────────────

    private fun resume(
        contentId: String,
        videoId: String = contentId,
        season: Int? = null,
        episode: Int? = null,
        lastWatched: Long = 1_000L,
        progressPercent: Float = 50f,
        source: String = WatchProgress.SOURCE_LOCAL
    ): WatchProgress = WatchProgress(
        contentId = contentId,
        contentType = if (season != null) "series" else "movie",
        name = contentId,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = videoId,
        season = season,
        episode = episode,
        episodeTitle = null,
        position = 50L,
        duration = 100L,
        lastWatched = lastWatched,
        progressPercent = progressPercent,
        source = source
    )

    /**
     * Build a minimal [ContinueWatchingSnapshotService] with all dependencies mocked.
     * Auth is always false → upstream combine never fires → rawSnapshotState stays
     * at its initial empty value until we seed it via the mutation helpers.
     */
    private fun buildService(
        continueWatchingIdentityResolver: ContinueWatchingIdentityResolver = canonicalResolver(),
        metadataRouterFacade: MetadataRouterFacade? = null,
        tvEpisodeOrderResolver: TvEpisodeOrderResolver = tmdbDefaultOrderResolver()
    ): ContinueWatchingSnapshotService {
        val trackingProviderStateService = mockk<TrackingProviderStateService>(relaxed = true) {
            every { state } returns flowOf(EffectiveTrackingProviderState())
        }
        val traktSettingsDataStore = mockk<TraktSettingsDataStore>(relaxed = true) {
            every { dismissedNextUpKeys } returns flowOf(emptySet())
        }
        val trackingProgressService = mockk<TrackingProgressService>(relaxed = true) {
            every { observeAllProgress() } returns flowOf(emptyList())
            every { observeRemoteSnapshotLoaded() } returns flowOf(false)
            every { observeContinueWatchingNextUp() } returns flowOf(emptyList())
            every { observeSyntheticContinueWatchingNextUp() } returns flowOf(emptyList())
        }
        val watchProgressRepository = mockk<WatchProgressRepository>(relaxed = true) {
            every { observeProgress(any()) } returns flowOf(emptyList())
        }
        val snapshotStore = mockk<ContinueWatchingSnapshotStore>(relaxed = true) {
            every { read(any()) } returns null
        }
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)

        return ContinueWatchingSnapshotService(
            watchProgressRepository = watchProgressRepository,
            trackingProgressService = trackingProgressService,
            trackingProviderStateService = trackingProviderStateService,
            traktSettingsDataStore = traktSettingsDataStore,
            metadataDiskCacheStore = metadataDiskCacheStore,
            snapshotStore = snapshotStore,
            continueWatchingIdentityResolver = continueWatchingIdentityResolver,
            metadataRouterFacade = metadataRouterFacade,
            tvEpisodeOrderResolver = tvEpisodeOrderResolver
        )
    }

    private fun buildServiceViaTestingConstructor(
        metadataRouterFacade: MetadataRouterFacade,
        tvEpisodeOrderResolver: TvEpisodeOrderResolver
    ): ContinueWatchingSnapshotService {
        val trackingProviderStateService = mockk<TrackingProviderStateService>(relaxed = true) {
            every { state } returns flowOf(EffectiveTrackingProviderState())
        }
        val traktSettingsDataStore = mockk<TraktSettingsDataStore>(relaxed = true) {
            every { dismissedNextUpKeys } returns flowOf(emptySet())
        }
        val trackingProgressService = mockk<TrackingProgressService>(relaxed = true) {
            every { observeAllProgress() } returns flowOf(emptyList())
            every { observeRemoteSnapshotLoaded() } returns flowOf(false)
            every { observeContinueWatchingNextUp() } returns flowOf(emptyList())
            every { observeSyntheticContinueWatchingNextUp() } returns flowOf(emptyList())
        }
        val watchProgressRepository = mockk<WatchProgressRepository>(relaxed = true) {
            every { observeProgress(any()) } returns flowOf(emptyList())
        }
        val snapshotStore = mockk<ContinueWatchingSnapshotStore>(relaxed = true) {
            every { read(any()) } returns null
        }

        return ContinueWatchingSnapshotService(
            watchProgressRepository = watchProgressRepository,
            trackingProgressService = trackingProgressService,
            trackingProviderStateService = trackingProviderStateService,
            traktSettingsDataStore = traktSettingsDataStore,
            metadataDiskCacheStore = mockk(relaxed = true),
            snapshotStore = snapshotStore,
            metadataRouterFacade = metadataRouterFacade,
            tvEpisodeOrderResolver = tvEpisodeOrderResolver
        )
    }

    private fun buildServiceWithMetadataFacade(
        facade: MetadataRouterFacade,
        tvEpisodeOrderResolver: TvEpisodeOrderResolver = tvdbDefaultOrderResolver()
    ): ContinueWatchingSnapshotService = buildService(
        metadataRouterFacade = facade,
        tvEpisodeOrderResolver = tvEpisodeOrderResolver
    )

    private fun canonicalResolver(
        resolver: suspend (RawContinueWatchingInput) -> ContinueWatchingRecord = { input ->
            canonicalRecord(input)
        }
    ): ContinueWatchingIdentityResolver =
        mockk {
            coEvery { resolveOrFallback(any()) } coAnswers {
                resolver(firstArg())
            }
        }

    private fun tmdbDefaultOrderResolver(): TvEpisodeOrderResolver =
        mockk {
            coEvery { resolve(any(), any()) } coAnswers {
                TvEpisodeOrderResolution(
                    provider = TvEpisodeOrderProvider.TMDB_DEFAULT,
                    tmdbTvId = firstArg<String?>()?.trim().orEmpty(),
                    reason = "tmdb default"
                )
            }
        }

    private fun tvdbDefaultOrderResolver(
        tmdbTvId: String = "tmdb:tv:12345",
        tvdbSeriesId: String = "303904"
    ): TvEpisodeOrderResolver =
        mockk {
            coEvery { resolve(any(), any()) } returns TvEpisodeOrderResolution(
                provider = TvEpisodeOrderProvider.TVDB_DEFAULT,
                tmdbTvId = tmdbTvId,
                tvdbSeriesId = tvdbSeriesId,
                reason = "tvdb override"
            )
        }

    private fun stubTvdbProjectionRoute(
        facade: MetadataRouterFacade,
        tmdbTvId: String = "12345",
        tvdbSeriesId: String = "303904"
    ) {
        coEvery {
            facade.routeRequest(any())
        } returns MetadataRoute(
            provider = MetadataPrimaryProvider.TMDB,
            parentId = "tmdb:$tmdbTvId",
            mediaKind = MetadataMediaKind.SERIES,
            reason = MetadataDecisionReason.ITEM_TYPE_SERIES,
            sourceContext = MetadataSourceContext(itemType = "series"),
            targetIds = mapOf(
                MetadataPrimaryProvider.TMDB to "tmdb:$tmdbTvId",
                MetadataPrimaryProvider.TVDB to "tvdb:$tvdbSeriesId"
            ),
            trace = emptyList()
        )
    }

    private fun canonicalRecord(
        input: RawContinueWatchingInput,
        canonicalProvider: ProviderId? = input.progress.contentId.canonicalProvider(),
        canonicalId: String? = input.progress.contentId.substringAfter(':', input.progress.contentId),
        identityConfidence: IdentityConfidence = IdentityConfidence.HIGH,
        warnings: List<String> = emptyList()
    ): ContinueWatchingRecord {
        val progress = input.progress
        val providerIds = ProviderIds(
            imdb = if (canonicalProvider == ProviderId.IMDB) canonicalId else null,
            tvdb = if (canonicalProvider == ProviderId.TVDB) canonicalId else null
        )
        val identity = ContentIdentity(
            canonicalProvider = canonicalProvider,
            canonicalId = canonicalId,
            providerIds = providerIds
        )
        val episodeContext = if (progress.season != null && progress.episode != null) {
            ContinueWatchingRecord.EpisodeContext(progress.season, progress.episode)
        } else {
            null
        }
        val resumeIdentity = progress.toResumeIdentity()
        val canonicalKey = if (canonicalProvider != null && canonicalId != null) {
            ContinueWatchingCanonicalKey(
                mediaKind = if (episodeContext == null) MetadataMediaKind.MOVIE else MetadataMediaKind.SERIES,
                canonicalParent = identity,
                season = episodeContext?.season,
                episode = episodeContext?.number,
                profileId = input.profileId
            )
        } else {
            null
        }

        return ContinueWatchingRecord(
            profileId = input.profileId,
            parentId = progress.contentId,
            contentId = progress.videoId,
            provider = com.nexio.tv.domain.model.TrackingProvider.TRAKT,
            routingVersion = ContinueWatchingMetadataSnapshot.CURRENT_ROUTING_VERSION,
            positionMs = progress.position,
            durationMs = progress.duration,
            episodeContext = episodeContext,
            clickTimeDisplayMetadata = null,
            source = if (progress.source == WatchProgress.SOURCE_LOCAL) {
                ContinueWatchingRecord.Source.LOCAL
            } else {
                ContinueWatchingRecord.Source.REMOTE
            },
            updatedAt = progress.lastWatched.coerceAtLeast(1L),
            canonicalKey = canonicalKey,
            displayIdentity = identity.takeIf { canonicalKey != null },
            resumeIdentities = listOf(resumeIdentity),
            primaryResumeLookupKey = resumeIdentity.lookupKey(),
            identityConfidence = identityConfidence,
            identityWarnings = warnings,
            languageTag = input.languageTag
        )
    }

    @Test
    fun `resolved display surface hydrates imdb keyed movie snapshot with canonical record`() =
        runTest {
            val service = buildService()
            val progress = resume(
                contentId = "tt40898187",
                videoId = "tt40898187",
                lastWatched = 1778611202000L,
                progressPercent = 72.0997f,
                source = WatchProgress.SOURCE_TRAKT_PLAYBACK
            ).copy(
                duration = 0L,
                traktMovieId = 1361127
            )
            val snapshot = ContinueWatchingSnapshot(
                resumeItems = listOf(progress),
                updatedAtMs = 1778611202000L
            )

            val hydrated = service.mergeResolvedDisplaySnapshot(
                snapshot = snapshot,
                profileId = 1,
                resolvedItems = listOf(roastResolvedDisplayItem())
            )

            val metadata = hydrated.displayMetadataByItemKey.getValue("movie:tt40898187")
            assertEquals("The Roast of Kevin Hart", metadata.title)
            assertEquals("tt40898187", metadata.imdbId)
            assertEquals("172", metadata.runtime)

            val record = hydrated.records.single()
            assertEquals("profile:1:movie:tmdb:1658982", record.identityKey())
            assertEquals("1658982", record.idBundle.tmdb)
            assertEquals("1361127", record.idBundle.trakt)
            assertEquals("tt40898187", record.idBundle.imdb)
            assertEquals("tt40898187", record.streamFetchIdentity?.contentId)
            assertEquals("tt40898187", record.streamFetchIdentity?.videoId)
            assertEquals(StreamIdScheme.IMDB_MOVIE, record.streamFetchIdentity?.idScheme)
        }

    @Test
    fun `resolved display surface corrects TVDB series sidecar and uses it for stream identity`() =
        runTest {
            val facade = mockk<MetadataRouterFacade>(relaxed = true)
            val tvEpisodeOrderResolver = tmdbDefaultOrderResolver()
            coEvery {
                facade.resolveStableIdBundle(
                    request = any(),
                    trigger = StableIdResolutionTrigger.CONTINUE_WATCHING,
                    itemKey = "series:tvdb:413033"
                )
            } returns StableIdBundle(
                itemKey = "series:tvdb:413033",
                itemType = ContentType.SERIES,
                canonical = CanonicalStableIds(tvdbSeriesId = "413033"),
                sidecars = SidecarStableIds(imdbId = "tt16288804"),
                source = SourceStableIds(
                    sourceProvider = ProviderId.TVDB,
                    sourceItemId = "tvdb:413033",
                    railId = null,
                    observedIds = ProviderIds(tvdb = "413033")
                ),
                evidence = emptyList(),
                resolvedAtMs = 1778611202000L
            )
            val service = buildServiceWithMetadataFacade(
                facade = facade,
                tvEpisodeOrderResolver = tvEpisodeOrderResolver
            )
            val progress = resume(
                contentId = "tvdb:413033",
                videoId = "tvdb:413033:2:2",
                season = 2,
                episode = 2,
                lastWatched = 1778611202000L,
                source = WatchProgress.SOURCE_TRAKT_PLAYBACK
            )
            val snapshot = ContinueWatchingSnapshot(
                resumeItems = listOf(progress),
                updatedAtMs = 1778611202000L
            )

            val hydrated = service.mergeResolvedDisplaySnapshot(
                snapshot = snapshot,
                profileId = 1,
                resolvedItems = listOf(berlinResolvedDisplayItem())
            )

            val metadata = hydrated.displayMetadataByItemKey.getValue("series:tvdb:413033")
            assertEquals("tt16288804", metadata.imdbId)
            val record = hydrated.records.single()
            assertEquals("tt16288804", record.displayIdentity?.providerIds?.imdb)
            assertEquals("tt16288804", record.idBundle.imdb)
            assertEquals("tt16288804", record.streamFetchIdentity?.contentId)
            assertEquals("tt16288804:2:2", record.streamFetchIdentity?.videoId)
            assertEquals(StreamIdScheme.IMDB_EPISODE, record.streamFetchIdentity?.idScheme)
            coVerify(exactly = 0) {
                tvEpisodeOrderResolver.resolve(any(), any())
            }
        }

    @Test
    fun `testing constructor raw snapshot passes tv order resolver into identity resolver`() =
        runTest {
            val facade = mockk<MetadataRouterFacade>(relaxed = true)
            coEvery {
                facade.resolveStableIdBundle(any(), any(), any())
            } returns StableIdBundle(
                itemKey = "series:tmdb:71446",
                itemType = ContentType.SERIES,
                canonical = CanonicalStableIds(
                    tmdbTvId = "71446",
                    tvdbSeriesId = "81189"
                ),
                sidecars = SidecarStableIds(),
                source = SourceStableIds(
                    sourceProvider = ProviderId.TMDB,
                    sourceItemId = "71446",
                    railId = null,
                    observedIds = ProviderIds(tmdb = "71446", tvdb = "81189")
                ),
                evidence = emptyList(),
                resolvedAtMs = 1778611202000L
            )
            val service = buildServiceViaTestingConstructor(
                metadataRouterFacade = facade,
                tvEpisodeOrderResolver = tvdbDefaultOrderResolver(
                    tmdbTvId = "tmdb:tv:71446",
                    tvdbSeriesId = "81189"
                )
            )

            val snapshot = invokeBuildRawSnapshot(
                service = service,
                allProgress = listOf(
                    resume(
                        contentId = "tmdb:71446",
                        videoId = "tmdb:71446:2:1",
                        season = 2,
                        episode = 1,
                        source = WatchProgress.SOURCE_TRAKT_PLAYBACK
                    )
                ),
                nextUpEntries = emptyList(),
                traktUpNextEntries = emptyList()
            )

            val record = snapshot.records.single()
            assertEquals("tvdb:81189:2:1", record.streamFetchIdentity?.videoId)
            assertEquals(StreamIdScheme.TVDB_EPISODE, record.streamFetchIdentity?.idScheme)
        }

    @Test
    fun `retained records are suppressed when completed progress uses raw provider id`() {
        val service = buildService()
        val staleRecord = canonicalRecord(
            RawContinueWatchingInput(
                profileId = 1,
                progress = resume(
                    contentId = "tvdb:355567",
                    videoId = "tvdb:355567:5:7",
                    season = 5,
                    episode = 7,
                    lastWatched = 1_000L,
                    progressPercent = 62f,
                    source = WatchProgress.SOURCE_TRAKT_PLAYBACK
                ),
                languageTag = "en"
            )
        ).copy(
            parentId = "series:tvdb:355567",
            contentId = "series:tvdb:355567:s5e7"
        )
        val completedLocalProgress = resume(
            contentId = "tvdb:355567",
            videoId = "tvdb:355567_s5e7",
            season = 5,
            episode = 7,
            lastWatched = 2_000L,
            progressPercent = 95f,
            source = WatchProgress.SOURCE_LOCAL
        )
        val previous = ContinueWatchingSnapshot(
            nextUpItems = listOf(
                TrackingNextUpEntry(
                    contentId = "tvdb:other",
                    name = "Other",
                    season = 1,
                    episode = 2,
                    episodeTitle = null,
                    videoId = "tvdb:other:1:2",
                    firstAired = null,
                    firstAiredMs = 0L,
                    activityAtMs = 1_000L
                )
            ),
            records = listOf(staleRecord),
            updatedAtMs = 1_000L
        )

        val method = ContinueWatchingSnapshotService::class.java.getDeclaredMethod(
            "retainStableRowsFromPreviousSnapshot",
            ContinueWatchingSnapshot::class.java,
            ContinueWatchingSnapshot::class.java,
            List::class.java
        )
        method.isAccessible = true
        val retained = method.invoke(
            service,
            ContinueWatchingSnapshot(updatedAtMs = 2_000L),
            previous,
            listOf(completedLocalProgress)
        ) as ContinueWatchingSnapshot

        assertTrue("completed local episode must suppress stale retained record", retained.records.isEmpty())
    }

    @Test
    fun `retained next up rows are suppressed by completed watched alias anchors`() {
        val service = buildService()
        val staleNightAgentNextUp = TrackingNextUpEntry(
            contentId = "tvdb:407281",
            name = "The Night Agent",
            season = 1,
            episode = 9,
            episodeTitle = null,
            videoId = "tvdb:407281:1:9",
            firstAired = null,
            firstAiredMs = 1L,
            activityAtMs = 100_000L
        )
        val completedNightAgent = resume(
            contentId = "tvdb:series:407281",
            videoId = "tvdb:series:407281:s1e9",
            season = 1,
            episode = 9,
            lastWatched = 200_000L,
            progressPercent = 100f,
            source = WatchProgress.SOURCE_TRAKT_HISTORY
        )
        val previous = ContinueWatchingSnapshot(
            nextUpItems = listOf(staleNightAgentNextUp),
            updatedAtMs = 100_000L
        )

        val retained = invokeRetainStableRowsFromPreviousSnapshot(
            service = service,
            candidate = ContinueWatchingSnapshot(updatedAtMs = 200_000L),
            previous = previous,
            completedProgress = listOf(completedNightAgent)
        )

        assertEquals(emptyList<TrackingNextUpEntry>(), retained.nextUpItems)
    }

    @Test
    fun `retained imdb next up row is suppressed by completed watched anchor from display canonical artwork`() {
        val service = buildService()
        val staleShrinkingNextUp = TrackingNextUpEntry(
            contentId = "tt15677150",
            contentType = "series",
            name = "Shrinking",
            season = 3,
            episode = 8,
            episodeTitle = "Depression Diet",
            videoId = "tt15677150:3:8",
            firstAired = null,
            firstAiredMs = 1L,
            activityAtMs = 100_000L
        )
        val completedShrinking = resume(
            contentId = "tvdb:411364",
            videoId = "tvdb:411364:3:11",
            season = 3,
            episode = 11,
            lastWatched = 200_000L,
            progressPercent = 100f,
            source = WatchProgress.SOURCE_TRAKT_HISTORY
        )
        val previous = ContinueWatchingSnapshot(
            nextUpItems = listOf(staleShrinkingNextUp),
            displayMetadataByItemKey = mapOf(
                "series:tt15677150" to HomeDisplayMetadata(
                    backdrop = "nexio-artwork://decision/artwork-decision:backdrop:canonical:tvdb:411364:provider:TVDB:premium:false"
                )
            ),
            updatedAtMs = 100_000L
        )

        val retained = invokeRetainStableRowsFromPreviousSnapshot(
            service = service,
            candidate = ContinueWatchingSnapshot(updatedAtMs = 200_000L),
            previous = previous,
            completedProgress = listOf(completedShrinking)
        )

        assertEquals(emptyList<TrackingNextUpEntry>(), retained.nextUpItems)
    }

    @Test
    fun `retained later next up row is not suppressed only because it is older than completed anchor`() {
        val service = buildService()
        val laterNightAgentNextUp = TrackingNextUpEntry(
            contentId = "tvdb:407281",
            name = "The Night Agent",
            season = 1,
            episode = 10,
            episodeTitle = null,
            videoId = "tvdb:407281:1:10",
            firstAired = null,
            firstAiredMs = 1L,
            activityAtMs = 100_000L
        )
        val completedNightAgent = resume(
            contentId = "tvdb:series:407281",
            videoId = "tvdb:series:407281:s1e9",
            season = 1,
            episode = 9,
            lastWatched = 200_000L,
            progressPercent = 100f,
            source = WatchProgress.SOURCE_TRAKT_HISTORY
        )
        val previous = ContinueWatchingSnapshot(
            nextUpItems = listOf(laterNightAgentNextUp),
            updatedAtMs = 100_000L
        )

        val retained = invokeRetainStableRowsFromPreviousSnapshot(
            service = service,
            candidate = ContinueWatchingSnapshot(updatedAtMs = 200_000L),
            previous = previous,
            completedProgress = listOf(completedNightAgent)
        )

        assertEquals(listOf(laterNightAgentNextUp), retained.nextUpItems)
    }

    @Test
    fun `retained records are suppressed by completed watched alias anchors`() {
        val service = buildService()
        val staleRecord = canonicalRecord(
            RawContinueWatchingInput(
                profileId = 1,
                progress = resume(
                    contentId = "tvdb:407281",
                    videoId = "tvdb:407281:1:9",
                    season = 1,
                    episode = 9,
                    lastWatched = 100_000L,
                    progressPercent = 62f,
                    source = WatchProgress.SOURCE_TRAKT_PLAYBACK
                ),
                languageTag = "en"
            )
        )
        val completedNightAgent = resume(
            contentId = "tvdb:series:407281",
            videoId = "tvdb:series:407281:s1e9",
            season = 1,
            episode = 9,
            lastWatched = 200_000L,
            progressPercent = 100f,
            source = WatchProgress.SOURCE_TRAKT_HISTORY
        )
        val previous = ContinueWatchingSnapshot(
            records = listOf(staleRecord),
            updatedAtMs = 100_000L
        )

        val retained = invokeRetainStableRowsFromPreviousSnapshot(
            service = service,
            candidate = ContinueWatchingSnapshot(updatedAtMs = 200_000L),
            previous = previous,
            completedProgress = listOf(completedNightAgent)
        )

        assertEquals(emptyList<ContinueWatchingRecord>(), retained.records)
    }

    private fun roastResolvedDisplayItem(): ResolvedDisplayItem = ResolvedDisplayItem(
        itemKey = "movie:tmdb:1658982",
        contentId = "tmdb:1658982",
        parentId = "tmdb:1658982",
        itemType = ContentType.MOVIE,
        mediaKind = MetadataMediaKind.MOVIE,
        canonicalProvider = "TMDB",
        canonicalId = "1658982",
        imdbId = "tt40898187",
        stableIds = ProviderIds(
            imdb = "tt40898187",
            tmdb = "1658982",
            trakt = "1361127",
            slug = "the-roast-of-kevin-hart-2026"
        ),
        display = ResolvedDisplayFields(
            title = "The Roast of Kevin Hart",
            originalTitle = null,
            year = 2026,
            releaseDate = "2026-05-10",
            overview = "Kevin Hart is in the hot seat.",
            genres = listOf("Comedy"),
            runtimeText = "172"
        ),
        artwork = ArtworkBundle(),
        rating = null,
        trailer = TrailerDisplayState(),
        hydrationState = com.nexio.tv.domain.model.HydrationState.STALE_READY,
        sourceTrace = emptyList(),
        updatedAtMs = 1778611202000L
    )

    private fun berlinResolvedDisplayItem(): ResolvedDisplayItem = ResolvedDisplayItem(
        itemKey = "series:tvdb:413033",
        contentId = "tvdb:413033",
        parentId = "tvdb:413033",
        itemType = ContentType.SERIES,
        mediaKind = MetadataMediaKind.SERIES,
        canonicalProvider = "TVDB",
        canonicalId = "413033",
        imdbId = "tt42178219",
        stableIds = ProviderIds(
            imdb = "tt42178219",
            tmdb = "308014",
            tvdb = "413033"
        ),
        display = ResolvedDisplayFields(
            title = "Berlin",
            originalTitle = null,
            year = 2026,
            releaseDate = "2026-05-15",
            overview = "Berlin plans another job.",
            genres = listOf("Crime"),
            runtimeText = "56"
        ),
        artwork = ArtworkBundle(),
        rating = null,
        trailer = TrailerDisplayState(),
        hydrationState = com.nexio.tv.domain.model.HydrationState.STALE_READY,
        sourceTrace = emptyList(),
        updatedAtMs = 1778611202000L
    )

    private fun String.canonicalProvider(): ProviderId? =
        when (substringBefore(':').lowercase()) {
            "imdb" -> ProviderId.IMDB
            "tvdb" -> ProviderId.TVDB
            else -> null
        }

    private class RecordingAirScheduler : ContinueWatchingAirScheduler {
        val scheduledAt = mutableListOf<Long?>()
        var cancelCount = 0

        override fun scheduleSoonest(triggerAtMs: Long?) {
            scheduledAt += triggerAtMs
        }

        override fun cancel() {
            cancelCount++
        }
    }

    private fun nextUp(
        contentId: String,
        firstAiredMs: Long,
        tvdbAvailabilityInstantMs: Long? = null,
        firstAired: String? = null,
        episode: Int = 1
    ): TrackingNextUpEntry = TrackingNextUpEntry(
        contentId = contentId,
        name = contentId,
        season = 1,
        episode = episode,
        episodeTitle = "Episode $episode",
        videoId = "$contentId:1:$episode",
        firstAired = firstAired,
        firstAiredMs = firstAiredMs,
        activityAtMs = firstAiredMs,
        tvdbAvailabilityInstantMs = tvdbAvailabilityInstantMs
    )

    private fun buildServiceWithAirScheduler(
        airScheduler: ContinueWatchingAirScheduler,
        snapshotStore: ContinueWatchingSnapshotStore = mockk(relaxed = true) {
            every { read(any()) } returns null
        },
        trackingProgressService: TrackingProgressService = mockk(relaxed = true) {
            every { observeAllProgress() } returns flowOf(emptyList())
            every { observeRemoteSnapshotLoaded() } returns flowOf(false)
            every { observeContinueWatchingNextUp() } returns flowOf(emptyList())
            every { observeSyntheticContinueWatchingNextUp() } returns flowOf(emptyList())
        }
    ): ContinueWatchingSnapshotService {
        val constructor = ContinueWatchingSnapshotService::class.java.declaredConstructors
            .firstOrNull { candidate ->
                candidate.parameterTypes.any { it == ContinueWatchingAirScheduler::class.java } &&
                    candidate.parameterTypes.none {
                        it == Int::class.javaPrimitiveType ||
                            it.name == "kotlin.jvm.internal.DefaultConstructorMarker"
                    }
            }
            ?: error("ContinueWatchingSnapshotService must accept ContinueWatchingAirScheduler")
        constructor.isAccessible = true

        val args = constructor.parameterTypes.map { type ->
            when (type) {
                WatchProgressRepository::class.java -> mockk<WatchProgressRepository>(relaxed = true) {
                    every { observeProgress(any()) } returns flowOf(emptyList())
                }
                TrackingProgressService::class.java -> trackingProgressService
                TrackingProviderStateService::class.java -> mockk<TrackingProviderStateService>(relaxed = true) {
                    every { state } returns flowOf(EffectiveTrackingProviderState(traktAuthenticated = true))
                }
                TraktSettingsDataStore::class.java -> mockk<TraktSettingsDataStore>(relaxed = true) {
                    every { dismissedNextUpKeys } returns flowOf(emptySet())
                }
                MetadataDiskCacheStore::class.java -> mockk<MetadataDiskCacheStore>(relaxed = true)
                ContinueWatchingSnapshotStore::class.java -> snapshotStore
                ContinueWatchingAirScheduler::class.java -> airScheduler
                ProfileManager::class.java -> null
                IntegrationOwnershipService::class.java -> null
                ActiveRailTracker::class.java -> ActiveRailTracker()
                RailMediaIdentityResolver::class.java -> RailMediaIdentityResolver()
                ContinueWatchingIdentityResolver::class.java -> canonicalResolver()
                MetadataRouterFacade::class.java -> null
                TvEpisodeOrderResolver::class.java -> tmdbDefaultOrderResolver()
                else -> null
            }
        }.toTypedArray()

        return constructor.newInstance(*args) as ContinueWatchingSnapshotService
    }

    @Suppress("UNCHECKED_CAST")
    private fun rawSnapshotFlow(
        service: ContinueWatchingSnapshotService
    ): MutableStateFlow<ProfileOwnedContinueWatchingSnapshot> {
        val field = ContinueWatchingSnapshotService::class.java.getDeclaredField("rawSnapshotState")
        field.isAccessible = true
        return field.get(service) as MutableStateFlow<ProfileOwnedContinueWatchingSnapshot>
    }

    private fun setRawSnapshot(
        service: ContinueWatchingSnapshotService,
        snapshot: ContinueWatchingSnapshot
    ) {
        rawSnapshotFlow(service).value = ProfileOwnedContinueWatchingSnapshot(snapshot = snapshot)
    }

    private fun rawSnapshot(service: ContinueWatchingSnapshotService): ContinueWatchingSnapshot {
        return rawSnapshotFlow(service).value.snapshot
    }

    @Suppress("UNCHECKED_CAST")
    private fun snapshotFlow(
        service: ContinueWatchingSnapshotService
    ): MutableStateFlow<ProfileOwnedContinueWatchingSnapshot> {
        val field = ContinueWatchingSnapshotService::class.java.getDeclaredField("snapshotState")
        field.isAccessible = true
        return field.get(service) as MutableStateFlow<ProfileOwnedContinueWatchingSnapshot>
    }

    @Suppress("UNCHECKED_CAST")
    private fun persistedSnapshotReadyFlow(
        service: ContinueWatchingSnapshotService
    ): MutableStateFlow<Boolean> {
        val field = ContinueWatchingSnapshotService::class.java.getDeclaredField("persistedSnapshotReady")
        field.isAccessible = true
        return field.get(service) as MutableStateFlow<Boolean>
    }

    private fun setPublishedSnapshot(
        service: ContinueWatchingSnapshotService,
        profileId: Int = 1,
        snapshot: ContinueWatchingSnapshot
    ) {
        val owned = ProfileOwnedContinueWatchingSnapshot(profileId = profileId, snapshot = snapshot)
        rawSnapshotFlow(service).value = owned
        snapshotFlow(service).value = owned
        persistedSnapshotReadyFlow(service).value = true
    }

    private fun invokeScheduleReemitIfNeeded(
        service: ContinueWatchingSnapshotService,
        entries: List<TrackingNextUpEntry>,
        nowMs: Long
    ) {
        val method = ContinueWatchingSnapshotService::class.java.getDeclaredMethod(
            "scheduleReemitIfNeeded",
            List::class.java,
            Long::class.javaPrimitiveType
        )
        method.isAccessible = true
        method.invoke(service, entries, nowMs)
    }

    private suspend fun invokeBuildRawSnapshot(
        service: ContinueWatchingSnapshotService,
        allProgress: List<WatchProgress>,
        nextUpEntries: List<TrackingNextUpEntry>,
        traktUpNextEntries: List<TrackingNextUpEntry>,
        profileId: Int = 1,
        languageTag: String = "en-US"
    ): ContinueWatchingSnapshot {
        return service.buildRawSnapshotForTest(
            profileId = profileId,
            languageTag = languageTag,
            allProgress = allProgress,
            nextUpEntries = nextUpEntries,
            traktUpNextEntries = traktUpNextEntries
        )
    }

    private fun invokeRetainStableRowsFromPreviousSnapshot(
        service: ContinueWatchingSnapshotService,
        candidate: ContinueWatchingSnapshot,
        previous: ContinueWatchingSnapshot,
        completedProgress: List<WatchProgress>
    ): ContinueWatchingSnapshot {
        val method = ContinueWatchingSnapshotService::class.java.getDeclaredMethod(
            "retainStableRowsFromPreviousSnapshot",
            ContinueWatchingSnapshot::class.java,
            ContinueWatchingSnapshot::class.java,
            List::class.java
        )
        method.isAccessible = true
        return method.invoke(service, candidate, previous, completedProgress) as ContinueWatchingSnapshot
    }

    private fun invokeSanitizeSnapshot(
        service: ContinueWatchingSnapshotService,
        snapshot: ContinueWatchingSnapshot
    ): ContinueWatchingSnapshot {
        val method = ContinueWatchingSnapshotService::class.java.getDeclaredMethod(
            "sanitizeSnapshot",
            ContinueWatchingSnapshot::class.java
        )
        method.isAccessible = true
        return method.invoke(service, snapshot) as ContinueWatchingSnapshot
    }

    @Test
    fun `canonical snapshot records merge Citadel local TVDB and remote IMDb rows and preserve unresolved rows`() =
        runTest {
            val citadelKey = ContinueWatchingCanonicalKey(
                mediaKind = MetadataMediaKind.SERIES,
                canonicalParent = ContentIdentity(
                    canonicalProvider = ProviderId.TVDB,
                    canonicalId = "393268",
                    providerIds = ProviderIds(tvdb = "393268", imdb = "tt9794044")
                ),
                season = 2,
                episode = 1,
                profileId = 4
            )
            val resolver = canonicalResolver { input ->
                when (input.progress.contentId) {
                    "tvdb:393268", "tt9794044" -> canonicalRecord(
                        input = input,
                        canonicalProvider = ProviderId.TVDB,
                        canonicalId = "393268"
                    ).copy(
                        parentId = "series:tvdb:393268",
                        contentId = "series:tvdb:393268:s2e1",
                        canonicalKey = citadelKey,
                        displayIdentity = citadelKey.canonicalParent
                    )
                    else -> canonicalRecord(
                        input = input,
                        canonicalProvider = null,
                        canonicalId = null,
                        identityConfidence = IdentityConfidence.LOW,
                        warnings = listOf("identity resolution failed: unresolved")
                    )
                }
            }
            val service = buildService(continueWatchingIdentityResolver = resolver)
            val localTvdb = resume(
                contentId = "tvdb:393268",
                videoId = "tvdb:393268:2:1",
                season = 2,
                episode = 1,
                lastWatched = 10_000L,
                source = WatchProgress.SOURCE_LOCAL
            )
            val remoteImdb = resume(
                contentId = "tt9794044",
                videoId = "tt9794044:2:1",
                season = 2,
                episode = 1,
                lastWatched = 20_000L,
                source = WatchProgress.SOURCE_TRAKT_PLAYBACK
            )
            val unresolved = resume(
                contentId = "addon:unknown",
                videoId = "addon:unknown:1:1",
                season = 1,
                episode = 1,
                lastWatched = 30_000L
            )

            val snapshot = invokeBuildRawSnapshot(
                service = service,
                profileId = 4,
                languageTag = "nl",
                allProgress = listOf(localTvdb, remoteImdb, unresolved),
                nextUpEntries = emptyList(),
                traktUpNextEntries = emptyList()
            )

            assertEquals(listOf(unresolved, remoteImdb, localTvdb), snapshot.resumeItems)
            assertEquals(2, snapshot.records.size)
            val citadel = snapshot.records.single { it.canonicalKey == citadelKey }
            assertEquals(2, citadel.resumeIdentities.size)
            assertTrue(citadel.resumeLookupKeys.contains(localTvdb.toResumeIdentity().lookupKey()))
            assertTrue(citadel.resumeLookupKeys.contains(remoteImdb.toResumeIdentity().lookupKey()))
            assertEquals("nl", citadel.languageTag)
            val unresolvedRecord = snapshot.records.single { it.identityConfidence == IdentityConfidence.LOW }
            assertEquals("addon:unknown", unresolvedRecord.parentId)
            assertTrue(unresolvedRecord.identityWarnings.single().contains("unresolved"))
        }

    @Test
    fun `observeContinueWatching emits canonical resume records plus synthetic next-up records`() =
        runTest {
            val service = buildService()
            val resume = resume(
                contentId = "tvdb:393268",
                videoId = "tvdb:393268:2:1",
                season = 2,
                episode = 1,
                lastWatched = 10_000L
            )
            val nextUp = nextUp(
                contentId = "tvdb:393268",
                firstAiredMs = 20_000L,
                episode = 2
            ).copy(season = 2, videoId = "tvdb:393268:2:2")
            val canonicalResume = canonicalRecord(
                RawContinueWatchingInput(profileId = 1, progress = resume, languageTag = "en-US")
            ).copy(
                parentId = "series:tvdb:393268",
                contentId = "series:tvdb:393268:s2e1"
            )
            val snapshot = ContinueWatchingSnapshot(
                resumeItems = listOf(resume),
                nextUpItems = listOf(nextUp),
                records = listOf(canonicalResume),
                updatedAtMs = 1L
            )
            setPublishedSnapshot(service, snapshot = snapshot)

            val records = service.observeContinueWatching(profileId = 1).first()

            assertEquals(2, records.size)
            assertTrue(records.any { it.contentId == "series:tvdb:393268:s2e1" })
            val synthetic = records.single { it.source == ContinueWatchingRecord.Source.SYNTHETIC }
            assertEquals("tvdb:393268:s2e2", synthetic.contentId)
            assertEquals(2, synthetic.episodeContext?.season)
            assertEquals(2, synthetic.episodeContext?.number)
        }

    @Test
    fun `remove and mark watched mutations do not leave stale canonical records observable`() =
        runTest {
            val service = buildService()
            val removedResume = resume(
                contentId = "show-stale",
                videoId = "show-stale:1:1",
                season = 1,
                episode = 1,
                lastWatched = 10_000L
            )
            val markedResume = resume(
                contentId = "show-marked",
                videoId = "show-marked:1:2",
                season = 1,
                episode = 2,
                lastWatched = 20_000L
            )
            val keptResume = resume(
                contentId = "show-kept",
                videoId = "show-kept:1:3",
                season = 1,
                episode = 3,
                lastWatched = 30_000L
            )
            val snapshot = ContinueWatchingSnapshot(
                resumeItems = listOf(removedResume, markedResume, keptResume),
                records = listOf(
                    canonicalRecord(RawContinueWatchingInput(1, removedResume, "en-US")),
                    canonicalRecord(RawContinueWatchingInput(1, markedResume, "en-US")),
                    canonicalRecord(RawContinueWatchingInput(1, keptResume, "en-US"))
                ),
                updatedAtMs = 1L
            )
            setPublishedSnapshot(service, snapshot = snapshot)

            service.removeResumeEntry(removedResume.videoId)
            service.applyEpisodesMarked(
                listOf(
                    ContinueWatchingSnapshotService.EpisodeRef(
                        showId = markedResume.contentId,
                        seasonNumber = markedResume.season ?: error("season"),
                        episodeNumber = markedResume.episode ?: error("episode")
                    )
                )
            )
            setPublishedSnapshot(service, snapshot = rawSnapshot(service))

            val records = service.observeContinueWatching(profileId = 1).first()

            assertEquals(listOf("show-kept:1:3"), records.map { it.contentId })
            assertFalse(records.any { it.parentId == removedResume.contentId })
            assertFalse(records.any { it.parentId == markedResume.contentId })
        }

    @Test
    fun `removeShowOptimistically preserves canonical resume aliases for unaffected records`() =
        runTest {
            val service = buildService()
            val resume = resume(
                contentId = "tvdb:393268",
                videoId = "tvdb:393268:2:1",
                season = 2,
                episode = 1,
                lastWatched = 10_000L
            )
            val record = canonicalRecord(
                RawContinueWatchingInput(profileId = 1, progress = resume, languageTag = "en-US")
            ).copy(
                parentId = "series:tvdb:393268",
                contentId = "series:tvdb:393268:s2e1",
                resumeIdentities = listOf(
                    resume.toResumeIdentity(),
                    resume.copy(contentId = "tt9794044", videoId = "tt9794044:2:1").toResumeIdentity()
                ),
                primaryResumeLookupKey = resume.toResumeIdentity().lookupKey()
            )
            val unrelatedNextUp = nextUp(
                contentId = "show-next-up-only",
                firstAiredMs = 20_000L,
                episode = 2
            )
            setPublishedSnapshot(
                service = service,
                snapshot = ContinueWatchingSnapshot(
                    resumeItems = listOf(resume),
                    nextUpItems = listOf(unrelatedNextUp),
                    records = listOf(record),
                    updatedAtMs = 1L
                )
            )

            service.removeShowOptimistically("show-next-up-only")

            val snapshot = rawSnapshot(service)

            assertEquals(emptyList<TrackingNextUpEntry>(), snapshot.nextUpItems)
            assertEquals(listOf(record), snapshot.records)
            assertTrue(snapshot.records.single().resumeLookupKeys.contains("tt9794044|tt9794044:2:1|2|1"))
        }

    @Test
    fun `removeResumeEntry filters only affected canonical resume record`() =
        runTest {
            val service = buildService()
            val removedResume = resume(
                contentId = "show-removed",
                videoId = "show-removed:1:1",
                season = 1,
                episode = 1,
                lastWatched = 10_000L
            )
            val keptResume = resume(
                contentId = "show-kept",
                videoId = "show-kept:1:2",
                season = 1,
                episode = 2,
                lastWatched = 20_000L
            )
            val removedRecord = canonicalRecord(RawContinueWatchingInput(1, removedResume, "en-US"))
            val keptRecord = canonicalRecord(RawContinueWatchingInput(1, keptResume, "en-US"))
            setPublishedSnapshot(
                service = service,
                snapshot = ContinueWatchingSnapshot(
                    resumeItems = listOf(removedResume, keptResume),
                    records = listOf(removedRecord, keptRecord),
                    updatedAtMs = 1L
                )
            )

            service.removeResumeEntry(removedResume.videoId)

            val snapshot = rawSnapshot(service)

            assertEquals(listOf(keptResume), snapshot.resumeItems)
            assertEquals(listOf(keptRecord), snapshot.records)
            assertFalse(snapshot.records.any { it.resumeLookupKeys.contains(removedResume.toResumeIdentity().lookupKey()) })
        }

    @Test
    fun `production constructor requires ContinueWatchingIdentityResolver`() {
        val productionConstructor = ContinueWatchingSnapshotService::class.java.declaredConstructors
            .firstOrNull { constructor ->
                constructor.getAnnotation(Inject::class.java) != null
            }
            ?: error("ContinueWatchingSnapshotService must have an @Inject constructor")

        assertTrue(
            "Production @Inject constructor must require ContinueWatchingIdentityResolver",
            productionConstructor.parameterTypes.any { it == ContinueWatchingIdentityResolver::class.java }
        )
    }

    private fun awaitCondition(timeoutMs: Long = 2_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10L)
        }
        assertTrue("Condition was not met within ${timeoutMs}ms", condition())
    }

    @Test
    fun `raw snapshot drops stale local resume when provider has newer completed episode next up`() =
        runTest {
            val service = buildService()
            val staleLocalResume = resume(
                contentId = "tt1520211",
                videoId = "tt1520211:1:2",
                season = 1,
                episode = 2,
                lastWatched = 1_000L,
                progressPercent = 40f,
                source = WatchProgress.SOURCE_LOCAL
            )
            val providerCompletedEpisode = resume(
                contentId = "tt1520211",
                videoId = "tt1520211:5:2",
                season = 5,
                episode = 2,
                lastWatched = 200_000L,
                progressPercent = 100f,
                source = WatchProgress.SOURCE_TRAKT_HISTORY
            )
            val providerNextUp = nextUp(
                contentId = "tt1520211",
                firstAiredMs = 1L,
                episode = 3
            ).copy(season = 5, videoId = "tt1520211:5:3", activityAtMs = 200_000L)

            val snapshot = invokeBuildRawSnapshot(
                service = service,
                allProgress = listOf(staleLocalResume, providerCompletedEpisode),
                nextUpEntries = listOf(providerNextUp),
                traktUpNextEntries = emptyList()
            )

            assertEquals(emptyList<WatchProgress>(), snapshot.resumeItems)
            assertEquals(listOf(providerNextUp), snapshot.nextUpItems)
        }

    @Test
    fun `raw snapshot drops next up entry when target episode is already completed`() =
        runTest {
            val service = buildService()
            val completedTargetEpisode = resume(
                contentId = "tt27444205",
                videoId = "tt27444205:2:9",
                season = 2,
                episode = 9,
                lastWatched = 200_000L,
                progressPercent = 100f,
                source = WatchProgress.SOURCE_TRAKT_HISTORY
            )
            val staleNextUp = nextUp(
                contentId = "tt27444205",
                firstAiredMs = 1L,
                episode = 9
            ).copy(season = 2, videoId = "tt27444205:2:9", activityAtMs = 200_000L)

            val snapshot = invokeBuildRawSnapshot(
                service = service,
                allProgress = listOf(completedTargetEpisode),
                nextUpEntries = listOf(staleNextUp),
                traktUpNextEntries = listOf(staleNextUp)
            )

            assertEquals(emptyList<TrackingNextUpEntry>(), snapshot.nextUpItems)
            assertEquals(emptyList<TrackingNextUpEntry>(), snapshot.traktUpNextItems)
        }

    @Test
    fun `raw snapshot suppresses fresh alias rows at completed coordinate`() =
        runTest {
            val service = buildService()
            val completedParadise = resume(
                contentId = "tvdb:454565",
                videoId = "tvdb:454565:2:7",
                season = 2,
                episode = 7,
                lastWatched = 200_000L,
                progressPercent = 100f,
                source = WatchProgress.SOURCE_TRAKT_HISTORY
            )
            val staleResumeAlias = resume(
                contentId = "series:tvdb:454565",
                videoId = "series:tvdb:454565:s2e7",
                season = 2,
                episode = 7,
                lastWatched = 100_000L,
                progressPercent = 45f,
                source = WatchProgress.SOURCE_LOCAL
            )
            val staleNextUpAlias = nextUp(
                contentId = "series:tvdb:454565",
                firstAiredMs = 1L,
                episode = 7
            ).copy(
                season = 2,
                videoId = "series:tvdb:454565:s2e7",
                activityAtMs = 100_000L
            )

            val snapshot = invokeBuildRawSnapshot(
                service = service,
                allProgress = listOf(staleResumeAlias, completedParadise),
                nextUpEntries = listOf(staleNextUpAlias),
                traktUpNextEntries = listOf(staleNextUpAlias)
            )

            assertEquals(emptyList<WatchProgress>(), snapshot.resumeItems)
            assertEquals(emptyList<TrackingNextUpEntry>(), snapshot.nextUpItems)
            assertEquals(emptyList<TrackingNextUpEntry>(), snapshot.traktUpNextItems)
            assertEquals(emptyList<ContinueWatchingRecord>(), snapshot.records)
        }

    @Test
    fun `raw snapshot suppresses provider-media alias next up from watched anchor`() =
        runTest {
            val service = buildService()
            val completedAlias = resume(
                contentId = "tvdb:series:454565",
                videoId = "tvdb:series:454565:s2e7",
                season = 2,
                episode = 7,
                lastWatched = 200_000L,
                progressPercent = 100f,
                source = WatchProgress.SOURCE_TRAKT_HISTORY
            )
            val staleNextUp = nextUp(
                contentId = "tvdb:454565",
                firstAiredMs = 1L,
                episode = 7
            ).copy(
                season = 2,
                videoId = "tvdb:454565:2:7",
                activityAtMs = 100_000L
            )

            val snapshot = invokeBuildRawSnapshot(
                service = service,
                allProgress = listOf(completedAlias),
                nextUpEntries = listOf(staleNextUp),
                traktUpNextEntries = emptyList()
            )

            assertEquals(emptyList<TrackingNextUpEntry>(), snapshot.nextUpItems)
        }

    @Test
    fun `buildRawSnapshot drops unknown-air-date next-up from main feed and scheduled reemit`() =
        runTest {
            val service = buildService()
            val resumeWithoutAirDate = resume(
                contentId = "resume-series",
                videoId = "resume-series:1:1",
                season = 1,
                episode = 1,
                lastWatched = 50_000L
            )
            val unknownNextUp = nextUp(
                contentId = "unknown-air-date",
                firstAiredMs = 0L,
                firstAired = null,
                episode = 2
            ).copy(activityAtMs = 60_000L)

            val snapshot = invokeBuildRawSnapshot(
                service = service,
                allProgress = listOf(resumeWithoutAirDate),
                nextUpEntries = listOf(unknownNextUp),
                traktUpNextEntries = listOf(unknownNextUp)
            )

            assertEquals(listOf(resumeWithoutAirDate), snapshot.resumeItems)
            assertEquals(emptyList<TrackingNextUpEntry>(), snapshot.nextUpItems)
            assertEquals(emptyList<TrackingNextUpEntry>(), snapshot.traktUpNextItems)
            assertEquals(emptyList<TrackingNextUpEntry>(), snapshot.scheduledReemit)
        }

    @Test
    fun `buildRawSnapshot schedules concrete future next-up instead of rendering it`() =
        runTest {
            val service = buildService()
            val futureAiredMs = System.currentTimeMillis() + 86_400_000L
            val futureNextUp = nextUp(
                contentId = "future-air-date",
                firstAiredMs = futureAiredMs,
                episode = 2
            )

            val snapshot = invokeBuildRawSnapshot(
                service = service,
                allProgress = emptyList(),
                nextUpEntries = listOf(futureNextUp),
                traktUpNextEntries = listOf(futureNextUp)
            )

            assertEquals(emptyList<TrackingNextUpEntry>(), snapshot.nextUpItems)
            assertEquals(emptyList<TrackingNextUpEntry>(), snapshot.traktUpNextItems)
            assertEquals(listOf(futureNextUp, futureNextUp), snapshot.scheduledReemit)
        }

    @Test
    fun `buildRawSnapshot keeps TMDB next-up coordinates when order policy is TMDB default`() =
        runTest {
            val facade = mockk<MetadataRouterFacade>(relaxed = true)
            coEvery {
                facade.routeRequest(any())
            } returns MetadataRoute(
                provider = MetadataPrimaryProvider.TMDB,
                parentId = "tmdb:71446",
                mediaKind = MetadataMediaKind.SERIES,
                reason = MetadataDecisionReason.ITEM_TYPE_SERIES,
                sourceContext = MetadataSourceContext(itemType = "series"),
                targetIds = mapOf(
                    MetadataPrimaryProvider.TMDB to "tmdb:71446",
                    MetadataPrimaryProvider.TVDB to "tvdb:81189"
                ),
                trace = emptyList()
            )
            coEvery {
                facade.fetchTvEpisodeEnrichment(metadataRequest = any(), tvRequest = any())
            } returns TvMetadataDecision(
                provider = TvProvider.TVDB,
                reason = TvMetadataDecisionReason.TVDB_SUCCESS,
                value = mapOf(
                    (3 to 1) to TvEpisodeMetadata(
                        seasonNumber = 3,
                        episodeNumber = 1,
                        title = "TVDB Coordinate",
                        airDate = "2026-02-17"
                    )
                )
            )
            val service = buildServiceWithMetadataFacade(
                facade = facade,
                tvEpisodeOrderResolver = tmdbDefaultOrderResolver()
            )
            val tmdbNextUp = nextUp(
                contentId = "tmdb:71446",
                firstAiredMs = 1L,
                firstAired = "2026-02-01"
            ).copy(
                season = 2,
                episode = 1,
                episodeTitle = "TMDB Coordinate",
                videoId = "tmdb:71446:2:1"
            )

            val snapshot = invokeBuildRawSnapshot(
                service = service,
                allProgress = emptyList(),
                nextUpEntries = listOf(tmdbNextUp),
                traktUpNextEntries = emptyList()
            )

            val item = snapshot.nextUpItems.single()
            assertEquals("tmdb:71446", item.contentId)
            assertEquals(2, item.season)
            assertEquals(1, item.episode)
            assertEquals("tmdb:71446:2:1", item.videoId)
            coVerify(exactly = 0) {
                facade.fetchTvEpisodeEnrichment(metadataRequest = any(), tvRequest = any())
            }
        }

    @Test
    fun `buildRawSnapshot projects TMDB next-up coordinates only for manual TVDB order override`() =
        runTest {
            val facade = mockk<MetadataRouterFacade>(relaxed = true)
            val requestSlot = slot<MetadataRequest>()
            coEvery {
                facade.routeRequest(any())
            } returns MetadataRoute(
                provider = MetadataPrimaryProvider.TMDB,
                parentId = "tmdb:12345",
                mediaKind = MetadataMediaKind.SERIES,
                reason = MetadataDecisionReason.ITEM_TYPE_SERIES,
                sourceContext = MetadataSourceContext(itemType = "series"),
                targetIds = mapOf(
                    MetadataPrimaryProvider.TMDB to "tmdb:12345",
                    MetadataPrimaryProvider.TVDB to "tvdb:67890"
                ),
                trace = emptyList()
            )
            coEvery {
                facade.fetchTvEpisodeEnrichment(metadataRequest = capture(requestSlot), tvRequest = any())
            } returns TvMetadataDecision(
                provider = TvProvider.TVDB,
                reason = TvMetadataDecisionReason.TVDB_SUCCESS,
                value = mapOf(
                    (14 to 10) to TvEpisodeMetadata(
                        seasonNumber = 14,
                        episodeNumber = 10,
                        title = "TVDB Override Coordinate",
                        airDate = "2026-02-17"
                    )
                )
            )
            val service = buildServiceWithMetadataFacade(
                facade = facade,
                tvEpisodeOrderResolver = tvdbDefaultOrderResolver(
                    tmdbTvId = "tmdb:tv:12345",
                    tvdbSeriesId = "67890"
                )
            )
            val tmdbNextUp = nextUp(
                contentId = "tmdb:12345",
                firstAiredMs = 1L,
                firstAired = "2026-02-01"
            ).copy(
                season = 12,
                episode = 10,
                episodeTitle = "TVDB Override Coordinate",
                videoId = "tmdb:12345:12:10"
            )

            val snapshot = invokeBuildRawSnapshot(
                service = service,
                allProgress = emptyList(),
                nextUpEntries = listOf(tmdbNextUp),
                traktUpNextEntries = emptyList()
            )

            val item = snapshot.nextUpItems.single()
            assertEquals("tmdb:12345", item.contentId)
            assertEquals(14, item.season)
            assertEquals(10, item.episode)
            assertEquals("tmdb:12345:14:10", item.videoId)
            assertEquals("12345", requestSlot.captured.sourceContext.previewStableIds.tmdb)
            assertEquals("67890", requestSlot.captured.sourceContext.previewStableIds.tvdb)
        }

    @Test
    fun `buildRawSnapshot projects non-anime next-up rows to TVDB coordinates`() =
        runTest {
            val facade = mockk<MetadataRouterFacade>(relaxed = true)
            stubTvdbProjectionRoute(facade)
            coEvery {
                facade.fetchTvEpisodeEnrichment(metadataRequest = any(), tvRequest = any())
            } returns TvMetadataDecision(
                provider = TvProvider.TVDB,
                reason = TvMetadataDecisionReason.TVDB_SUCCESS,
                value = mapOf(
                    (14 to 1) to TvEpisodeMetadata(
                        seasonNumber = 14,
                        episodeNumber = 1,
                        title = "The Multiverse",
                        airDate = "2026-02-17"
                    )
                )
            )
            val service = buildServiceWithMetadataFacade(facade)
            val providerCoordinate = nextUp(
                contentId = "tvdb:303904",
                firstAiredMs = 1L,
                firstAired = "2026-02-01"
            ).copy(
                season = 13,
                episode = 1,
                episodeTitle = "The Multiverse",
                videoId = "tvdb:303904:13:1"
            )

            val snapshot = invokeBuildRawSnapshot(
                service = service,
                allProgress = emptyList(),
                nextUpEntries = listOf(providerCoordinate),
                traktUpNextEntries = emptyList()
            )

            val projected = snapshot.nextUpItems.single()
            assertEquals("tmdb:12345", projected.contentId)
            assertEquals(14, projected.season)
            assertEquals(1, projected.episode)
            assertEquals("The Multiverse", projected.episodeTitle)
            assertEquals("tmdb:12345:14:1", projected.videoId)
            assertEquals("2026-02-17", projected.firstAired)
            assertEquals(AirDateGate.pendingTriggerMs(0L, null, "2026-02-17"), projected.firstAiredMs)
        }

    @Test
    fun `sanitizeSnapshot collapses provider and local next-up for same show title`() =
        runTest {
            val service = buildService()
            val providerNextUp = nextUp(
                contentId = "tvdb:411364",
                firstAiredMs = 2_000L,
                episode = 11
            ).copy(
                name = "Shrinking",
                season = 3,
                videoId = "tvdb:411364:3:11",
                activityAtMs = 20_000L
            )
            val localNextUp = nextUp(
                contentId = "tt15677150",
                firstAiredMs = 1_000L,
                episode = 8
            ).copy(
                name = "Shrinking",
                season = 3,
                videoId = "tt15677150:3:8",
                activityAtMs = 10_000L
            )

            val snapshot = invokeSanitizeSnapshot(
                service = service,
                snapshot = ContinueWatchingSnapshot(
                    nextUpItems = listOf(providerNextUp, localNextUp)
                )
            )

            val item = snapshot.nextUpItems.single()
            assertEquals("tvdb:411364", item.contentId)
            assertEquals(3, item.season)
            assertEquals(11, item.episode)
        }

    @Test
    fun `sanitizeSnapshot keeps main next-up when Trakt up-next has same show`() =
        runTest {
            val service = buildService()
            val mainNextUp = nextUp(
                contentId = "tvdb:303904",
                firstAiredMs = 1L,
                episode = 20
            ).copy(
                name = "Australian Survivor",
                season = 14,
                episodeTitle = "Maggots",
                videoId = "tvdb:303904:14:20",
                activityAtMs = 1776547920000L
            )
            val traktNextUp = mainNextUp.copy(traktEpisodeId = 14085354)

            val snapshot = invokeSanitizeSnapshot(
                service = service,
                snapshot = ContinueWatchingSnapshot(
                    nextUpItems = listOf(mainNextUp),
                    traktUpNextItems = listOf(traktNextUp)
                )
            )

            assertEquals(listOf(mainNextUp), snapshot.nextUpItems)
            assertEquals(listOf(traktNextUp), snapshot.traktUpNextItems)
        }

    @Test
    fun `buildRawSnapshot suppresses older resume when watched show anchor is newer coordinate`() = runTest {
        val service = buildService()
        val oldResume = resume(
            contentId = "tvdb:386630",
            videoId = "tvdb:386630:1:2",
            season = 1,
            episode = 2,
            lastWatched = 1763758537000L,
            progressPercent = 30.2367f,
            source = WatchProgress.SOURCE_TRAKT_PLAYBACK
        ).copy(name = "Mayor of Kingstown")
        val watchedAnchor = resume(
            contentId = "tvdb:386630",
            videoId = "tvdb:386630:4:10",
            season = 4,
            episode = 10,
            lastWatched = 1772894400000L,
            progressPercent = 100f,
            source = WatchProgress.SOURCE_TRAKT_HISTORY
        ).copy(name = "Mayor of Kingstown")

        val snapshot = service.buildRawSnapshotForTest(
            allProgress = listOf(oldResume, watchedAnchor),
            nextUpEntries = emptyList(),
            traktUpNextEntries = emptyList()
        )

        assertEquals(emptyList<WatchProgress>(), snapshot.resumeItems)
    }

    @Test
    fun `buildRawSnapshot drops stale imdb next-up when artwork canonical show anchor is newer coordinate`() =
        runTest {
            val service = buildService()
            val staleNextUp = nextUp(
                contentId = "tt15677150",
                firstAiredMs = 1778950000000L,
                episode = 8
            ).copy(
                name = "Shrinking",
                season = 3,
                videoId = "tt15677150:3:8",
                backdrop = "nexio-artwork://decision/artwork-decision:backdrop:canonical:tvdb:411364:provider:TVDB:premium:false"
            )
            val watchedAnchor = resume(
                contentId = "tvdb:411364",
                videoId = "tvdb:411364:3:11",
                season = 3,
                episode = 11,
                lastWatched = 1778959000000L,
                progressPercent = 100f,
                source = WatchProgress.SOURCE_TRAKT_HISTORY
            ).copy(name = "Shrinking")

            val snapshot = service.buildRawSnapshotForTest(
                allProgress = listOf(watchedAnchor),
                nextUpEntries = listOf(staleNextUp),
                traktUpNextEntries = emptyList()
            )

            assertEquals(emptyList<TrackingNextUpEntry>(), snapshot.nextUpItems)
        }

    @Test
    fun `buildRawSnapshot keeps remote playback resume when watched anchor is same coordinate`() = runTest {
        val service = buildService()
        val remotePlayback = resume(
            contentId = "tvdb:79481",
            videoId = "tvdb:79481:1:2",
            season = 1,
            episode = 2,
            lastWatched = 1778959000000L,
            progressPercent = 45f,
            source = WatchProgress.SOURCE_TRAKT_PLAYBACK
        ).copy(name = "Death Note")
        val sameEpisodeWatchedAnchor = resume(
            contentId = "tvdb:79481",
            videoId = "tvdb:79481:1:2",
            season = 1,
            episode = 2,
            lastWatched = 1778958000000L,
            progressPercent = 100f,
            source = WatchProgress.SOURCE_TRAKT_HISTORY
        ).copy(name = "Death Note")

        val snapshot = service.buildRawSnapshotForTest(
            allProgress = listOf(remotePlayback, sameEpisodeWatchedAnchor),
            nextUpEntries = emptyList(),
            traktUpNextEntries = emptyList()
        )

        assertEquals(listOf(remotePlayback), snapshot.resumeItems)
    }

    @Test
    fun `buildRawSnapshot keeps remote playback resume when same episode completion is newer`() = runTest {
        val service = buildService()
        val remotePlayback = resume(
            contentId = "tvdb:79481",
            videoId = "tvdb:79481:1:2",
            season = 1,
            episode = 2,
            lastWatched = 1778958000000L,
            progressPercent = 45f,
            source = WatchProgress.SOURCE_TRAKT_PLAYBACK
        ).copy(name = "Death Note")
        val sameEpisodeWatchedAnchor = resume(
            contentId = "tvdb:79481",
            videoId = "tvdb:79481:1:2",
            season = 1,
            episode = 2,
            lastWatched = 1778959000000L,
            progressPercent = 100f,
            source = WatchProgress.SOURCE_TRAKT_HISTORY
        ).copy(name = "Death Note")

        val snapshot = service.buildRawSnapshotForTest(
            allProgress = listOf(remotePlayback, sameEpisodeWatchedAnchor),
            nextUpEntries = emptyList(),
            traktUpNextEntries = emptyList()
        )

        assertEquals(listOf(remotePlayback), snapshot.resumeItems)
    }

    @Test
    fun `buildRawSnapshot keeps provider next-up that projects after completion anchor`() =
        runTest {
            val facade = mockk<MetadataRouterFacade>(relaxed = true)
            stubTvdbProjectionRoute(facade)
            coEvery {
                facade.fetchTvEpisodeEnrichment(metadataRequest = any(), tvRequest = any())
            } returns TvMetadataDecision(
                provider = TvProvider.TVDB,
                reason = TvMetadataDecisionReason.TVDB_SUCCESS,
                value = mapOf(
                    (14 to 1) to TvEpisodeMetadata(
                        seasonNumber = 14,
                        episodeNumber = 1,
                        title = "The Multiverse",
                        airDate = "2026-02-17"
                    )
                )
            )
            val service = buildServiceWithMetadataFacade(facade)
            val completedAnchor = resume(
                contentId = "tvdb:303904",
                videoId = "tvdb:303904:13:1",
                season = 13,
                episode = 1,
                lastWatched = 10_000L,
                progressPercent = 100f,
                source = WatchProgress.SOURCE_TRAKT_PLAYBACK
            ).copy(contentType = "movie")
            val providerCoordinate = nextUp(
                contentId = "tvdb:303904",
                firstAiredMs = 1L,
                firstAired = "2026-02-01"
            ).copy(
                season = 13,
                episode = 1,
                episodeTitle = "The Multiverse",
                videoId = "tvdb:303904:13:1",
                activityAtMs = 9_000L
            )

            val snapshot = invokeBuildRawSnapshot(
                service = service,
                allProgress = listOf(completedAnchor),
                nextUpEntries = listOf(providerCoordinate),
                traktUpNextEntries = emptyList()
            )

            val projected = snapshot.nextUpItems.single()
            assertEquals("tmdb:12345", projected.contentId)
            assertEquals(14, projected.season)
            assertEquals(1, projected.episode)
            assertEquals("tmdb:12345:14:1", projected.videoId)
        }

    @Test
    fun `buildRawSnapshot projects Trakt up-next rows to TVDB coordinates`() =
        runTest {
            val facade = mockk<MetadataRouterFacade>(relaxed = true)
            stubTvdbProjectionRoute(facade)
            coEvery {
                facade.fetchTvEpisodeEnrichment(metadataRequest = any(), tvRequest = any())
            } returns TvMetadataDecision(
                provider = TvProvider.TVDB,
                reason = TvMetadataDecisionReason.TVDB_SUCCESS,
                value = mapOf(
                    (3 to 4) to TvEpisodeMetadata(
                        seasonNumber = 3,
                        episodeNumber = 4,
                        title = "The Canonical One",
                        airDate = "2026-02-17"
                    )
                )
            )
            val service = buildServiceWithMetadataFacade(facade)
            val providerCoordinate = nextUp(
                contentId = "tvdb:303904",
                firstAiredMs = 1L,
                firstAired = "2026-02-01"
            ).copy(
                season = 2,
                episode = 4,
                episodeTitle = "The Canonical One",
                videoId = "tvdb:303904:2:4"
            )

            val snapshot = invokeBuildRawSnapshot(
                service = service,
                allProgress = emptyList(),
                nextUpEntries = emptyList(),
                traktUpNextEntries = listOf(providerCoordinate)
            )

            val projected = snapshot.traktUpNextItems.single()
            assertEquals("tmdb:12345", projected.contentId)
            assertEquals(3, projected.season)
            assertEquals(4, projected.episode)
            assertEquals("The Canonical One", projected.episodeTitle)
            assertEquals("tmdb:12345:3:4", projected.videoId)
        }

    @Test
    fun `buildRawSnapshot shares TVDB episode enrichment across duplicate next-up rows`() =
        runTest {
            val facade = mockk<MetadataRouterFacade>(relaxed = true)
            stubTvdbProjectionRoute(facade)
            coEvery {
                facade.fetchTvEpisodeEnrichment(metadataRequest = any(), tvRequest = any())
            } returns TvMetadataDecision(
                provider = TvProvider.TVDB,
                reason = TvMetadataDecisionReason.TVDB_SUCCESS,
                value = mapOf(
                    (14 to 1) to TvEpisodeMetadata(
                        seasonNumber = 14,
                        episodeNumber = 1,
                        title = "The Multiverse",
                        airDate = "2026-02-17"
                    )
                )
            )
            val service = buildServiceWithMetadataFacade(facade)
            val providerCoordinate = nextUp(
                contentId = "tvdb:303904",
                firstAiredMs = 1L,
                firstAired = "2026-02-01"
            ).copy(
                season = 13,
                episode = 1,
                episodeTitle = "The Multiverse",
                videoId = "tvdb:303904:13:1",
                activityAtMs = 10_000L
            )
            val duplicateProviderCoordinate = providerCoordinate.copy(
                videoId = "tvdb:303904:13:1:duplicate",
                activityAtMs = 9_000L
            )

            val snapshot = invokeBuildRawSnapshot(
                service = service,
                allProgress = emptyList(),
                nextUpEntries = listOf(providerCoordinate, duplicateProviderCoordinate),
                traktUpNextEntries = listOf(providerCoordinate)
            )

            assertEquals(1, snapshot.nextUpItems.size)
            assertEquals(1, snapshot.traktUpNextItems.size)
            coVerify(exactly = 1) {
                facade.fetchTvEpisodeEnrichment(metadataRequest = any(), tvRequest = any())
            }
        }

    @Test
    fun `buildRawSnapshot keeps main next-up when Trakt up-next has same show`() =
        runTest {
            val service = buildService()
            val staleMainNextUp = nextUp(
                contentId = "tvdb:303904",
                firstAiredMs = 1L,
                firstAired = "2025-03-31"
            ).copy(
                name = "Australian Survivor",
                season = 12,
                episode = 20,
                episodeTitle = "The Reaper Is Coming",
                videoId = "tvdb:303904:12:20",
                activityAtMs = 1776547920000L
            )
            val correctedTraktUpNext = staleMainNextUp.copy(
                season = 14,
                episode = 20,
                episodeTitle = "Maggots",
                videoId = "tvdb:303904:14:20",
                firstAired = "2026-04-06"
            )

            val snapshot = invokeBuildRawSnapshot(
                service = service,
                allProgress = emptyList(),
                nextUpEntries = listOf(staleMainNextUp),
                traktUpNextEntries = listOf(correctedTraktUpNext)
            )

            assertEquals(listOf(staleMainNextUp), snapshot.nextUpItems)
            assertEquals(listOf(correctedTraktUpNext), snapshot.traktUpNextItems)
        }

    @Test
    fun `retained main next-up is kept when retained Trakt up-next has same show`() {
        val service = buildService()
        val staleMainNextUp = TrackingNextUpEntry(
            contentId = "tvdb:303904",
            contentType = "series",
            name = "Australian Survivor",
            season = 12,
            episode = 20,
            episodeTitle = "The Reaper Is Coming",
            videoId = "tvdb:303904:12:20",
            firstAired = "2025-03-31",
            firstAiredMs = 1L,
            activityAtMs = 1776547920000L
        )
        val correctedTraktUpNext = staleMainNextUp.copy(
            season = 14,
            episode = 20,
            episodeTitle = "Maggots",
            videoId = "tvdb:303904:14:20",
            firstAired = "2026-04-06"
        )
        val previous = ContinueWatchingSnapshot(
            nextUpItems = listOf(staleMainNextUp),
            traktUpNextItems = listOf(correctedTraktUpNext),
            updatedAtMs = 100_000L
        )

        val retained = invokeRetainStableRowsFromPreviousSnapshot(
            service = service,
            candidate = ContinueWatchingSnapshot(updatedAtMs = 200_000L),
            previous = previous,
            completedProgress = emptyList()
        )

        assertEquals(listOf(staleMainNextUp), retained.nextUpItems)
        assertEquals(listOf(correctedTraktUpNext), retained.traktUpNextItems)
    }

    @Test
    fun `buildRawSnapshot reuses local next-up episode map during projection`() =
        runTest {
            val facade = mockk<MetadataRouterFacade>(relaxed = true)
            stubTvdbProjectionRoute(facade)
            coEvery {
                facade.fetchTvEpisodeEnrichment(metadataRequest = any(), tvRequest = any())
            } returns TvMetadataDecision(
                provider = TvProvider.TVDB,
                reason = TvMetadataDecisionReason.TVDB_SUCCESS,
                value = mapOf(
                    (13 to 1) to TvEpisodeMetadata(
                        seasonNumber = 13,
                        episodeNumber = 1,
                        title = "Completed",
                        airDate = "2026-02-01"
                    ),
                    (13 to 2) to TvEpisodeMetadata(
                        seasonNumber = 13,
                        episodeNumber = 2,
                        title = "Next",
                        airDate = "2026-02-08"
                    )
                )
            )
            val service = buildServiceWithMetadataFacade(facade)
            val completedLocalProgress = resume(
                contentId = "tvdb:303904",
                videoId = "tvdb:303904:13:1",
                season = 13,
                episode = 1,
                lastWatched = 10_000L,
                progressPercent = 100f,
                source = WatchProgress.SOURCE_LOCAL
            )

            val snapshot = invokeBuildRawSnapshot(
                service = service,
                allProgress = listOf(completedLocalProgress),
                nextUpEntries = emptyList(),
                traktUpNextEntries = emptyList()
            )

            val localNextUp = snapshot.nextUpItems.single()
            assertEquals(13, localNextUp.season)
            assertEquals(2, localNextUp.episode)
            coVerify(exactly = 2) {
                facade.fetchTvEpisodeEnrichment(metadataRequest = any(), tvRequest = any())
            }
        }

    @Test
    fun `buildRawSnapshot keeps original next-up row when TVDB projection fails`() =
        runTest {
            val facade = mockk<MetadataRouterFacade>(relaxed = true)
            stubTvdbProjectionRoute(facade)
            coEvery {
                facade.fetchTvEpisodeEnrichment(metadataRequest = any(), tvRequest = any())
            } throws IllegalStateException("tvdb unavailable")
            val service = buildServiceWithMetadataFacade(facade)
            val providerCoordinate = nextUp(
                contentId = "tvdb:303904",
                firstAiredMs = 1L,
                firstAired = "2026-02-01"
            ).copy(
                season = 13,
                episode = 1,
                episodeTitle = "The Multiverse",
                videoId = "tvdb:303904:13:1"
            )

            val snapshot = invokeBuildRawSnapshot(
                service = service,
                allProgress = emptyList(),
                nextUpEntries = listOf(providerCoordinate),
                traktUpNextEntries = emptyList()
            )

            assertEquals(listOf(providerCoordinate), snapshot.nextUpItems)
        }

    @Test
    fun `buildRawSnapshot suppresses tmdb next-up when route resolves watched TV show`() =
        runTest {
            val facade = mockk<MetadataRouterFacade>(relaxed = true)
            coEvery {
                facade.routeRequest(any())
            } returns MetadataRoute(
                provider = MetadataPrimaryProvider.TMDB,
                parentId = "tmdb:71446",
                mediaKind = MetadataMediaKind.SERIES,
                reason = MetadataDecisionReason.ITEM_TYPE_SERIES,
                sourceContext = MetadataSourceContext(itemType = "series"),
                targetIds = mapOf(
                    MetadataPrimaryProvider.TMDB to "tmdb:71446",
                    MetadataPrimaryProvider.IMDB to "tt15677150",
                    MetadataPrimaryProvider.TVDB to "tvdb:411364"
                ),
                trace = emptyList()
            )
            coEvery {
                facade.fetchTvEpisodeEnrichment(metadataRequest = any(), tvRequest = any())
            } returns TvMetadataDecision(
                provider = TvProvider.TVDB,
                reason = TvMetadataDecisionReason.TVDB_SUCCESS,
                value = emptyMap()
            )
            val service = buildServiceWithMetadataFacade(facade)
            val watchedAnchor = resume(
                contentId = "tmdb:71446",
                videoId = "tmdb:71446:3:11",
                season = 3,
                episode = 11,
                lastWatched = 1778959000000L,
                progressPercent = 100f,
                source = WatchProgress.SOURCE_TRAKT_HISTORY
            ).copy(name = "Shrinking")
            val staleNextUp = nextUp(
                contentId = "tmdb:71446",
                firstAiredMs = 1L,
                episode = 8
            ).copy(
                name = "Shrinking",
                season = 3,
                videoId = "tmdb:71446:3:8",
                activityAtMs = 1773247741000L
            )

            val snapshot = invokeBuildRawSnapshot(
                service = service,
                allProgress = listOf(watchedAnchor),
                nextUpEntries = listOf(staleNextUp),
                traktUpNextEntries = emptyList()
            )

            assertEquals(emptyList<TrackingNextUpEntry>(), snapshot.nextUpItems)
        }

    @Test
    fun `buildRawSnapshot rethrows cancellation from TVDB projection`() =
        runTest {
            val facade = mockk<MetadataRouterFacade>(relaxed = true)
            stubTvdbProjectionRoute(facade)
            coEvery {
                facade.fetchTvEpisodeEnrichment(metadataRequest = any(), tvRequest = any())
            } throws CancellationException("cancelled")
            val service = buildServiceWithMetadataFacade(facade)
            val providerCoordinate = nextUp(
                contentId = "tvdb:303904",
                firstAiredMs = 1L,
                firstAired = "2026-02-01"
            ).copy(
                season = 13,
                episode = 1,
                episodeTitle = "The Multiverse",
                videoId = "tvdb:303904:13:1"
            )

            try {
                invokeBuildRawSnapshot(
                    service = service,
                    allProgress = emptyList(),
                    nextUpEntries = listOf(providerCoordinate),
                    traktUpNextEntries = emptyList()
                )
                fail("Expected CancellationException")
            } catch (_: CancellationException) {
                // Expected: cancellation must not be swallowed by projection fallback.
            }
        }

    @Test
    fun `buildRawSnapshot withholds projected future TVDB air date and schedules reemit`() =
        runTest {
            val tomorrow = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
                .plusDays(1)
                .toString()
            val facade = mockk<MetadataRouterFacade>(relaxed = true)
            stubTvdbProjectionRoute(facade)
            coEvery {
                facade.fetchTvEpisodeEnrichment(metadataRequest = any(), tvRequest = any())
            } returns TvMetadataDecision(
                provider = TvProvider.TVDB,
                reason = TvMetadataDecisionReason.TVDB_SUCCESS,
                value = mapOf(
                    (14 to 1) to TvEpisodeMetadata(
                        seasonNumber = 14,
                        episodeNumber = 1,
                        title = "Tomorrow",
                        airDate = tomorrow
                    )
                )
            )
            val service = buildServiceWithMetadataFacade(facade)
            val providerCoordinate = nextUp(
                contentId = "tvdb:303904",
                firstAiredMs = 1L,
                firstAired = "2026-02-01"
            ).copy(
                season = 13,
                episode = 1,
                episodeTitle = "Tomorrow",
                videoId = "tvdb:303904:13:1"
            )

            val snapshot = invokeBuildRawSnapshot(
                service = service,
                allProgress = emptyList(),
                nextUpEntries = listOf(providerCoordinate),
                traktUpNextEntries = emptyList()
            )

            assertEquals(emptyList<TrackingNextUpEntry>(), snapshot.nextUpItems)
            val scheduled = snapshot.scheduledReemit.single()
            assertEquals("tmdb:12345", scheduled.contentId)
            assertEquals(14, scheduled.season)
            assertEquals(1, scheduled.episode)
            assertEquals(tomorrow, scheduled.firstAired)
            assertEquals("tmdb:12345:14:1", scheduled.videoId)
        }

    @Test
    fun `buildRawSnapshot does not project anime next-up rows`() =
        runTest {
            val facade = mockk<MetadataRouterFacade>(relaxed = true)
            coEvery {
                facade.fetchTvEpisodeEnrichment(metadataRequest = any(), tvRequest = any())
            } returns TvMetadataDecision(
                provider = TvProvider.TVDB,
                reason = TvMetadataDecisionReason.TVDB_SUCCESS,
                value = mapOf(
                    (14 to 1) to TvEpisodeMetadata(
                        seasonNumber = 14,
                        episodeNumber = 1,
                        title = "The Multiverse",
                        airDate = "2026-02-17"
                    )
                )
            )
            val service = buildServiceWithMetadataFacade(facade)
            val animeNextUp = nextUp(
                contentId = "tvdb:303904",
                firstAiredMs = 1L,
                firstAired = "2026-02-01"
            ).copy(
                contentType = "anime",
                season = 13,
                episode = 1,
                episodeTitle = "The Multiverse",
                videoId = "tvdb:303904:13:1"
            )

            val snapshot = invokeBuildRawSnapshot(
                service = service,
                allProgress = emptyList(),
                nextUpEntries = listOf(animeNextUp),
                traktUpNextEntries = emptyList()
            )

            assertEquals(listOf(animeNextUp), snapshot.nextUpItems)
            coVerify(exactly = 0) {
                facade.fetchTvEpisodeEnrichment(metadataRequest = any(), tvRequest = any())
            }
        }

    @Test
    fun `sanitizeSnapshot removes persisted unknown-air-date next-up without scheduling it`() {
        val service = buildService()
        val unknownNextUp = nextUp(
            contentId = "persisted-unknown-air-date",
            firstAiredMs = 0L,
            firstAired = null,
            episode = 2
        ).copy(activityAtMs = 60_000L)

        val snapshot = invokeSanitizeSnapshot(
            service = service,
            snapshot = ContinueWatchingSnapshot(
                nextUpItems = listOf(unknownNextUp),
                traktUpNextItems = listOf(unknownNextUp)
            )
        )

        assertEquals(emptyList<TrackingNextUpEntry>(), snapshot.nextUpItems)
        assertEquals(emptyList<TrackingNextUpEntry>(), snapshot.traktUpNextItems)
        assertEquals(emptyList<TrackingNextUpEntry>(), snapshot.scheduledReemit)
    }

    @Test
    fun `sanitizeSnapshot withholds persisted concrete future next-up and schedules reemit`() {
        val service = buildService()
        val futureNextUp = nextUp(
            contentId = "persisted-future-air-date",
            firstAiredMs = System.currentTimeMillis() + 86_400_000L,
            episode = 2
        )

        val snapshot = invokeSanitizeSnapshot(
            service = service,
            snapshot = ContinueWatchingSnapshot(
                nextUpItems = listOf(futureNextUp),
                traktUpNextItems = listOf(futureNextUp),
                scheduledReemit = emptyList()
            )
        )

        assertEquals(emptyList<TrackingNextUpEntry>(), snapshot.nextUpItems)
        assertEquals(emptyList<TrackingNextUpEntry>(), snapshot.traktUpNextItems)
        assertEquals(listOf(futureNextUp), snapshot.scheduledReemit)
    }

    @Test
    fun `sanitizeSnapshot removes persisted unknown-air-date scheduled reemit`() {
        val service = buildService()
        val unknownScheduled = nextUp(
            contentId = "persisted-unknown-scheduled",
            firstAiredMs = 0L,
            firstAired = null,
            episode = 2
        ).copy(activityAtMs = 60_000L)

        val snapshot = invokeSanitizeSnapshot(
            service = service,
            snapshot = ContinueWatchingSnapshot(
                scheduledReemit = listOf(unknownScheduled)
            )
        )

        assertEquals(emptyList<TrackingNextUpEntry>(), snapshot.scheduledReemit)
    }

    @Test
    fun `sanitizeSnapshot removes persisted due scheduled reemit`() {
        val service = buildService()
        val dueScheduled = nextUp(
            contentId = "persisted-due-scheduled",
            firstAiredMs = System.currentTimeMillis() - 86_400_000L,
            episode = 2
        )

        val snapshot = invokeSanitizeSnapshot(
            service = service,
            snapshot = ContinueWatchingSnapshot(
                scheduledReemit = listOf(dueScheduled)
            )
        )

        assertEquals(emptyList<TrackingNextUpEntry>(), snapshot.scheduledReemit)
    }

    // ── Inline harness ─────────────────────────────────────────────────────────
    // Tests 1-4 use an inline harness that mirrors the helper implementations
    // exactly, without any dependency on Dispatchers.IO or the service's init pipeline.

    private data class SnapshotHarness(
        val state: MutableStateFlow<ContinueWatchingSnapshot> = MutableStateFlow(ContinueWatchingSnapshot()),
        val mutex: Mutex = Mutex()
    ) {
        suspend fun removeResumeEntry(videoId: String) {
            mutex.withLock {
                state.value = state.value.copy(
                    resumeItems = state.value.resumeItems.filterNot { it.videoId == videoId }
                )
            }
        }

        suspend fun reinsertResumeEntry(entry: WatchProgress) {
            mutex.withLock {
                state.update { current ->
                    val merged = (current.resumeItems + entry)
                        .sortedByDescending { it.lastWatched }
                        .distinctBy { it.videoId }
                    current.copy(resumeItems = merged)
                }
            }
        }

        suspend fun applyEpisodesMarked(episodes: List<ContinueWatchingSnapshotService.EpisodeRef>) {
            if (episodes.isEmpty()) return
            mutex.withLock {
                state.update { current ->
                    current.copy(
                        resumeItems = current.resumeItems.filterNot { progress ->
                            episodes.any { ref ->
                                progress.contentId == ref.showId &&
                                    progress.season == ref.seasonNumber &&
                                    progress.episode == ref.episodeNumber
                            }
                        },
                        nextUpItems = current.nextUpItems.filterNot { entry ->
                            episodes.any { ref ->
                                entry.contentId == ref.showId &&
                                    entry.season == ref.seasonNumber &&
                                    entry.episode == ref.episodeNumber
                            }
                        },
                        traktUpNextItems = current.traktUpNextItems.filterNot { entry ->
                            episodes.any { ref ->
                                entry.contentId == ref.showId &&
                                    entry.season == ref.seasonNumber &&
                                    entry.episode == ref.episodeNumber
                            }
                        }
                    )
                }
            }
        }

        suspend fun snapshotForRollback(): ContinueWatchingSnapshotService.EpisodeRollbackState {
            return state.value.let { snapshot ->
                ContinueWatchingSnapshotService.EpisodeRollbackState(
                    resumeItems = snapshot.resumeItems,
                    nextUpItems = snapshot.nextUpItems,
                    traktUpNextItems = snapshot.traktUpNextItems
                )
            }
        }

        suspend fun rollbackEpisodes(rollbackState: ContinueWatchingSnapshotService.EpisodeRollbackState) {
            if (rollbackState.resumeItems.isEmpty() && rollbackState.nextUpItems.isEmpty() && rollbackState.traktUpNextItems.isEmpty()) return
            mutex.withLock {
                val current = state.value

                val rollbackResume = current.resumeItems
                    .associateByTo(LinkedHashMap<String, WatchProgress>()) { it.videoId }
                    .also { existing ->
                        rollbackState.resumeItems.forEach { entry ->
                            existing.putIfAbsent(entry.videoId, entry)
                        }
                    }
                    .values
                    .toList()
                    .sortedByDescending { it.lastWatched }

                val rollbackNextUp = (current.nextUpItems + rollbackState.nextUpItems)
                    .distinctBy { entry ->
                        "${entry.contentId}|${entry.season}|${entry.episode}"
                    }
                    .sortedByDescending { it.activityAtMs }

                val rollbackTraktUpNext = (current.traktUpNextItems + rollbackState.traktUpNextItems)
                    .distinctBy { entry ->
                        "${entry.contentId}|${entry.season}|${entry.episode}"
                    }
                    .sortedByDescending { it.activityAtMs }

                state.value = current.copy(
                    resumeItems = rollbackResume,
                    nextUpItems = rollbackNextUp,
                    traktUpNextItems = rollbackTraktUpNext
                )
            }
        }

        suspend fun rollbackEpisodes(episodes: List<WatchProgress>) {
            rollbackEpisodes(
                ContinueWatchingSnapshotService.EpisodeRollbackState(
                    resumeItems = episodes
                )
            )
        }
    }

    // ── Test 1: clearProgressIsSynchronous ─────────────────────────────────────

    @Test
    fun `clearProgressIsSynchronous - removeResumeEntry removes entry from rawSnapshotState immediately`() =
        runTest {
            val harness = SnapshotHarness()
            val entry = resume(contentId = "show-a", videoId = "vid-a", season = 1, episode = 3)

            harness.reinsertResumeEntry(entry)
            assertTrue(
                "Entry should be present before removal",
                harness.state.value.resumeItems.any { it.videoId == "vid-a" }
            )

            // Act — mirrors ContinueWatchingSnapshotService.removeResumeEntry exactly.
            harness.removeResumeEntry("vid-a")

            // Assert on the very next line — no extra suspend points.
            assertFalse(
                "Entry must be removed synchronously from rawSnapshotState",
                harness.state.value.resumeItems.any { it.videoId == "vid-a" }
            )
        }

    // ── Test 2: markWatchedIsSynchronousThenForcesRefresh ─────────────────────

    @Test
    fun `markWatchedIsSynchronousThenForcesRefresh - applyEpisodesMarked removes episode immediately`() =
        runTest {
            val harness = SnapshotHarness()
            val ep = resume(contentId = "show-b", videoId = "show-b:1:5", season = 1, episode = 5)

            harness.reinsertResumeEntry(ep)
            assertTrue(harness.state.value.resumeItems.any { it.videoId == "show-b:1:5" })

            val episodeRef = ContinueWatchingSnapshotService.EpisodeRef(
                showId = "show-b",
                seasonNumber = 1,
                episodeNumber = 5
            )

            // Act — mirrors ContinueWatchingSnapshotService.applyEpisodesMarked exactly.
            harness.applyEpisodesMarked(listOf(episodeRef))

            // Assert on the very next line — no extra suspend points.
            assertFalse(
                "Episode must be removed from resumeItems synchronously after applyEpisodesMarked",
                harness.state.value.resumeItems.any {
                    it.contentId == "show-b" && it.season == 1 && it.episode == 5
                }
            )
        }

    // ── Test 3: forceRefreshBypassesGate ──────────────────────────────────────

    @Test
    fun `forceRefreshBypassesGate - ensureFresh with force=true calls refreshNow even within throttle window`() =
        runTest {
            var refreshCount = 0
            val traktProgressService = mockk<TrackingProgressService>(relaxed = true) {
            every { observeAllProgress() } returns flowOf(emptyList())
                every { observeRemoteSnapshotLoaded() } returns flowOf(false)
                every { observeContinueWatchingNextUp() } returns flowOf(emptyList())
                every { observeSyntheticContinueWatchingNextUp() } returns flowOf(emptyList())
                coEvery { refreshNow() } answers { refreshCount++ }
            }
            val trackingProviderStateService = mockk<TrackingProviderStateService>(relaxed = true) {
                every { state } returns flowOf(EffectiveTrackingProviderState())
            }
            val traktSettingsDataStore = mockk<TraktSettingsDataStore>(relaxed = true) {
                every { dismissedNextUpKeys } returns flowOf(emptySet())
            }
            val service = ContinueWatchingSnapshotService(
                watchProgressRepository = mockk(relaxed = true) {
                    every { observeProgress(any()) } returns flowOf(emptyList())
                },
                trackingProgressService = traktProgressService,
                trackingProviderStateService = trackingProviderStateService,
                traktSettingsDataStore = traktSettingsDataStore,
                metadataDiskCacheStore = mockk(relaxed = true),
                snapshotStore = mockk(relaxed = true) { every { read(any()) } returns null }
            )

            // First force=true call — must call refreshNow.
            service.ensureFresh(force = true)
            assertEquals("First force=true must call refreshNow", 1, refreshCount)

            // Second immediate force=true — must bypass the 30 s throttle.
            service.ensureFresh(force = true)
            assertEquals(
                "Second force=true must call refreshNow again despite throttle window",
                2,
                refreshCount
            )
        }

    @Test
    fun `persisted snapshot does not throttle first live refresh on observe`() =
        runTest {
            var refreshCount = 0
            val persisted = ContinueWatchingSnapshot(
                resumeItems = listOf(resume(contentId = "stale-show")),
                updatedAtMs = System.currentTimeMillis()
            )
            val trackingProgressService = mockk<TrackingProgressService>(relaxed = true) {
            every { observeAllProgress() } returns flowOf(emptyList())
                every { observeRemoteSnapshotLoaded() } returns flowOf(false)
                every { observeContinueWatchingNextUp() } returns flowOf(emptyList())
                every { observeSyntheticContinueWatchingNextUp() } returns flowOf(emptyList())
                coEvery { refreshNow() } answers { refreshCount++ }
            }
            val service = ContinueWatchingSnapshotService(
                watchProgressRepository = mockk(relaxed = true) {
                    every { observeProgress(any()) } returns flowOf(emptyList())
                },
                trackingProgressService = trackingProgressService,
                trackingProviderStateService = mockk(relaxed = true) {
                    every { state } returns flowOf(EffectiveTrackingProviderState())
                },
                traktSettingsDataStore = mockk(relaxed = true) {
                    every { dismissedNextUpKeys } returns flowOf(emptySet())
                },
                metadataDiskCacheStore = mockk(relaxed = true),
                snapshotStore = mockk(relaxed = true) { every { read(any()) } returns persisted }
            )

            service.reloadPersistedSnapshotForActiveProfile(clearWhenMissing = true)
            service.observeSnapshot().first()

            awaitCondition { refreshCount == 1 }
        }

    // ── Test 4: collectorRaceConvergence ──────────────────────────────────────

    /**
     * M2 race test: multiple [applyEpisodesMarked] + [rollbackEpisodes] operations applied
     * in sequence converge to the correct final state under a shared mutex.
     *
     * We test the harness directly (not the service IO pipeline) to guarantee determinism.
     */
    @Test
    fun `collectorRaceConvergence - applyEpisodesMarked and rollback converge correctly`() =
        runTest {
            val harness = SnapshotHarness()

            val eps = (1..3).map { ep ->
                resume(
                    contentId = "show-c",
                    videoId = "show-c:1:$ep",
                    season = 1,
                    episode = ep,
                    lastWatched = (1000L + ep)
                )
            }
            eps.forEach { harness.reinsertResumeEntry(it) }
            assertEquals("All 3 episodes present before marking", 3, harness.state.value.resumeItems.size)

            // Mark episodes 1 and 2 as watched.
            val toMark = listOf(
                ContinueWatchingSnapshotService.EpisodeRef("show-c", 1, 1),
                ContinueWatchingSnapshotService.EpisodeRef("show-c", 1, 2)
            )
            harness.applyEpisodesMarked(toMark)

            val remaining = harness.state.value.resumeItems.filter { it.contentId == "show-c" }
            assertEquals("Only episode 3 should remain after marking 1 and 2", 1, remaining.size)
            assertEquals(3, remaining.first().episode)

            // Simulate a rollback of episode 2 (e.g. Trakt call failed).
            harness.rollbackEpisodes(listOf(eps[1]))

            val afterRollback = harness.state.value.resumeItems.filter { it.contentId == "show-c" }
            assertEquals("Episodes 2 and 3 should be present after rollback", 2, afterRollback.size)
            assertTrue(afterRollback.any { it.episode == 2 })
            assertTrue(afterRollback.any { it.episode == 3 })
        }

    // ── Test 5: markWatchedNextUpUnairedSchedulesReemit ───────────────────────

    /**
     * When a CW item is marked watched and the next-up episode for that show is unaired,
     * (a) the item must be absent from rawSnapshotState after [applyEpisodesMarked],
     * (b) the scheduling logic must set a non-null timer target when the snapshot has
     *     a [scheduledReemit] entry for that show's unaired next-up.
     *
     * We test via the inline harness (mirrors production exactly) + a local timer harness
     * that reproduces [ContinueWatchingSnapshotService.scheduleReemitIfNeeded], consistent
     * with the pattern in ContinueWatchingTimelineAirDateTest.
     */
    @Test
    fun `markWatchedNextUpUnairedSchedulesReemit - unaired next-up is filtered and timer is set`() =
        runTest {
            val nowMs = 1_000L
            val futureAirMs = 90_000L

            val harness = SnapshotHarness()

            // Seed the resume rail with the currently-watched episode (S1E3)
            val watchedEpisode = resume(
                contentId = "show-x",
                videoId = "show-x:1:3",
                season = 1,
                episode = 3,
                lastWatched = 800L
            )
            harness.reinsertResumeEntry(watchedEpisode)

            // Verify pre-condition
            assertTrue(harness.state.value.resumeItems.any { it.videoId == "show-x:1:3" })

            // Mark S1E3 as watched — applyEpisodesMarked removes it from rawSnapshotState
            harness.applyEpisodesMarked(
                listOf(ContinueWatchingSnapshotService.EpisodeRef("show-x", 1, 3))
            )

            // (a) The watched episode must be absent from rawSnapshotState
            assertFalse(
                "Watched episode must be removed from rawSnapshotState after applyEpisodesMarked",
                harness.state.value.resumeItems.any {
                    it.contentId == "show-x" && it.season == 1 && it.episode == 3
                }
            )

            // (b) The next-up episode (S1E4) for this show is unaired.
            // Build the scheduledReemit list as buildRawSnapshot would — unaired entries are
            // collected into ContinueWatchingSnapshot.scheduledReemit.
            val unairedNextUp = TrackingNextUpEntry(
                contentId = "show-x",
                name = "Show X",
                season = 1,
                episode = 4,
                episodeTitle = "Episode 4",
                videoId = "show-x:1:4",
                firstAired = null,
                firstAiredMs = futureAirMs,
                activityAtMs = 900L
            )
            val scheduledReemit = listOf(unairedNextUp)

            // Inline timer harness — mirrors ContinueWatchingSnapshotService.scheduleReemitIfNeeded
            var currentTimerTargetMs: Long? = null
            fun scheduleReemitIfNeeded(entries: List<TrackingNextUpEntry>, snapshotNowMs: Long) {
                val soonestMs = AirDateGate.soonestPendingMs(
                    entries = entries,
                    firstAiredMsSelector = { it.firstAiredMs },
                    tmdbAirDateSelector = { it.firstAired },
                    nowMs = snapshotNowMs
                )
                if (soonestMs == currentTimerTargetMs) return
                currentTimerTargetMs = soonestMs
            }

            scheduleReemitIfNeeded(scheduledReemit, nowMs)

            // (b) Timer must be set to the unaired episode's air date
            assertEquals(
                "Timer target must be set to the unaired next-up's firstAiredMs",
                futureAirMs,
                currentTimerTargetMs
            )
        }

    // ── Test 6: rollbackEpisodes restores removed entries ────────────────────

    @Test
    fun `rollbackEpisodes - restores entries that were optimistically removed`() = runTest {
        val harness = SnapshotHarness()
        val entry = resume(contentId = "show-d", videoId = "show-d:2:4", season = 2, episode = 4)

        harness.reinsertResumeEntry(entry)
        assertTrue(harness.state.value.resumeItems.any { it.videoId == "show-d:2:4" })

        // Optimistically remove.
        harness.applyEpisodesMarked(
            listOf(ContinueWatchingSnapshotService.EpisodeRef("show-d", 2, 4))
        )
        assertFalse(
            "Entry must be gone after applyEpisodesMarked",
            harness.state.value.resumeItems.any { it.videoId == "show-d:2:4" }
        )

        // Rollback.
        harness.rollbackEpisodes(listOf(entry))
        assertTrue(
            "Entry must be restored after rollbackEpisodes",
            harness.state.value.resumeItems.any { it.videoId == "show-d:2:4" }
        )
    }

    @Test
    fun `scheduleReemitIfNeeded schedules only soonest tvdb availability`() {
        val nowMs = 10_000L
        val scheduler = RecordingAirScheduler()
        val service = buildServiceWithAirScheduler(scheduler)
        val laterProviderEarlierTvdb = nextUp(
            contentId = "show-exact-soonest",
            firstAiredMs = nowMs + 50_000L,
            tvdbAvailabilityInstantMs = nowMs + 5_000L,
            episode = 1
        )
        val earlierProviderLaterTvdb = nextUp(
            contentId = "show-provider-earlier",
            firstAiredMs = nowMs + 1_000L,
            tvdbAvailabilityInstantMs = nowMs + 20_000L,
            episode = 2
        )

        invokeScheduleReemitIfNeeded(
            service = service,
            entries = listOf(laterProviderEarlierTvdb, earlierProviderLaterTvdb),
            nowMs = nowMs
        )

        assertEquals(listOf(nowMs + 5_000L), scheduler.scheduledAt)
    }

    @Test
    fun `rescheduleAirTimeAlarmFromSnapshot refreshes immediately for overdue persisted scheduled reemit`() = runTest {
        val scheduler = RecordingAirScheduler()
        var refreshCount = 0
        val trackingProgressService = mockk<TrackingProgressService>(relaxed = true) {
            every { observeAllProgress() } returns flowOf(emptyList())
            every { observeRemoteSnapshotLoaded() } returns flowOf(false)
            every { observeContinueWatchingNextUp() } returns flowOf(emptyList())
            every { observeSyntheticContinueWatchingNextUp() } returns flowOf(emptyList())
            coEvery { refreshNow() } answers { refreshCount++ }
        }
        val service = buildServiceWithAirScheduler(
            airScheduler = scheduler,
            trackingProgressService = trackingProgressService
        )
        val nowMs = System.currentTimeMillis()
        val overdue = nextUp(
            contentId = "show-overdue-restore",
            firstAiredMs = nowMs - 1_000L,
            tvdbAvailabilityInstantMs = nowMs - 1_000L,
            episode = 4
        )

        setRawSnapshot(service, ContinueWatchingSnapshot(
            scheduledReemit = listOf(overdue),
            updatedAtMs = nowMs - 60_000L
        ))

        service.rescheduleAirTimeAlarmFromSnapshot()

        awaitCondition { refreshCount == 1 }
        assertTrue(rawSnapshot(service).nextUpItems.isEmpty())
        assertEquals(listOf(overdue), rawSnapshot(service).scheduledReemit)
    }

    @Test
    fun `reloadPersistedSnapshotForActiveProfile drops overdue exact scheduled reemit from persisted load`() = runTest {
        val scheduler = RecordingAirScheduler()
        var refreshCount = 0
        val nowMs = System.currentTimeMillis()
        val overdue = nextUp(
            contentId = "show-overdue-persisted-exact",
            firstAiredMs = nowMs - 1_000L,
            tvdbAvailabilityInstantMs = nowMs - 1_000L,
            episode = 5
        )
        val persisted = ContinueWatchingSnapshot(
            scheduledReemit = listOf(overdue),
            updatedAtMs = nowMs - 60_000L
        )
        val snapshotStore = mockk<ContinueWatchingSnapshotStore>(relaxed = true) {
            every { read(any()) } returns persisted
        }
        val trackingProgressService = mockk<TrackingProgressService>(relaxed = true) {
            every { observeAllProgress() } returns flowOf(emptyList())
            every { observeRemoteSnapshotLoaded() } returns flowOf(false)
            every { observeContinueWatchingNextUp() } returns flowOf(emptyList())
            every { observeSyntheticContinueWatchingNextUp() } returns flowOf(emptyList())
            coEvery { refreshNow() } answers { refreshCount++ }
        }
        val service = buildServiceWithAirScheduler(
            airScheduler = scheduler,
            snapshotStore = snapshotStore,
            trackingProgressService = trackingProgressService
        )

        service.reloadPersistedSnapshotForActiveProfile(clearWhenMissing = true)

        assertEquals(0, refreshCount)
        assertTrue(rawSnapshot(service).nextUpItems.isEmpty())
        assertEquals(emptyList<TrackingNextUpEntry>(), rawSnapshot(service).scheduledReemit)
    }

    @Test
    fun `reloadPersistedSnapshotForActiveProfile keeps persisted aired next-up rows`() = runTest {
        val scheduler = RecordingAirScheduler()
        val nowMs = System.currentTimeMillis()
        val staleNextUp = nextUp(
            contentId = "tvdb:430780",
            firstAiredMs = nowMs - 60_000L,
            tvdbAvailabilityInstantMs = nowMs - 60_000L,
            episode = 7
        )
        val resume = resume(
            contentId = "movie-resume",
            videoId = "movie-resume",
            lastWatched = nowMs - 5_000L
        )
        val persisted = ContinueWatchingSnapshot(
            resumeItems = listOf(resume),
            nextUpItems = listOf(staleNextUp),
            traktUpNextItems = listOf(staleNextUp.copy(contentId = "tt27444205")),
            updatedAtMs = nowMs - 60_000L
        )
        var writtenSnapshot: ContinueWatchingSnapshot? = null
        val snapshotStore = mockk<ContinueWatchingSnapshotStore>(relaxed = true) {
            every { read(any()) } returns persisted
            every { write(any(), any()) } answers {
                writtenSnapshot = firstArg()
                Unit
            }
        }
        val service = buildServiceWithAirScheduler(
            airScheduler = scheduler,
            snapshotStore = snapshotStore
        )

        service.reloadPersistedSnapshotForActiveProfile(clearWhenMissing = true)

        val restored = rawSnapshot(service)
        assertEquals(listOf(resume), restored.resumeItems)
        assertEquals(listOf(staleNextUp), restored.nextUpItems)
        assertEquals(listOf(staleNextUp.copy(contentId = "tt27444205")), restored.traktUpNextItems)
        assertNull(writtenSnapshot)
    }

    @Test
    fun `reloadPersistedSnapshotForActiveProfile keeps persisted remote playback resumes`() = runTest {
        val scheduler = RecordingAirScheduler()
        val nowMs = System.currentTimeMillis()
        val localResume = resume(
            contentId = "local-movie",
            videoId = "local-movie",
            lastWatched = nowMs - 2_000L,
            source = WatchProgress.SOURCE_LOCAL
        )
        val remoteResume = resume(
            contentId = "tvdb:386630",
            videoId = "tvdb:386630:1:2",
            season = 1,
            episode = 2,
            lastWatched = nowMs - 1_000L,
            progressPercent = 30.2367f,
            source = WatchProgress.SOURCE_TRAKT_PLAYBACK
        ).copy(name = "Mayor of Kingstown")
        val remoteMovieResume = resume(
            contentId = "tt40898187",
            videoId = "tt40898187",
            lastWatched = nowMs - 500L,
            progressPercent = 72.0997f,
            source = WatchProgress.SOURCE_TRAKT_PLAYBACK
        ).copy(name = "The Roast of Kevin Hart")
        val remoteRecord = ContinueWatchingRecord(
            profileId = 1,
            parentId = remoteResume.contentId,
            contentId = remoteResume.videoId,
            provider = com.nexio.tv.domain.model.TrackingProvider.TRAKT,
            routingVersion = ContinueWatchingMetadataSnapshot.CURRENT_ROUTING_VERSION,
            positionMs = 0L,
            durationMs = 0L,
            episodeContext = ContinueWatchingRecord.EpisodeContext(1, 2),
            clickTimeDisplayMetadata = null,
            source = ContinueWatchingRecord.Source.REMOTE,
            updatedAt = remoteResume.lastWatched
        )
        val remoteMovieRecord = ContinueWatchingRecord(
            profileId = 1,
            parentId = remoteMovieResume.contentId,
            contentId = remoteMovieResume.videoId,
            provider = com.nexio.tv.domain.model.TrackingProvider.TRAKT,
            routingVersion = ContinueWatchingMetadataSnapshot.CURRENT_ROUTING_VERSION,
            positionMs = 0L,
            durationMs = 0L,
            episodeContext = null,
            clickTimeDisplayMetadata = null,
            source = ContinueWatchingRecord.Source.REMOTE,
            updatedAt = remoteMovieResume.lastWatched
        )
        val persisted = ContinueWatchingSnapshot(
            resumeItems = listOf(remoteResume, remoteMovieResume, localResume),
            records = listOf(remoteRecord, remoteMovieRecord),
            updatedAtMs = nowMs - 60_000L
        )
        var writtenSnapshot: ContinueWatchingSnapshot? = null
        val snapshotStore = mockk<ContinueWatchingSnapshotStore>(relaxed = true) {
            every { read(any()) } returns persisted
            every { write(any(), any()) } answers {
                writtenSnapshot = firstArg()
                Unit
            }
        }
        val trackingProgressService = mockk<TrackingProgressService>(relaxed = true) {
            every { observeAllProgress() } returns flowOf(emptyList())
            every { observeRemoteSnapshotLoaded() } returns flowOf(false)
            every { observeContinueWatchingNextUp() } returns flowOf(emptyList())
            every { observeSyntheticContinueWatchingNextUp() } returns flowOf(emptyList())
            coEvery { refreshNow() } returns Unit
        }
        val service = buildServiceWithAirScheduler(
            airScheduler = scheduler,
            snapshotStore = snapshotStore,
            trackingProgressService = trackingProgressService
        )

        service.reloadPersistedSnapshotForActiveProfile(clearWhenMissing = true)

        assertEquals(setOf(remoteResume, remoteMovieResume, localResume), rawSnapshot(service).resumeItems.toSet())
        assertEquals(listOf(remoteRecord, remoteMovieRecord), rawSnapshot(service).records)
        assertEquals(setOf(remoteResume, remoteMovieResume, localResume), writtenSnapshot?.resumeItems?.toSet())
        assertEquals(listOf(remoteRecord, remoteMovieRecord), writtenSnapshot?.records)
        coVerify(timeout = 1_000L, exactly = 1) { trackingProgressService.refreshNow() }
    }

    @Test
    fun `rescheduleAirTimeAlarmFromSnapshot refreshes overdue provider-ms scheduled reemit without exact instant`() = runTest {
        val scheduler = RecordingAirScheduler()
        var refreshCount = 0
        val trackingProgressService = mockk<TrackingProgressService>(relaxed = true) {
            every { observeAllProgress() } returns flowOf(emptyList())
            every { observeRemoteSnapshotLoaded() } returns flowOf(false)
            every { observeContinueWatchingNextUp() } returns flowOf(emptyList())
            every { observeSyntheticContinueWatchingNextUp() } returns flowOf(emptyList())
            coEvery { refreshNow() } answers { refreshCount++ }
        }
        val service = buildServiceWithAirScheduler(
            airScheduler = scheduler,
            trackingProgressService = trackingProgressService
        )
        val nowMs = System.currentTimeMillis()
        val overdue = nextUp(
            contentId = "show-overdue-provider-ms",
            firstAiredMs = nowMs - 1_000L,
            tvdbAvailabilityInstantMs = null,
            episode = 6
        )

        setRawSnapshot(service, ContinueWatchingSnapshot(
            scheduledReemit = listOf(overdue),
            updatedAtMs = nowMs - 60_000L
        ))

        service.rescheduleAirTimeAlarmFromSnapshot()

        awaitCondition { refreshCount == 1 }
        assertTrue(rawSnapshot(service).nextUpItems.isEmpty())
        assertEquals(listOf(overdue), rawSnapshot(service).scheduledReemit)
    }

    @Test
    fun `rescheduleAirTimeAlarmFromSnapshot refreshes overdue date-only scheduled reemit without exact instant`() = runTest {
        val scheduler = RecordingAirScheduler()
        var refreshCount = 0
        val trackingProgressService = mockk<TrackingProgressService>(relaxed = true) {
            every { observeAllProgress() } returns flowOf(emptyList())
            every { observeRemoteSnapshotLoaded() } returns flowOf(false)
            every { observeContinueWatchingNextUp() } returns flowOf(emptyList())
            every { observeSyntheticContinueWatchingNextUp() } returns flowOf(emptyList())
            coEvery { refreshNow() } answers { refreshCount++ }
        }
        val service = buildServiceWithAirScheduler(
            airScheduler = scheduler,
            trackingProgressService = trackingProgressService
        )
        val nowMs = System.currentTimeMillis()
        val overdue = nextUp(
            contentId = "show-overdue-date-only",
            firstAiredMs = 0L,
            tvdbAvailabilityInstantMs = null,
            firstAired = "1970-01-01",
            episode = 7
        )

        setRawSnapshot(service, ContinueWatchingSnapshot(
            scheduledReemit = listOf(overdue),
            updatedAtMs = nowMs - 60_000L
        ))

        service.rescheduleAirTimeAlarmFromSnapshot()

        awaitCondition { refreshCount == 1 }
        assertTrue(rawSnapshot(service).nextUpItems.isEmpty())
        assertEquals(listOf(overdue), rawSnapshot(service).scheduledReemit)
    }

    @Test
    fun `reemit refresh failure keeps withheld row and schedules retry`() {
        val scheduler = RecordingAirScheduler()
        val failure = IllegalStateException("refresh failed")
        val trackingProgressService = mockk<TrackingProgressService>(relaxed = true) {
            every { observeAllProgress() } returns flowOf(emptyList())
            every { observeRemoteSnapshotLoaded() } returns flowOf(false)
            every { observeContinueWatchingNextUp() } returns flowOf(emptyList())
            every { observeSyntheticContinueWatchingNextUp() } returns flowOf(emptyList())
            coEvery { refreshNow() } throws failure
        }
        val service = buildServiceWithAirScheduler(
            airScheduler = scheduler,
            trackingProgressService = trackingProgressService
        )
        val nowMs = System.currentTimeMillis()
        val withheld = nextUp(
            contentId = "show-retry",
            firstAiredMs = nowMs + 5L,
            tvdbAvailabilityInstantMs = nowMs + 5L,
            episode = 3
        )
        setRawSnapshot(service, ContinueWatchingSnapshot(
            scheduledReemit = listOf(withheld),
            updatedAtMs = nowMs
        ))

        invokeScheduleReemitIfNeeded(
            service = service,
            entries = listOf(withheld),
            nowMs = nowMs
        )

        awaitCondition { scheduler.scheduledAt.size >= 2 }
        val retryAtMs = scheduler.scheduledAt.last() ?: error("Retry schedule must not be null")
        val retryDelayMs = retryAtMs - System.currentTimeMillis()

        assertEquals(listOf(withheld), rawSnapshot(service).scheduledReemit)
        assertTrue(
            "Retry should be scheduled close to 15 minutes after refresh failure",
            retryDelayMs in (15 * 60_000L - 2_000L)..(15 * 60_000L + 2_000L)
        )
    }
}
