// effectiveProvider remains for legacy single-provider next-up surfaces.
// Progress source aggregation and optimistic mutation hooks use activeProviders.
// Plan: docs/superpowers/plans/2026-05-12-scrobble-cw-dual-provider-overhaul.md
@file:Suppress("DEPRECATION")

package com.nexio.tv.data.repository

import com.nexio.tv.core.tvdb.TvdbAirAvailabilityDiagnosticReason
import com.nexio.tv.core.tvdb.TvdbAirAvailabilityPrecision
import com.nexio.tv.data.integration.mdblist.MDBListProgressService
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.data.repository.trakt.TraktEpisodeRef
import com.nexio.tv.domain.model.TrackingProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class TrackingNextUpEntry(
    val contentId: String,
    val contentType: String = "series",
    val name: String,
    val season: Int,
    val episode: Int,
    val episodeTitle: String?,
    val videoId: String,
    val firstAired: String?,
    val firstAiredMs: Long,
    val activityAtMs: Long,
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val traktShowId: Int? = null,
    val traktEpisodeId: Int? = null,
    val tvdbAvailabilityInstantMs: Long? = null,
    val tvdbAvailabilityPrecision: TvdbAirAvailabilityPrecision = TvdbAirAvailabilityPrecision.UNKNOWN,
    val tvdbAvailabilitySourceZoneId: String? = null,
    val tvdbAvailabilitySourcePolicy: String? = null,
    val tvdbAvailabilityDiagnosticReason: TvdbAirAvailabilityDiagnosticReason? = null,
    val tvdbAvailabilityDeviceLocalDateTime: String? = null
)

interface TrackingProgressService {
    fun observeAllProgress(): Flow<List<WatchProgress>>
    fun observeAllProgress(profileId: Int): Flow<List<WatchProgress>>
    fun observeRemoteSnapshotLoaded(): Flow<Boolean>
    fun observeRemoteSnapshotLoaded(profileId: Int): Flow<Boolean>
    @Deprecated(
        message = "Profile-boundary: use ContinueWatchingSnapshotService.observeContinueWatching(profileId) for profile-scoped CW. This method routes by active tracking provider only and is consumed internally by ContinueWatchingSnapshotService.",
        replaceWith = ReplaceWith("ContinueWatchingSnapshotService.observeContinueWatching(profileId)")
    )
    fun observeContinueWatchingNextUp(): Flow<List<TrackingNextUpEntry>>
    fun observeContinueWatchingNextUp(profileId: Int): Flow<List<TrackingNextUpEntry>>
    fun observeSyntheticContinueWatchingNextUp(): Flow<List<TrackingNextUpEntry>>
    fun observeSyntheticContinueWatchingNextUp(profileId: Int): Flow<List<TrackingNextUpEntry>>
    fun observeEpisodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>>
    fun observeMovieWatched(contentId: String): Flow<Boolean>
    fun applyOptimisticProgress(progress: WatchProgress)
    fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?)
    fun clearOptimistic()
    fun invalidateLocalizedMetadata()
    suspend fun refreshOnStartup()
    suspend fun refreshOnStartup(profileId: Int) {
        refreshOnStartup()
    }
    suspend fun refreshNow()
    suspend fun refreshNow(profileId: Int) {
        refreshNow()
    }
    suspend fun resolvePlaybackDeleteIdsForOutbox(
        contentId: String,
        season: Int?,
        episode: Int?
    ): List<Long>
    suspend fun resolveSeasonEpisodeTraktIds(
        showContentId: String,
        season: Int,
        episodeNumbers: List<Int>
    ): Map<Int, TraktEpisodeRef>
    suspend fun rollbackQueuedHistoryRemove(
        contentId: String,
        season: Int?,
        episode: Int?,
        removeShow: Boolean
    )
    suspend fun rollbackQueuedPlaybackDelete(
        contentId: String,
        season: Int?,
        episode: Int?,
        clearShow: Boolean
    )
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultTrackingProgressService @Inject constructor(
    private val traktProgressService: TraktProgressService,
    private val simklProgressService: SimklProgressService,
    private val mdbListProgressService: MDBListProgressService? = null,
    private val trackingProviderStateService: TrackingProviderStateService,
    private val tvdbContinueWatchingTimingEnricher: TvdbContinueWatchingTimingEnricher = TvdbContinueWatchingTimingEnricher()
) : TrackingProgressService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var currentActiveProviders: Set<TrackingProvider> = emptySet()

    init {
        scope.launch {
            trackingProviderStateService.state.collect { state ->
                currentActiveProviders = state.activeProviders
            }
        }
    }

    override fun observeAllProgress(): Flow<List<WatchProgress>> =
        trackingProviderStateService.state.flatMapLatest { state ->
            observeAllProgress(state)
        }

    override fun observeAllProgress(profileId: Int): Flow<List<WatchProgress>> =
        trackingProviderStateService.stateForProfile(profileId).flatMapLatest { state ->
            observeAllProgress(state, profileId)
        }

    private fun observeAllProgress(
        state: EffectiveTrackingProviderState,
        profileId: Int? = null
    ): Flow<List<WatchProgress>> {
            val active = state.activeProviders
            when {
                active.isEmpty() -> return flowOf(emptyList())
                active.size == 1 -> when (active.single()) {
                    TrackingProvider.SIMKL -> return profileId
                        ?.let(simklProgressService::observeAllProgress)
                        ?: simklProgressService.observeAllProgress()
                    TrackingProvider.TRAKT -> return profileId
                        ?.let(traktProgressService::observeAllProgress)
                        ?: traktProgressService.observeAllProgress()
                    TrackingProvider.MDBLIST -> return mdbListProgressService?.observeAllProgress() ?: flowOf(emptyList())
                }
                // Multiple authed: concatenate provider rows. Downstream
                // ContinueWatchingMerger collapses cross-provider duplicates by idBundle
                // and routes conflicts through ContinueWatchingProgressDiffPlanner.
                else -> return combine(active.map { provider -> allProgressFlowForProvider(provider, profileId) }) { providerRows ->
                    providerRows.flatMap { it }
                }
            }
        }

    private fun allProgressFlowForProvider(
        provider: TrackingProvider,
        profileId: Int? = null
    ): Flow<List<WatchProgress>> {
        return when (provider) {
            TrackingProvider.SIMKL -> profileId
                ?.let(simklProgressService::observeAllProgress)
                ?: simklProgressService.observeAllProgress()
            TrackingProvider.TRAKT -> profileId
                ?.let(traktProgressService::observeAllProgress)
                ?: traktProgressService.observeAllProgress()
            TrackingProvider.MDBLIST -> mdbListProgressService?.observeAllProgress() ?: flowOf(emptyList())
        }
    }

    override fun observeRemoteSnapshotLoaded(): Flow<Boolean> =
        trackingProviderStateService.state.flatMapLatest { state ->
            observeRemoteSnapshotLoaded(state)
        }

    override fun observeRemoteSnapshotLoaded(profileId: Int): Flow<Boolean> =
        trackingProviderStateService.stateForProfile(profileId).flatMapLatest { state ->
            observeRemoteSnapshotLoaded(state, profileId)
        }

    private fun observeRemoteSnapshotLoaded(
        state: EffectiveTrackingProviderState,
        profileId: Int? = null
    ): Flow<Boolean> {
            val active = state.activeProviders
            if (active.isEmpty()) {
                return flowOf(false)
            }
            if (active.size == 1) {
                return snapshotLoadedFlowForProvider(active.single(), profileId)
            }
            return combine(active.map { provider -> snapshotLoadedFlowForProvider(provider, profileId) }) { loaded ->
                loaded.all { it }
            }
        }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun observeContinueWatchingNextUp(): Flow<List<TrackingNextUpEntry>> =
        trackingProviderStateService.state.flatMapLatest { state ->
            observeContinueWatchingNextUp(state)
        }

    override fun observeContinueWatchingNextUp(profileId: Int): Flow<List<TrackingNextUpEntry>> =
        trackingProviderStateService.stateForProfile(profileId).flatMapLatest { state ->
            observeContinueWatchingNextUp(state, profileId)
        }

    private fun observeContinueWatchingNextUp(
        state: EffectiveTrackingProviderState,
        profileId: Int? = null
    ): Flow<List<TrackingNextUpEntry>> {
            val active = state.activeProviders
            if (active.isEmpty()) {
                return flowOf(emptyList())
            }
            if (active.size == 1) {
                return continueWatchingNextUpFlowForProvider(active.single(), profileId)
                    .mapLatest { items -> tvdbContinueWatchingTimingEnricher.enrich(items) }
            }
            return combine(active.map { provider -> continueWatchingNextUpFlowForProvider(provider, profileId) }) { providerRows ->
                providerRows.flatMap { it }
                    .sortedByDescending { it.activityAtMs }
            }.mapLatest { items -> tvdbContinueWatchingTimingEnricher.enrich(items) }
        }

    override fun observeSyntheticContinueWatchingNextUp(): Flow<List<TrackingNextUpEntry>> =
        trackingProviderStateService.state.flatMapLatest { state ->
            observeSyntheticContinueWatchingNextUp(state)
        }

    override fun observeSyntheticContinueWatchingNextUp(profileId: Int): Flow<List<TrackingNextUpEntry>> =
        trackingProviderStateService.stateForProfile(profileId).flatMapLatest { state ->
            observeSyntheticContinueWatchingNextUp(state, profileId)
        }

    private fun observeSyntheticContinueWatchingNextUp(
        state: EffectiveTrackingProviderState,
        profileId: Int? = null
    ): Flow<List<TrackingNextUpEntry>> {
            val active = state.activeProviders
            if (active.isEmpty()) {
                return flowOf(emptyList())
            }
            if (active.size == 1) {
                return syntheticContinueWatchingNextUpFlowForProvider(active.single(), profileId)
                    .mapLatest { items -> tvdbContinueWatchingTimingEnricher.enrich(items) }
            }
            return combine(active.map { provider -> syntheticContinueWatchingNextUpFlowForProvider(provider, profileId) }) { providerRows ->
                providerRows.flatMap { it }
                    .sortedByDescending { it.activityAtMs }
            }.mapLatest { items -> tvdbContinueWatchingTimingEnricher.enrich(items) }
        }

    private fun continueWatchingNextUpFlowForProvider(
        provider: TrackingProvider,
        profileId: Int? = null
    ): Flow<List<TrackingNextUpEntry>> {
        return when (provider) {
            TrackingProvider.SIMKL -> profileId
                ?.let(simklProgressService::observeContinueWatchingNextUp)
                ?: simklProgressService.observeContinueWatchingNextUp()
            TrackingProvider.TRAKT -> (profileId
                ?.let(traktProgressService::observeContinueWatchingNextUp)
                ?: traktProgressService.observeContinueWatchingNextUp())
                .mapLatest { items -> items.map(TraktProgressService.NextUpEntry::toTrackingNextUpEntry) }
            TrackingProvider.MDBLIST -> flowOf(emptyList())
        }
    }

    private fun syntheticContinueWatchingNextUpFlowForProvider(
        provider: TrackingProvider,
        profileId: Int? = null
    ): Flow<List<TrackingNextUpEntry>> {
        return when (provider) {
            TrackingProvider.SIMKL -> profileId
                ?.let(simklProgressService::observeSyntheticContinueWatchingNextUp)
                ?: simklProgressService.observeSyntheticContinueWatchingNextUp()
            TrackingProvider.TRAKT -> (profileId
                ?.let(traktProgressService::observeSyntheticContinueWatchingNextUp)
                ?: traktProgressService.observeSyntheticContinueWatchingNextUp())
                .mapLatest { items -> items.map(TraktProgressService.NextUpEntry::toTrackingNextUpEntry) }
            TrackingProvider.MDBLIST -> flowOf(emptyList())
        }
    }

    override fun observeEpisodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> =
        trackingProviderStateService.state.flatMapLatest { state ->
            val active = state.activeProviders
            if (active.isEmpty()) {
                return@flatMapLatest flowOf(emptyMap())
            }
            if (active.size == 1) {
                return@flatMapLatest episodeProgressFlowForProvider(active.single(), contentId)
            }
            combine(active.map { provider -> episodeProgressFlowForProvider(provider, contentId) }) { maps ->
                val merged = linkedMapOf<Pair<Int, Int>, WatchProgress>()
                for (i in maps.indices) {
                    merged.putAll(maps[i])
                }
                merged
            }
        }

    override fun observeMovieWatched(contentId: String): Flow<Boolean> =
        trackingProviderStateService.state.flatMapLatest { state ->
            val active = state.activeProviders
            if (active.isEmpty()) {
                return@flatMapLatest flowOf(false)
            }
            if (active.size == 1) {
                return@flatMapLatest movieWatchedFlowForProvider(active.single(), contentId)
            }
            combine(active.map { provider -> movieWatchedFlowForProvider(provider, contentId) }) { values ->
                values.any { it }
            }
        }

    override fun applyOptimisticProgress(progress: WatchProgress) {
        val active = currentActiveProviders.toList()
        for (i in active.indices) {
            when (active[i]) {
                TrackingProvider.SIMKL -> simklProgressService.applyOptimisticProgress(progress)
                TrackingProvider.TRAKT -> traktProgressService.applyOptimisticProgress(progress)
                TrackingProvider.MDBLIST -> Unit
            }
        }
    }

    override fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?) {
        val active = currentActiveProviders.toList()
        for (i in active.indices) {
            when (active[i]) {
                TrackingProvider.SIMKL -> simklProgressService.applyOptimisticRemoval(contentId, season, episode)
                TrackingProvider.TRAKT -> traktProgressService.applyOptimisticRemoval(contentId, season, episode)
                TrackingProvider.MDBLIST -> Unit
            }
        }
    }

    override fun clearOptimistic() {
        val active = currentActiveProviders.toList()
        for (i in active.indices) {
            when (active[i]) {
                TrackingProvider.SIMKL -> simklProgressService.clearOptimistic()
                TrackingProvider.TRAKT -> traktProgressService.clearOptimistic()
                TrackingProvider.MDBLIST -> Unit
            }
        }
    }

    override fun invalidateLocalizedMetadata() {
        val active = currentActiveProviders.toList()
        for (i in active.indices) {
            when (active[i]) {
                TrackingProvider.SIMKL -> simklProgressService.invalidateLocalizedMetadata()
                TrackingProvider.TRAKT -> traktProgressService.invalidateLocalizedMetadata()
                TrackingProvider.MDBLIST -> Unit
            }
        }
    }

    override suspend fun refreshNow() {
        refreshNow(trackingProviderStateService.currentProfileId())
    }

    override suspend fun refreshNow(profileId: Int) {
        val state = trackingProviderStateService.currentState(profileId)
        val active = state.activeProviders.toList()
        for (i in active.indices) {
            when (active[i]) {
                TrackingProvider.SIMKL -> simklProgressService.refreshNowImmediate(profileId)
                TrackingProvider.TRAKT -> traktProgressService.refreshNowImmediate()
                TrackingProvider.MDBLIST -> mdbListProgressService?.refreshNowImmediate()
            }
        }
    }

    override suspend fun refreshOnStartup() {
        refreshOnStartup(trackingProviderStateService.currentProfileId())
    }

    override suspend fun refreshOnStartup(profileId: Int) {
        val state = trackingProviderStateService.currentState(profileId)
        val active = state.activeProviders.toList()
        for (i in active.indices) {
            when (active[i]) {
                TrackingProvider.SIMKL -> simklProgressService.refreshNowImmediate(profileId)
                TrackingProvider.TRAKT -> traktProgressService.refreshNowImmediate()
                TrackingProvider.MDBLIST -> mdbListProgressService?.refreshNowImmediate()
            }
        }
    }

    override suspend fun resolvePlaybackDeleteIdsForOutbox(
        contentId: String,
        season: Int?,
        episode: Int?
    ): List<Long> {
        val state = trackingProviderStateService.currentState()
        if (!state.canReadEffectiveProvider) return emptyList()
        return when (state.effectiveProvider) {
            TrackingProvider.SIMKL -> simklProgressService.resolvePlaybackDeleteIdsForOutbox(
                contentId = contentId,
                season = season,
                episode = episode
            )
            TrackingProvider.TRAKT -> traktProgressService.resolvePlaybackDeleteIdsForOutbox(
                contentId = contentId,
                season = season,
                episode = episode
            )
            TrackingProvider.MDBLIST -> emptyList()
        }
    }

    override suspend fun resolveSeasonEpisodeTraktIds(
        showContentId: String,
        season: Int,
        episodeNumbers: List<Int>
    ): Map<Int, TraktEpisodeRef> {
        val state = trackingProviderStateService.currentState()
        if (!state.traktAuthenticated) return emptyMap()
        return traktProgressService.resolveSeasonEpisodeTraktIds(
            showContentId = showContentId,
            season = season,
            episodeNumbers = episodeNumbers
        )
    }

    override suspend fun rollbackQueuedHistoryRemove(
        contentId: String,
        season: Int?,
        episode: Int?,
        removeShow: Boolean
    ) {
        val state = trackingProviderStateService.currentState()
        if (!state.canReadEffectiveProvider) return
        when (state.effectiveProvider) {
            TrackingProvider.SIMKL -> simklProgressService.rollbackQueuedHistoryRemove(
                contentId = contentId,
                season = season,
                episode = episode,
                removeShow = removeShow
            )
            TrackingProvider.TRAKT -> traktProgressService.rollbackQueuedHistoryRemove(
                contentId = contentId,
                season = season,
                episode = episode,
                removeShow = removeShow
            )
            TrackingProvider.MDBLIST -> Unit
        }
    }

    override suspend fun rollbackQueuedPlaybackDelete(
        contentId: String,
        season: Int?,
        episode: Int?,
        clearShow: Boolean
    ) {
        val state = trackingProviderStateService.currentState()
        if (!state.canReadEffectiveProvider) return
        when (state.effectiveProvider) {
            TrackingProvider.SIMKL -> simklProgressService.rollbackQueuedPlaybackDelete(
                contentId = contentId,
                season = season,
                episode = episode,
                clearShow = clearShow
            )
            TrackingProvider.TRAKT -> traktProgressService.rollbackQueuedPlaybackDelete(
                contentId = contentId,
                season = season,
                episode = episode,
                clearShow = clearShow
            )
            TrackingProvider.MDBLIST -> Unit
        }
    }

    private fun snapshotLoadedFlowForProvider(
        provider: TrackingProvider,
        profileId: Int? = null
    ): Flow<Boolean> {
        return when (provider) {
            TrackingProvider.SIMKL -> profileId
                ?.let(simklProgressService::observeRemoteSnapshotLoaded)
                ?: simklProgressService.observeRemoteSnapshotLoaded()
            TrackingProvider.TRAKT -> profileId
                ?.let(traktProgressService::observeRemoteSnapshotLoaded)
                ?: traktProgressService.observeRemoteSnapshotLoaded()
            TrackingProvider.MDBLIST -> mdbListProgressService?.observeRemoteSnapshotLoaded() ?: flowOf(false)
        }
    }

    private fun episodeProgressFlowForProvider(
        provider: TrackingProvider,
        contentId: String
    ): Flow<Map<Pair<Int, Int>, WatchProgress>> {
        return when (provider) {
            TrackingProvider.SIMKL -> simklProgressService.observeEpisodeProgress(contentId)
            TrackingProvider.TRAKT -> traktProgressService.observeEpisodeProgress(contentId)
            TrackingProvider.MDBLIST -> mdbListProgressService?.observeEpisodeProgress(contentId) ?: flowOf(emptyMap())
        }
    }

    private fun movieWatchedFlowForProvider(provider: TrackingProvider, contentId: String): Flow<Boolean> {
        return when (provider) {
            TrackingProvider.SIMKL -> simklProgressService.observeMovieWatched(contentId)
            TrackingProvider.TRAKT -> traktProgressService.observeMovieWatched(contentId)
            TrackingProvider.MDBLIST -> mdbListProgressService?.observeMovieWatched(contentId) ?: flowOf(false)
        }
    }
}

fun TraktProgressService.NextUpEntry.toTrackingNextUpEntry(): TrackingNextUpEntry {
    return TrackingNextUpEntry(
        contentId = contentId,
        contentType = contentType,
        name = name,
        season = season,
        episode = episode,
        episodeTitle = episodeTitle,
        videoId = videoId,
        firstAired = firstAired,
        firstAiredMs = firstAiredMs,
        activityAtMs = activityAtMs,
        poster = poster,
        backdrop = backdrop,
        logo = logo,
        traktShowId = traktShowId,
        traktEpisodeId = traktEpisodeId
    )
}
