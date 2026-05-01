package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.data.local.WatchProgressPreferences
import com.nexio.tv.data.repository.simkl.SimklProgressHistoryMutationAdapter
import com.nexio.tv.data.repository.simkl.SimklSeasonMarkMutationAdapter
import com.nexio.tv.data.repository.trakt.SeasonMarkBatcher
import com.nexio.tv.data.repository.trakt.TraktProgressHistoryMutationAdapter
import com.nexio.tv.data.trakt.outbox.TraktMutationOutboxCoordinator
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.SeasonEpisodeMark
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.domain.repository.WatchProgressRepository
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class WatchProgressRepositoryImpl @Inject constructor(
    private val watchProgressPreferences: WatchProgressPreferences,
    private val trackingProviderStateService: TrackingProviderStateService,
    private val trackingProgressService: TrackingProgressService,
    private val traktMutationOutboxCoordinator: TraktMutationOutboxCoordinator,
    private val seasonMarkBatcher: SeasonMarkBatcher,
    private val traktAuthService: TraktRepositoryAuthGateway,
    // Provider<> breaks the DI cycle: ContinueWatchingSnapshotService → WatchProgressRepository
    //   → WatchProgressRepositoryImpl → ContinueWatchingSnapshotService
    private val snapshotServiceProvider: Provider<ContinueWatchingSnapshotService>,
    private val metadataRouterFacade: MetadataRouterFacade
) : WatchProgressRepository {
    companion object {
        private const val TAG = "WatchProgressRepo"
    }

    internal data class EpisodeMetadata(
        val title: String?,
        val thumbnail: String?
    )

    internal data class ContentMetadata(
        val name: String?,
        val poster: String?,
        val backdrop: String?,
        val logo: String?,
        val episodes: Map<Pair<Int, Int>, EpisodeMetadata>
    )

    private val metadataState = MutableStateFlow<Map<String, ContentMetadata>>(emptyMap())
    private val metadataMutex = Mutex()
    private val inFlightMetadataKeys = mutableSetOf<String>()
    private val metadataHydrationLimit = 30
    private val metadataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun hydrateMetadata(progressList: List<WatchProgress>) {
        val sorted = progressList.sortedByDescending { it.lastWatched }
        val uniqueByContent = linkedMapOf<String, WatchProgress>()
        sorted.forEach { progress ->
            if (uniqueByContent.size < metadataHydrationLimit) {
                uniqueByContent.putIfAbsent(progress.contentId, progress)
            }
        }

        uniqueByContent.values.forEach { progress ->
            val contentId = progress.contentId
            if (contentId.isBlank()) return@forEach
            if (metadataState.value.containsKey(contentId)) return@forEach

            metadataScope.launch {
                val shouldFetch = metadataMutex.withLock {
                    if (metadataState.value.containsKey(contentId)) return@withLock false
                    if (inFlightMetadataKeys.contains(contentId)) return@withLock false
                    inFlightMetadataKeys.add(contentId)
                    true
                }
                if (!shouldFetch) return@launch

                try {
                    val metadata = fetchContentMetadata(
                        progress = progress
                    ) ?: return@launch
                    metadataState.update { current ->
                        current + (contentId to metadata)
                    }
                } finally {
                    metadataMutex.withLock {
                        inFlightMetadataKeys.remove(contentId)
                    }
                }
            }
        }
    }

    override fun invalidateLocalizedMetadata() {
        metadataState.value = emptyMap()
        metadataScope.launch {
            metadataMutex.withLock {
                inFlightMetadataKeys.clear()
            }
        }
    }

    internal suspend fun fetchContentMetadata(progress: WatchProgress): ContentMetadata? {
        val request = MetadataRequest(
            contentId = progress.contentId,
            contentType = ContentType.fromString(progress.contentType),
            sourceContext = MetadataSourceContext(
                itemType = progress.contentType,
                addonMetadata = HomeDisplayMetadata(
                    title = progress.name,
                    poster = progress.poster,
                    backdrop = progress.backdrop,
                    logo = progress.logo
                )
            ),
            depth = MetadataDepth.DETAIL_CORE
        )
        val canonical = try {
            metadataRouterFacade.resolveRequest(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "fetchContentMetadata resolveRequest failed for ${progress.contentId}: ${e.message}", e)
            return null
        }
        if (canonical.route == null) return null
        val display = canonical.displayMetadata
        return ContentMetadata(
            name = display.title ?: progress.name,
            poster = display.poster ?: progress.poster,
            backdrop = display.backdrop ?: progress.backdrop,
            logo = display.logo ?: progress.logo,
            episodes = emptyMap()
        )
    }

    private fun enrichWithMetadata(
        progress: WatchProgress,
        metadataMap: Map<String, ContentMetadata>
    ): WatchProgress {
        val metadata = metadataMap[progress.contentId] ?: return progress
        val episodeMeta = if (progress.season != null && progress.episode != null) {
            metadata.episodes[progress.season to progress.episode]
        } else {
            null
        }
        val shouldOverrideName = progress.name.isBlank() || progress.name == progress.contentId
        val backdrop = progress.backdrop
            ?: metadata.backdrop
            ?: episodeMeta?.thumbnail

        return progress.copy(
            name = if (shouldOverrideName) metadata.name ?: progress.name else progress.name,
            poster = progress.poster ?: metadata.poster,
            backdrop = backdrop,
            logo = progress.logo ?: metadata.logo,
            episodeTitle = progress.episodeTitle ?: episodeMeta?.title
        )
    }

    override val allProgress: Flow<List<WatchProgress>>
        get() = trackingProviderStateService.state.map { it.hasAuthenticatedProvider }
            .distinctUntilChanged()
            .flatMapLatest { isAuthenticated ->
                if (isAuthenticated) {
                    combine(
                        trackingProgressService.observeAllProgress()
                            .onStart {
                                // Emit local-cache-backed continue watching immediately on app start
                                // while the first Trakt snapshot is still loading.
                                emit(emptyList())
                            },
                        watchProgressPreferences.allRawProgress,
                        metadataState
                    ) { remoteItems, localItems, metadataMap ->
                        val merged = mergeProgressLists(remoteItems, localItems)
                        hydrateMetadata(merged)
                        merged.map { enrichWithMetadata(it, metadataMap) }
                    }
                } else {
                    flowOf(emptyList())
                }
            }

    override val continueWatching: Flow<List<WatchProgress>>
        get() = allProgress.map { list -> list.filter { it.isInProgress() } }

    override fun getProgress(contentId: String): Flow<WatchProgress?> {
        return trackingProviderStateService.state.map { it.hasAuthenticatedProvider }
            .distinctUntilChanged()
            .flatMapLatest { isAuthenticated ->
                if (isAuthenticated) {
                    allProgress.map { items ->
                        items
                            .filter { it.contentId == contentId }
                            .maxByOrNull { it.lastWatched }
                    }
                } else {
                    flowOf(null)
                }
            }
    }

    override fun getEpisodeProgress(contentId: String, season: Int, episode: Int): Flow<WatchProgress?> {
        return trackingProviderStateService.state.map { it.hasAuthenticatedProvider }
            .distinctUntilChanged()
            .flatMapLatest { isAuthenticated ->
                if (isAuthenticated) {
                    allProgress.map { items ->
                        items.firstOrNull {
                            it.contentId == contentId && it.season == season && it.episode == episode
                        }
                    }
                } else {
                    flowOf(null)
                }
            }
    }

    override fun getAllEpisodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> {
        return trackingProviderStateService.state.map { it.hasAuthenticatedProvider }
            .distinctUntilChanged()
            .flatMapLatest { isAuthenticated ->
                if (isAuthenticated) {
                    combine(
                        trackingProgressService.observeEpisodeProgress(contentId),
                        allProgress.map { items ->
                            items.filter { it.contentId == contentId && it.season != null && it.episode != null }
                        }
                    ) { remoteMap, liveEpisodes ->
                        val merged = remoteMap.toMutableMap()
                        liveEpisodes.forEach { episodeProgress ->
                            val season = episodeProgress.season ?: return@forEach
                            val episode = episodeProgress.episode ?: return@forEach
                            merged[season to episode] = episodeProgress
                        }
                        merged
                    }.distinctUntilChanged()
                } else {
                    flowOf(emptyMap())
                }
            }
    }

    override fun isWatched(contentId: String, season: Int?, episode: Int?): Flow<Boolean> {
        return trackingProviderStateService.state.map { it.hasAuthenticatedProvider }
            .distinctUntilChanged()
            .flatMapLatest { isAuthenticated ->
                if (!isAuthenticated) {
                    return@flatMapLatest flowOf(false)
                }

                if (season != null && episode != null) {
                    trackingProgressService.observeEpisodeProgress(contentId)
                        .map { progressMap ->
                            progressMap[season to episode]?.isCompleted() == true
                        }
                        .distinctUntilChanged()
                } else {
                    trackingProgressService.observeMovieWatched(contentId)
                }
            }
    }

    override suspend fun saveProgress(progress: WatchProgress, syncRemote: Boolean) {
        if (!trackingProviderStateService.currentState().hasAuthenticatedProvider) {
            return
        }
        trackingProgressService.applyOptimisticProgress(progress)
        watchProgressPreferences.saveProgress(progress)
    }

    override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) {
        val providerState = trackingProviderStateService.currentState()
        val isAuthenticated = providerState.traktAuthenticated || providerState.simklAuthenticated
        if (!isAuthenticated) return
        val profileId = if (providerState.effectiveProvider == com.nexio.tv.domain.model.TrackingProvider.TRAKT) {
            traktAuthService.currentTraktProfileId()
        } else {
            1
        }
        trackingProgressService.applyOptimisticRemoval(contentId, season, episode)
        runCatching {
            trackingProgressService.resolvePlaybackDeleteIdsForOutbox(contentId, season, episode)
                .forEach { playbackId ->
                    val envelope = when (providerState.effectiveProvider) {
                        com.nexio.tv.domain.model.TrackingProvider.SIMKL ->
                            SimklProgressHistoryMutationAdapter.buildPlaybackDeleteEnvelope(
                                playbackId = playbackId,
                                contentId = contentId,
                                season = season,
                                episode = episode
                            )
                        com.nexio.tv.domain.model.TrackingProvider.TRAKT ->
                            TraktProgressHistoryMutationAdapter.buildPlaybackDeleteEnvelope(
                                playbackId = playbackId,
                                contentId = contentId,
                                season = season,
                                episode = episode,
                                profileId = profileId
                            )
                    }
                    traktMutationOutboxCoordinator.enqueueAndDrain(envelope)
                }
        }.onFailure {
            trackingProgressService.rollbackQueuedPlaybackDelete(
                contentId = contentId,
                season = season,
                episode = episode,
                clearShow = false
            )
            throw it
        }
        watchProgressPreferences.removeProgress(contentId, season, episode)
    }

    override suspend fun removeFromHistory(contentId: String, season: Int?, episode: Int?) {
        val providerState = trackingProviderStateService.currentState()
        if (!providerState.hasAuthenticatedProvider) {
            return
        }
        val profileId = if (providerState.effectiveProvider == com.nexio.tv.domain.model.TrackingProvider.TRAKT) {
            traktAuthService.currentTraktProfileId()
        } else {
            1
        }
        trackingProgressService.applyOptimisticRemoval(contentId, season, episode)
        runCatching {
            val envelope = when (providerState.effectiveProvider) {
                com.nexio.tv.domain.model.TrackingProvider.SIMKL ->
                    SimklProgressHistoryMutationAdapter.buildHistoryRemoveEnvelope(
                        contentId = contentId,
                        season = season,
                        episode = episode
                    )
                com.nexio.tv.domain.model.TrackingProvider.TRAKT ->
                    TraktProgressHistoryMutationAdapter.buildHistoryRemoveEnvelope(
                        contentId = contentId,
                        season = season,
                        episode = episode,
                        profileId = profileId
                    )
            }
            traktMutationOutboxCoordinator.enqueueAndDrain(envelope)
        }.onFailure {
            trackingProgressService.rollbackQueuedHistoryRemove(
                contentId = contentId,
                season = season,
                episode = episode,
                removeShow = false
            )
            throw it
        }
        watchProgressPreferences.removeProgress(contentId, season, episode)
    }

    override suspend fun clearShowProgress(contentId: String) {
        val providerState = trackingProviderStateService.currentState()
        if (!providerState.hasAuthenticatedProvider) {
            return
        }
        val profileId = if (providerState.effectiveProvider == com.nexio.tv.domain.model.TrackingProvider.TRAKT) {
            traktAuthService.currentTraktProfileId()
        } else {
            1
        }
        val playbackIds = trackingProgressService.resolvePlaybackDeleteIdsForOutbox(
            contentId = contentId,
            season = null,
            episode = null
        )
        trackingProgressService.applyOptimisticRemoval(contentId, null, null)
        runCatching {
            playbackIds.forEach { playbackId ->
                val deleteEnvelope = when (providerState.effectiveProvider) {
                    com.nexio.tv.domain.model.TrackingProvider.SIMKL ->
                        SimklProgressHistoryMutationAdapter.buildPlaybackDeleteEnvelope(
                            playbackId = playbackId,
                            contentId = contentId,
                            season = null,
                            episode = null,
                            clearShow = true
                        )
                    com.nexio.tv.domain.model.TrackingProvider.TRAKT ->
                        TraktProgressHistoryMutationAdapter.buildPlaybackDeleteEnvelope(
                            playbackId = playbackId,
                            contentId = contentId,
                            season = null,
                            episode = null,
                            clearShow = true,
                            profileId = profileId
                        )
                }
                traktMutationOutboxCoordinator.enqueueAndDrain(deleteEnvelope)
            }
            val removeEnvelope = when (providerState.effectiveProvider) {
                com.nexio.tv.domain.model.TrackingProvider.SIMKL ->
                    SimklProgressHistoryMutationAdapter.buildHistoryRemoveEnvelope(
                        contentId = contentId,
                        season = null,
                        episode = null,
                        removeShow = true
                    )
                com.nexio.tv.domain.model.TrackingProvider.TRAKT ->
                    TraktProgressHistoryMutationAdapter.buildHistoryRemoveEnvelope(
                        contentId = contentId,
                        season = null,
                        episode = null,
                        removeShow = true,
                        profileId = profileId
                    )
            }
            traktMutationOutboxCoordinator.enqueueAndDrain(removeEnvelope)
        }.onFailure {
            trackingProgressService.rollbackQueuedHistoryRemove(
                contentId = contentId,
                season = null,
                episode = null,
                removeShow = true
            )
            throw it
        }
        watchProgressPreferences.removeProgress(contentId, null, null)
    }

    override suspend fun markAsCompleted(progress: WatchProgress) {
        val providerState = trackingProviderStateService.currentState()
        if (!providerState.hasAuthenticatedProvider) {
            return
        }
        val profileId = if (providerState.effectiveProvider == com.nexio.tv.domain.model.TrackingProvider.TRAKT) {
            traktAuthService.currentTraktProfileId()
        } else {
            1
        }
        val now = System.currentTimeMillis()
        val duration = progress.duration.takeIf { it > 0L } ?: 1L
        val completed = progress.copy(
            position = duration,
            duration = duration,
            progressPercent = 100f,
            lastWatched = now
        )
        runCatching {
            val envelope = when (providerState.effectiveProvider) {
                com.nexio.tv.domain.model.TrackingProvider.SIMKL ->
                    SimklProgressHistoryMutationAdapter.buildHistoryAddEnvelope(
                        progress = completed,
                        title = completed.name.takeIf { it.isNotBlank() },
                        year = null
                    )
                com.nexio.tv.domain.model.TrackingProvider.TRAKT ->
                    TraktProgressHistoryMutationAdapter.buildHistoryAddEnvelope(
                        progress = completed,
                        title = completed.name.takeIf { it.isNotBlank() },
                        year = null,
                        profileId = profileId
                    )
            }
            traktMutationOutboxCoordinator.enqueueAndDrain(envelope)
        }.onFailure {
            trackingProgressService.applyOptimisticRemoval(
                contentId = completed.contentId,
                season = completed.season,
                episode = completed.episode
            )
            throw it
        }
        watchProgressPreferences.saveProgress(completed)
    }

    override suspend fun clearAll() {
        trackingProgressService.clearOptimistic()
        watchProgressPreferences.clearAll()
    }

    override suspend fun markAsCompletedBatch(
        meta: Meta,
        seasonNumber: Int,
        episodes: List<SeasonEpisodeMark>
    ) {
        val providerState = trackingProviderStateService.currentState()
        if (!providerState.hasAuthenticatedProvider) return
        if (episodes.isEmpty()) return
        val profileId = if (providerState.effectiveProvider == com.nexio.tv.domain.model.TrackingProvider.TRAKT) {
            traktAuthService.currentTraktProfileId()
        } else {
            1
        }

        val showContentId = meta.id

        // 1. Map TmdbEpisodes → EpisodeRef for optimistic CW update
        val episodeRefs = episodes.mapNotNull { ep ->
            val epNum = ep.episodeNumber ?: return@mapNotNull null
            ContinueWatchingSnapshotService.EpisodeRef(
                showId = showContentId,
                seasonNumber = seasonNumber,
                episodeNumber = epNum
            )
        }
        val snapshotService = snapshotServiceProvider.get()

        // 2. Capture only the rails touched by this season mutation for later rollback.
        val rollbackState = snapshotService.snapshotForEpisodes(episodeRefs)

        // 3. Atomic optimistic update — remove from CW
        snapshotService.applyEpisodesMarked(episodeRefs)

        val episodeNumbers = episodes.mapNotNull { it.episodeNumber }
        if (providerState.effectiveProvider == com.nexio.tv.domain.model.TrackingProvider.SIMKL) {
            try {
                traktMutationOutboxCoordinator.enqueueAndDrain(
                    SimklSeasonMarkMutationAdapter.buildEnvelope(
                        showContentId = showContentId,
                        showTitle = meta.name,
                        showYear = extractYear(meta.releaseInfo),
                        isAnime = meta.rawType.equals("anime", ignoreCase = true),
                        seasonNumber = seasonNumber,
                        episodeNumbers = episodeNumbers,
                        rollbackState = rollbackState
                    )
                )
            } catch (e: Exception) {
                snapshotService.rollbackEpisodes(rollbackState)
                snapshotService.ensureFresh(force = true)
                throw e
            }
            return
        }

        // 4. Resolve Trakt episode IDs in parallel — map<episodeNumber, TraktEpisodeRef>
        val epNumToTraktRef = trackingProgressService.resolveSeasonEpisodeTraktIds(
            showContentId = showContentId,
            season = seasonNumber,
            episodeNumbers = episodeNumbers
        )
        val initialNotFoundEpisodeNumbers = episodeNumbers.toSet() - epNumToTraktRef.keys
        val traktRefs = epNumToTraktRef.values.toList()

        if (traktRefs.isEmpty()) {
            if (initialNotFoundEpisodeNumbers.isNotEmpty()) {
                snapshotService.rollbackEpisodes(
                    rollbackState.filterEpisodeNumbers(initialNotFoundEpisodeNumbers)
                )
            }
            snapshotService.ensureFresh(force = true)
            return
        }

        if (initialNotFoundEpisodeNumbers.isNotEmpty()) {
            snapshotService.rollbackEpisodes(
                rollbackState.filterEpisodeNumbers(initialNotFoundEpisodeNumbers)
            )
        }

        // 5. One batched POST via SeasonMarkBatcher; settlement is handled asynchronously by the outbox.
        try {
            seasonMarkBatcher.markSeasonWatched(
                showContentId = showContentId,
                seasonNumber = seasonNumber,
                episodes = traktRefs,
                rollbackState = rollbackState.filterEpisodeNumbers(epNumToTraktRef.keys),
                profileId = profileId
            )
        } catch (e: Exception) {
            snapshotService.rollbackEpisodes(rollbackState)
            snapshotService.ensureFresh(force = true)
            throw e
        }
    }

    private fun ContinueWatchingSnapshotService.EpisodeRollbackState.filterEpisodeNumbers(
        episodeNumbers: Collection<Int>
    ): ContinueWatchingSnapshotService.EpisodeRollbackState {
        val episodeSet = episodeNumbers.toSet()
        if (episodeSet.isEmpty()) return ContinueWatchingSnapshotService.EpisodeRollbackState()
        return ContinueWatchingSnapshotService.EpisodeRollbackState(
            resumeItems = resumeItems.filter { it.episode in episodeSet },
            nextUpItems = nextUpItems.filter { it.episode in episodeSet },
            traktUpNextItems = traktUpNextItems.filter { it.episode in episodeSet }
        )
    }

    private fun progressKey(progress: WatchProgress): String {
        return if (progress.season != null && progress.episode != null) {
            "${progress.contentId}_s${progress.season}e${progress.episode}"
        } else {
            progress.contentId
        }
    }

    private fun mergeProgressLists(
        remoteItems: List<WatchProgress>,
        localItems: List<WatchProgress>
    ): List<WatchProgress> {
        val mergedByKey = linkedMapOf<String, WatchProgress>()

        fun upsert(progress: WatchProgress) {
            val key = progressKey(progress)
            val existing = mergedByKey[key]
            if (existing == null || shouldPreferProgress(existing, progress)) {
                mergedByKey[key] = progress
            }
        }

        remoteItems.forEach(::upsert)
        localItems.forEach(::upsert)

        return mergedByKey.values
            .sortedByDescending { it.lastWatched }
    }

    private fun shouldPreferProgress(existing: WatchProgress, candidate: WatchProgress): Boolean {
        val timeDiffMs = candidate.lastWatched - existing.lastWatched
        if (timeDiffMs > 1_000L) return true
        if (timeDiffMs < -1_000L) return false

        val candidateInProgress = candidate.isInProgress()
        val existingInProgress = existing.isInProgress()
        if (candidateInProgress && !existingInProgress) return true
        if (!candidateInProgress && existingInProgress) return false

        val candidateIsPlayback = candidate.source == WatchProgress.SOURCE_TRAKT_PLAYBACK
        val existingIsPlayback = existing.source == WatchProgress.SOURCE_TRAKT_PLAYBACK
        if (candidateIsPlayback && !existingIsPlayback) return true
        if (!candidateIsPlayback && existingIsPlayback) return false

        return false
    }
}
