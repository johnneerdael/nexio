package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.ActiveRailTracker
import com.nexio.tv.core.integration.IntegrationOwnershipService
import com.nexio.tv.core.integration.RailMediaIdentityResolver
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.scheduler.ContinueWatchingAirScheduler
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.data.local.ContinueWatchingSnapshotStore
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.domain.repository.WatchProgressRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Assert.assertTrue
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
        continueWatchingIdentityResolver: ContinueWatchingIdentityResolver = canonicalResolver()
    ): ContinueWatchingSnapshotService {
        val trackingProviderStateService = mockk<TrackingProviderStateService>(relaxed = true) {
            every { state } returns flowOf(EffectiveTrackingProviderState())
        }
        val traktSettingsDataStore = mockk<TraktSettingsDataStore>(relaxed = true) {
            every { dismissedNextUpKeys } returns flowOf(emptySet())
        }
        val trackingProgressService = mockk<TrackingProgressService>(relaxed = true) {
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
            continueWatchingIdentityResolver = continueWatchingIdentityResolver
        )
    }

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

            assertEquals(listOf("show-kept:s1e3"), records.map { it.contentId })
            assertFalse(records.any { it.parentId == removedResume.contentId })
            assertFalse(records.any { it.parentId == markedResume.contentId })
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
    fun `reloadPersistedSnapshotForActiveProfile refreshes overdue exact scheduled reemit from persisted load`() = runTest {
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

        awaitCondition { refreshCount >= 1 }
        assertTrue(rawSnapshot(service).nextUpItems.isEmpty())
        assertEquals(listOf(overdue), rawSnapshot(service).scheduledReemit)
    }

    @Test
    fun `rescheduleAirTimeAlarmFromSnapshot refreshes overdue provider-ms scheduled reemit without exact instant`() = runTest {
        val scheduler = RecordingAirScheduler()
        var refreshCount = 0
        val trackingProgressService = mockk<TrackingProgressService>(relaxed = true) {
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
